# Livewire Idea: REPL-Driven Test Runner with Hotswap

## Concept

When a Spring Boot app runs in debug mode with `-Dexec.classpathScope=test` (as in the local dev
profile), the full test classpath — compiled test classes, JUnit 5 Platform, Mockito — is already
live inside the JVM. This makes it possible to run unit tests directly from the Livewire REPL
without any Maven invocation, and to iterate on fixes with a very tight loop:

1. Edit the source in the IDE
2. Recompile the single file (e.g. via JetBrains MCP `build_project` with `filesToRebuild`)
3. Hotswap kicks in automatically (debug mode replaces method bodies in the running JVM)
4. Re-run the test via JUnit Platform Launcher API from the REPL — picks up the new bytecode immediately

## The REPL call pattern

```clojure
(import '[org.junit.platform.launcher.core LauncherDiscoveryRequestBuilder LauncherFactory]
        '[org.junit.platform.engine.discovery DiscoverySelectors]
        '[org.junit.platform.launcher.listeners SummaryGeneratingListener])

(let [listener (SummaryGeneratingListener.)
      request  (-> (LauncherDiscoveryRequestBuilder/request)
                   (.selectors [(DiscoverySelectors/selectClass "com.example.MyTest")])
                   .build)
      launcher (LauncherFactory/create)]
  (.execute launcher request (into-array [listener]))
  (let [s (.getSummary listener)]
    {:started   (.getTestsStartedCount s)
     :succeeded (.getTestsSucceededCount s)
     :failed    (.getTestsFailedCount s)
     :failures  (mapv #(hash-map :test    (-> % .getTestIdentifier .getDisplayName)
                                 :message (str (.getMessage (.getException %))))
                       (.getFailures s))}))
```

## Limitations

- **Structural changes don't hotswap** — adding/removing methods, fields, or changing class
  hierarchy requires a full JVM restart. Only method body changes are safe.
- **Requires debug mode** — the JVM must be started with a debug agent for hotswap to work.
- **`-Dexec.classpathScope=test` required** — without it, test classes and test-scoped
  dependencies (JUnit, Mockito) are not on the runtime classpath.
- **Static state in test subjects** — if the class under test holds static/singleton state
  (like `NumberFormats.formatter`), tests share that state — same as running normally, but
  worth being aware of.

## Potential Livewire integration

A first-class `(lw/run-test "com.example.MyTest")` or
`(lw/run-test "com.example.MyTest" "test method name")` wrapper in the core namespace would
make this frictionless. It could also support running all tests in a package via
`DiscoverySelectors/selectPackage`. The summary output could be formatted as a markdown table
by the REPL client for readability.

## Ideas for further exploration

- **Reload-friendly classloader for test classes** — is it possible to reload the test class at
  runtime? Test classes are probably never loaded automatically before the first test execution,
  which might offer an opportunity to hook a better, disposable, reload-friendly classloader for
  them specifically.
- **Integration tests** — can integration tests be called as well? So far only unit tests have
  been tried.
