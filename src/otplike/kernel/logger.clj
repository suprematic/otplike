(ns otplike.kernel.logger
  (:require
   [clojure.walk :as walk]
   [clojure.core.async :as async]
   [otplike.process :as p]))

(def ^:private sink (async/chan 16))

(def ^:private mult
  (async/mult sink))

(defn- tap []
  (let [ch (async/chan 16)]
    (async/tap mult ch)
    ch))

(def console-tap
  (tap))

(def otel-tap
  (tap))

(defn mask [config message]
  (let [mask-keys (get config :mask-keys)]
    (walk/postwalk
     (fn [node]
       (cond
         (map? node)
         (->>
          node
          (map
           (fn [[k v]]
             (if (contains? mask-keys k)
               [k "*********"]
               [k v])))
          (into {}))
         :else
         node))
     message)))

#_(mask {:password "123"})

(defn log* [ns level {:keys [in] :as message}]
  (assert (#{:emergency :alert :critical :error :warning :notice :info :debug} level))
  (let
   [at (str ns)
    message
    (->
     message
     (merge
      {:at at
       :in
       (cond
         (or (keyword? in) (symbol? in)) (str (or (namespace in) at) "/" (name in))
         (string? in) in
         :else at)
       :level level
       :id (str (java.util.UUID/randomUUID))
       :pid (or (some-> otplike.process/*self* otplike.process/pid->str) "noproc")
       :when (java.time.ZonedDateTime/now)}))]
    (async/>!! sink message)))
