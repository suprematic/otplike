(ns otplike.timer-test
  (:require [clojure.test :refer [is deftest]]
            [clojure.future :refer :all]
            [clojure.core.match :refer [match]]
            [otplike.process :as process :refer [!]]
            [clojure.core.async :as async :refer [<! >!]]
            [otplike.test-util :refer :all]
            [otplike.proc-util :as proc-util]
            [otplike.timer :as timer]))

(defn ms-diff [start-ns]
  (quot (- (System/nanoTime) start-ns) 1000000))

;; ====================================================================
;; (apply-after [msecs f args])

(deftest ^:parallel apply-after.correct-time
  (let [done (async/chan)]
    (timer/apply-after 0 async/close! [done])
    (is (match (await-completion!! done 100) :closed :ok)
        "fn must be applied just after timeout"))
  (let [done (async/chan)
        start (System/nanoTime)]
    (timer/apply-after 100 async/close! [done])
    (is (match (await-completion!! done 200)
               :closed (is (>= (ms-diff start) 100)
                           "fn must not be applied before timeout"))
        "fn must be applied just after timeout"))
  (let [done (async/chan)
        start (System/nanoTime)]
    (timer/apply-after 100 #(async/close! done) [])
    (is (match (await-completion!! done 200)
               :closed (is (>= (ms-diff start) 100)
                           "fn must not be applied before timeout"))
        "fn must be applied just after timeout")))

(deftest ^:parallel apply-after.in-process-context
  (let [done (async/chan)
        f (fn []
            (is (process/self)
                "fn must be applied in process context")
            (async/close! done))]
    (timer/apply-after 0 f [])
    (is (match (await-completion!! done 100) :closed :ok)
        "fn must be applied just after timeout")))

(deftest ^:parallel apply-after.fn-exit
;(otplike.proc-util/execute-proc!!
  (let [done (async/chan)]
    (timer/apply-after 100 #(async/close! done) [])
    (timer/apply-after 1 #(process/exit (process/self) :kill) [])
    (timer/apply-after 1 inc [])
    (timer/apply-after 1 #(throw (Exception.)) [])
    (timer/apply-after 1 #(process/exit :abnormal) [])
    (is (match (await-completion!! done 200) :closed :ok)
        "timer must work after fn has thrown")))

(deftest ^:parallel apply-after.bad-args
  (is (thrown? Exception (timer/apply-after 1 inc {})))
  (is (thrown? Exception (timer/apply-after 1 inc "args")))
  (is (thrown? Exception (timer/apply-after 1 inc 1)))
  (is (thrown? Exception (timer/apply-after 1 "str" [1])))
  (is (thrown? Exception (timer/apply-after 1 :key [1])))
  (is (thrown? Exception (timer/apply-after :a inc [1])))
  (is (thrown? Exception (timer/apply-after -1 inc [1]))))

;; ====================================================================
;; (cancel [])

(deftest ^:parallel cancel.apply-after
  (let [done (async/chan)
        tref (timer/apply-after 100 #(async/close! done))]
    (timer/cancel tref)
    (is (thrown? Exception (await-completion!! done 200))
        "fn must not be applied after timer has been canceled"))
  (let [done (async/chan)
        tref (timer/apply-after 100 async/close! [done])]
    (timer/cancel tref)
    (is (thrown? Exception (await-completion!! done 200))
        "fn must not be applied after timer has been canceled")))

(deftest ^:parallel cancel.send-after
  (proc-util/execute-proc!!
    (let [tref (timer/send-after 100 (process/self) :msg)]
      (timer/cancel tref)
      (process/receive!
        :msg (is false "message must not be sent after timer has been canceled")
        (after 200 :ok))))
  (proc-util/execute-proc!!
    (let [tref (timer/send-after 100 :msg)]
      (timer/cancel tref)
      (process/receive!
        :msg (is false "message must not be sent after timer has been canceled")
        (after 200 :ok)))))

(deftest ^:parallel cancel.exit-after
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [tref (timer/exit-after 100 (process/self) :abnormal)]
      (timer/cancel tref)
      (process/receive!
        [:EXIT _ :abnormal]
        (is false "exit must not be sent after timer has been canceled")
        (after 200 :ok))))
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [tref (timer/exit-after 100 :abnormal)]
      (timer/cancel tref)
      (process/receive!
        [:EXIT _ :abnormal]
        (is false "exit must not be sent after timer has been canceled")
        (after 200 :ok)))))

(deftest ^:parallel cancel.kill-after
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [pfn (process/proc-fn [] (process/receive! _ :ok))
          pid (process/spawn-link pfn)
          tref (timer/kill-after 100 pid)]
      (timer/cancel tref)
      (process/receive!
        [:EXIT _ :killed]
        (is false "process must not be killed after timer has been canceled")
        (after 200 :ok))))
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [tref (timer/kill-after 100)]
      (timer/cancel tref)
      (is (process/receive!  _ false (after 200 :ok))
          "process must not be killed after timer has been canceled"))))

