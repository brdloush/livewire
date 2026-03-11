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
  (:import [java.nio.file Files LinkOption Path Paths]
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

;;; Holds the running flag atom, or nil if the watcher is not active.
(defonce ^:private running-atom (atom nil))

;;; Holds the watcher Thread, or nil.
(defonce ^:private watch-thread-atom (atom nil))

;;; Poll interval in milliseconds.
(def ^:private poll-interval-ms 500)

;;; Tracks the last-seen mtime (millis) per absolute .class file path string.
(defonce ^:private mtime-cache (atom {}))

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
;; Initial disk-state builder
;; ---------------------------------------------------------------------------

(defn- build-initial-disk-state
  "Scans all Spring beans for @Query methods via hot-queries/list-queries and
   returns the full {[bean-name method-name] -> jpql-string} baseline map.
   Passed to hq/seed-disk-state! at watcher startup."
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
     4. Diff each method's JPQL against hq/disk-state (shared source of truth).
     5. Call hq/watcher-apply! for every changed entry.
        watcher-apply! updates disk-state and either swaps live or tracks silently
        if the query is currently manually pinned by the user."
  [^Path abs-path ^String asm-class-name]
  (try
    (let [path-str (.toString abs-path)]
      (when (contains-query-annotation? path-str)
        (let [queries (read-queries-from-class path-str)
              bean-name (class-name->bean-name asm-class-name)]
          (when (and (seq queries) bean-name)
            (doseq [[method-name new-jpql] queries]
              (let [reg-key [bean-name method-name]
                    current-jpql (get @hq/disk-state reg-key)]
                (when (not= current-jpql new-jpql)
                  (println (str "[query-watcher] detected change " bean-name "#" method-name))
                  (hq/watcher-apply! bean-name method-name new-jpql))))))))
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
;; Polling loop — scans output dirs for changed .class files every 500 ms.
;; Uses mtime comparison rather than WatchService because Maven's Eclipse
;; compiler (ecj) writes .class files in a way that does not trigger inotify
;; events on Linux.
;;
;; Note: Spring Boot DevTools ships a FileSystemWatcher that does the same
;; thing and is battle-tested, but spring-boot-devtools is not reliably on
;; the classpath (it's typically excluded from production JARs). Depending
;; on it would make Livewire fragile for anyone not using devtools. The
;; hand-rolled mtime scan is equivalent and keeps Livewire self-contained.
;; ---------------------------------------------------------------------------

(defn- scan-dir!
  "Walks `dir` recursively, checks every .class file's mtime against
   `mtime-cache`, and calls `handle-class-change!` for any that changed."
  [^Path dir]
  (try
    (Files/walkFileTree dir
      (reify java.nio.file.FileVisitor
        (preVisitDirectory [_ _d _a] java.nio.file.FileVisitResult/CONTINUE)
        (visitFile [_ file _attrs]
          (let [path-str (.toString file)]
            (when (str/ends-with? path-str ".class")
              (try
                (let [mtime (.toMillis (Files/getLastModifiedTime file (make-array LinkOption 0)))
                      prev  (get @mtime-cache path-str)]
                  (when (not= prev mtime)
                    (swap! mtime-cache assoc path-str mtime)
                    (let [rel      (.toString (.relativize dir file))
                          asm-name (str/replace (str/replace rel ".class" "") java.io.File/separator "/")]
                      (handle-class-change! file asm-name))))
                (catch Exception e
                  (println (str "[query-watcher] error scanning " path-str ": " (.getMessage e)))))))
          java.nio.file.FileVisitResult/CONTINUE)
        (visitFileFailed [_ _f _e] java.nio.file.FileVisitResult/CONTINUE)
        (postVisitDirectory [_ _d _e] java.nio.file.FileVisitResult/CONTINUE)))
    (catch Exception e
      (println (str "[query-watcher] error walking " dir ": " (.getMessage e))))))

(defn- poll-loop!
  "Repeatedly scans all `dirs` for changed .class files until the running
   flag is set to false or the thread is interrupted."
  [dirs running?]
  (try
    (loop []
      (when (and @running? (not (.isInterrupted (Thread/currentThread))))
        (doseq [^Path d dirs]
          (scan-dir! d))
        (try (Thread/sleep poll-interval-ms)
             (catch InterruptedException _ (.interrupt (Thread/currentThread))))
        (recur)))
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
     3. Seed the mtime-cache with current timestamps (no false positives on start).
     4. Spawn a daemon thread running the poll loop."
  []
  (if @running-atom
    (println "[query-watcher] already running — skipping start")
    (let [dirs (detect-output-dirs)]
      (if (empty? dirs)
        (println "[query-watcher] no output directories found — watcher not started")
        (do
          (hq/seed-disk-state! (build-initial-disk-state))
          ;; Seed mtime-cache so the first scan doesn't re-fire everything
          (doseq [^Path d dirs]
            (scan-dir! d))
          (let [running? (atom true)
                thread   (doto (Thread. ^Runnable #(poll-loop! dirs running?))
                           (.setName "livewire-query-watcher")
                           (.setDaemon true)
                           (.start))]
            (reset! running-atom running?)
            (reset! watch-thread-atom thread)
            (println (str "[query-watcher] started — watching " (count dirs) " dir(s), "
                          (count @hq/disk-state) " @Query method(s) in registry"))))))))

(defn stop-watcher!
  "Stops the query-watcher: sets the running flag to false and interrupts
   the poll thread.  Idempotent — calling when not running is a no-op."
  []
  (when-let [running? @running-atom]
    (reset! running? false)
    (reset! running-atom nil))
  (when-let [^Thread t @watch-thread-atom]
    (.interrupt t)
    (reset! watch-thread-atom nil))
  (println "[query-watcher] stopped"))

(defn status
  "Returns a map describing the current watcher state."
  []
  {:running?       (some? @running-atom)
   :disk-state-size (count @hq/disk-state)
   :disk-state     @hq/disk-state})
