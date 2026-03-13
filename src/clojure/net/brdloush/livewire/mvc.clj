(ns net.brdloush.livewire.mvc
  "Spring MVC utilities — response serialization using the live converter chain."
  (:require [net.brdloush.livewire.core :as core])
  (:import [org.springframework.http MediaType]
           [org.springframework.http.converter.json JacksonJsonHttpMessageConverter]
           [java.io ByteArrayOutputStream]
           [java.util.zip GZIPOutputStream]))

(defn- mvc-converters []
  (.getMessageConverters (core/bean "requestMappingHandlerAdapter")))

(defn- gzip-size ^long [^String s]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [gz (GZIPOutputStream. baos)]
      (.write gz (.getBytes s "UTF-8")))
    (.size baos)))

(defn- winning-converter [obj]
  (let [mt MediaType/APPLICATION_JSON]
    (->> (mvc-converters)
         (filter #(.canWrite % (.getClass obj) mt))
         first)))

(defn serialize
  "Serializes `obj` using the same Jackson ObjectMapper that Spring MVC would
   use for the HTTP response — same modules, same naming strategy, same config.

   Options (keyword args):
     :limit   When set and the result is a top-level List, truncates to the
              first N items and attaches metadata {:total N :returned M} to
              the returned Clojure vector. Inner elements are left as-is
              (Java LinkedHashMaps with string keys — readable from the REPL).
              When not set or result is not a List, returns a pretty-printed
              JSON string.

   - If the winning converter is not JacksonJsonHttpMessageConverter (e.g.
     byte[] → ByteArrayHttpMessageConverter), returns a descriptive string.
   - Returns nil for a nil result."
  [obj & {:keys [limit]}]
  (when obj
    (let [winner (winning-converter obj)]
      (if (instance? JacksonJsonHttpMessageConverter winner)
        (let [om   (.getMapper winner)
              json (.writeValueAsString om obj)
              parsed (.readValue om json Object)]
          (if (and limit (instance? java.util.List parsed))
            (let [total (count parsed)]
              (with-meta (vec (take limit parsed))
                         {:total              total
                          :returned           (min limit total)
                          :content-size       (count (.getBytes json "UTF-8"))
                          :content-size-gzip  (gzip-size json)}))
            (.writeValueAsString (.writerWithDefaultPrettyPrinter om) obj)))
        (str "<binary or non-JSON result: "
             (.getSimpleName (.getClass obj))
             " — would be handled by "
             (.getSimpleName (.getClass winner)) ">")))))
