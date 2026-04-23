// Livewire attach.jsh — zero-install bootstrap for live Spring Boot inspection.
// Usage: echo "/open https://raw.githubusercontent.com/brdloush/livewire/refs/heads/main/attach.jsh" | jshell
//
// Set LIVEWIRE_BUNDLE_PATH to a local jar path to skip the download (dev/offline use).

// ─── bootstrap ────────────────────────────────────────────────────────────────

String  _LW_VERSION     = "0.12.0-SNAPSHOT";
String  _LW_VERSIONS_URL =
    "https://raw.githubusercontent.com/brdloush/livewire/refs/heads/main/versions.json";

// Mutable session state — populated by attach(int).
String[]      _lw_pid         = {null};   // [0] = pid of attached JVM (String)
Object[]      _lw_helpers     = {null};   // [0] = AttachHelpers instance (via reflection)
Object[]      _lw_client      = {null};   // [0] = Client instance (via reflection)
ClassLoader[] _lw_cl          = {null};   // [0] = URLClassLoader holding bundle classes
String[]      _lw_bundle_path = {null};   // [0] = resolved local path to bundle jar

// ─── internal helpers ─────────────────────────────────────────────────────────

/** Print a [livewire] prefixed message to stdout. */
void _lw_print(String msg) {
    System.out.println("[livewire] " + msg);
}

/** Resolve the local bundle jar path: env override > download. */
String _lw_resolveBundle() throws Exception {
    String envPath = System.getenv("LIVEWIRE_BUNDLE_PATH");
    if (envPath != null && !envPath.isBlank()) {
        _lw_print("using local bundle: " + envPath);
        return envPath;
    }

    // --- read versions.json to find the download URL + sha256 for this version ---
    _lw_print("attach.jsh v" + _LW_VERSION + " — fetching versions.json...");
    java.net.URL vurl = new java.net.URI(_LW_VERSIONS_URL).toURL();
    String versionsJson;
    try (java.io.InputStream in = vurl.openStream()) {
        versionsJson = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
        _lw_print("couldn't fetch versions.json from " + _LW_VERSIONS_URL + ": " + e.getMessage());
        _lw_print("set LIVEWIRE_BUNDLE_PATH to a local jar to skip the download.");
        throw e;
    }

    // Minimal JSON parsing — no external deps allowed here.
    // versions.json shape: {"latest":"X","versions":{"X":{"bundle-url":"...","sha256":"..."}}}
    String bundleUrl = _lw_jsonString(versionsJson, "bundle-url");
    String sha256    = _lw_jsonString(versionsJson, "sha256");

    String tmpPath = "/tmp/livewire-bundle-" + _LW_VERSION + ".jar";
    java.io.File tmpFile = new java.io.File(tmpPath);

    // Reuse cached file if checksum matches.
    if (tmpFile.exists() && _lw_sha256(tmpPath).equalsIgnoreCase(sha256)) {
        _lw_print("bundle already cached → " + tmpPath);
        return tmpPath;
    }

    _lw_print("downloading bundle from " + bundleUrl + " ...");
    java.net.URL burl = new java.net.URI(bundleUrl).toURL();
    try (java.io.InputStream in  = burl.openStream();
         java.io.OutputStream out = new java.io.FileOutputStream(tmpFile)) {
        in.transferTo(out);
    } catch (Exception e) {
        _lw_print("couldn't download bundle from " + bundleUrl + ": " + e.getMessage());
        _lw_print("check your network, or set LIVEWIRE_BUNDLE_PATH to a local jar.");
        throw e;
    }

    // Verify checksum.
    String actual = _lw_sha256(tmpPath);
    if (!actual.equalsIgnoreCase(sha256)) {
        _lw_print("SHA-256 mismatch for " + tmpPath);
        _lw_print("  expected: " + sha256);
        _lw_print("  got:      " + actual);
        throw new RuntimeException("bundle checksum mismatch");
    }

    long sizeKb = tmpFile.length() / 1024;
    _lw_print("bundle downloaded (" + sizeKb + " KB) → " + tmpPath);
    return tmpPath;
}

/** Compute SHA-256 hex digest of a file. */
String _lw_sha256(String path) throws Exception {
    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
    byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path));
    md.update(bytes);
    StringBuilder sb = new StringBuilder();
    for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
    return sb.toString();
}

/** Minimal JSON string-value extractor — finds the first occurrence of "key":"value". */
String _lw_jsonString(String json, String key) {
    String needle = "\"" + key + "\"";
    int ki = json.indexOf(needle);
    if (ki < 0) return "";
    int colon = json.indexOf(':', ki + needle.length());
    int q1    = json.indexOf('"', colon + 1);
    int q2    = json.indexOf('"', q1 + 1);
    return json.substring(q1 + 1, q2);
}

