(defproject net.brdloush/livewire "0.1.0-SNAPSHOT"
  :description "Live nREPL wire into your Spring Boot app. Dev only. You've been warned."
  :url "https://github.com/brdloush/livewire"
  :license {:name "MIT"}

  :source-paths      ["src/clojure"]
  :java-source-paths ["src/java"]
  :resource-paths    ["resources"]
  :target-path       "target/%s"

  :dependencies [[org.clojure/clojure "1.12.0"]
                 [nrepl/nrepl "1.3.1"]]

  :profiles
  {:provided
   {:dependencies
    [[org.springframework.boot/spring-boot-autoconfigure "3.3.5"
      :scope "provided"]
     [org.springframework/spring-context "6.1.14"
      :scope "provided"]
     [org.springframework/spring-tx "6.1.14"
      :scope "provided"]
     [org.hibernate.orm/hibernate-core "6.5.3.Final"
      :scope "provided"]]}}

  :javac-options ["--release" "17" "-Xlint:unchecked"]

  :repl-options {:init-ns net.brdloush.livewire.core})
