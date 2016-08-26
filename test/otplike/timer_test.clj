(ns otplike.timer-test
  (:require [otplike.process :as process]
            [otplike.timer :as timer]))

(defn test-1 []
  (let [process (process/spawn
                  (process/proc-fn []
                    ;(send-after 5000 self :timer-message)
                    (loop []
                      ;(process/trace "user" (str "message: " message))
                      (process/receive!
                        :stop :ok
                        _ (recur))))
                  []
                  {:name "user"})]
    (let [tref (timer/send-after 1000 process :cancelled)]
      (timer/cancel tref))
    (let [tref (timer/send-interval 1000 process :interval)]
      #_(timer/cancel tref))
    (timer/send-after 5000 process :stop)
    ;(async/put! process :msg)
    )
  :ok)