(deftest ^:parallel cancel.apply-interval
  (proc-util/execute-proc!!
    (let [done (async/chan)
          tref (timer/apply-interval 100 async/put! [done :msg])]
      (timer/cancel tref)
      (is (thrown? Exception (await-completion! done 200))
          (str "fn must not be applied if timer has been canceled before"
               " first timeout"))))
  (proc-util/execute-proc!!
    (let [done (async/chan)
          tref (timer/apply-interval 100 #(async/put! done :msg))]
      (timer/cancel tref)
      (is (thrown? Exception (await-completion! done 200))
          (str "fn must not be applied if timer has been canceled before"
               " first timeout"))))
  (proc-util/execute-proc!!
    (let [done (async/chan)
          tref (timer/apply-interval 100 #(async/put! done :msg))]
      (is (= (await-completion! done 200) [:ok :msg])
          "fn must be applied by timeout while timer has not been canceled")
      (timer/cancel tref)
      (is (thrown? Exception (await-completion! done 200))
          (str "fn must not be applied if timer has been canceled before"
               " first timeout")))))

(deftest ^:parallel cancel.send-interval
  (proc-util/execute-proc!!
    (let [tref (timer/send-interval 100 :msg)]
      (timer/cancel tref)
      (process/receive!
        :msg
        (is false
            (str "message must not be sent if timer has been canceled before"
                 " first timeout"))
        (after 200 :ok))))
  (proc-util/execute-proc!!
    (let [tref (timer/send-interval 100 (process/self) :msg)]
      (timer/cancel tref)
      (process/receive!
        :msg
        (is false
            (str "message must not be sent if timer has been canceled before"
                 " first timeout"))
        (after 200 :ok))))
  (proc-util/execute-proc!!
    (let [tref (timer/send-interval 100 (process/self) :msg)]
      (process/receive!
        :msg :ok
        (after 200
          (is false "message must be sent if timer has not been canceled")))
      (timer/cancel tref)
      (process/receive!
        :msg
        (is false
            (str "message must not be sent if timer has been canceled before"
                 " first timeout"))
        (after 200 :ok)))))

(deftest ^:parallel cancel.after-timer-finished
  (proc-util/execute-proc!!
    (let [done (async/chan)
          tref (timer/apply-after 0 async/close! [done])]
      (is (= :closed (await-completion! done 100))
        "fn must be applied just after timeout")
      (is (do (timer/cancel tref) true)
          "cancel must accept tref even if tref's timer has already fired")))
  (proc-util/execute-proc!!
    (let [tref (timer/send-after 0 (process/self) :msg)]
      (process/receive!
        :msg :ok
        (after 100 (is false "message must be sent just after timeout")))
      (is (do (timer/cancel tref) true)
          "cancel must accept tref even if tref's timer has already fired")))
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [tref (timer/exit-after 0 (process/self) :normal)]
      (process/receive!
        [:EXIT _ :normal] :ok
        (after 100 (is false "exit must be sent just after timeout")))
      (is (do (timer/cancel tref) true)
          "cancel must accept tref even if tref's timer has already fired")))
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [pid (process/spawn-link (process/proc-fn [] (process/receive! _ :ok)))
          tref (timer/kill-after 0 pid)]
      (process/receive!
        [:EXIT _ :killed] :ok
        (after 100 (is false "kill must be sent just after timeout")))
      (is (do (timer/cancel tref) true)
          "cancel must accept tref even if tref's timer has already fired"))))

(deftest ^:parallel cancel.bad-args
  (is (thrown? Exception (timer/cancel 1)))
  (is (thrown? Exception (timer/cancel :a)))
  (is (thrown? Exception (timer/cancel "timer-ref")))
  (is (thrown? Exception (timer/cancel [])))
  (is (thrown? Exception (timer/cancel [:timer-ref])))
  (is (thrown? Exception (timer/cancel {})))
  (is (thrown? Exception (timer/cancel (fn [] :ok)))))

;; ====================================================================
;; (send-after [msecs pid message])

