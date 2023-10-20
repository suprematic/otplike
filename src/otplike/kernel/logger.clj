(ns otplike.kernel.logger
  (:require
   [otplike.process]
   [otplike.kernel.logger-otel :as otel]
   [otplike.kernel.logger-console :as console]))

(defn set-config! [new]
  (reset! console/config (get new :console new))
  (reset! console/cache {}))

(defn log* [ns level {:keys [in] :as message}]
  (assert (#{:emergency :alert :critical :error :warning :notice :info :debug} level))
  (let
   [at (str ns)
    in
    (cond
      (or (keyword? in) (symbol? in))
      (str (or (namespace in) at) "/" (name in))
      (string? in)
      in
      :else
      at)

    message
    (delay
      (merge
       message
       {:at at
        :pid (or (some-> otplike.process/*self* otplike.process/pid->str) "noproc")
        :when (java.time.ZonedDateTime/now)}))]

    (when (console/enabled? level in)
      (console/output
       (merge
        @message
        {:in in
         :level level
         :id (java.util.UUID/randomUUID)})))

    (when otel/otel-output
      (otel/otel-output in level @message))))