(ns otplike.example.benchmark-ring
  (:require [otplike.process :as process :refer [!]]
            [otplike.proc-util :as proc-util]))

(process/proc-defn proc [n root-pid]
  (if (= 0 n)
    (! root-pid :ok)
    (let [npid (process/spawn proc [(dec n) root-pid])]
      (! npid :ok)
      (process/receive! :ok :ok))))

(defn start [n]
  (proc-util/execute-proc!!
    (let [pid (process/spawn proc [n (process/self)])]
      (! pid :ok)
      (process/receive! :ok :ok)
      (println "done" n))))

;(time (start 1000000))

;---

(process/proc-defn proc1 [n done]
  (if (= 0 n)
    (clojure.core.async/close! done)
    (let [npid (process/spawn proc1 [(dec n) done])]
      (! npid :ok)
      (process/receive! :ok :ok))))

(defn start1 [n]
  (let [done (clojure.core.async/chan)
        pid (process/spawn proc1 [n done])]
    (! pid :ok)
    (clojure.core.async/<!! done)
    (println "done" n)))

;(time (start1 1000000))

;---

(process/proc-defn proc2 []
  (process/receive! pid (! pid :ok)))

(defn start2 [n]
  (proc-util/execute-proc!!
    (let [self (process/self)]
      (dotimes [_ n]
        (let [pid (process/spawn
                    (process/proc-fn []
                      (process/receive! pid (! pid :ok))))]
          (! pid self)
          (process/receive! :ok :ok))))
    (println "done" n)))

;(time (start2 10))
;(time (start2 10000000))