(deftest ^:parallel send-after.correct-time
  (proc-util/execute-proc!!
    (timer/send-after 0 (process/self) :msg)
    (process/receive!
      :msg :ok
      (after 100 (is false "message must be sent just after timeout"))))
  (let [done (async/chan)
        pfn (process/proc-fn [] (process/receive! :msg (async/close! done)))
        pid (process/spawn pfn)
        start (System/nanoTime)]
    (timer/send-after 100 pid :msg)
    (is (= :closed (await-completion!! done 200))
        "message must be sent just after timeout")
    (is (>= (ms-diff start) 100) "message must not be sent before timeout"))
  (proc-util/execute-proc!!
    (let [start (System/nanoTime)]
      (timer/send-after 100 :msg)
      (process/receive!
        :msg (is (>= (ms-diff start) 100)
                 "message must not be sent before timeout")
        (after 200 (is false "message must be sent just after timeout"))))))

(deftest ^:parallel send-after.send-to-not-existing-process
  (proc-util/execute-proc!!
    (let [start (System/nanoTime)]
      (timer/send-after 0 :proc :msg1)
      (timer/send-after 100 :msg2)
      (process/receive!
        :msg2 (is (>= (ms-diff start) 100)
                 "message must not be sent before timeout")
        (after 200 (is false "message must be sent just after timeout")))))
  (proc-util/execute-proc!!
    (let [pid (process/spawn (process/proc-fn [] :ok))
          _ (timer/send-after 100 pid :msg1)
          start (System/nanoTime)]
      (timer/send-after 100 :msg2)
      (process/receive!
        :msg2 (is (>= (ms-diff start) 100)
                 "message must not be sent before timeout")
        (after 200 (is false "message must be sent just after timeout"))))))

(def-proc-test ^:parallel send-after.bad-args
;(proc-util/execute-proc!!
  (is (thrown? Exception (timer/send-after 1 nil :msg)))
  (is (thrown? Exception (timer/send-after :a :msg)))
  (is (thrown? Exception (timer/send-after -1 :msg)))
  (is (thrown? Exception (timer/send-after :a (process/self) :msg)))
  (is (thrown? Exception (timer/send-after -1 :process :msg))))

;; ====================================================================
;; (exit-after [msecs ])

(deftest ^:parallel exit-after.correct-time
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (timer/exit-after 0 :test)
    (process/receive!
      [:EXIT _ :test] :ok
      (after 100 (is false "exit must be sent just after timeout"))))
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [pfn (process/proc-fn [] (process/receive! _ :ok))
          pid (process/spawn-opt pfn [] {:link true :register :proc})
          start (System/nanoTime)]
      (timer/exit-after 100 :proc :test)
      (process/receive!
        [:EXIT pid :test] (is (>= (ms-diff start) 100)
                            "exit must not be sent before timeout")
        (after 200 (is false "exit must be sent just after timeout")))))
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [pfn (process/proc-fn [] (process/receive! _ :ok))
          pid (process/spawn-link pfn)
          start (System/nanoTime)]
      (timer/exit-after 100 pid :test)
      (process/receive!
        [:EXIT pid :test] (is (>= (ms-diff start) 100)
                            "exit must not be sent before timeout")
        (after 200 (is false "exit must be sent just after timeout")))))
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [start (System/nanoTime)]
      (timer/exit-after 100 :test)
      (process/receive!
        [:EXIT _ :test] (is (>= (ms-diff start) 100)
                            "exit must not be sent before timeout")
        (after 200 (is false "exit must be sent just after timeout"))))))

(deftest ^:parallel exit-after.exit-not-existing-process
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [start (System/nanoTime)]
      (timer/exit-after 0 :proc :test1)
      (timer/exit-after 100 :test2)
      (process/receive!
        [:EXIT _ :test2] (is (>= (ms-diff start) 100)
                             "exit must not be sent before timeout")
        (after 200 (is false "exit must be sent just after timeout")))))
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [pid (process/spawn (process/proc-fn [] :ok))
          _ (timer/exit-after 100 pid :test1)
          start (System/nanoTime)]
      (timer/exit-after 200 :test2)
      (process/receive!
        [:EXIT _ :test2] (is (>= (ms-diff start) 200)
                             "exit must not be sent before timeout")
        (after 300 (is false "exit must be sent just after timeout"))))))

