# https://engineering.telia.no/blog/java-troubleshooting-on-steroids-with-clojure-repl

<!--
URL: https://engineering.telia.no/blog/java-troubleshooting-on-steroids-with-clojure-repl
title: Java/Spring App Troubleshooting on Steroids with Clojure REPL
-->

[![](/images/logo.svg)](/)

* [Open Source](/open-source)
* [Blog](/blog)
* [Podcast](/podcasts)
* [Careers](https://www.teliacompany.com/en/careers/)

![](/images/blub.svg)

## Java/Spring App Troubleshooting on Steroids with Clojure REPL

01.02.2019 â 7 Min Read â In [Development](/categories/development)

|

Author: Â  **[Jakub HolÃ½](https://blog.jakubholy.net)**

![](/images/blog.jpg)

We have a Java/Groovy Spring Boot webapp, mainly running a bunch of batch jobs fetching, transforming and combining data. It is challenging to troubleshoot production issues because some production APIs are only accessible from the production servers and it is difficult and possibly dangerous to run the application in full production setup locally. Fortunately, we can now connect a [REPL](https://clojure.org/guides/repl/introduction) to the running application, get hold of its Spring beans, and interact with it (invoking remote calls, checking the returned data, ...), which is a real life-saver and something I want to demonstrate and describe here.

*Aside*: What is REPL? A [REPL - or Read-Eval-Print-Loop - is an "interactive terminal"](https://clojure.org/guides/repl/introduction) into your live application, where you can inspect data, call functions, and (re)define code - in the context of the running application. It enables interactive development (and troubleshooting). The REPL puts all the power of our development tools and programming language at our fingertips and the immediate feedback we get from it enables us to iterate quickly toward the solution or answer we are looking for.

First an example. Imagine that the logs tell you that a job failed to fetch `/subscribers` information for 30 of our business customers (we are a mobile operator, among other things) due to 404 Not Found. What do you do? Simple; first, get into the REPL:

```
$ kubectl-shell-into-jobs-app.sh # runs kubectl exec ...
/var/app# ./repl-in.sh # runs env LOADER_MAIN=nrepl.main .. java -cp jobs-app.jar  ..
```

then:

```
user=> (def orgs ["12345678" ...]) ;; paste from logs
user=> (map #(-> (.getAgreementStatus (bean "CustomersServiceImpl") %)
                 deref
                 jbean
                 (select-keys [:status :name])) orgs)
({:status "ACTIVE_AGREEMENT", :name "TROLLBYGG HOLDING AS"} ...
user=> (def results *1)
user=> (filter #(not= "ACTIVE_AGREEMENT" (:status %)) results)
() ;; none => all are active
```

We have just used Clojure and a few helper functions (`bean` to find Spring Beans, `jbean` = `clojure.core/bean`) to fetch the status of each of the troublesome organizations and verified that all are active customers. Now we have enough information to talk to the Customer Service developers and ask them for help.

Other examples where the REPL in the live app proved incredibly useful were:

* Find out the actual REST service URL used in production (fetched from a configuration service with complex rules)
* Check whether a static property contains the version + git sha that we tried to get into it
* Retry a failed call after infrastructure fixes

Soon I will answer the questions certainly swirling in your head:

1. How do I start Clojure REPL from my Java application?
2. How do I expose Spring Beans to it?
3. How do I connect to it?
4. What helper functions do I need/want?

### Implementation

We create a standard Spring bean with an `@Autowired` `ApplicationContext` and start the REPL from a method annotated with `@PostConstruct` so that it is only run after the app context has been made available.

The challenge here was to expose the context to Clojure, which we do via `(intern 'user '<var name> <var value>)` (we cannot `def` it from the outside). (Alternatively, we could make our Clojure setup code expose a function and invoke that function, passing the context as a parameter.)

The REPL server itself is started from within the `clojure-repl-init.clj` (see below) loaded within `start`. We could just `.invoke` the `start-server` function from the Groovy code but since we have other Clojure code to load, it is simpler to just do it there as well.

Here is the relevant (Groovy) code:

```
@Component
class ClojureReplServer {

    // public so that we can access it from Clojure
    public final static int port = 55555

    @Autowired
    private ApplicationContext ctx

    private IFn symbol = Clojure.var("clojure.core", "symbol")
    private IFn intern = Clojure.var("clojure.core", "intern")
    private IFn stopReplServer = Clojure.var("user", "stop-repl-server")
    private Object symUser = symbol.invoke("user")

    @PostConstruct
    start() {
        String res = ""
        res += intern.invoke(symUser, symbol.invoke("_injected-spring-ctx"), ctx)
        res += intern.invoke(symUser, symbol.invoke("_injected-port"), port)
        res += intern.invoke(symUser, symbol.invoke("_injected-ClojureReplServer"), this)

        // Run the init code and start the server
        IFn loadString = Clojure.var("clojure.core", "load-reader")
        def reader = new InputStreamReader(getClass().getClassLoader().getResourceAsStream("clojure-repl-init.clj"))
        res += "|" + loadString.invoke(reader)
        println("ClojureReplServer started at port ${port}, res=" + res)
    }

    @PreDestroy
    stop() {
        // Here we use `stop-repl-server` defined in clojure-repl-init.clj:
        stopReplServer.invoke()
        Agent.soloExecutor.shutdown()
    }

    /** Called from Clojure when we screw up and need to reset vars etc */
    void reset() {
        // Calling start is enough; the server will not be started again thanks to `defonce`
        start()
    }
}
```

Gotchas: You might have noticed I use `clojure.core/load-reader` to load the Clojure setup code. I originally used `load-string` but it evaluates the code within the `clojure.core` namespace and you cannot change that, while you want to be able to `def[n]` stuff in the REPL user's `user` namespace.

Here is the Clojure setup code, with helper functions and initialization of the REPL server:

```
(in-ns 'user)
(require
    '[clojure.reflect :refer [reflect]]
    '[clojure.repl :refer [doc]]
    '[clojure.string :as s]
    '[nrepl.server :as n]))

;; Provide an alias since we are going to use `bean` for Spring:
;; (we need the value to be fn? and to have the bean's docstring; I don't know of a better way:)
(intern 'user (with-meta 'jb    (meta #'clojure.core/bean)) clojure.core/bean)
(intern 'user (with-meta 'jbean (meta #'clojure.core/bean)) clojure.core/bean)

;; Helper functions
(defn help
    "List our helper functions (and vars)"
    []
    (println "Helper functions available in the user namespace:")
    (->> (vals (ns-publics 'user))
        (filter #(fn? (deref %)))
        (map #(let [{:keys [name doc]} (meta %)]
                (str "* " name (if doc (str " :- " doc) ""))))
        (sort)
        (s/join "\n")
        (println))
    (println "\nYou can also use `(doc a-fn)` and `(reflect an-object)`.")
    (println "Remember that *1 holds the result of the last call and *e the last error."))

(defn list-beans
    "List all Spring Beans; ex: `(list-beans)`"
    []
    (seq (.getBeanDefinitionNames user/_injected-spring-ctx)))

(defn find-bean
    "List all Spring bean names containing the given substring (case-insensitive)"
    [substring]
    (filter
        #(re-matches
            (re-pattern
                (str "(?i).*" substring ".*")) %)
        (list-beans)))

(defn bean
    "Get Spring Bean by a name (from (list-beans)); ex: `(bean \"configService\")`"
    [name]
    (.getBean user/_injected-spring-ctx name))

(defn members
    "Show public methods, fields of a bean; ex: `(members aBean)`"
    [bean]
    (->> bean clojure.reflect/reflect :members (filter (comp :public :flags)) (map :name)))

(defonce server (n/start-server :port user/_injected-port))

(defn stop-repl-server
    "Called from ClojureReplServer upon exit; don't use directly"
    []
    (n/stop-server server))

(defn reset
    "Reset the pre-defined functions and vars in the case that you messed up with them. Does not remove vars you made (we'd need clojure.tools/refresh for that)."
    []
    (.reset user/_injected-ClojureReplServer))
```

And the Gradle dependencies:

```
// build.gradle:
compile "org.clojure:clojure:1.10.0"
compile "nrepl:nrepl:0.5.0"
```

#### Comments

I use nREPL but I guess it is an overkill and I could just as well use the built-in Clojure Socket REPL.

#### Connecting to the REPL

If we used the Clojure Socket REPL or nREPL with the tty transport, we could simply use `telnet localhost 55555` (or `nc`). We could even install the [unravel REPL](https://github.com/Unrepl/unravel) client for a rich user experience.

But since we run nREPL with its default transport, we need to use the nREPL Client. It is a little tricky but possibly to invoke its function from the Spring Boot application jar:

```
env LOADER_MAIN=nrepl.main LOADER_ARGS="--connect --host 127.0.0.1 --port 55555" \
  rlwrap java -cp myapp.jar org.springframework.boot.loader.PropertiesLauncher
```

(`rlwrap` is optional and can be omitted from the command line; however it makes editing in the REPL much nicer.)

### Aside: Security

It might seem scary to enable REPL access to a production application. Whether it is something for you or not depends on multiple levels - the trust and skill level in your team and the domain you work with.

If you are security-conscious, you can mandate and enforce that any live coding is done by a pair of developers (our experience is that having two pairs of eyes also really helps to get things right) and you can log and review REPL sessions. (I don't need to mention that you secure access to the REPL port by all means, do I?)

If you are afraid that a change/fix will be executed in production but not the version-controlled source code, you can automatically restart or even re-deploy the application after a finished REPL session.

### Conclusion

Having a REPL to the live prod application has been invaluable for troubleshooting. Truth be told, it doesn't really matter whether it is Clojure, Groovy or perhaps [CRaSH](https://www.crashub.org). Anything that allows us to invoke Java methods and process and display data would do. (Though I have made a good use of Clojure's map/filter/deref/... and the fact that it has a remote REPL built in gives it a head start.)

Despite its usefulness, a Clojure REPL in a Java app is a far cry from the interactive development and troubleshooting afforded by a Clojure app. I can only invoke existing methods, I cannot change them, for example to check whether adding a particular query parameter to a REST URL would fix a problem (it would) or to inject more logging.

---

[**Clojure** ,](/tags/clojure) [**DevOps**](/tags/dev-ops)

[Open Source](/open-source)[Blog](/blog)[Podcast](/podcasts)[Careers](https://www.teliacompany.com/en/careers/)[Personvern og Cookies](https://www.telia.no/personvern/)

---