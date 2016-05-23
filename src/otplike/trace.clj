(ns otplike.trace
  (:require
    [clojure.core.match :refer [match]]
    [clojure.core.async :as async]))

(def traceble
  #{:start :terminate :_deliver :_inbound :return})

(defn- default-trace-fn [pid event]
  (match event
    [:terminate reason]
    (when-not (or (= reason :normal) (= reason :shutdown))
      (locking println
        (println "ERROR: " pid "terminated with reason: " reason)))
    _
    nil))

(def *trace-fn
  (atom default-trace-fn))

(defn trace [pid event]
  (when-let [trace-fn @*trace-fn]
    (trace-fn pid event)))

(defn set-trace [tracefn]
  (reset! *trace-fn tracefn))

(defn trace-collector [stop-list]
  (let [result (async/chan)
        traces (atom [])
        stop-list (atom (apply hash-set stop-list))
        saved @*trace-fn]
    (set-trace
      (fn [pid event]
        (swap! traces conj [pid event])

        (when-let [name (:name pid)]
          (when (= (first event) :terminate)
            (swap! stop-list disj name)))

        (when (empty? @stop-list)
          (async/close! result))))

    (fn [timeout]
      (async/alts!! [result (async/timeout timeout)])
      (set-trace saved)
        @traces)))

(defn terminated? [trace pid reason]
  (some
    (fn [t]
      (match t
        [pid [:terminate reason]]
        true
        _
        false
        )) trace))
