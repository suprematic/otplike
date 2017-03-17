(ns otplike.test-util
  (:require [clojure.core.async :as async]
            [clojure.test :as clojure-test]
            [clojure.core.match :refer [match]]
            [otplike.proc-util :as proc-util]
            [otplike.process :as process]))

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
  "Returns:
    - [:ok val] if chan gets any value during timeout-ms,
    - :closed if chan becomes closed during timeout-ms.
  Otherwise throws."
  [chan timeout-ms]
  (let [timeout (async/timeout timeout-ms)]
    (match (async/alts!! [chan timeout])
      [nil chan] :closed
      [value chan] [:ok value]
      [nil timeout] (throw (Exception. (str "timeout " timeout-ms))))))

(defn await-process-exit
  "Waits for process to exit. Returns [:ok reason] if process exited
  during timeout-ms. Otherwise throws."
  [pid timeout-ms]
  (let [done (async/chan)]
    (process/spawn (process/proc-fn []
                     (process/receive!
                       [:EXIT pid reason] (async/put! done [:ok reason])
                       (after timeout-ms :timeout)))
                   {:link-to pid :flags {:trap-exit true}})
    (match (await-completion done timeout-ms) [:ok reason] reason)))

(defn notify-on-process-exit
  "Puts [:ok reason] to chan if process exited during timeout-ms
  or :timeout if not."
  [pid chan timeout-ms]
  (process/spawn (process/proc-fn []
                   (process/receive!
                     [:EXIT pid reason] (async/put! chan [:ok reason])
                     (after timeout-ms (async/put! chan :timeout))))
                 {:link-to pid :flags {:trap-exit true}}))

(defmacro def-proc-test [name & body]
  `(clojure-test/deftest ~name
     (proc-util/execute-proc
       ~@body)))
