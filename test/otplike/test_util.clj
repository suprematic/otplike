(ns otplike.test-util
  (:require [otplike.process :as process]
            [clojure.core.async :as async]
            [clojure.core.match :refer [match]]))

(defn uuid-keyword []
  (keyword (str (java.util.UUID/randomUUID))))

(defn await-message [timeout-ms]
  (async/go
    (try
      (process/receive!
        [:EXIT pid reason] [:exit [pid reason]]
        [:DOWN ref :process object reason] [:down [ref object reason]]
        msg [:message msg]
        (after timeout-ms
          :timeout))
      (catch Exception _e
        nil))))

(defn await-completion [chan timeout-ms]
  (let [timeout (async/timeout timeout-ms)]
    (match (async/alts!! [chan timeout])
      [nil chan] :ok
      [nil timeout] (throw (Exception. (str "timeout " timeout-ms))))))
