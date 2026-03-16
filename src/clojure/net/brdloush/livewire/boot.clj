(ns net.brdloush.livewire.boot
  "Bootstrap namespace: starts and stops the nREPL server.
   Called by the Spring LivewireBootstrapBean via clojure.java.api.Clojure."
  (:require [net.brdloush.livewire.core :as core]
            [net.brdloush.livewire.query-watcher :as query-watcher]
            [nrepl.server :as nrepl])
  (:import [net.brdloush.livewire LivewireSqlTracer]
           [org.hibernate.engine.spi SessionFactoryImplementor]))

;;; defonce so repeated calls to start! (e.g. hot-reload of this ns) don't
;;; spin up a second server.
(defonce ^:private server-atom (atom nil))

(defn- check-statement-inspector!
  "Verifies that LivewireSqlTracer is the active StatementInspector on the
   SessionFactory. Prints a warning if it is not — which means trace-sql will
   silently return {:count 0, :queries []} until the issue is resolved."
  []
  (try
    (let [sf (core/bean "entityManagerFactory")
          inspector (-> sf
                        (.unwrap SessionFactoryImplementor)
                        .getSessionFactoryOptions
                        .getStatementInspector)]
      (when-not (instance? LivewireSqlTracer inspector)
        (println (str "[livewire] WARNING: StatementInspector is not LivewireSqlTracer (found: "
                      (some-> inspector class .getName)
                      ") — trace-sql will return {:count 0, :queries []}. "
                      "Check that spring.jpa.properties.hibernate.session_factory.statement_inspector is not overridden."))))
    (catch Exception e
      (println "[livewire] WARNING: Could not verify StatementInspector registration —" (.getMessage e)))))

(defn- init-user-ns!
  "Automatically requires and aliases Livewire namespaces in the `user` namespace
   so the REPL is ready to use immediately upon connection."
  []
  (try
    (require 'net.brdloush.livewire.core
             'net.brdloush.livewire.query
             'net.brdloush.livewire.introspect
             'net.brdloush.livewire.trace
             'net.brdloush.livewire.jpa-query
             'net.brdloush.livewire.mvc)
    (binding [*ns* (the-ns 'user)]
      (eval '(require '[net.brdloush.livewire.core :as lw]
                      '[net.brdloush.livewire.query :as q]
                      '[net.brdloush.livewire.introspect :as intro]
                      '[net.brdloush.livewire.trace :as trace]
                      '[net.brdloush.livewire.query-watcher :as qw]
                      '[net.brdloush.livewire.hot-queries :as hq]
                      '[net.brdloush.livewire.jpa-query :as jpa]
                      '[net.brdloush.livewire.mvc :as mvc])))
    (catch Exception e
      (println "[livewire] Warning: Failed to auto-alias namespaces in user ns:" (.getMessage e)))))

(defn start!
  "Injects the ApplicationContext and starts the nREPL server on the given port.
   Idempotent: if the server is already running this is a no-op."
  [app-ctx port]
  (core/set-context! app-ctx)
  (check-statement-inspector!)
  (if @server-atom
    (println (str "[livewire] nREPL server already running on port " port " — skipping start"))
    (let [server (nrepl/start-server :port port)]
      (reset! server-atom server)
      (init-user-ns!)
      (query-watcher/start-watcher!)
      (println (str "[livewire] nREPL server started on port " port " with user aliases (lw, q, intro, trace, qw, hq, jpa, mvc)")))))

(defn stop!
  "Stops the nREPL server if it is running."
  []
  (when-let [s @server-atom]
    (nrepl/stop-server s)
    (reset! server-atom nil)
    (println "[livewire] nREPL server stopped")))
