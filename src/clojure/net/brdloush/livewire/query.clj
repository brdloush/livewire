(ns net.brdloush.livewire.query
  "Query tools: execute SQL and JPQL directly against the live datasource."
  (:require [net.brdloush.livewire.core :as core]
            [net.brdloush.livewire.entity-serialize :as es]))

;;; ---------------------------------------------------------------------------
;;; Internal helpers

(defn- rs->maps
  "Converts an open ResultSet into a vector of maps with lowercased keyword keys."
  [rs]
  (let [md         (.getMetaData rs)
        col-count  (.getColumnCount md)
        col-keys   (mapv #(keyword (clojure.string/lower-case (.getColumnLabel md %)))
                         (range 1 (inc col-count)))]
    (loop [rows []]
      (if (.next rs)
        (recur (conj rows (zipmap col-keys
                                  (map #(.getObject rs ^int %) (range 1 (inc col-count))))))
        rows))))

;;; ---------------------------------------------------------------------------
;;; Public API

(defn diff-entity
  "Observes what `thunk` would change on the entity identified by
   `entity-class` (Hibernate entity name or Java class) and `id`.

   Captures the entity state before the thunk, runs the thunk inside a
   transaction that is always rolled back (so nothing persists), captures
   the entity state after the thunk but before the rollback, then returns:

     {:before  { ... entity map before ... }
      :after   { ... entity map after  ... }
      :changed { key [old-value new-value] ... }}  ; only differing keys

   Safe to call against a live dev database — the thunk is always rolled back."
  [entity-class id thunk]
  (let [before (es/load-entity entity-class id)
        after  (atom nil)]
    (core/in-tx
      (thunk)
      (let [em (core/bean jakarta.persistence.EntityManager)]
        (.flush em)
        (reset! after (es/entity->map (es/build-meta-map) 20 #{} (.find em (es/resolve-class em entity-class) id)))))
    (let [after-val @after
          changed   (into {}
                      (for [k     (clojure.set/union (set (keys before)) (set (keys after-val)))
                            :let  [vb (get before k) va (get after-val k)]
                            :when (not= vb va)]
                        [k [vb va]]))]
      {:before before :after after-val :changed changed})))

(defn sql
  "Executes a native SQL query against the live DataSource.
   Returns a vector of maps with lowercased keyword keys.

   Positional parameters are supported via ? placeholders.

   Examples:
     (sql \"SELECT * FROM books LIMIT 10\")
     (sql \"SELECT * FROM user_identity WHERE id = ?\" 1)
     (sql \"SELECT * FROM books WHERE active = ? LIMIT ?\" true 5)"
  [query & params]
  ;; Intentionally bypasses Spring's transaction management: we acquire a raw
  ;; JDBC connection directly from the DataSource so that ad-hoc REPL queries
  ;; are fully independent of any surrounding Spring transaction.  This is the
  ;; desired behaviour for a dev-time exploration tool — do not wrap this in a
  ;; TransactionTemplate.
  (let [ds (core/bean javax.sql.DataSource)]
    (with-open [conn (.getConnection ds)]
      (.setReadOnly conn true)
      (with-open [stmt (.prepareStatement conn query)]
        (dorun (map-indexed (fn [i p] (.setObject stmt (inc i) p)) params))
        (with-open [rs (.executeQuery stmt)]
          (rs->maps rs))))))
