# Livewire jshell-attach — Feature Spec

**Status:** Proposed
**Target:** Claude Code agent working against `github.com/brdloush/livewire`
**Scope:** Two-part delivery. Part 1 ships a zero-install bootstrap path ("try it yourself"). Part 2 adds a rich sub-REPL experience on top. This spec covers Part 1 in full and Part 2 at the level of intent + boundaries so Part 1 doesn't paint Part 2 into a corner.

---

## 1. Motivation

Livewire today requires a build-time opt-in: add the Maven/Gradle dependency, set `livewire.enabled=true`, rebuild, restart. That's fine for the user who already decided to adopt Livewire. It's a wall for the curious user who wants to try it against their own running Spring Boot app in under a minute.

The goal of this feature is to collapse first-run friction to a single shell command, with **no changes to the target application**, **no package manager**, and **no dependency pre-installed beyond a JDK**:

```bash
echo "/open https://raw.githubusercontent.com/brdloush/livewire/refs/heads/main/attach.jsh" | jshell
```

Running this on the same host as a live Spring Boot JVM should:
1. Download the Livewire agent bundle.
2. List attachable JVMs and let the user pick one.
3. Inject Livewire into the chosen JVM at runtime.
4. Drop the user into a working REPL prompt (basic in Part 1, rich in Part 2).

The intended audience is a Java/Spring developer who has never heard of nREPL, Clojure, or babashka. The conversion goal is "oh, that was easy — let me read the README to see what else this can do." It is **not** to provide a production-grade REPL experience; the rich editing story is Part 2.

---

## 2. Part 1 — One-step attach with minimal REPL

### 2.1 User-facing experience (the contract)

The entire Part 1 experience is defined by this transcript. The implementation should hit these beats; deviations need justification.

```
$ echo "/open https://raw.githubusercontent.com/brdloush/livewire/refs/heads/main/attach.jsh" | jshell
|  Welcome to JShell -- Version 21.0.4
|  For an introduction type: /help intro

[livewire] attach.jsh v0.12.0 — downloading bundle...
[livewire] bundle downloaded (5.8 MB) → /tmp/livewire-bundle-0.12.0.jar
[livewire] scanning for attachable JVMs...

  [1] pid 28451  com.example.OrderService         (Spring Boot 3.2, Java 21)
  [2] pid 28612  com.example.BillingWorker        (Spring Boot 4.0, Java 21)
  [3] pid 31004  org.jetbrains.idea.maven.server  (not Spring — skipped by default)

[livewire] to attach, type:  attach(1)
[livewire] type  help()  for the list of available commands.

jshell> attach(1)
[livewire] loading agent into pid 28451...
[livewire] ✓ agent loaded
[livewire] ✓ Spring ApplicationContext discovered: "order-service"
[livewire] ✓ nREPL server started on 127.0.0.1:7888
[livewire] ✓ client connected (session bcd-1234)
[livewire] ready. try:  info()  or  demo()

jshell> info()
{:application-name "order-service"
 :spring-boot "3.2.1"
 :hibernate "6.4.0.Final"
 :java "21.0.4"
 :datasource {:db-product "PostgreSQL 16.2"
              :jdbc-url   "jdbc:postgresql://localhost:5432/orders"
              :pool-name  "HikariPool-1"
              :pool-size-max 10}}

jshell> beans(".*Repository.*")
[bookRepository, authorRepository, customerRepository, orderRepository, reviewRepository]

jshell> eval("(trace/trace-sql (.count (lw/bean \"orderRepository\")))")
{:result 1284
 :count 1
 :duration-ms 12
 :queries [{:sql "select count(*) from orders o1_0" ...}]}

jshell> help()
Livewire jshell client — available commands:

  info()                     — runtime, datasource, framework versions
  beans(pattern)             — list Spring beans matching regex
  demo()                     — show something interesting about this app
  eval(clojureCode)          — evaluate arbitrary Clojure against the nREPL
  sql(query)                 — run read-only SQL through the live DataSource
  detach()                   — stop the nREPL, unload the client, keep jshell running
  help()                     — this message

Full Livewire API (for use inside eval):  https://github.com/brdloush/livewire
Tip: once you've seen what's possible here, try the agentic workflow —
     point Claude Code at the same nREPL on port 7888 and let it explore.

jshell> /exit
[livewire] detaching... ✓
|  Goodbye
$
```

### 2.2 Deliverables

