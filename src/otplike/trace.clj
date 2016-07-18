(ns otplike.trace
  (:require
    [clojure.core.match :refer [match]]
    [clojure.core.async :as async :refer [<!! <! >! put! go go-loop]]))

(defn- default-trace-fn [pid event]
  (match event
    [:terminate reason]
    (when-not (or (= reason :normal) (= reason :shutdown))
      (locking println
        (println "ERROR:" pid "terminated with reason"
                 (clojure.pprint/write reason :stream nil))))
    _
    nil))

(def *trace-fn
  (atom default-trace-fn))

(def *trace-chan
  (async/chan 1024))

(go
  (loop []
    (when-let [[pid event] (<! *trace-chan)]
      (when-let [trace-fn @*trace-fn]
        (try
          (trace-fn pid event)
          (catch Throwable e
            (.printStackTrace e)))
        (recur)))))

(defn trace [pid event]
  (when-let [trace-fn @*trace-fn]
    (async/put! *trace-chan [pid event])))

(defn set-trace [tracefn]
  (reset! *trace-fn tracefn))

(defn- timestamp-ms []
  (System/currentTimeMillis))

(defn trace-collector [stop-list]
  (let [result (async/chan)
        traces (atom [])
        stop-list (atom (apply hash-set stop-list))
        saved @*trace-fn]
    (set-trace
      (fn [pid event]
        (swap! traces conj [(timestamp-ms) pid event])

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
        [_ pid [:terminate reason]]
        true
        _
        false))
    trace))