(def-proc-test ^:parallel exit-after.bad-args
;(proc-util/execute-proc!!
  (is (thrown? Exception (timer/exit-after 1 nil :test)))
  (is (thrown? Exception (timer/exit-after :a :test)))
  (is (thrown? Exception (timer/exit-after -1 :test)))
  (is (thrown? Exception (timer/exit-after :a (process/self) :test)))
  (is (thrown? Exception (timer/exit-after -1 :process :test))))

;; ====================================================================
;; (kill-after [msecs ])

(deftest ^:parallel kill-after.correct-time
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [start (System/nanoTime)
          pfn (process/proc-fn []
                (process/receive!
                  _ :ok))
          pid (process/spawn-link pfn)]
      (timer/kill-after 0 pid)
      (process/receive!
        [:EXIT pid :killed] :ok
        (after 100 (is false "kill must be sent just after timeout")))))
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [pfn (process/proc-fn [] (process/receive! _ :ok))
          pid (process/spawn-opt pfn [] {:link true :register :proc})
          start (System/nanoTime)]
      (timer/kill-after 100 :proc)
      (process/receive!
        [:EXIT pid :killed] (is (>= (ms-diff start) 100)
                            "kill must not be sent before timeout")
        (after 200 (is false "kill must be sent just after timeout")))))
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [pfn (process/proc-fn [] (process/receive! _ :ok))
          pid (process/spawn-link pfn)
          start (System/nanoTime)]
      (timer/kill-after 100 pid)
      (process/receive!
        [:EXIT pid :killed] (is (>= (ms-diff start) 100)
                            "kill must not be sent before timeout")
        (after 200 (is false "kill must be sent just after timeout")))))
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [pfn (process/proc-fn []
                (timer/kill-after 100)
                (process/receive! _ :ok))
          pid (process/spawn-link pfn)
          start (System/nanoTime)]
      (process/receive!
        [:EXIT pid :killed] (is (>= (ms-diff start) 100)
                                "kill must not be sent before timeout")
        (after 200 (is false "kill must be sent just after timeout"))))))

(deftest ^:parallel kill-after.kill-not-existing-process
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [_ (timer/kill-after 0 :proc)
          pfn (process/proc-fn [] (process/receive!  _ :ok))
          pid (process/spawn-link pfn)
          start (System/nanoTime)]
      (timer/kill-after 100 pid)
      (process/receive!
        [:EXIT pid :killed] (is (>= (ms-diff start) 100)
                                "kill must not be sent before timeout")
        (after 200 (is false "kill must be sent just after timeout")))))
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [pfn1 (process/proc-fn [] :ok)
          pid1 (process/spawn pfn1)
          _ (timer/kill-after 100 pid1)
          pfn2 (process/proc-fn [] (process/receive!  _ :ok))
          pid2 (process/spawn-link pfn2)
          start (System/nanoTime)]
      (timer/kill-after 200 pid2)
      (process/receive!
        [:EXIT pid :killed] (is (>= (ms-diff start) 200)
                                "kill must not be sent before timeout")
        (after 300 (is false "kill must be sent just after timeout"))))))

(def-proc-test ^:parallel kill-after.bad-args
  (is (thrown? Exception (timer/kill-after 1 nil)))
  (is (thrown? Exception (timer/kill-after :a)))
  (is (thrown? Exception (timer/kill-after -1)))
  (is (thrown? Exception (timer/kill-after :a (process/self))))
  (is (thrown? Exception (timer/kill-after -1 :process))))

;; ====================================================================
;; (apply-interval [msecs ])

