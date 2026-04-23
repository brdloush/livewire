package net.brdloush.livewire.attach;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import javax.management.*;

/**
 * Java agent entry point. Loaded into the target JVM via VirtualMachine.loadAgent().
 * <p>
 * Responsibilities:
 * <ol>
 *   <li>Find the Spring {@code ApplicationContext} in the running JVM.</li>
 *   <li>Ensure the Livewire nREPL server is running (start it if not).</li>
 *   <li>Write the resolved port to {@code /tmp/livewire-attach-<pid>.port}
 *       so the jshell-side client knows where to connect.</li>
 * </ol>
 *
 * <h3>ApplicationContext discovery strategies (tried in order)</h3>
 * <ol>
 *   <li><b>ContextLoader</b> — Spring MVC's {@code ContextLoader.getCurrentWebApplicationContext()}.
 *       Works for all servlet-based Spring Boot apps (the common case).</li>
 *   <li><b>Shutdown hook</b> — Spring Boot registers a
 *       {@code SpringApplicationShutdownHook} thread; its {@code contexts} field
 *       holds the live {@code ConfigurableApplicationContext} instances.</li>
 * </ol>
 */
public class LivewireAgent {

    private static final String LOG_FILE = "/tmp/livewire-attach.log";

    // ─── agent entry points ───────────────────────────────────────────────────

    /** Entry point for dynamic attach via {@code VirtualMachine.loadAgent()}. */
    public static void agentmain(String args, Instrumentation inst) {
        // Heartbeat: set a system property before anything else to confirm invocation.
        // Checked from jshell via jcmd <pid> VM.system_properties | grep livewire.agent
        System.setProperty("livewire.agent.status", "called");
        // Also try a raw file write to /tmp (catches file-I/O problems separately).
        try (java.io.FileOutputStream _fos = new java.io.FileOutputStream("/tmp/livewire-agent-heartbeat.txt")) {
            _fos.write(("agentmain called, pid=" + ProcessHandle.current().pid() + "\n").getBytes());
        } catch (Exception _hb) {
            System.err.println("[livewire] heartbeat file write failed: " + _hb);
        }

        int    port = parsePort(args, 7888);
        String pid  = String.valueOf(ProcessHandle.current().pid());

        try {
            log("[livewire] agent v2 starting in pid " + pid + " ...");

            // Fast path: nREPL is already listening (Livewire started via autoconfig).
            // No context discovery needed — just write the port file and we're done.
            if (isPortListening(port)) {
                log("[livewire] ✓ nREPL already running on port " + port);
                writePortFile(pid, port);
                log("[livewire] ✓ port file written: /tmp/livewire-attach-" + pid + ".port");
                return;
            }

            // Slow path: nREPL is not running — find the ApplicationContext and start it.
            log("[livewire] nREPL not running on port " + port + ", attempting to start...");

            // 1. Find the classloader that owns Spring / Livewire classes.
            ClassLoader appCl = findAppClassLoader();
            if (appCl == null) {
                log("[livewire] ERROR: no Spring Boot classloader found — is this a Spring Boot app?");
                return;
            }
            log("[livewire] ✓ found app classloader: " + appCl.getClass().getName());

            // 2. Discover the ApplicationContext.
            Object ctx = discoverContext(appCl);
            if (ctx == null) {
                log("[livewire] ERROR: no Spring ApplicationContext found — is the app fully started?");
                return;
            }
            String appName = getAppName(ctx, appCl);
            log("[livewire] ✓ Spring ApplicationContext discovered" +
                    (appName != null ? ": \"" + appName + "\"" : ""));

            // 3. Start nREPL via Livewire.
            int actualPort = ensureNrepl(ctx, appCl, port);
            log("[livewire] ✓ nREPL ready on 127.0.0.1:" + actualPort);

            // 4. Write port file so the jshell client knows where to connect.
            writePortFile(pid, actualPort);
            log("[livewire] ✓ port file written: /tmp/livewire-attach-" + pid + ".port");

        } catch (Throwable e) {
            log("[livewire] ERROR: " + e);
            logToFile(e);
        }
    }

