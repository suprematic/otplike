(ns otplike.example.e4-receive-timeout
  (:require [otplike.process :as process :refer [!]]
            [clojure.core.async :as async]))

(process/proc-defn await-message [timeout]
  (let [self (process/self)]
    (process/receive!
      msg (printf "[%s] receive %s%n" self msg)
      (after timeout
             (printf "[%s] timeout %s%n" self timeout)))))

(defn run []
  (let [pid0 (process/spawn await-message [1000])]
    (printf "[main] started %s%n" pid0)
    (let [pid1 (process/spawn await-message [(async/timeout 1000)])]
      (printf"[main] started %s%n" pid1)
      (let [pid2 (process/spawn await-message [:infinity])]
        (printf "[main] started %s%n" pid2)
        (printf"[main] waiting for 2 seconds%n")
        (Thread/sleep 2000)
        (printf "[main] sending :stop to %s%n" pid2)
        (! pid2 :stop)
        (printf "[main] waiting for 100ms%n")
        (Thread/sleep 100)
        (printf "[main] done%n")
        (flush)))))

#_(run)
