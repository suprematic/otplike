(ns otplike.kernel.logger
  (:require
   [clojure.walk :as walk]
   [otplike.process :as p]
   [otplike.kernel.logger-console :as console]
   [otplike.kernel.logger-filter :as lf]))

(defn- mask [config message]
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

(defn make-backend [config log]
  (let
   [mask (partial mask config)
    log (partial log config)]
    {:filter-fn
     (lf/make-filter-fn config)

     :log-fn
     (fn [log-entry]
       (log (mask log-entry)))}))

(def ^:private backends
  (atom
   {:console
    (make-backend
     {:threshold :warning
      :mask-keys #{}}
     console/log)}))

(defn register-backend! [id backend]
  (swap! backends assoc id backend))

(defn unregister-backend! [id]
  (swap! backends dissoc id))

(defn enabled? [level in]
  (boolean
   (some
    (fn [{:keys [filter-fn]}]
      (filter-fn level in))
    (vals @backends))))

(defn log [ns level {:keys [in] :as message}]
  (let
   [ns (str ns)
    in
    (cond
      (or (keyword? in) (symbol? in)) (str (or (namespace in) ns) "/" (name in))
      (string? in) in
      :else ns)]

    (when (enabled? level in) ; at least one backend
      (let
       [message
        (->
         message
         (merge
          {:at ns
           :in in
           :level level
           :id (str (java.util.UUID/randomUUID))
           :pid (or (some-> otplike.process/*self* otplike.process/pid->str) "noproc")
           :when (java.time.ZonedDateTime/now)}))]

        (doseq [{:keys [filter-fn log-fn]} (vals @backends)]
          (when (filter-fn level in)
            (log-fn message)))))))
