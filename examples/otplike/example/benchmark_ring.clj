(ns otplike.example.benchmark-ring
  "Creates n processes. Exits when all created processes exited.
  Each process (except the last one):
  1. Creates next process.
  2. Sends message to created process.
  3. Waits for message (from parent process).
  4. Exits normally.
  The last process created does the same but sends message to the first
  process so the first process could exit."
  (:require [otplike.process :as process :refer [!]]
            [clojure.core.async :as async :refer [<! <!!]]))

(process/defproc proc [inbox n pid]
  (if (= 0 n)
    (! pid :ok)
    (let [npid (process/spawn proc [(dec n) pid] {})]
      (! npid :ok)
      (process/receive! :ok :ok))))

(defn start [n]
  (let [done (async/chan)]
    (process/spawn (process/proc-fn [inbox]
                     (let [pid (process/spawn proc [n (process/self)] {})]
                       (! pid :ok)
                       (process/receive! :ok :ok)
                       (println "done" n)
                       (async/close! done)))
                 []
                 {})
    (<!! done)))
