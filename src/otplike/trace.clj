(ns otplike.trace
  "Examples:
  1. Print all events about processes exited abnormally:
  (console-trace (filter (filter-event crashed?)))

  See console-trace code for trace function usage example."
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
          (printf "pid:%s %s%n" pid (clojure.pprint/write event :stream nil))
          (recur))))
    ch))

(defn untrace [chan]
  (async/untap *trace-mult chan))

(defn send-trace [pid event]
  (>!! *trace-chan [pid event]))

(defn filter-pid [pid]
  (fn [[[pid1 _] _]]
    (= pid pid1)))

(defn filter-name [pname]
  (fn [[[_ pname1] _]]
    (and pname1 (= pname pname1))))

(defn filter-event [efn]
  (fn [[_ event]]
    (efn event)))

(defn crashed? [event]
  (match event
    [:terminate reason]
    (not (#{:normal :shutdown} reason))
    _
    false))
