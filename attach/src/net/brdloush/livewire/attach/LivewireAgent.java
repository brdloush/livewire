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

            // Open java.base/java.lang so the shutdown hook strategy can access
            // private fields (ApplicationShutdownHooks.hooks, Thread.target).
            // Using Instrumentation.redefineModule() avoids the need for any
            // --add-opens JVM startup flag on the target.
            openJavaLang(inst);

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

            // 3. Start nREPL via bundled Livewire (works whether or not the target has Livewire).
            //    Fall back to a bare exploration nREPL if Livewire bootstrap fails.
            int actualPort;
            if (ctx != null) {
                String appName = getAppName(ctx, appCl);
                log("[livewire] ✓ Spring ApplicationContext discovered" +
                        (appName != null ? ": \"" + appName + "\"" : ""));
                try {
                    actualPort = startLivewireNrepl(ctx, appCl, port);
                } catch (Exception e) {
                    log("[livewire] Livewire nREPL bootstrap failed (" + e.getMessage()
                            + ") — falling back to exploration nREPL");
                    actualPort = startExplorationNrepl(appCl, port);
                }
            } else {
                log("[livewire] no Spring ApplicationContext found — starting exploration nREPL");
                actualPort = startExplorationNrepl(appCl, port);
            }

            // 4. Write port file so the jshell client knows where to connect.
            writePortFile(pid, actualPort);
            log("[livewire] ✓ port file written: /tmp/livewire-attach-" + pid + ".port");
            log("[livewire] ✓ nREPL ready on 127.0.0.1:" + actualPort);

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
     *
     * <ol>
     *   <li>ContextLoader — only set for WAR deployments; usually null for embedded Boot.</li>
     *   <li>SpringApplicationShutdownHook — requires java.lang to be opened first via
     *       {@link #openJavaLang}.</li>
     *   <li>Tomcat container thread — walks the {@code container-N} Thread subclass
     *       ({@code TomcatWebServer$N}) through {@code TomcatWebServer → Tomcat → Host
     *       → StandardContext → ServletContext → ROOT WebApplicationContext attribute}.
     *       No {@code --add-opens} needed; all touched classes are in unnamed modules.</li>
     * </ol>
     */
    static Object discoverContext(ClassLoader appCl) {
        Object ctx;

        // Strategy 1: Spring MVC ContextLoader (works for WAR deployments).
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

        // Strategy 3: Embedded Tomcat container thread (Spring Boot embedded; most common).
        ctx = tryTomcatContainerThread(appCl);
        if (ctx != null) {
            log("[livewire] context found via Tomcat container thread (strategy 3)");
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

    /**
     * Extracts the Runnable target from a platform Thread.
     * <ul>
     *   <li>Java 11–20: stored in {@code Thread.target}</li>
     *   <li>Java 21+:   stored in {@code Thread.holder.task} (Project Loom refactor)</li>
     * </ul>
     * Requires {@code java.lang} to be opened ({@link #openJavaLang} must have been called).
     */
    private static Object getThreadTarget(Thread t) {
        // Java 11–20: direct field on Thread
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
        // Java 21+: task lives in Thread.holder (FieldHolder inner class)
        try {
            java.lang.reflect.Field holderField = Thread.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            Object holder = holderField.get(t);
            if (holder != null) {
                java.lang.reflect.Field taskField = holder.getClass().getDeclaredField("task");
                taskField.setAccessible(true);
                return taskField.get(holder);
            }
        } catch (Exception e) {
            log("[livewire] getThreadTarget holder.task failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Strategy 3 — Embedded Tomcat container thread.
     * <p>
     * Spring Boot's {@code TomcatWebServer} keeps the JVM alive by blocking a dedicated
     * {@code Thread} subclass ({@code TomcatWebServer$N}) in
     * {@code StandardServer.await()}. Because this class is an anonymous inner class,
     * it holds a synthetic {@code this$0} reference to the outer {@code TomcatWebServer}
     * instance. From there the path is:
     * {@code TomcatWebServer → Tomcat → Host → StandardContext → ServletContext →
     * ROOT WebApplicationContext attribute}.
     * <p>
     * All touched classes (Spring Boot, Tomcat) are in unnamed modules, so
     * {@code setAccessible(true)} works without any {@code --add-opens}.
     */
    private static Object tryTomcatContainerThread(ClassLoader appCl) {
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                // The keeper thread is an anonymous subclass of Thread defined inside
                // TomcatWebServer, so its class name contains "TomcatWebServer".
                if (!t.getClass().getName().contains("TomcatWebServer")) continue;

                // Synthetic this$0 field → TomcatWebServer instance.
                // setAccessible works: TomcatWebServer$N is in unnamed module (Spring Boot jar).
                java.lang.reflect.Field f0 = t.getClass().getDeclaredField("this$0");
                f0.setAccessible(true);
                Object tws = f0.get(t);

                // TomcatWebServer.tomcat → org.apache.catalina.startup.Tomcat
                java.lang.reflect.Field tf = tws.getClass().getDeclaredField("tomcat");
                tf.setAccessible(true);
                Object tomcat = tf.get(tws);

                // Tomcat.getHost() → Host; Host.findChildren()[0] → StandardContext
                Object host = tomcat.getClass().getMethod("getHost").invoke(tomcat);
                Object[] children = (Object[]) host.getClass()
                        .getMethod("findChildren").invoke(host);
                if (children == null || children.length == 0) continue;

                // StandardContext.getServletContext() → javax/jakarta.servlet.ServletContext
                Object sc = children[0].getClass()
                        .getMethod("getServletContext").invoke(children[0]);

                // getAttribute("...ROOT") → Spring's WebApplicationContext
                Object ctx = sc.getClass()
                        .getMethod("getAttribute", String.class)
                        .invoke(sc, "org.springframework.web.context.WebApplicationContext.ROOT");

                if (ctx != null) return ctx;
            }
        } catch (Exception e) {
            log("[livewire] strategy 3 (Tomcat container thread) failed: " + e.getMessage());
        }
        return null;
    }

    // ─── nREPL bootstrap ──────────────────────────────────────────────────────



    // ─── module opening ───────────────────────────────────────────────────────

    /**
     * Opens {@code java.base/java.lang} to this (unnamed) module using
     * {@link Instrumentation#redefineModule}. This allows the shutdown-hook
     * strategy to access private fields ({@code ApplicationShutdownHooks.hooks},
     * {@code Thread.target}) without requiring {@code --add-opens} at JVM startup.
     */
    private static void openJavaLang(Instrumentation inst) {
        try {
            Module unnamed  = LivewireAgent.class.getModule();
            Module javaBase = Object.class.getModule();
            Map<String, Set<Module>> extraOpens = new HashMap<>();
            extraOpens.put("java.lang", Collections.singleton(unnamed));
            inst.redefineModule(javaBase,
                    Collections.emptySet(),
                    Collections.emptyMap(),
                    extraOpens,
                    Collections.emptySet(),
                    Collections.emptyMap());
            log("[livewire] ✓ java.lang opened via Instrumentation.redefineModule");
        } catch (Exception e) {
            log("[livewire] note: could not open java.lang (" + e.getMessage()
                    + ") — shutdown hook strategy may fail");
        }
    }

    // ─── Livewire + exploration nREPL ─────────────────────────────────────────

    /**
     * Starts a full Livewire nREPL using the Clojure runtime and Livewire namespaces
     * bundled in this agent jar. Works regardless of whether Livewire is on the target
     * app's classpath.
     * <p>
     * A child {@link java.net.URLClassLoader} (parent = {@code appCl}) loads Clojure
     * and Livewire from the agent jar. Spring/Hibernate classes are resolved via parent
     * delegation ({@code appCl}). The discovered Spring {@code ApplicationContext} is
     * injected via {@code boot/start!}, giving a fully functional Livewire REPL with
     * {@code lw}, {@code q}, {@code trace}, and all other aliases set up.
     */
    private static int startLivewireNrepl(Object ctx, ClassLoader appCl, int port)
            throws Exception {
        java.net.URL agentUrl = LivewireAgent.class
                .getProtectionDomain().getCodeSource().getLocation();

        // Parent = appCl so Livewire/Clojure code can reach Spring/Hibernate classes.
        ClassLoader agentCl = new java.net.URLClassLoader(
                new java.net.URL[]{agentUrl}, appCl);

        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(agentCl);
        try {
            Class<?> clj  = agentCl.loadClass("clojure.java.api.Clojure");
            java.lang.reflect.Method var  = clj.getMethod("var",  Object.class, Object.class);
            java.lang.reflect.Method read = clj.getMethod("read", String.class);
            Class<?> ifn  = agentCl.loadClass("clojure.lang.IFn");
            java.lang.reflect.Method inv1 = ifn.getMethod("invoke", Object.class);
            java.lang.reflect.Method inv2 = ifn.getMethod("invoke", Object.class, Object.class);

            // (require 'net.brdloush.livewire.boot)
            Object requireFn = var.invoke(null, "clojure.core", "require");
            inv1.invoke(requireFn, read.invoke(null, "net.brdloush.livewire.boot"));

            // (net.brdloush.livewire.boot/start! ctx port)
            Object startFn = var.invoke(null, "net.brdloush.livewire.boot", "start!");
            inv2.invoke(startFn, ctx, (long) port);

            // Patch Hibernate's StatementInspector so trace-sql captures SQL.
            // LivewireEnvironmentPostProcessor normally does this before SessionFactory
            // is built, but in agent-inject mode Spring is already started.
            // SessionFactoryOptionsBuilder.statementInspector is not final —
            // Hibernate reads it on every new session creation, so this takes
            // effect immediately for all subsequent queries.
            patchStatementInspector(ctx, agentCl);

            log("[livewire] ✓ Livewire nREPL started on port " + port
                    + " (lw/q/trace/hq/trace-sql all active)");
            return port;
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }
    }

    /**
     * Starts a minimal Clojure + nREPL server using the Clojure runtime bundled
     * in this agent jar. Works even when Livewire is not on the target classpath.
     * <p>
     * A child {@link java.net.URLClassLoader} (parent = {@code appCl}) loads
     * Clojure from the agent jar, so Spring classes remain accessible via
     * parent delegation. This gives us a live REPL inside the target JVM for
     * interactive exploration.
     */
    private static int startExplorationNrepl(ClassLoader appCl, int port) throws Exception {
        java.net.URL agentUrl = LivewireAgent.class
                .getProtectionDomain().getCodeSource().getLocation();

        // Parent = appCl so Clojure code can reach Spring/Hibernate classes.
        ClassLoader agentCl = new java.net.URLClassLoader(
                new java.net.URL[]{agentUrl}, appCl);

        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(agentCl);
        try {
            Class<?> clj  = agentCl.loadClass("clojure.java.api.Clojure");
            java.lang.reflect.Method var  = clj.getMethod("var",  Object.class, Object.class);
            java.lang.reflect.Method read = clj.getMethod("read", String.class);
            Class<?> ifn  = agentCl.loadClass("clojure.lang.IFn");
            java.lang.reflect.Method inv1 = ifn.getMethod("invoke", Object.class);
            java.lang.reflect.Method inv2 = ifn.getMethod("invoke", Object.class, Object.class);

            // (require 'nrepl.server)
            Object requireFn = var.invoke(null, "clojure.core", "require");
            inv1.invoke(requireFn, read.invoke(null, "nrepl.server"));

            // (nrepl.server/start-server :port port)
            Object startFn = var.invoke(null, "nrepl.server", "start-server");
            inv2.invoke(startFn, read.invoke(null, ":port"), (long) port);

            log("[livewire] ✓ exploration nREPL started on port " + port);
            log("[livewire]   (no Spring context injected — explore freely via eval)");
            return port;
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }
    }

    // ─── StatementInspector patching ──────────────────────────────────────────

    /**
     * Patches Hibernate's {@code StatementInspector} so that {@code trace-sql}
     * captures SQL queries in agent-inject mode.
     * <p>
     * Normally {@code LivewireEnvironmentPostProcessor} injects the inspector
     * before the {@code SessionFactory} is built. When Livewire is loaded via
     * agent, the {@code SessionFactory} is already constructed with no inspector.
     * {@code SessionFactoryOptionsBuilder.statementInspector} is not final —
     * Hibernate reads it on every new session construction, so patching it
     * here takes effect immediately for all subsequent queries.
     */
    private static void patchStatementInspector(Object ctx, ClassLoader agentCl) {
        try {
            // Get entityManagerFactory bean from the Spring context
            Class<?> ctxClass    = ctx.getClass();
            Object   emf         = ctxClass.getMethod("getBean", String.class)
                                           .invoke(ctx, "entityManagerFactory");
            // Unwrap to SessionFactoryImplementor
            Class<?> sfiClass    = agentCl.loadClass(
                    "org.hibernate.engine.spi.SessionFactoryImplementor");
            Object   sfi         = emf.getClass()
                                      .getMethod("unwrap", Class.class)
                                      .invoke(emf, sfiClass);
            Object   opts        = sfi.getClass()
                                      .getMethod("getSessionFactoryOptions")
                                      .invoke(sfi);
            java.lang.reflect.Field field = opts.getClass()
                                                .getDeclaredField("statementInspector");
            field.setAccessible(true);

            // Create a LivewireSqlTracer instance from agentCl
            Object tracer = agentCl.loadClass("net.brdloush.livewire.LivewireSqlTracer")
                                   .getDeclaredConstructor()
                                   .newInstance();
            field.set(opts, tracer);
            log("[livewire] ✓ StatementInspector patched — trace-sql will capture SQL");
        } catch (Exception e) {
            log("[livewire] note: could not patch StatementInspector ("
                    + e.getMessage() + ") — trace-sql may not capture SQL");
        }
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
