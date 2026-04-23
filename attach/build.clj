(ns build
  (:require [clojure.tools.build.api :as b]))

;;; Version must stay in sync with the root project.clj.
(def version "0.12.0-SNAPSHOT")
(def lib     'net.brdloush/livewire-attach)

(def class-dir "target/classes")
(def basis     (b/create-basis {:project "deps.edn"}))
(def jar-file  (format "target/livewire-attach-%s.jar" version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (println (str "[livewire-attach] compiling Java sources..."))
  (b/delete {:path class-dir})
  (b/javac {:src-dirs   ["src"]
            :class-dir  class-dir
            :basis      basis
            :javac-opts ["--release" "17" "-Xlint:unchecked"]})
  (println (str "[livewire-attach] assembling " jar-file " ..."))
  (b/jar {:class-dir class-dir
          :jar-file  jar-file
          :manifest  {"Agent-Class"             "net.brdloush.livewire.attach.LivewireAgent"
                      "Can-Retransform-Classes"  "true"
                      "Can-Redefine-Classes"     "true"}})
  (println (str "✅ " jar-file)))