    /** Entry point for static {@code -javaagent} flag (not the primary path). */
    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }

    // ─── classloader discovery ────────────────────────────────────────────────

    /**
     * Finds the classloader that has Spring Boot's classes by scanning thread
     * context classloaders. Returns the first non-system classloader that can
     * successfully load {@code org.springframework.boot.SpringApplication}.
     */
    static ClassLoader findAppClassLoader() {
        ClassLoader sysCl = ClassLoader.getSystemClassLoader();
        Set<ClassLoader> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        // First: look for a non-system classloader that has Spring (fat-jar / LaunchedURLClassLoader).
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            ClassLoader cl = t.getContextClassLoader();
            if (cl == null || cl == sysCl || seen.contains(cl)) continue;
            seen.add(cl);
            try {
                cl.loadClass("org.springframework.boot.SpringApplication");
                return cl;
            } catch (ClassNotFoundException ignored) {}
        }

        // Fallback: mvn spring-boot:run loads everything via the system classloader directly.
        try {
            sysCl.loadClass("org.springframework.boot.SpringApplication");
            log("[livewire] using system classloader (mvn spring-boot:run mode)");
            return sysCl;
        } catch (ClassNotFoundException ignored) {}

        return null;
    }

    // ─── ApplicationContext discovery ─────────────────────────────────────────

    /**
     * Tries each discovery strategy in order, returning the first non-null result.
     */
    static Object discoverContext(ClassLoader appCl) {
        Object ctx;

        // Strategy 1: Spring MVC ContextLoader (works for all servlet-based Boot apps).
        ctx = tryContextLoader(appCl);
        if (ctx != null) {
            log("[livewire] context found via ContextLoader (strategy 1)");
            return ctx;
        }

        // Strategy 2: SpringApplicationShutdownHook thread inspection.
        ctx = tryShutdownHook(appCl);
        if (ctx != null) {
            log("[livewire] context found via shutdown hook (strategy 2)");
            return ctx;
        }

        return null;
    }

    /** Strategy 1: {@code ContextLoader.getCurrentWebApplicationContext()} */
    private static Object tryContextLoader(ClassLoader appCl) {
        try {
            Class<?> cl  = appCl.loadClass("org.springframework.web.context.ContextLoader");
            Method   m   = cl.getMethod("getCurrentWebApplicationContext");
            return m.invoke(null);
        } catch (Exception e) {
            log("[livewire] strategy 1 (ContextLoader) failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Strategy 2: walks JVM shutdown hooks looking for a
     * {@code SpringApplicationShutdownHook} thread whose {@code target} runnable
     * stores the live ApplicationContext(s) in a {@code contexts} field.
     */
    @SuppressWarnings("unchecked")
    private static Object tryShutdownHook(ClassLoader appCl) {
        try {
            // java.lang.ApplicationShutdownHooks maintains a Map<Thread,Thread>
            // of registered hooks.  It's package-private, so we need setAccessible.
            Class<?> hooksClass  = Class.forName("java.lang.ApplicationShutdownHooks");
            java.lang.reflect.Field hooksField = hooksClass.getDeclaredField("hooks");
            hooksField.setAccessible(true);
            Map<Thread, Thread> hooks = (Map<Thread, Thread>) hooksField.get(null);

            for (Thread hookThread : hooks.keySet()) {
                if (!hookThread.getName().contains("SpringApplicationShutdownHook")) continue;

                // The Thread was created with new Thread(runnable, name).
                // The runnable is stored in Thread.target (platform threads, Java 11-25).
                Object target = getThreadTarget(hookThread);
                if (target == null) continue;
                if (!target.getClass().getName().contains("SpringApplicationShutdownHook")) continue;

                // SpringApplicationShutdownHook stores contexts in a Set<ConfigurableApplicationContext>.
                for (String fieldName : new String[]{"contexts", "context"}) {
                    try {
                        java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
                        f.setAccessible(true);
                        Object val = f.get(target);
                        if (val instanceof Collection) {
                            Collection<?> coll = (Collection<?>) val;
                            if (!coll.isEmpty()) return coll.iterator().next();
                        } else if (val != null) {
                            return val;
                        }
                    } catch (NoSuchFieldException ignored) {}
                }
            }
        } catch (Exception e) {
            log("[livewire] strategy 2 (shutdown hook) failed: " + e.getMessage());
        }
        return null;
    }

    /** Extracts the Runnable target from a platform Thread (Java 11-25). */
    private static Object getThreadTarget(Thread t) {
        for (String name : new String[]{"target", "task", "runnable"}) {
            try {
                java.lang.reflect.Field f = Thread.class.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(t);
            } catch (NoSuchFieldException ignored) {}
            catch (Exception e) {
                log("[livewire] getThreadTarget field '" + name + "' failed: " + e.getMessage());
            }
        }
        return null;
    }

    // ─── nREPL bootstrap ──────────────────────────────────────────────────────

    /**
     * Ensures an nREPL server is running on {@code port}.
     * <p>
     * If the port is already listening (Livewire was started via Spring autoconfig),
     * this is a no-op and returns the port immediately.
     * Otherwise it starts Livewire by reflectively calling
     * {@code LivewireBootstrapBean.afterPropertiesSet()} through the app classloader.
     */
    private static int ensureNrepl(Object ctx, ClassLoader appCl, int port) throws Exception {
        if (isPortListening(port)) {
            log("[livewire] nREPL already running on port " + port);
            return port;
        }

        log("[livewire] starting nREPL on port " + port + " via LivewireBootstrapBean ...");

        // LivewireBootstrapBean uses Clojure.var() internally, which reads
        // Thread.currentThread().getContextClassLoader().  We must set it to
        // appCl so the Livewire namespaces are found.
        ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(appCl);
        try {
            Class<?> bootstrapClass = appCl.loadClass("net.brdloush.livewire.LivewireBootstrapBean");
            Class<?> appCtxClass    = appCl.loadClass("org.springframework.context.ApplicationContext");
            Constructor<?> ctor     = bootstrapClass.getConstructor(appCtxClass, int.class);
            Object bootstrap        = ctor.newInstance(ctx, port);
            bootstrapClass.getMethod("afterPropertiesSet").invoke(bootstrap);
        } finally {
            Thread.currentThread().setContextClassLoader(prevCl);
        }

        // Give nREPL a moment to bind the port before we declare success.
        for (int i = 0; i < 50; i++) {
            if (isPortListening(port)) return port;
            Thread.sleep(100);
        }
        throw new RuntimeException("nREPL started but port " + port
                + " is not yet listening after 5s — check the target app's logs.");
    }

    // ─── utilities ────────────────────────────────────────────────────────────

    private static boolean isPortListening(int port) {
        try (Socket s = new Socket("127.0.0.1", port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getAppName(Object ctx, ClassLoader appCl) {
        try {
            Class<?> ctxClass = appCl.loadClass(
                    "org.springframework.context.ApplicationContext");
            Method m = ctxClass.getMethod("getApplicationName");
            Object name = m.invoke(ctx);
            return name != null ? name.toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writePortFile(String pid, int port) throws IOException {
        Path path = Path.of("/tmp/livewire-attach-" + pid + ".port");
        Files.writeString(path, String.valueOf(port));
    }

    private static int parsePort(String args, int defaultPort) {
        if (args == null || args.isBlank()) return defaultPort;
        try { return Integer.parseInt(args.trim()); }
        catch (NumberFormatException e) { return defaultPort; }
    }

    static void log(String msg) {
        System.out.println(msg);
        // Also write to the log file so jshell-side can see agent output.
        try (FileWriter fw = new FileWriter(LOG_FILE, true)) {
            fw.write(msg + "\n");
        } catch (IOException ignored) {}
    }

    private static void logToFile(Throwable t) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(LOG_FILE, true))) {
            pw.println("--- " + new java.util.Date() + " ---");
            t.printStackTrace(pw);
        } catch (IOException ignored) {}
    }
}
