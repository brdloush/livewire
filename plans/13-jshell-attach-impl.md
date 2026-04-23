# Implementation Plan — jshell-attach Part 1

**Spec:** `specs/13-jshell-attach.md`  
**Scope:** Part 1 only — one-step attach with minimal REPL. Part 2 (rich JLine sub-REPL) is explicitly deferred.  
**Working style:** Runnable slices — each step ends with something testable in jshell before moving on. Pause and verify with the user after every step.

---

## Key design decisions

- **Module layout:** New `attach/` subdirectory inside the existing repo. Self-contained with its own `deps.edn` and `build.clj`. Not a Leiningen project — pure `deps.edn` + `tools.build`.
- **Artifact:** Separate Maven artifact `net.brdloush:livewire-attach` (not a classifier on the existing jar). Cleaner dep tree for regular Livewire users.
- **Bundle jar contents:** One uber-jar containing both halves:
  - Agent payload (`LivewireAgent`) — loaded into the target JVM.
  - jshell-side client (`Client`, `JvmScanner`, `AttachHelpers`) — loaded by `attach.jsh` via `URLClassLoader`.
  - Both halves are isolated by design; they never use each other's classes at runtime.
- **Jar release target:** Java 17 (`--release 17`), consistent with the main `livewire` artifact.
- **Agent manifest entries required:**
  ```
  Agent-Class: net.brdloush.livewire.attach.LivewireAgent
  Can-Retransform-Classes: true
  Can-Redefine-Classes: true
  ```

---

## Files to create

| Path | Purpose |
|---|---|
| `attach/deps.edn` | `tools.build` `:build` alias; no runtime Clojure deps |
| `attach/build.clj` | `tools.build` script: `jar` task compiles Java, produces uber-jar with agent manifest |
| `attach/src/net/brdloush/livewire/attach/LivewireAgent.java` | Agent entry point (`agentmain`), ApplicationContext discovery, nREPL boot |
| `attach/src/net/brdloush/livewire/attach/Client.java` | bencode nREPL client (~200 lines pure Java) |
| `attach/src/net/brdloush/livewire/attach/JvmScanner.java` | Wraps `VirtualMachine.list()`, filters/annotates JVM entries |
| `attach/src/net/brdloush/livewire/attach/AttachHelpers.java` | Thin wrappers for `info()`, `beans()`, `demo()`, `sql()`, `detach()` |
| `attach.jsh` | jshell bootstrap script, checked into repo root |
| `versions.json` | Maps `attach.jsh` versions to bundle download URLs + SHA-256 checksums |

## Files to modify

| Path | Change |
|---|---|
| `bb.edn` | Add `attach-jar` task; later extend `bundle` for attach artifact |
| `README.md` | Add one-liner "try it yourself" command above Maven/Gradle install block |
| `skills/livewire/SKILL.md` | Note jshell attach availability |

---

## Steps

### Step 1 — `attach/` module skeleton + `attach-jar` bb task

**Goal:** A buildable `deps.edn` project producing a jar with the correct agent manifest.

**Deliverables:**
- `attach/deps.edn` with `:build` alias pulling in `io.github.clojure/tools.build`
- `attach/build.clj` with a `jar` fn that:
  - Compiles Java sources under `attach/src/`
  - Produces `attach/target/livewire-attach-<version>.jar`
  - Injects agent manifest entries (`Agent-Class`, `Can-Retransform-Classes`, `Can-Redefine-Classes`)
- Stub classes for all four Java files (no-op implementations, just enough to compile)
- Root `bb.edn`: new `attach-jar` task that shells out to `clj -T:build jar` in the `attach/` dir

**Verify:** `bb attach-jar` completes cleanly. `jar tf target/...jar` shows the class files and `META-INF/MANIFEST.MF`. `unzip -p target/...jar META-INF/MANIFEST.MF` shows the agent entries.

---

### Step 2 — `attach.jsh`: download + JVM list

**Goal:** A jshell script that loads the bundle jar and lists attachable JVMs.

**Deliverables:**
- `attach.jsh` at repo root
- On load:
  - Prints version banner: `[livewire] attach.jsh vX.Y.Z — downloading bundle...`
  - Respects `LIVEWIRE_BUNDLE_PATH` env var for local override (skips download if set)
  - Falls back to downloading from URL in `versions.json` with SHA-256 verification
  - Loads the jar via `URLClassLoader` parented to the system classloader
  - Reflectively calls `JvmScanner` to list attachable JVMs
  - Prints the numbered JVM list (pid, main class, Spring Boot version if detectable)
  - Prints next-step guidance: `[livewire] to attach, type: attach(1)`
- Defines top-level jshell methods: `attach(int)` stub, `help()` (content from spec §2.1)

**Verify:** Run `LIVEWIRE_BUNDLE_PATH=/path/to/jar jshell attach.jsh` locally. JVM list appears including the bloated-shelf process. `attach(1)` prints "not yet implemented". `help()` prints the full command list.

---

