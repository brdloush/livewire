(ns net.brdloush.livewire.query-watcher
  "File watcher + ASM bytecode reader that makes @Query hot-swapping invisible.

  When active, monitors compiled output directories for .class file changes.
  On change, ASM reads the bytecode (no classloading, no ClassLoader pollution),
  extracts all @Query annotation values, diffs against a known-state registry,
  and calls hot-swap-query! for each changed method.

  Usage:
    (require '[net.brdloush.livewire.query-watcher :as qw])

    (qw/start-watcher!)   ; called automatically by boot/start!
    (qw/stop-watcher!)    ; shuts down the WatchService and watch thread
    (qw/status)           ; {:running? true/false :dirs [...] :registry-size N}"
  (:require [net.brdloush.livewire.core :as core]
            [net.brdloush.livewire.hot-queries :as hq]
            [clojure.string :as str])
  (:import [java.nio.file FileSystems Files Path Paths LinkOption
            StandardWatchEventKinds WatchEvent WatchKey WatchService]
           [org.springframework.asm
            AnnotationVisitor ClassReader ClassVisitor MethodVisitor Opcodes]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private query-annotation-desc
  "Lorg/springframework/data/jpa/repository/Query;")

(def ^:private query-annotation-guard-bytes
  "Byte sequence used for the fast pre-check — present in every .class file
   that carries at least one @Query annotation."
  (.getBytes "data/jpa/repository/Query"))

(def ^:private candidate-output-dirs
  "Relative paths of compiled-output directories to watch, in priority order.
   All that exist at startup are watched simultaneously."
  ["target/classes"
   "build/classes/java/main"
   "build/classes/kotlin/main"
   "out/production/classes"])

;; ---------------------------------------------------------------------------
;; State atoms
;; ---------------------------------------------------------------------------

;;; Holds the running WatchService, or nil if the watcher is not active.
(defonce ^:private watcher-atom (atom nil))

;;; Holds the watcher Thread, or nil.
(defonce ^:private watch-thread-atom (atom nil))

;;; Registry of the last-known JPQL per [bean-name method-name] pair.
;;; Initialised at watcher startup from hot-queries/list-queries over all beans.
;;; Updated on every successful hot-swap.
(defonce ^:private known-state (atom {}))

;; ---------------------------------------------------------------------------
;; ASM bytecode reader — no classloading, pure bytecode inspection
;; ---------------------------------------------------------------------------

(defn- contains-query-annotation?
  "Fast pre-check: returns true if the raw bytes of `class-file-path` contain
   the @Query annotation descriptor string.  Skips the full ASM parse for the
   vast majority of non-repository .class files."
  [^String class-file-path]
  (try
    (let [bytes (Files/readAllBytes (Paths/get class-file-path (make-array String 0)))
          needle query-annotation-guard-bytes
          n (count needle)
          limit (- (count bytes) n)]
      (loop [i 0]
        (cond
          (> i limit) false
          (= (seq (java.util.Arrays/copyOfRange bytes i (+ i n)))
             (seq needle)) true
          :else (recur (inc i)))))
    (catch Exception _ false)))

(defn read-queries-from-class
  "Reads a .class file at `class-file-path` and returns a map of
   method-name -> JPQL-string for every @Query annotation found.
   Uses Spring's repackaged ASM — no classloading, no ClassLoader pollution."
  [^String class-file-path]
  (let [result (atom {})
        cr (ClassReader. (java.io.FileInputStream. class-file-path))]
    (.accept
     cr
     (proxy [ClassVisitor] [Opcodes/ASM9]
       (visitMethod [_access method-name _desc _signature _exceptions]
         (proxy [MethodVisitor] [Opcodes/ASM9]
           (visitAnnotation [ann-desc _visible]
             (when (= ann-desc query-annotation-desc)
               (proxy [AnnotationVisitor] [Opcodes/ASM9]
                 (visit [attr-name value]
                   (when (= attr-name "value")
                     (swap! result assoc method-name value)))))))))
     0)
    @result))

;; ---------------------------------------------------------------------------
;; Class-name → Spring bean-name resolver
;; ---------------------------------------------------------------------------

(defn- class-name->bean-name
  "Given an ASM internal class name (e.g. \"com/example/BookRepository\"),
   returns the Spring bean name whose actual or proxied class matches,
   or nil if not found."
  [^String asm-class-name]
  (let [fqn (str/replace asm-class-name "/" ".")]
    (->> (core/bean-names)
         (keep (fn [bean-name]
                 (try
                   (let [b (.getBean (core/ctx) ^String bean-name)
                         klass (class b)]
                     (when (some #(= fqn (.getName ^Class %))
                                 (ancestors klass))
                       bean-name))
                   (catch Exception _ nil))))
         first)))

;; ---------------------------------------------------------------------------
;; Known-state registry
;; ---------------------------------------------------------------------------

(defn- build-initial-registry
  "Scans all Spring beans for @Query methods via hot-queries/list-queries and
   returns the full {[bean-name method-name] -> jpql-string} baseline map."
  []
  (->> (core/bean-names)
       (mapcat (fn [bean-name]
                 (try
                   (->> (hq/list-queries bean-name)
                        (keep (fn [{:keys [method jpql]}]
                                (when jpql
                                  [[bean-name method] jpql]))))
                   (catch Exception _ nil))))
       (into {})))

;; ---------------------------------------------------------------------------
;; Change handler — diff + swap
;; ---------------------------------------------------------------------------

(defn- handle-class-change!
  "Called when a .class file event fires.
   `abs-path`       — absolute Path to the .class file
   `asm-class-name` — ASM-style class name (e.g. \"com/example/BookRepository\")

   Flow:
     1. Fast guard: skip if no @Query descriptor in raw bytes.
     2. ASM-parse the file to get method -> JPQL map.
     3. Resolve the Spring bean name.
     4. Diff each method's JPQL against known-state.
     5. Call hot-swap-query! for every changed entry; update known-state."
  [^Path abs-path ^String asm-class-name]
  (try
    (let [path-str (.toString abs-path)]
      (when (contains-query-annotation? path-str)
        (let [queries (read-queries-from-class path-str)
              bean-name (class-name->bean-name asm-class-name)]
          (when (and (seq queries) bean-name)
            (doseq [[method-name new-jpql] queries]
              (let [reg-key [bean-name method-name]
                    current-jpql (get @known-state reg-key)]
                (when (not= current-jpql new-jpql)
                  (println (str "[query-watcher] hot-swapping " bean-name "#" method-name))
                  (hq/hot-swap-query! bean-name method-name new-jpql)
                  (swap! known-state assoc reg-key new-jpql))))))))
    (catch Exception e
      (println (str "[query-watcher] error processing " (.toString abs-path) ": " (.getMessage e))))))

;; ---------------------------------------------------------------------------
;; Output directory auto-detection
;; ---------------------------------------------------------------------------

(defn detect-output-dirs
  "Returns a vector of absolute Paths for all candidate output directories
   that currently exist on the filesystem."
  []
  (->> candidate-output-dirs
       (keep (fn [rel]
               (let [p (.toAbsolutePath (Paths/get rel (make-array String 0)))]
                 (when (Files/isDirectory p (make-array LinkOption 0))
                   p))))
       vec))

;; ---------------------------------------------------------------------------
;; WatchService loop
;; ---------------------------------------------------------------------------

(defn- register-dir-recursive!
  "Registers `dir` and all subdirectories under the WatchService `ws`
   for ENTRY_CREATE and ENTRY_MODIFY events.
   Returns a map of registered-Path -> that same Path (used as the dir-map
   in watch-loop! so WatchKey.watchable() can be looked up directly)."
  [^WatchService ws ^Path dir]
  (let [result (java.util.concurrent.ConcurrentHashMap.)]
    (Files/walkFileTree
     dir
     (reify java.nio.file.FileVisitor
       (preVisitDirectory [_ d _attrs]
         (let [^WatchKey k (.register d ws
                                      (into-array [StandardWatchEventKinds/ENTRY_CREATE
                                                   StandardWatchEventKinds/ENTRY_MODIFY]))]
            ;; Store the watchable (which equals the registered path) as the key
           (.put result (.watchable k) d))
         java.nio.file.FileVisitResult/CONTINUE)
       (visitFile [_ _f _a] java.nio.file.FileVisitResult/CONTINUE)
       (visitFileFailed [_ _f _e] java.nio.file.FileVisitResult/CONTINUE)
       (postVisitDirectory [_ _d _e] java.nio.file.FileVisitResult/CONTINUE)))
    (into {} result)))

(defn- watch-loop!
  "Blocking loop that polls the WatchService `ws` and dispatches .class change
   events to handle-class-change!.  Returns when the thread is interrupted or
   the WatchService is closed."
  [^WatchService ws ^java.util.Map dir->path-map]
  (try
    (loop []
      (when-not (.isInterrupted (Thread/currentThread))
        (let [^WatchKey k (try (.take ws)
                               (catch java.nio.file.ClosedWatchServiceException _ nil)
                               (catch InterruptedException _ nil))]
          (when k
            (try
              (let [^Path watch-dir (get dir->path-map (.watchable k))]
                (when watch-dir
                  (doseq [^WatchEvent ev (.pollEvents k)]
                    (let [^Path rel-path (.context ev)
                          abs-path (.resolve watch-dir rel-path)
                          path-str (.toString rel-path)]
                      (when (str/ends-with? path-str ".class")
                        (let [asm-name (str/replace (str/replace path-str ".class" "") "\\" "/")]
                          (handle-class-change! abs-path asm-name)))))))
              (catch Exception e
                (println (str "[query-watcher] error in watch loop: " (.getMessage e))))
              (finally
                (.reset k)))
            (recur)))))
    (catch Exception e
      (println (str "[query-watcher] fatal error, watcher stopped: " (.getMessage e))))))

;; ---------------------------------------------------------------------------
;; Public lifecycle API
;; ---------------------------------------------------------------------------

(defn start-watcher!
  "Starts the query-watcher if it is not already running.
   Idempotent: subsequent calls while the watcher is active are no-ops.

   Steps:
     1. Detect all existing output directories.
     2. Build the initial known-state registry from live Spring beans.
     3. Register all dirs (recursively) with a WatchService.
     4. Spawn a daemon thread running the watch loop."
  []
  (if @watcher-atom
    (println "[query-watcher] already running — skipping start")
    (let [dirs (detect-output-dirs)]
      (if (empty? dirs)
        (println "[query-watcher] no output directories found — watcher not started")
        (do
          (reset! known-state (build-initial-registry))
          (let [ws (.newWatchService (FileSystems/getDefault))
                dir-map (reduce (fn [m ^Path d]
                                  (let [registered (register-dir-recursive! ws d)]
                                    (merge m registered)))
                                {}
                                dirs)
                thread (doto (Thread. ^Runnable #(watch-loop! ws dir-map))
                         (.setName "livewire-query-watcher")
                         (.setDaemon true)
                         (.start))]
            (reset! watcher-atom ws)
            (reset! watch-thread-atom thread)
            (println (str "[query-watcher] started — watching " (count dirs) " dir(s), "
                          (count @known-state) " @Query method(s) in registry"))))))))

(defn stop-watcher!
  "Stops the query-watcher: closes the WatchService and interrupts the watch
   thread.  Idempotent — calling when not running is a no-op."
  []
  (when-let [ws @watcher-atom]
    (.close ws)
    (reset! watcher-atom nil))
  (when-let [^Thread t @watch-thread-atom]
    (.interrupt t)
    (reset! watch-thread-atom nil))
  (println "[query-watcher] stopped"))

(defn status
  "Returns a map describing the current watcher state:
   {:running? true/false :dirs [...] :registry-size N}"
  []
  {:running? (some? @watcher-atom)
   :registry-size (count @known-state)
   :known-state @known-state})
