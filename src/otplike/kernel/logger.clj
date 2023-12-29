(ns otplike.kernel.logger
  (:require
   [clojure.core.async :as async]
   [otplike.process :as p]
   [otplike.kernel.logger-console :as console]))

(def ^:private sink (async/chan 16))

(def mult
  (async/mult sink))

(def clog
  (atom
   (fn [m]
     (console/log {:threshold :warning :mask-keys #{}} m))))

(defn configure! [config]
  (reset! clog (partial console/log config)))

(defn log* [ns level {:keys [in] :as message}]
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
    (@clog message)
    (async/>!! sink message)))