/** Load the bundle jar into an isolated URLClassLoader and store it in _lw_cl[0]. */
void _lw_loadBundle(String jarPath) throws Exception {
    java.net.URL jarUrl = java.nio.file.Path.of(jarPath).toUri().toURL();
    // Parent = system classloader. Isolated from jshell's own classpath so that
    // a future JLine 3 load (Part 2) doesn't collide with jshell's embedded JLine.
    _lw_cl[0] = new java.net.URLClassLoader(new java.net.URL[]{jarUrl},
                                             ClassLoader.getSystemClassLoader());
}

/** Reflectively call a static method on a bundle class, returning its String result. */
String _lw_callStatic(String className, String methodName, Object... args) {
    try {
        Class<?> cls = _lw_cl[0].loadClass(className);
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) paramTypes[i] = args[i].getClass();
        java.lang.reflect.Method m = cls.getMethod(methodName, paramTypes);
        Object result = m.invoke(null, args);
        return result == null ? "" : result.toString();
    } catch (Exception e) {
        return "[livewire] error calling " + className + "." + methodName + ": " + e.getMessage();
    }
}

// ─── scan and print JVM list on startup ───────────────────────────────────────

{
    try {
        String jarPath = _lw_resolveBundle();
        _lw_bundle_path[0] = jarPath;
        _lw_loadBundle(jarPath);

        _lw_print("scanning for attachable JVMs...");
        System.out.println();

        // JvmScanner.listJvms() returns List<String> — each entry is one display line.
        Class<?> scannerClass = _lw_cl[0].loadClass("net.brdloush.livewire.attach.JvmScanner");
        java.lang.reflect.Method listMethod = scannerClass.getMethod("listJvms");
        @SuppressWarnings("unchecked")
        java.util.List<String> jvms = (java.util.List<String>) listMethod.invoke(null);

        if (jvms.isEmpty()) {
            System.out.println("  (no attachable JVMs found — is a Spring Boot app running on this host?)");
        } else {
            for (String entry : jvms) {
                System.out.println("  " + entry);
            }
        }
        System.out.println();
        _lw_print("to attach, type:  attach(1)");
        _lw_print("type  help()  for the list of available commands.");
        System.out.println();
    } catch (Exception _lw_e) {
        _lw_print("startup error: " + _lw_e.getMessage());
    }
}

// ─── public API ───────────────────────────────────────────────────────────────

/** Attach to the JVM at position [index], using a custom nREPL port. */
void attach(int index, int port) {
    if (_lw_bundle_path[0] == null) { _lw_print("bundle not loaded — restart jshell"); return; }
    try {
        // 1. Resolve PID from the scan list.
        Class<?> scannerClass = _lw_cl[0].loadClass("net.brdloush.livewire.attach.JvmScanner");
        String pid = (String) scannerClass.getMethod("getPid", int.class).invoke(null, index);

        // 2. Load agent into the target JVM.
        _lw_print("loading agent into pid " + pid + " ...");
        Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        Object   vm      = vmClass.getMethod("attach", String.class).invoke(null, pid);
        try {
            vmClass.getMethod("loadAgent", String.class, String.class)
                   .invoke(vm, _lw_bundle_path[0], String.valueOf(port));
        } finally {
            vmClass.getMethod("detach").invoke(vm);
        }
        _lw_print("✓ agent loaded");

        // 3. Poll for the port file written by the agent (up to 15s).
        java.nio.file.Path portFile = java.nio.file.Path.of("/tmp/livewire-attach-" + pid + ".port");
        int actualPort = -1;
        for (int i = 0; i < 150; i++) {
            if (java.nio.file.Files.exists(portFile)) {
                actualPort = Integer.parseInt(java.nio.file.Files.readString(portFile).trim());
                break;
            }
            Thread.sleep(100);
        }
        if (actualPort < 0) {
            _lw_print("ERROR: agent loaded but no port file appeared at " + portFile);
            _lw_print("       check the target JVM's stdout or /tmp/livewire-attach.log");
            return;
        }

        // 4. Connect the nREPL client.
        Class<?> clientClass = _lw_cl[0].loadClass("net.brdloush.livewire.attach.Client");
        Object   client      = clientClass.getDeclaredConstructor(int.class).newInstance(actualPort);
        clientClass.getMethod("connect").invoke(client);
        String sessionId = (String) clientClass.getMethod("getSession").invoke(client);

        _lw_pid[0]    = pid;
        _lw_client[0] = client;

        _lw_print("✓ nREPL server on 127.0.0.1:" + actualPort);
        _lw_print("✓ client connected (session " + sessionId + ")");
        _lw_print("ready. try:  info()  or  eval(\"(+ 1 2)\")");

    } catch (Exception e) {
        _lw_print("attach failed: " + e.getMessage());
        // Full stack trace to log file
        try (var pw = new java.io.PrintWriter(new java.io.FileWriter("/tmp/livewire-attach.log", true))) {
            pw.println("--- attach(" + index + ") " + new java.util.Date() + " ---");
            e.printStackTrace(pw);
        } catch (Exception ignored) {}
    }
}

