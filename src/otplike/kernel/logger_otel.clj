(ns otplike.kernel.logger-otel
  (:require
   [clojure.core.match :refer [match]]
   [clojure.core.async :as async]
   [otplike.process :as p]
   [otplike.proc-util :as pu]
   [otplike.kernel.logger :as klogger]))

(def ^:private otel-severity
  (try
    (let [logs-severity (import 'io.opentelemetry.api.logs.Severity)]
      (letfn
       [(severity [s]
          (-> (.getField logs-severity (name s)) (.get nil)))]
        #(get
          {:debug   (severity 'DEBUG)
           :notice  (severity 'INFO)
           :info (severity 'INFO2)
           :warning (severity 'WARN)
           :error (severity 'ERROR)
           :critical (severity 'FATAL)
           :alert (severity 'FATAL2)
           :emergency (severity 'FATAL3)}
          %
          (severity 'UNDEFINED_SEVERITY_NUMBER))))
    (catch ClassNotFoundException _
      nil)))

(defn- flatten-map [m prefix]
  (letfn
   [(fvalue [v]
      (cond
        (or (keyword? v) (symbol? v))
        (name v)
        :else
        (str v)))]

    (reduce
     (fn [acc [k v]]
       (let
        [k (name k)
         new-prefix (if (empty? prefix) k (str prefix "." k))]
         (cond
           (map? v)
           (merge acc (flatten-map v new-prefix))

           (or (sequential? v) (set? v))
           (apply
            merge acc
            (map-indexed
             (fn [i v]
               (cond
                 (map? v)
                 (flatten-map v (str new-prefix "." i))

                 (or (sequential? v) (set? v))
                 (flatten-map {(str i) v} new-prefix)

                 :else
                 {(str new-prefix "." i) (fvalue v)}))
             v))

           :else
           (assoc acc new-prefix (fvalue v)))))
     {}
     m)))

(def ^:private otel-attributes
  (let
   [make-builder
    (try
      #(.invoke
        (->
         (import 'io.opentelemetry.api.common.Attributes)
         (.getMethod "builder" (make-array Class 0))) nil (make-array Object 0))
      (catch ClassNotFoundException _
        nil))]
    (when make-builder
      (fn [attributes]
        (let [flat (flatten-map attributes "")]
          (->>
           flat
           (reduce
            (fn [ac [k v]]
              (.put ac k (str v)))
            (make-builder))
           (.build)))))))

#_(otel-attributes {:a 1 :b {:a "hello" :b true}})
#_(flatten-map {:a [1 2 3 [4 5 {:c [1 {:x [1 2] :y {:b [1 {:p :q} 3]}}] :d {:v [1 2 3]}}]] :b 1} "")
#_(flatten-map {:q {:a {:x [1 2 {:a 1 :b [4 5 6]}]}} :w 1 :z [1 23] :kw :kw :s 'symbol} "")
#_(flatten-map {:a [1 2 3 [4 5 6 7]]} "")
#_(flatten-map {:a #{:a :b :c} :b :x} "")

(def ^:private output
  (let
   [bridge
    (try
      (->
       (import 'io.opentelemetry.api.GlobalOpenTelemetry)
       (.getMethod "get" (make-array Class 0))
       (.invoke nil (make-array Object 0))
       (.getLogsBridge))
      (catch ClassNotFoundException _
        nil))]
    (when (some? bridge)
      (fn [{:keys [level message when in] :as m}]
        (let
         [log
          (-> (.get bridge in)
              (.logRecordBuilder)
              (.setAllAttributes (otel-attributes (dissoc m :level :when :message)))
              (.setSeverityText (name level))
              (.setSeverity (otel-severity level))
              (.setTimestamp (.toInstant when)))]
          (if (string? message)
            (-> log (.setBody message) (.emit))
            (.emit log)))))))

(defn- filter-fn [_config {:keys [src]}]
  (not= src :j-log))

(p/proc-defn p-log [config ch]
  (p/flag :trap-exit true)
  (pu/!chan ch {:close? true :close-reason :normal})

  (loop [timeout :infinity reason nil]
    (p/receive!
     [:EXIT _ reason']
     (recur 1000 reason')

     message
     (do
       (when output
         (let [message (klogger/mask config message)]
           (when (filter-fn config message)
             (output message))))
       (recur timeout reason))

     (after timeout (p/exit reason)))))

