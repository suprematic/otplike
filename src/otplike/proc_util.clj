(ns otplike.proc-util
  (:require [clojure.core.async :as async :refer [<! go-loop]]
            [otplike.process :refer [!]]))

(defn pipe [from to]
  (go-loop []
    (let [message (<! from)]
      (! to [from message])
      (when message
        (recur)))))
