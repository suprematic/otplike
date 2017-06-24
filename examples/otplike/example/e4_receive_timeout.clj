(ns otplike.example.e4-receive-timeout
  (:require [otplike.process :as process :refer [!]]
            [clojure.core.async :as async]
            [otplike.proc-util :as proc-util]))

(process/proc-defn await-message [timeout]
  (process/receive!
    msg (printf "receive %s%n" msg)
    (after timeout
      (printf "timeout %s%n" timeout))))

(defn run []
  (process/spawn await-message [1000])
  (process/spawn await-message [(async/timeout 1000)])
  (let [pid (process/spawn await-message [:infinity])]
    (Thread/sleep 2000)
    (! pid :stop)
    (Thread/sleep 100)))

#_(run)