**D1. `attach.jsh`** — single jshell script, checked into the repo root, served via `raw.githubusercontent.com`.

- Downloads the bundle jar to a temp path (or reuses a cached one if checksum matches).
- Defines the following methods in the jshell session's top-level scope: `attach(int)`, `info()`, `beans(String)`, `demo()`, `eval(String)`, `sql(String)`, `detach()`, `help()`.
- On load, lists attachable JVMs and prints next-step guidance.
- Contains a version banner: `[livewire] attach.jsh vX.Y.Z`. This version must match the bundle jar it pulls.

**D2. `livewire-bundle` jar** — a new Maven artifact (or classifier on the existing artifact), published to Maven Central alongside the existing `net.brdloush:livewire`. Contents:

- The **agent payload**: manifest with `Agent-Class` pointing at a new `net.brdloush.livewire.attach.LivewireAgent` class. `agentmain` discovers the Spring `ApplicationContext` and boots the nREPL server (reusing the existing Livewire autoconfig logic as much as possible).
- The **jshell-side client**: a small bencode-over-socket nREPL client (~200 lines of pure Java, no Clojure runtime on the jshell side). Implements `eval`, `clone`, `describe`, `interrupt`. Exposed as `net.brdloush.livewire.attach.Client`.
- The **helper methods** called from `attach.jsh` — thin wrappers over `Client` plus pretty-printing for the specific shapes that `info()` / `beans()` / `demo()` return.

**D3. Build & release wiring** — CI job that publishes the bundle jar on release, and a `versions.json` served from the repo that maps `attach.jsh` versions to bundle URLs (see §2.6 on versioning).

### 2.3 Technical approach

**Attach mechanism.** Use `com.sun.tools.attach.VirtualMachine.attach(pid)` + `loadAgent(path)`. This is the standard dynamic agent attach path — same one Arthas, BTrace, and JProfiler use. The agent jar's `META-INF/MANIFEST.MF` must declare:

```
Agent-Class: net.brdloush.livewire.attach.LivewireAgent
Can-Retransform-Classes: true
Can-Redefine-Classes: true
```

