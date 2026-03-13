(ns net.brdloush.livewire.mvc
  "Spring MVC utilities — response serialization using the live converter chain."
  (:require [net.brdloush.livewire.core :as core])
  (:import [org.springframework.http MediaType]
           [org.springframework.http.converter.json JacksonJsonHttpMessageConverter]))

(defn- mvc-converters []
  (.getMessageConverters (core/bean "requestMappingHandlerAdapter")))

(defn- winning-converter [obj]
  (let [mt MediaType/APPLICATION_JSON]
    (->> (mvc-converters)
         (filter #(.canWrite % (.getClass obj) mt))
         first)))

(defn serialize
  "Serializes `obj` using the same Jackson ObjectMapper that Spring MVC would
   use for the HTTP response — same modules, same naming strategy, same config.

   - If the first converter that can handle the result type is
     JacksonJsonHttpMessageConverter, returns a pretty-printed JSON string.
   - If another converter would win (e.g. ByteArrayHttpMessageConverter for
     byte[]), returns a descriptive string indicating the result is non-JSON.
   - Returns nil for a nil result."
  [obj]
  (when obj
    (let [winner (winning-converter obj)]
      (if (instance? JacksonJsonHttpMessageConverter winner)
        (.writeValueAsString (.writerWithDefaultPrettyPrinter (.getMapper winner)) obj)
        (str "<binary or non-JSON result: "
             (.getSimpleName (.getClass obj))
             " — would be handled by "
             (.getSimpleName (.getClass winner)) ">")))))
