(ns otplike.example.benchmarks
  (:require [otplike.process :as process :refer [!]]
            [otplike.proc-util :as proc-util])
  (:gen-class))

(process/proc-defn proc [n root-pid]
  (if (= 0 n)
    (! root-pid :ok)
    (let [npid (process/spawn proc [(dec n) root-pid])]
      (! npid :ok)
      (process/receive! :ok :ok))))

(defn start-ring [n]
  (proc-util/execute-proc!!
    (let [pid (process/spawn proc [n (process/self)])]
      (! pid :ok)
      (process/receive! :ok :ok)
      (println "done" n))))

#_(time (start-ring 100000))

;---

(process/proc-defn proc1 [n done]
  (if (= 0 n)
    (clojure.core.async/close! done)
    (let [npid (process/spawn proc1 [(dec n) done])]
      (! npid :ok)
      (process/receive! :ok :ok))))

(defn start-ring.no-parent [n]
  (let [done (clojure.core.async/chan)
        pid (process/spawn proc1 [n done])]
    (! pid :ok)
    (clojure.core.async/<!! done)
    (println "done" n)))

#_(time (start-ring.no-parent 100000))

;---

(process/proc-defn proc2 [] :ok)

(defn start-spawn [n]
  (dotimes [_ n]
    (process/spawn proc2))
  (println "done" n))

#_(time (start-spawn 100000))

;---

(process/proc-defn proc3 []
  (process/receive! pid (! pid :ok)))

(defn start-spawn:ping-pong [n]
  (proc-util/execute-proc!!
    (let [self (process/self)]
      (dotimes [_ n]
        (let [pid (process/spawn proc3)]
          (! pid self)
          (process/receive! :ok :ok))))
    (println "done" n)))

#_(time (start-spawn:ping-pong 100000))

;---

(process/proc-defn proc4 [parent]
  (process/receive!
    1 (! parent 1)
    n (do
        (! parent n)
        (recur parent))))

(defn start-ping-pong [n0]
  (proc-util/execute-proc!!
    (let [pid (process/spawn-link proc4 [(process/self)])]
      (loop [n n0]
        (! pid n)
        (process/receive!
          1 (println "done" n0)
          n (recur (dec n)))))))

#_(time (start-ping-pong 100000))

;---

(process/proc-defn node [parent]
  (process/receive!
    [:spread 1] (! parent [:result 1])
    [:spread n] (let [args [(process/self)]
                      msg [:spread (dec n)]]
                  (! (process/spawn node args) msg)
                  (! (process/spawn node args) msg)
                  (process/receive!
                    [:result r1]
                    (process/receive!
                      [:result r2] (! parent [:result (+ 1 r1 r2)]))))))

(defn start-process-tree [depth]
  (proc-util/execute-proc!!
    (! (process/spawn node [(process/self)]) [:spread depth])
    (process/receive!
      [:result r] [:nodes (inc r)])))

#_(time (start-process-tree 18))

(defn -main [& args]
  (time (start-process-tree 18)))