(The `Can-*` flags aren't strictly needed for Part 1 but leave the door open for future hot-swap features without requiring users to re-download.)

**ApplicationContext discovery inside the agent.** This is the hardest single piece of the implementation and deserves its own §2.4.

**nREPL server boot.** Once the agent has an `ApplicationContext`, reuse the existing `LivewireBootstrap` / autoconfig path. The agent should behave as if `livewire.enabled=true` was set at startup — including aliases, SKILL.md-relevant setup, and `127.0.0.1` binding by default.

**jshell-side client classloading.** `attach.jsh` downloads the bundle jar to `/tmp/livewire-bundle-<version>.jar` and loads it via a `URLClassLoader` parented to the system classloader. Helper methods are defined in jshell such that they delegate via reflection into the loaded classes. Because jshell's own compilation doesn't know about the side classloader, the top-level helper methods must be written in terms of `Object`, `String`, and reflection — not typed references into the bundle. The bundle's public Client API should be designed with this in mind (string-in, string-or-primitive-out where possible).

**Wire protocol.** Standard nREPL bencode over TCP. Client speaks to `127.0.0.1:<port>`. Port configurable via `attach(pid, port)` overload; defaults to 7888.

### 2.4 ApplicationContext discovery — the one hard problem

The agent lands in the target JVM's system classloader, one level above Spring's `LaunchedURLClassLoader`. There's no static registry of live `ApplicationContext` instances. Implementation must try, in order:

1. **JMX path.** If the target has `spring.jmx.enabled=true` (default since Boot 2.2), the MBean `org.springframework.boot:type=Admin,name=SpringApplication` exposes the running context. Query it via `ManagementFactory.getPlatformMBeanServer()` — no classloader problems because JMX is in `java.management`.

2. **Thread stack walking.** Walk `Thread.getAllStackTraces().keySet()`, find a thread whose context classloader is a `LaunchedURLClassLoader` (or an embedded-jetty equivalent), use that classloader to load `org.springframework.context.ApplicationContext` and reflectively find the singleton via Spring's own registry.

3. **Instrumentation fallback.** `Instrumentation.getAllLoadedClasses()` → find a class whose type is `ConfigurableApplicationContext` → find its active instance via a static registry Spring maintains internally (`SpringApplication$Running` or similar; check current Boot internals).

Each path must log which one worked at DEBUG level. If all three fail, the agent prints a legible error (§2.5) and does not start the nREPL server.

**Constraint:** the agent must work against Spring Boot 3.x *and* 4.x without a compile-time dependency on either. All Spring-touching code in the agent goes through reflection against classes loaded from the discovered `LaunchedURLClassLoader`.

### 2.5 Error handling — the non-negotiables

The entire "try it yourself" pitch collapses the moment a user sees a raw Java stack trace. The following error paths must produce human-readable single-line diagnostics with a suggested remediation:

| Condition | Message |
|---|---|
| `AttachNotSupportedException` — target running as different user | `[livewire] can't attach to pid N — it's running as user 'root' but you're 'tomas'. Try: sudo jshell` |
| `AgentLoadException` on Java 21+ without dynamic agent flag | `[livewire] agent load blocked. Java 21+ requires -XX:+EnableDynamicAgentLoading on the target JVM. Restart the target with that flag and retry.` |
| Target JVM exists but is not Spring | `[livewire] attached, but no Spring ApplicationContext found in pid N. Is this actually a Spring Boot app?` |
| Target JVM exists but is GraalVM native image | `[livewire] pid N is a native image — Livewire needs a JVM with the Attach API. This won't work.` |
| Bundle download fails | `[livewire] couldn't download bundle from <url>: <reason>. Check your network, or set LIVEWIRE_BUNDLE_PATH to a local jar.` |
| nREPL port already in use | `[livewire] port 7888 is in use. Try: attach(N, 7999)` |
| Client-side eval fails (nREPL unreachable after attach) | `[livewire] agent loaded but nREPL isn't answering on 127.0.0.1:7888. Check the target JVM's logs.` |

Stack traces for these cases go to a log file (`/tmp/livewire-attach.log`), not the terminal. A `--verbose` mechanism (e.g. `System.setProperty("livewire.attach.verbose", "true")` before `/open`) flips them back to stderr for debugging.

### 2.6 Versioning and URL stability

`attach.jsh` is going to be screenshotted, blogged, and tweeted. The exact URL must remain stable **forever**. Strategy:

- `raw.githubusercontent.com/brdloush/livewire/refs/heads/main/attach.jsh` always points to the latest launcher.
- The launcher reads `versions.json` from the same path to discover which bundle jar to download. This indirection means bundle-only updates don't require the blogged URL to change.
- The launcher prints its own version *and* the resolved bundle version on startup.
- If a specific version is needed for reproducibility (old blog posts, etc.), `attach-X.Y.Z.jsh` variants are also served from the repo, pinned to that bundle. These are never modified.

### 2.7 Security posture

Part 1 inherits the existing Livewire security model and does not weaken it:

- Agent binds the nREPL server to `127.0.0.1` only.
- No `0.0.0.0` binding is possible via this flow — would require a separate, explicitly-named config path.
- The bundle jar is served from GitHub (or Maven Central, preferably — TBD in implementation). Launcher verifies SHA-256 checksum against a value in `versions.json` before loading.
- The README must loudly reiterate: **dev/staging only, never attach to a JVM with real user data.** The `help()` output should include a one-line reminder.

**Threat note:** anyone who can run `jshell` on the host can already attach to any JVM they have permission to attach to, with or without Livewire. Livewire-attach doesn't create new privilege; it makes existing privilege more ergonomic. That distinction belongs in the docs.

### 2.8 Platform support matrix

| Environment | Part 1 supported? |
|---|---|
| Linux, JDK 11–25, regular Spring Boot JVM | ✅ Primary target |
| macOS, same | ✅ |
| Windows, same | ⚠️ Best-effort. Attach API works; path handling and ANSI in jshell output need testing |
| JRE-only pod (no jshell, has `jdk.attach`) | ❌ Part 1 scope. Needs a separate bootstrap; out of scope |
| Distroless / minimal jlinked runtime | ❌ Document as unsupported |
| GraalVM native-image target | ❌ Fundamentally impossible; error clearly |
| Java 21+ without `EnableDynamicAgentLoading` | ⚠️ Works but warns. Document prominently |

### 2.9 Testing strategy (all of these optional at this point where no automatic tests exists)

- **Unit tests** for the bencode client — round-trip encode/decode, partial reads, large payloads, `:status done` handling.
- **Integration test** using testcontainers: spin up the `bloated-shelf` app in a container with a JDK, run `attach.jsh` against it from outside the container (or via `exec` inside), assert that `info()` returns a plausible map.
- **Smoke test CI job** that runs against a matrix of Spring Boot versions (3.2, 3.4, 4.0) and JDK versions (17, 21, 25).
- **Error-path tests** for each row in §2.5 — simulate the condition, assert the exact user-facing message.

### 2.10 Out of scope for Part 1

Explicitly deferred:

- Rich terminal editing (→ Part 2).
- Multi-line Clojure input in jshell (stays single-line for Part 1; users paste pre-written snippets).
- Tab completion against Livewire APIs.
- History persistence.
- Pretty-printing beyond naive line breaks for map entries.
- Cross-pod / cross-host attach (localhost only).
- Windows polish beyond "doesn't crash."
- Installing as a system command (`livewire-attach` binary) — the `echo | jshell` one-liner *is* the install story for Part 1.

---

## 3. Part 2 — Rich sub-REPL via JLine 3 (future task, sketched only)

### 3.1 Intent

Once a user runs `attach(1)` and wants more than single-line `eval("...")` calls, they type `repl()` and drop into a proper Clojure REPL experience — paren balancing, multi-line input, history, basic completion, ANSI pretty-printing. Ctrl-D returns them to jshell with the session still attached.

### 3.2 What Part 1 must NOT do that would block Part 2

These are the "don't paint yourself into a corner" constraints for Part 1:

- **Do not let the bundle jar's classloader design preclude loading JLine 3 later.** JLine collides destructively with jshell's own embedded JLine if loaded into a shared classloader. The bundle's `URLClassLoader` must already be isolated from jshell's classpath, parent-first from system — which is the right choice for Part 1 anyway.
- **Keep `Client` (the nREPL wire client) separable from the helper methods.** Part 2 will want to reuse `Client` directly from its own REPL loop, without going through the reflection layer that jshell's helper methods use.
- **The session abstraction matters.** Whatever nREPL session is opened by `attach(...)` must be reusable across `eval()` calls and, in Part 2, across `repl()` invocations. Don't create-and-close per call.
- **Output pretty-printing.** Part 1 does naive formatting. Part 2 will want a real edn pretty-printer. Structure the output path so the pretty-printer is a swappable component, not hardcoded into each helper method.

### 3.3 Part 2 scope summary (for later)

- JLine 3 `Terminal` + `LineReader` acquired on `repl()` entry, released on exit.
- `ClojureParser` for paren balancing and multi-line prompts.
- Static completion against a hard-coded list of Livewire API symbols; dynamic completion via nREPL `completions` op as a stretch goal.
- History persisted to `~/.livewire_history`.
- Pretty-printed edn output with configurable width.
- Clean re-entry into jshell's own prompt on exit.

Detailed spec to follow when Part 1 is stable.

---

## 4. Acceptance criteria for Part 1

Part 1 is done when **all** of the following are true:

1. The transcript in §2.1 can be reproduced end-to-end against the `bloated-shelf` demo app on Linux + JDK 21, with no prior installation beyond the JDK itself.
2. All error conditions in §2.5 produce the specified one-line message, not a stack trace.
3. The agent works against Spring Boot 3.x and 4.x without rebuild.
4. The integration test in §2.9 passes in CI.
5. The README top section is updated with the one-line "try it yourself" command as the primary hook, above the existing Maven/Gradle install instructions.
6. `help()` output includes the one-line reminder about dev-only usage and a pointer to the agentic Claude Code workflow.
7. `versions.json` + `attach.jsh` version banner is wired up so that future bundle updates don't break blogged URLs.

---

## 5. Open questions for the implementer

1. Should the bundle jar be published to Maven Central or served directly from GitHub Releases? Maven Central is more idiomatic for Java devs and gets caching for free; GitHub Releases is simpler to automate. Recommend Maven Central if the existing release pipeline already publishes there.
2. Is reusing the existing `net.brdloush:livewire` artifact with a classifier (`:agent`) cleaner than a separate `net.brdloush:livewire-attach` artifact? Slight preference for separate artifact — keeps dep tree clean for regular users.
3. The three ApplicationContext-discovery strategies (§2.4) — is there a fourth worth considering, e.g. walking `Instrumentation.getInitiatedClasses(systemCL)` for Spring marker classes? Worth evaluating during implementation.
4. For the Windows path: is it worth a dedicated GitHub Action runner and test, or punt to "best effort, document known issues"? Depends on expected Windows user share.

---

## 6. Nice to have

- Colored output in `help()` in jshell (ANSI escape codes for command names, descriptions, and the warning line).
