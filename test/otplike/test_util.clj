(ns otplike.test-util
  (:require [clojure.core.async :as async]
            [clojure.core.match :refer [match]]))

(defn uuid-keyword []
  (keyword (str (java.util.UUID/randomUUID))))

(defn await-message [inbox timeout-ms]
  (async/go
    (let [timeout (async/timeout timeout-ms)]
      (match (async/alts! [inbox timeout])
        [[:EXIT _ reason] inbox] [:exit-message [:reason reason]]
        [[:DOWN ref :process object reason] inbox] [:down-message [ref object reason]]
        [nil inbox] :inbox-closed
        [msg inbox] [:message msg]
        [nil timeout] :timeout))))

(defn await-completion [chan timeout-ms]
  (let [timeout (async/timeout timeout-ms)]
    (match (async/alts!! [chan timeout])
      [nil chan] :ok
      [nil timeout] (throw (Exception. (str "timeout " timeout-ms))))))

