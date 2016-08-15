(ns otplike.trace
  (:require
    [clojure.core.match :refer [match]]
    [clojure.core.async :as async :refer [<!! <! >! >!! go go-loop]]))

(def ^:private *trace-chan
  (async/chan 1024))

(def ^:private *trace-mult
  (async/mult *trace-chan))

(defn trace
  ([xform]
   (trace (async/buffer 1024) xform))
  ([buf-or-n xform]
   (let [chan (async/chan buf-or-n xform)]
      (async/tap *trace-mult chan)
      chan)))

(defn console-trace [& params]
  (let [ch (apply trace params)]
    (go
      (loop []
        (when-let [[pid event] (<! ch)]
          (println "pid:" pid
            (clojure.pprint/write event :stream nil))
          (recur))))
    ch))

(defn untrace [chan]
  (async/untap *trace-mult chan))

(defn send-trace [{:keys [id] :as pid} [type :as event]]
  (>!! *trace-chan [pid event]))

(defn filter-pid [pid]
  (fn [[[pid1 _] _]]
    (= pid pid1)))

(defn filter-name [name]
  (fn [[[_ name1] _]]
    (and name1 (= name name1))))

(defn filter-event [efn]
  (fn [[_ event]]
    (efn event)))

(defn crashed? [event]
  (match event
    [:terminate reason]
    (not (#{:normal :shutdown} reason))
    _
    false))