/** Attach to the JVM at position [index] in the list printed above (default nREPL port 7888). */
// Defined after the two-arg overload so jshell resolves the call correctly.
void attach(int index) {
    attach(index, 7888);
}

/** Print runtime, datasource, and framework version info for the attached JVM. */
void info() {
    if (_lw_helpers[0] == null) { _lw_print("not attached — run attach(N) first"); return; }
    System.out.println(_lw_callStatic("net.brdloush.livewire.attach.AttachHelpers", "info"));
}

/** List Spring beans whose names match the given regex pattern. */
void beans(String pattern) {
    if (_lw_helpers[0] == null) { _lw_print("not attached — run attach(N) first"); return; }
    System.out.println(_lw_callStatic("net.brdloush.livewire.attach.AttachHelpers", "beans", pattern));
}

/** Evaluate arbitrary Clojure code against the live nREPL session. */
void eval(String clojureCode) {
    if (_lw_client[0] == null) { _lw_print("not attached — run attach(N) first"); return; }
    // Use Client directly (AttachHelpers wired in Step 5).
    try {
        Class<?> cc  = _lw_cl[0].loadClass("net.brdloush.livewire.attach.Client");
        Object result = cc.getMethod("eval", String.class).invoke(_lw_client[0], clojureCode);
        System.out.println(result);
    } catch (Exception e) {
        _lw_print("eval error: " + e.getMessage());
    }
}

/** Run a read-only SQL query through the live DataSource and print results. */
void sql(String query) {
    if (_lw_helpers[0] == null) { _lw_print("not attached — run attach(N) first"); return; }
    System.out.println(_lw_callStatic("net.brdloush.livewire.attach.AttachHelpers", "sql", query));
}

/** Show something interesting about the attached application (N+1 demo, bean count, etc.). */
void demo() {
    if (_lw_helpers[0] == null) { _lw_print("not attached — run attach(N) first"); return; }
    System.out.println(_lw_callStatic("net.brdloush.livewire.attach.AttachHelpers", "demo"));
}

/** Stop the nREPL server, close the client connection, keep jshell running. */
void detach() {
    if (_lw_client[0] == null) { _lw_print("not attached"); return; }
    if (_lw_helpers[0] != null) {
        System.out.println(_lw_callStatic("net.brdloush.livewire.attach.AttachHelpers", "detach"));
    } else {
        try {
            Class<?> cc = _lw_cl[0].loadClass("net.brdloush.livewire.attach.Client");
            cc.getMethod("close").invoke(_lw_client[0]);
            _lw_print("detached ✓");
        } catch (Exception e) { _lw_print("detach error: " + e.getMessage()); }
    }
    _lw_helpers[0] = null;
    _lw_client[0]  = null;
    _lw_pid[0]     = null;
}

/** Print available commands. */
void help() {
    System.out.println();
    System.out.println("Livewire jshell client v" + _LW_VERSION + " — available commands:");
    System.out.println();
    System.out.println("  attach(N)              — inject Livewire into JVM #N from the list above");
    System.out.println("  attach(N, port)        — same, using a custom nREPL port (default: 7888)");
    System.out.println("  info()                 — runtime, datasource, framework versions");
    System.out.println("  beans(pattern)         — list Spring beans matching regex");
    System.out.println("  demo()                 — show something interesting about this app");
    System.out.println("  eval(clojureCode)      — evaluate arbitrary Clojure against the nREPL");
    System.out.println("  sql(query)             — run read-only SQL through the live DataSource");
    System.out.println("  detach()               — stop the nREPL, unload the client, keep jshell running");
    System.out.println("  help()                 — this message");
    System.out.println();
    System.out.println("  Full Livewire API (for use inside eval):  https://github.com/brdloush/livewire");
    System.out.println("  Tip: once you've seen what's possible here, try the agentic workflow —");
    System.out.println("       point Claude Code at the same nREPL on port 7888 and let it explore.");
    System.out.println();
    System.out.println("  \u26a0 Dev/staging only — never attach to a JVM with real user data.");
    System.out.println();
}
