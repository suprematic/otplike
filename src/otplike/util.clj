(ns otplike.util
  (:require [clojure.core.async :as async :refer [<!! <! >! put! go go-loop]]
            [clojure.core.async.impl.protocols :as ap]
            [otplike.process :refer [!]]))

; TODO move to util namespace
(defn pipe [from to]
  (go-loop []
    (let [message (<! from)]
      (! to [from message])
      (when message
        (recur)))))
