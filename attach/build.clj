(ns build
  (:require [clojure.tools.build.api :as b]))

;;; Version must stay in sync with the root project.clj.
(def version "0.12.0")
(def lib     'net.brdloush/livewire-attach)

(def class-dir        "target/classes")
(def out-dir          "target/provided")
(def basis            (b/create-basis {:project "deps.edn"}))
;;; Simple jar path used by `bb attach-jar` for fast dev iteration.
(def jar-file         (format "target/livewire-attach-%s.jar" version))
;;; Release paths — all under target/provided/ for Maven Central bundling.
(def rel-jar-file     (format "%s/livewire-attach-%s.jar"            out-dir version))
(def src-jar          (format "%s/livewire-attach-%s-sources.jar"    out-dir version))
(def javadoc-jar-file (format "%s/livewire-attach-%s-javadoc.jar"   out-dir version))
(def pom-file         (format "%s/livewire-attach-%s.pom"            out-dir version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (println (str "[livewire-attach] compiling Java sources..."))
  (b/delete {:path class-dir})
  (b/javac {:src-dirs   ["src"]
            :class-dir  class-dir
            :basis      basis
            :javac-opts ["--release" "17" "-Xlint:unchecked"]})
  (.mkdirs (java.io.File. "target"))
  (println (str "[livewire-attach] assembling uber-jar " jar-file " ..."))
  (b/uber {:class-dir class-dir
           :uber-file jar-file
           :basis     basis
           :manifest  {"Agent-Class"             "net.brdloush.livewire.attach.LivewireAgent"
                       "Can-Retransform-Classes"  "true"
                       "Can-Redefine-Classes"     "true"}})
  (println (str "✅ " jar-file)))

(defn pom [_]
  (.mkdirs (java.io.File. out-dir))
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     basis
                :src-dirs  ["src"]
                :pom-data
                [[:description "Livewire jshell attach bundle — dynamic agent for zero-install Spring Boot inspection"]
                 [:url "https://github.com/brdloush/livewire"]
                 [:licenses
                  [:license
                   [:name "MIT License"]
                   [:url "https://opensource.org/licenses/MIT"]]]
                 [:developers
                  [:developer
                   [:name "Tomas Brdlik"]
                   [:email "brdloush@gmail.com"]]]
                 [:scm
                  [:url "https://github.com/brdloush/livewire"]
                  [:connection "scm:git:git://github.com/brdloush/livewire.git"]
                  [:developerConnection "scm:git:ssh://git@github.com/brdloush/livewire.git"]]]})
  ;; Copy from Maven-standard location to flat out-dir for release bundling.
  (let [generated (format "%s/META-INF/maven/net.brdloush/livewire-attach/pom.xml" class-dir)]
    (b/copy-file {:src generated :target pom-file}))
  (println (str "✅ " pom-file)))

(defn source-jar [_]
  (.mkdirs (java.io.File. out-dir))
  (b/jar {:class-dir "src"   ; pack sources directly
          :jar-file  src-jar})
  (println (str "✅ " src-jar)))

(defn javadoc-jar [_]
  (clojure.java.io/make-parents javadoc-jar-file)
  (let [doc-dir "target/javadoc"]
    (.mkdirs (java.io.File. doc-dir))
    (spit (str doc-dir "/README.md")
          "Livewire-attach API documentation: https://github.com/brdloush/livewire\n")
    (b/jar {:class-dir doc-dir :jar-file javadoc-jar-file}))
  (println (str "✅ " javadoc-jar-file)))

(defn release-jars [_]
  (clean nil)
  (jar nil)
  ;; Copy the dev jar into the release dir alongside sources/javadoc/pom.
  (.mkdirs (java.io.File. out-dir))
  (b/copy-file {:src jar-file :target rel-jar-file})
  (pom nil)
  (source-jar nil)
  (javadoc-jar nil)
  (println (str "\nRelease artifacts for livewire-attach " version ":"))
  (doseq [f [rel-jar-file src-jar javadoc-jar-file pom-file]]
    (println (str "  " f " — " (if (.exists (java.io.File. f)) "✅" "❌")))))