(deftest ^:parallel apply-interval.correct-time
  (proc-util/execute-proc!!
    (let [parent (process/self)
          f #(! parent :msg)]
      (timer/apply-interval 0 f)
      (dotimes [_ 3]
        (process/receive!
          :msg :ok
          (after 100 (is false "fn must be applied just after timeout"))))))
  (proc-util/execute-proc!!
    (let [parent (process/self)
          f #(! parent %)
          start (System/nanoTime)]
      (timer/apply-interval 100 f [:msg])
      (dotimes [n 3]
        (process/receive!
          :msg (is (>= (ms-diff start) (* 100 (inc n)))
                   "fn must not be applied before timeout")
          (after 200 (is false "fn must be applied just after timeout")))))))

(deftest ^:parallel apply-interval.apply-in-process-context
  (proc-util/execute-proc!!
    (let [parent (process/self)
          f (fn [msg]
              (is (process/self) "fn must be applied in process context")
              (! parent msg))]
      (timer/apply-interval 0 f [:msg])
      (dotimes [n 3]
        (process/receive!
          :msg :ok
          (after 100 (is false "fn must be applied just after timeout")))))))

(deftest ^:parallel apply-interval.fn-exit
  (proc-util/execute-proc!!
    (let [parent (process/self)
          f (fn [msg]
              (! parent msg)
              (throw (Exception.)))]
      (timer/apply-interval 0 f [:msg])
      (dotimes [n 3]
        (process/receive!
          :msg :ok
          (after 100 (is false "fn must be applied just after timeout"))))))
  (proc-util/execute-proc!!
    (let [parent (process/self)
          f (fn [msg]
              (! parent msg)
              (process/exit :abnormal))]
      (timer/apply-interval 0 f [:msg])
      (dotimes [n 3]
        (process/receive!
          :msg :ok
          (after 100 (is false "fn must be applied just after timeout"))))))
  (proc-util/execute-proc!!
    (let [parent (process/self)
          f (fn [msg]
              (! parent msg)
              (process/exit (process/self) :kill))]
      (timer/apply-interval 0 f [:msg])
      (dotimes [n 3]
        (process/receive!
          :msg :ok
          (after 100 (is false "fn must be applied just after timeout")))))))

(def-proc-test ^:parallel apply-interval.bad-args
;(proc-util/execute-proc!!
  (is (thrown? Exception (timer/apply-interval 1 inc {})))
  (is (thrown? Exception (timer/apply-interval 1 inc "args")))
  (is (thrown? Exception (timer/apply-interval 1 inc 1)))
  (is (thrown? Exception (timer/apply-interval 1 "str" [1])))
  (is (thrown? Exception (timer/apply-interval 1 :key [1])))
  (is (thrown? Exception (timer/apply-interval :a inc [1])))
  (is (thrown? Exception (timer/apply-interval -1 inc [1]))))

(deftest ^:parallel apply-interval.not-in-process-context
  (is (thrown? Exception (timer/apply-interval 1 inc [1])))
  (is (thrown? Exception (timer/apply-interval 1 #(inc 1) [])))
  (is (thrown? Exception (timer/apply-interval 1 #(inc 1)))))

(deftest ^:parallel apply-interval.stops-on-linked-process-exit
  (proc-util/execute-proc!!
    (let [parent (process/self)
          pfn (process/proc-fn []
                (timer/apply-interval 100 #(! parent :msg))
                (process/receive!
                  _ :ok))
          pid (process/spawn pfn)]
      (dotimes [n 3]
        (process/receive!
          :msg :ok
          (after 200 (is false "fn must be applied just after timeout"))))
      (process/exit pid :stop)
      (process/receive!
        _ (is false "interval timer must stop when parent process exits")
        (after 200 :ok)))))

;; ====================================================================
;; (send-interval [msecs ])

(deftest ^:parallel send-interval.correct-time
  (proc-util/execute-proc!!
    (timer/send-interval 0 :msg)
    (dotimes [n 3]
      (process/receive!
        :msg :ok
        (after 100 (is false "message must be sent just after timeout")))))
  (proc-util/execute-proc!!
    (let [parent (process/self)
          pfn (process/proc-fn []
                (process/receive!
                  :msg1 (do
                          (! parent :msg2)
                          (recur))))
          pid (process/spawn pfn)
          start (System/nanoTime)]
      (timer/send-interval 100 pid :msg1)
      (dotimes [n 3]
        (process/receive!
          :msg2 (is (>= (ms-diff start) (* 100 (inc n)))
                    "message must not be sent before timeout")
          (after 200 (is false "message must be sent just after timeout")))))))

(def-proc-test ^:parallel send-interval.bad-args
;(proc-util/execute-proc!!
  (is (thrown? Exception (timer/send-interval 1 nil :msg)))
  (is (thrown? Exception (timer/send-interval :a :msg)))
  (is (thrown? Exception (timer/send-interval -1 :msg)))
  (is (thrown? Exception (timer/send-interval :a (process/self) :msg)))
  (is (thrown? Exception (timer/send-interval -1 :proc :msg))))

(deftest ^:parallel send-interval.not-in-process-context
  (is (thrown? Exception (timer/send-interval 1 :msg))))

(deftest ^:parallel send-interval.stops-on-linked-process-exit
  (proc-util/execute-proc!!
    (let [parent (process/self)
          pfn (process/proc-fn []
                (timer/send-interval 100 :msg1)
                (loop []
                  (process/receive!
                    :msg1 (! parent :msg2))
                  (recur)))
          pid (process/spawn pfn)]
      (dotimes [n 3]
        (process/receive!
          :msg2 :ok
          (after 200 (is false "message must be sent just after timeout"))))
      (process/exit pid :stop)
      (process/receive!
        _ (is false "interval timer must stop when parent process exits")
        (after 200 :ok)))))
