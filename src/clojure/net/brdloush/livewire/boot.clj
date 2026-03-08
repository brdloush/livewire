(ns net.brdloush.livewire.boot
  "Bootstrap namespace: starts and stops the nREPL server.
   Called by the Spring LivewireBootstrapBean via clojure.java.api.Clojure."
  (:require [net.brdloush.livewire.core :as core]
            [nrepl.server :as nrepl]))

;;; defonce so repeated calls to start! (e.g. hot-reload of this ns) don't
;;; spin up a second server.
(defonce ^:private server-atom (atom nil))

(defn start!
  "Injects the ApplicationContext and starts the nREPL server on the given port.
   Idempotent: if the server is already running this is a no-op."
  [app-ctx port]
  (core/set-context! app-ctx)
  (if @server-atom
    (println (str "[livewire] nREPL server already running on port " port " — skipping start"))
    (let [server (nrepl/start-server :port port)]
      (reset! server-atom server)
      (println (str "[livewire] nREPL server started on port " port)))))

(defn stop!
  "Stops the nREPL server if it is running."
  []
  (when-let [s @server-atom]
    (nrepl/stop-server s)
    (reset! server-atom nil)
    (println "[livewire] nREPL server stopped")))
