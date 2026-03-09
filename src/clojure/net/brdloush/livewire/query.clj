(ns net.brdloush.livewire.query
  "Query tools: execute SQL and JPQL directly against the live datasource."
  (:require [net.brdloush.livewire.core :as core]))

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

(defn sql
  "Executes a native SQL query against the live DataSource.
   Returns a vector of maps with lowercased keyword keys.

   Positional parameters are supported via ? placeholders.

   Examples:
     (sql \"SELECT * FROM clients LIMIT 10\")
     (sql \"SELECT * FROM user_identity WHERE id = ?\" 1)
     (sql \"SELECT * FROM clients WHERE active = ? LIMIT ?\" true 5)"
  [query & params]
  (let [ds (core/bean javax.sql.DataSource)]
    (with-open [conn (.getConnection ds)]
      (.setReadOnly conn true)
      (with-open [stmt (.prepareStatement conn query)]
        (dorun (map-indexed (fn [i p] (.setObject stmt (inc i) p)) params))
        (with-open [rs (.executeQuery stmt)]
          (rs->maps rs))))))