### Step 3 — bencode nREPL client (`Client.java`)

**Goal:** A working nREPL wire client in pure Java.

**Deliverables:**
- `Client.java` implementing:
  - TCP socket to `127.0.0.1:<port>` (default 7888)
  - Bencode encoder: `String`, `long`, `Map`, `List`
  - Bencode decoder: handles partial reads, accumulates until `:status done`
  - `clone()` → new nREPL session ID
  - `eval(session, code)` → `:value` string from response
  - `describe()` → server capabilities map
  - `interrupt(session)` → sends `:interrupt` op
  - `close()` → closes socket
  - Session is created once on `connect()` and reused across calls (not create-and-close per eval)

**Part 2 constraint:** Keep `Client` separable from `AttachHelpers`. Part 2 will use `Client` directly from its own REPL loop, bypassing the reflection wrappers that jshell helper methods use.

**Verify:** With bloated-shelf already running Livewire on port 7888, instantiate `Client` manually in jshell and call `eval("(+ 1 2)")` — should return `"3"`. Call `eval("(lw/bean \"bookRepository\")")` — should return a string representation.

---

### Step 4 — Java agent: `LivewireAgent.java` + ApplicationContext discovery

**Goal:** An `agentmain` that finds the Spring ApplicationContext and boots the nREPL server inside the target JVM.

**Deliverables:**
- `LivewireAgent.java` with `agentmain(String args, Instrumentation inst)` entry point
- ApplicationContext discovery — three strategies tried in order:
  1. **JMX path** — query `org.springframework.boot:type=Admin,name=SpringApplication` via `ManagementFactory.getPlatformMBeanServer()`; no classloader issues since JMX is in `java.management`
  2. **Thread stack walk** — `Thread.getAllStackTraces()`, find thread with a `LaunchedURLClassLoader` context classloader, use it to reflectively find the Spring context
  3. **Instrumentation fallback** — `inst.getAllLoadedClasses()`, find a `ConfigurableApplicationContext` implementation, locate its live instance
  - Each path logs which one worked at DEBUG level
  - If all three fail: print legible error (see error table) and do NOT start nREPL
- Once context is found:
  - Reflectively invoke `LivewireBootstrapBean` (or equivalent `boot/start!` path) using the discovered classloader — behave as if `livewire.enabled=true`
  - Write the resolved nREPL port to `/tmp/livewire-attach-<pid>.port` for jshell side to read
- `JvmScanner.java` implemented for real:
  - `VirtualMachine.list()` → annotated list of attachable JVMs
  - Per-JVM: pid, main class, detect Spring Boot presence (check system properties or JMX), Java version
  - Filter: skip non-attachable entries gracefully

**Verify:** Run `attach(N)` in jshell targeting bloated-shelf. Agent loads; `/tmp/livewire-attach-<pid>.port` appears with `7888`. `Client` connects and `eval("(+ 1 2)")` returns `"3"`.

---

### Step 5 — Wire up `attach.jsh` helper methods

**Goal:** Full transcript from spec §2.1 can be reproduced end-to-end.

**Deliverables:**
- `AttachHelpers.java` implementing (all return `String` for easy jshell printing):
  - `info()` → EDN map: application name, Spring Boot version, Hibernate version, Java version, datasource details
  - `beans(String pattern)` → list of bean names matching regex
  - `demo()` → calls `trace/trace-sql` on a bean count, returns formatted result
  - `eval(String clojureCode)` → raw nREPL eval result, pretty-printed
  - `sql(String query)` → runs read-only SQL via live DataSource, returns tabular result
  - `detach()` → sends nREPL `close` op, closes `Client` socket
- `attach.jsh` updated:
  - `attach(int index)` — full implementation: calls `VirtualMachine.loadAgent(bundleJarPath)`, waits for `/tmp/livewire-attach-<pid>.port`, connects `Client`
  - `attach(int index, int port)` — overload for custom port
  - `info()`, `beans(String)`, `eval(String)`, `sql(String)`, `demo()`, `detach()` — thin reflection wrappers over `AttachHelpers`
  - On `/exit`: auto-detach if session is live

**Verify:** Full transcript from spec §2.1 reproduced against bloated-shelf: `attach(N)` → `info()` → `beans(".*Repository.*")` → `eval(...)` → `help()`.

---

### Step 6 — Error handling

**Goal:** Every error condition from spec §2.5 produces a single human-readable line. No raw stack traces to the terminal.

**Error table (from spec §2.5):**

