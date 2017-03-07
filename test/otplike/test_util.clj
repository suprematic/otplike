(ns otplike.test-util
  (:require [otplike.process :as process]
            [clojure.core.async :as async]
            [clojure.core.match :refer [match]]))

(defn uuid-keyword
  "Makes random keyword with a name being UUID string."
  []
  (keyword (str (java.util.UUID/randomUUID))))

(defn await-message
  "Tries to receive message. Returns:
    - :timeout if no message appears during timeout-ms
    - [:exit [pid reason]] if :EXIT message received
    - [:down [ref object reason]] if :DOWN message received
    - [:message message] on any other message
    - nil if inbox becomes closed before message appears"
  [timeout-ms]
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


(defn await-completion
  "Returns :ok if chan is closed during timeout-ms, otherwise throws."
  [chan timeout-ms]
  (let [timeout (async/timeout timeout-ms)]
    (match (async/alts!! [chan timeout])
      [nil chan] :ok
      [nil timeout] (throw (Exception. (str "timeout " timeout-ms))))))
