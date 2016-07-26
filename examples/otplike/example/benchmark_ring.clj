(ns otplike.example.benchmark-ring
  (:require [otplike.process :as process :refer [!]]
            [clojure.core.async :as async :refer [<! <!!]]))

(process/defproc proc [inbox n pid]
  #_(println "ready" n)
  (if (= 0 n)
    (! pid :ok)
    (let [npid (process/spawn proc [(dec n) pid] {})]
      (! npid :ok)
      (process/receive! :ok :ok)))
  #_(println "done" n))

(defn start [n]
  (let [done (async/chan)]
    (process/spawn (process/proc-fn [inbox]
                     (process/spawn proc [n (process/self)] {})
                     (process/receive! :ok :ok)
                     #_(println "done" n)
                     (async/close! done))
                 []
                 {})
    (<!! done)))
