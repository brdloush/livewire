(ns net.brdloush.livewire.jpa-query
  "Speculative JPQL execution with smart entity serialization.
   Runs a JPQL query against the live EntityManager inside a read-only
   transaction and returns a vector of plain Clojure maps, with lazy
   collections rendered as \"<lazy>\" and ancestor-chain cycles as \"<circular>\"."
  (:require [net.brdloush.livewire.core :as core]
            [net.brdloush.livewire.entity-serialize :as es]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Scalar projection helpers
;; ---------------------------------------------------------------------------

(defn- extract-select-aliases
  "Extracts AS <alias> names from the SELECT clause of `jpql` in order,
   returning a seq of keywords, or nil if none are found.
   E.g. \"SELECT b.title AS title, COUNT(lr) AS cnt FROM ...\"
        => [:title :cnt]"
  [jpql]
  (when jpql
    (let [select-clause (-> jpql
                            (str/replace #"(?i)\s+" " ")
                            (str/replace #"(?i)^.*?SELECT\s+" "")
                            (str/replace #"(?i)\s+FROM\s+.*$" ""))]
      (when-let [aliases (seq (map (comp keyword str/lower-case second)
                                   (re-seq #"(?i)\bAS\s+(\w+)" select-clause)))]
        aliases))))

(defn- unpack-scalar-row
  "Converts a single scalar-projection row to a Clojure map.
   If the row is an Object[], each element is mapped to the corresponding
   alias keyword (or :col0, :col1, … when no aliases are present).
   A bare scalar (single-column projection) is returned as-is."
  [aliases row]
  (if (.isArray (.getClass row))
    (let [items (object-array row)]
      (if aliases
        (zipmap aliases items)
        (into {} (map-indexed (fn [i v] [(keyword (str "col" i)) v]) items))))
    ;; Single-column projection — wrap for consistency
    (if aliases
      {(first aliases) row}
      {:col0 row})))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn jpa-query
  "Executes `jpql` against the live EntityManager inside a read-only
   transaction and returns a vector of serialized entity maps.

   Options (keyword args):
     :page       0-based page index  (default 0)
     :page-size  rows per page        (default 20)

   Collections within entities are capped at `:page-size` items.
   Uninitialized lazy collections render as \"<lazy>\".
   Ancestor-chain cycles render as \"<circular>\"."
  [jpql & {:keys [page page-size] :or {page 0 page-size 20}}]
  (core/in-readonly-tx
        (let [meta-map (es/build-meta-map)
            em       (core/bean jakarta.persistence.EntityManager)
            q        (doto (.createQuery em jpql)
                       (.setFirstResult (* page page-size))
                       (.setMaxResults page-size))]
        (let [results (.getResultList q)]
          (if (and (seq results)
                   (let [first-row (first results)]
                     (or (.isArray (.getClass first-row))
                         ;; single scalar: not an entity known to meta-map
                         (nil? (get meta-map (first (str/split (.getSimpleName (.getClass first-row)) #"\$")))))))
            ;; Scalar projection — unpack each row using AS aliases or :col0/:col1/...
            (let [aliases (extract-select-aliases jpql)]
              (mapv #(unpack-scalar-row aliases %) results))
            ;; Entity projection — use the full entity serializer
            (mapv #(es/entity->map meta-map page-size #{} %) results))))))