| Condition | Message |
|---|---|
| `AttachNotSupportedException` — target running as different user | `[livewire] can't attach to pid N — it's running as user 'root' but you're 'tomas'. Try: sudo jshell` |
| `AgentLoadException` on Java 21+ without dynamic agent flag | `[livewire] agent load blocked. Java 21+ requires -XX:+EnableDynamicAgentLoading on the target JVM. Restart the target with that flag and retry.` |
| Target JVM exists but is not Spring | `[livewire] attached, but no Spring ApplicationContext found in pid N. Is this actually a Spring Boot app?` |
| Target JVM exists but is GraalVM native image | `[livewire] pid N is a native image — Livewire needs a JVM with the Attach API. This won't work.` |
| Bundle download fails | `[livewire] couldn't download bundle from <url>: <reason>. Check your network, or set LIVEWIRE_BUNDLE_PATH to a local jar.` |
| nREPL port already in use | `[livewire] port 7888 is in use. Try: attach(N, 7999)` |
| nREPL unreachable after attach | `[livewire] agent loaded but nREPL isn't answering on 127.0.0.1:7888. Check the target JVM's logs.` |

**Deliverables:**
- All exceptions caught in `attach.jsh` helper methods; routed to human-readable messages above
- Stack traces written to `/tmp/livewire-attach.log`, not the terminal
- `System.setProperty("livewire.attach.verbose", "true")` before `/open` flips stack traces back to stderr
- `help()` output includes one-line dev-only reminder: `⚠ Dev/staging only — never attach to a JVM with real user data.`

**Verify:** Simulate each condition (attach to wrong-user process, non-Spring JVM, already-used port) and confirm message matches the table. Check `/tmp/livewire-attach.log` contains the full stack trace.

---

### Step 7 — `versions.json` + build/release wiring

**Goal:** `attach.jsh` URL is stable forever; bundle updates don't require blogged URLs to change.

**Deliverables:**
- `versions.json` at repo root:
  ```json
  {
    "latest": "0.12.0",
    "versions": {
      "0.12.0": {
        "bundle-url": "https://github.com/brdloush/livewire/releases/download/v0.12.0/livewire-attach-0.12.0.jar",
        "sha256": "<hash>"
      }
    }
  }
  ```
- `attach.jsh` reads `versions.json` at load time to resolve download URL; prints both its own version and the resolved bundle version in the banner
- `attach-X.Y.Z.jsh` pinned variants served from repo for reproducibility (old blog posts)
- Root `bb.edn` extended:
  - `attach-source-jar` — sources jar for Maven Central
  - `attach-javadoc-jar` — placeholder javadoc jar
  - `attach-pom` — generates POM for `net.brdloush:livewire-attach`
  - `attach-release-jars` — depends on all above; reports ✅/❌
  - `bundle` task extended to include attach artifact alongside the existing livewire artifact

**Verify:** `bb attach-jar` + `bb attach-release-jars` complete cleanly. `attach.jsh` reads `versions.json`, resolves the URL, and prints the correct version banner. SHA-256 verification passes for a local jar.

---

### Step 8 — Docs + SKILL.md

**Goal:** First thing a visitor sees in the README is the one-liner. SKILL.md reflects the new attach path.

**Deliverables:**
- `README.md`: one-liner "try it yourself" command added as the **primary hook**, above the existing Maven/Gradle install instructions
- `skills/livewire/SKILL.md`: new section documenting the jshell attach path — what it is, how to trigger it, what `info()` / `beans()` / `eval()` / `sql()` return
- `help()` output (already wired in Step 5/6) includes pointer to full Livewire API and the agentic Claude Code workflow tip (from spec §2.1)

**Verify:** README renders correctly. SKILL.md `help()` section matches actual `help()` output from jshell.

---

## Acceptance criteria (from spec §4)

1. Transcript in spec §2.1 reproduced end-to-end against bloated-shelf on Linux + JDK 21, no prior installation beyond JDK.
2. All 7 error conditions in spec §2.5 produce specified one-line message, not a stack trace.
3. Agent works against Spring Boot 3.x and 4.x without rebuild.
4. README top section updated with one-liner "try it yourself" command as primary hook.
5. `help()` output includes dev-only reminder and pointer to agentic Claude Code workflow.
6. `versions.json` + `attach.jsh` version banner wired so future bundle updates don't break blogged URLs.

---

## Open questions (from spec §5 — resolved for this plan)

1. **Maven Central vs GitHub Releases for bundle jar:** Maven Central — consistent with existing release pipeline.
2. **Classifier vs separate artifact:** Separate artifact `net.brdloush:livewire-attach` — cleaner dep tree.
3. **Fourth ApplicationContext discovery strategy:** Worth evaluating `Instrumentation.getInitiatedClasses(systemCL)` during Step 4 implementation; add as fourth fallback if the three primary strategies prove insufficient in practice.
4. **Windows support:** Best-effort, document known issues. No dedicated CI runner for Part 1.

---

## Part 2 constraints (do not violate during Part 1)

- Bundle jar's `URLClassLoader` must be isolated from jshell's own classpath (parent = system classloader). Required so JLine 3 can be loaded later without collision.
- Keep `Client` separable from `AttachHelpers` — Part 2 reuses `Client` directly from its REPL loop.
- nREPL session opened by `attach(...)` must be reusable across `eval()` calls and future `repl()` invocations. No create-and-close per call.
- Output pretty-printing must be a swappable component, not hardcoded into each helper method.
