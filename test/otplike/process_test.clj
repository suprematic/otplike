(ns otplike.process-test
  (:require [clojure.test :refer [is deftest]]
            [otplike.process :as process :refer [! proc-fn]]
            [otplike.trace :as trace]
            [otplike.test-util :refer :all]
            [clojure.core.async :as async :refer [<!! <! >!]]
            [clojure.core.match :refer [match]]))

(trace/set-trace (fn [_pid _event]))

;; ====================================================================
;; (self [])

(deftest ^:parallel self-returns-process-pid-in-process-context
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (process/pid? (process/self))
                  "self must return pid when called in process context")
              (is (= (process/self) (process/self))
                  "self must return the same pid when called by the same process")
              (! (process/self) :msg)
              (is (= [:message :msg] (<! (await-message inbox 100)))
                  "message sent to self must appear in inbox")
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 200)))

(deftest ^:parallel self-fails-in-non-process-context
  (is (thrown? Exception (process/self))
      "self must throw when called not in process context"))

;; ====================================================================
;; (pid? [term])

(deftest ^:parallel pid?-returns-false-on-non-pid
  (is (not (process/pid? nil)) "pid? must return false on nonpid arguement")
  (is (not (process/pid? 1)) "pid? must return false on nonpid arguement")
  (is (not (process/pid? "not-a-pid"))
      "pid? must return false on nonpid arguement")
  (is (not (process/pid? [])) "pid? must return false on nonpid arguement")
  (is (not (process/pid? '())) "pid? must return false on nonpid arguement")
  (is (not (process/pid? #{})) "pid? must return false on nonpid arguement")
  (is (not (process/pid? {})) "pid? must return false on nonpid arguement"))

(deftest ^:parallel pid?-returns-true-on-pid
  (is (process/pid? (process/spawn (proc-fn [_inbox]) [] {}))
      "pid? must return true on pid argument"))

;; ====================================================================
;; (pid->str [pid])

(deftest ^:parallel pid->str-returns-string
  (is (string? (process/pid->str (process/spawn (proc-fn [_inbox]) [] {})))
      "pid->str must return string on pid argument"))

(deftest ^:parallel pid->str-fails-on-non-pid
  (is (thrown? Exception (process/pid->str nil))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Exception (process/pid->str 1))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Exception (process/pid->str "not-a-pid"))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Exception (process/pid->str []))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Exception (process/pid->str '()))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Exception (process/pid->str #{}))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Exception (process/pid->str {}))
      "pid->str must throw on nonpid arguement"))

;; ====================================================================
;; (whereis [reg-name])

(deftest ^:parallel whereis-returns-process-pid-on-registered-name
  (let [done (async/chan)
        reg-name (uuid-keyword)
        pid (process/spawn
              (proc-fn [_inbox]
                (is (= (process/self) (process/whereis reg-name))
                    "whereis must return process pid on registered name")
                (await-completion done 100))
              []
              {:register reg-name})]
    (is (= pid (process/whereis reg-name))
        "whereis must return process pid on registered name")
    (async/close! done)))

(deftest ^:parallel whereis-returns-nil-on-not-registered-name
  (is (nil? (process/whereis "name"))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis :name))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis [:some :name]))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis 123))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis '(:a :b)))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis {:a 1}))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis #{:b}))
      "whereis must return nil on not registered name"))

(deftest ^:parallel whereis-throws-on-nil-argument
  (is (thrown? Exception (process/whereis nil))
      "whereis must throw on nil argument"))

;; ====================================================================
;; (! [dest message])

(deftest ^:parallel !-returns-true-sending-to-alive-process-by-pid
  (let [done (async/chan)
        pid (process/spawn (proc-fn [_] (await-completion done 100)) [] {})]
    (is (= true (! pid :msg)) "! must return true sending to alive process")
    (async/close! done)))

(deftest ^:parallel !-returns-true-sending-to-alive-process-by-reg-name
  (let [done (async/chan)
        reg-name (uuid-keyword)]
    (process/spawn (proc-fn [_] (await-completion done 100))
                   []
                   {:register reg-name})
    (is (= true (! reg-name :msg)) "! must return true sending to alive process")
    (async/close! done)))

(deftest ^:parallel !-returns-false-sending-to-terminated-process-by-reg-name
  (let [reg-name (uuid-keyword)]
    (process/spawn (proc-fn [_inbox]) [] {:register reg-name})
    (<!! (async/timeout 50))
    (is (= false (! reg-name :msg))
        "! must return false sending to terminated process")))

(deftest ^:parallel !-returns-false-sending-to-unregistered-name
  (is (= false (! (uuid-keyword) :msg))
      "! must return false sending to unregistered name"))

(deftest ^:parallel !-returns-false-sending-to-terminated-process-by-pid
  (let [done (async/chan)
        pid (process/spawn (proc-fn [_] (async/close! done)) [] {})]
    (await-completion done 100)
    (<!! (async/timeout 50))
    (is (= false (! pid :msg))
        "! must return false sending to terminated process")))

(deftest ^:parallel !-throws-on-nil-arguments
  (is (thrown? Exception (! nil :msg))
      "! must throw on when dest argument is nil")
  (is (thrown? Exception (! (process/spawn (proc-fn [_]) [] {}) nil))
      "! must throw on when message argument is nil")
  (is (thrown? Exception (! nil nil))
      "! must throw on when both arguments are nil"))

(deftest ^:parallel !-delivers-message-sent-by-pid-to-alive-process
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= [:message :msg] (<! (await-message inbox 100)))
                  "message sent with ! to pid must appear in inbox")
              (async/close! done))
        pid (process/spawn pfn [] {})]
    (! pid :msg)
    (await-completion done 300)))

(deftest ^:parallel !-delivers-message-sent-by-registered-name-to-alive-process
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= [:message :msg] (<! (await-message inbox 100)))
                  (str "message sent with ! to registered name"
                       " must appear in inbox"))
              (async/close! done))
        reg-name (uuid-keyword)]
    (process/spawn pfn [] {:register reg-name})
    (! reg-name :msg)
    (await-completion done 200)))

;; ====================================================================
;; (exit [pid reason])

(deftest ^:parallel exit-throws-on-nil-reason
  (let [done (async/chan)
        pid (process/spawn
              (proc-fn [_] (await-completion done 100)) [] {})]
    (is (thrown? Exception (process/exit pid nil))
        "exit must throw when reason argument is nil")
    (async/close! done)))

(deftest ^:parallel exit-throws-on-not-a-pid
  (is (thrown? Exception (process/exit nil :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Exception (process/exit 1 :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Exception (process/exit "pid1" :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Exception (process/exit [] :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Exception (process/exit '() :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Exception (process/exit {} :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Exception (process/exit #{} :normal))
      "exit must throw on not a pid argument"))

(deftest ^:parallel exit-normal-no-trap-exit
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= :timeout (<! (await-message inbox 100)))
                  "exit with reason :normal must not close process' inbox")
              (async/close! done))
        pid (process/spawn pfn [] {})]
    (process/exit pid :normal)
    (await-completion done 500)))

(deftest ^:parallel exit-normal-trap-exit
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= [:exit [(process/self) :normal]]
                     (<! (await-message inbox 100)))
                  (str "exit must send [:EXIT pid :normal] message"
                       " to process trapping exits"))
              (async/close! done))
        pid (process/spawn pfn [] {:flags {:trap-exit true}})]
    (process/exit pid :normal)
    (await-completion done 500)))

(deftest ^:parallel exit-normal-registered-process
  (let [done (async/chan)
        reg-name (uuid-keyword)
        pid (process/spawn (proc-fn [_] (await-completion done 300))
                           []
                           {:register reg-name})]
    (is ((into #{} (process/registered)) reg-name)
        "registered process must be in list of registered before exit")
    (async/close! done)
    (<!! (async/timeout 50))
    (is (nil? ((into #{} (process/registered)) reg-name))
        "process must not be registered after exit")))

(deftest ^:parallel exit-abnormal-no-trap-exit
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= :inbox-closed (<! (await-message inbox 100)))
                  (str "exit with reason other than :normal must close"
                       "process' inbox"))
              (async/close! done))
        pid (process/spawn pfn [] {})]
    (process/exit pid :abnormal)
    (await-completion done 500)))

(deftest ^:parallel exit-abnormal-trap-exit
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= [:exit [(process/self) :abnormal]]
                     (<! (await-message inbox 100)))
                  (str "exit must send [:EXIT _ reason] message"
                       " to process trapping exits"))
              (async/close! done))
        pid (process/spawn pfn [] {:flags {:trap-exit true}})]
    (process/exit pid :abnormal)
    (await-completion done 500)))

(deftest ^:parallel exit-kill-no-trap-exit
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= :inbox-closed (<! (await-message inbox 300)))
                  "exit with reason :kill must close process' inbox")
              (async/close! done))
        pid (process/spawn pfn [] {})]
    (process/exit pid :kill)
    (await-completion done 500)))

(deftest ^:parallel exit-kill-trap-exit
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= :inbox-closed (<! (await-message inbox 300)))
                  "exit with reason :kill must close process' inbox")
              (async/close! done))
        pid (process/spawn pfn [] {:flags {:trap-exit true}})]
    (process/exit pid :kill)
    (await-completion done 500)))

(deftest ^:parallel exit-returns-true-on-alive-process
  (let [pfn (proc-fn [_inbox] (<! (async/timeout 100)))]
    (let [pid (process/spawn pfn [] {})]
      (is (= true (process/exit pid :normal))
          "exit must return true on alive process"))
    (let [pid (process/spawn pfn [] {})]
      (is (= true (process/exit pid :abnormal))
          "exit must return true on alive process"))
    (let [pid (process/spawn pfn [] {})]
      (is (= true (process/exit pid :kill))
          "exit must return true on alive process"))
    (let [pid (process/spawn pfn [] {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :normal))
          "exit must return true on alive process"))
    (let [pid (process/spawn pfn [] {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :abnormal))
          "exit must return true on alive process"))
    (let [pid (process/spawn pfn [] {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :kill))
          "exit must return true on alive process"))))

(deftest ^:parallel exit-returns-false-on-terminated-process
  (let [pid (process/spawn (proc-fn [_inbox]) [] {})]
    (<!! (async/timeout 50))
    (is (= false (process/exit pid :normal))
        "exit must return false on terminated process")
    (is (= false (process/exit pid :abnormal))
        "exit must return false on terminated process")
    (is (= false (process/exit pid :kill))
        "exit must return false on terminated process")
    (is (= false (process/exit pid :normal))
        "exit must return false on terminated process")
    (is (= false (process/exit pid :abnormal))
        "exit must return false on terminated process")
    (is (= false (process/exit pid :kill))
        "exit must return false on terminated process")))

(deftest ^:parallel exit-self
  (let [done (async/chan)]
    (process/spawn
      (proc-fn [inbox]
        (process/exit (process/self) :normal)
        (is (= [:exit [(process/self) :normal]]
               (<! (await-message inbox 100)))
            (str "exit with reason :normal must send [:EXIT pid :normal]"
                 " message to process trapping exits"))
        (async/close! done))
      []
      {:flags {:trap-exit true}})
    (await-completion done 500))
  (let [done (async/chan)]
    (process/spawn
      (proc-fn [inbox]
        (process/exit (process/self) :abnormal-1)
        (is (= [:exit [(process/self) :abnormal-1]]
               (<! (await-message inbox 300)))
            (str "exit must send [:EXIT pid reason]"
                 " message to process trapping exits"))
        (process/exit (process/self) :abnormal-2)
        (is (= [:exit [(process/self) :abnormal-2]]
               (<! (await-message inbox 300)))
            (str "exit must send [:EXIT pid reason]"
                 " message to process trapping exits"))
        (async/close! done))
      []
      {:flags {:trap-exit true}})
    (await-completion done 1000))
  (let [done (async/chan)]
    (process/spawn
      (proc-fn [inbox]
        (process/exit (process/self) :kill)
        (is (= :inbox-closed (<! (await-message inbox 300)))
            "exit with reason :kill must close inbox of process trapping exits")
        (async/close! done))
      []
      {:flags {:trap-exit true}})
    (await-completion done 500))
  (let [done (async/chan)]
    (process/spawn
      (proc-fn [inbox]
        (process/exit (process/self) :normal)
        (is (= :timeout (<! (await-message inbox 300)))
            (str "exit with reason :normal must do nothing"
                 " to process not trapping exits"))
        (async/close! done))
      []
      {})
    (await-completion done 500))
  (let [done (async/chan)]
    (process/spawn
      (proc-fn [inbox]
        (process/exit (process/self) :abnormal)
        (is (= :inbox-closed (<! (await-message inbox 300)))
            (str "exit with any reason except :normal must close"
                 " inbox of proces not trapping exits"))
        (async/close! done))
      []
      {})
    (await-completion done 500))
  (let [done (async/chan)]
    (process/spawn
      (proc-fn [inbox]
        (process/exit (process/self) :kill)
        (is (= :inbox-closed (<! (await-message inbox 300)))
            (str "exit with reason :kill must close inbox of process"
                 " not trapping exits"))
        (async/close! done))
      []
      {})
    (await-completion done 500)))

; TODO
(deftest ^:parallel exit-kill-reason-killed) ; use link or monitor to test the reason
(deftest ^:parallel exit-kill-does-not-propagate)

(deftest ^:parallel
  exit-sends-exit-message-containing-pid-of-the-target-process
  (let [done (async/chan)
        pfn (proc-fn[inbox]
              (is (= [:exit [(process/self) :abnormal]]
                     (<! (await-message inbox 100)))
                  (str "trapping exits process must receive exit message"
                       " containing its pid, after exit was called for the"
                       " process"))
              (async/close! done))
        pid (process/spawn pfn [] {:flags {:trap-exit true}})]
    (process/exit pid :abnormal)
    (await-completion done 200)))

;; ====================================================================
;; (flag [flag value])

(deftest ^:parallel flag-trap-exit-true-makes-process-to-trap-exits
  (let [done1 (async/chan)
        done2 (async/chan)
        pfn (proc-fn [inbox]
              (process/flag :trap-exit true)
              (async/close! done1)
              (is (= [:exit [(process/self) :normal]]
                     (<! (await-message inbox 100)))
                  (str "flag :trap-exit set to true in process must"
                       " make process to trap exits"))
              (async/close! done2))
        pid (process/spawn pfn [] {})]
    (await-completion done1 100)
    (match (process/exit pid :normal) true :ok)
    (await-completion done2 200)))

(deftest ^:parallel flag-trap-exit-false-makes-process-not-to-trap-exits
  (let [done1 (async/chan)
        done2 (async/chan)
        pfn (proc-fn [inbox]
              (process/flag :trap-exit false)
              (async/close! done1)
              (is (= :timeout (<! (await-message inbox 100)))
                  (str "flag :trap-exit set to false in process must"
                       " make process not to trap exits"))
              (async/close! done2))
        pid (process/spawn pfn [] {:flags {:trap-exit true}})]
    (await-completion done1 100)
    (match (process/exit pid :normal) true :ok)
    (await-completion done2 200)))

(deftest ^:parallel flag-trap-exit-switches-trapping-exit
  (let [done1 (async/chan)
        done2 (async/chan)
        done3 (async/chan)
        pfn (proc-fn [inbox]
              (process/flag :trap-exit true)
              (async/close! done1)
              (is (= [:exit [(process/self) :abnormal]]
                     (<! (await-message inbox 50)))
                  (str "flag :trap-exit set to true in process must"
                       " make process to trap exits"))
              (process/flag :trap-exit false)
              (async/close! done2)
              (is (= :inbox-closed (<! (await-message inbox 100)))
                  (str "flag :trap-exit switched second time  in process"
                       " must make process to switch trapping exits"))
              (async/close! done3))
        pid (process/spawn pfn [] {})]
    (await-completion done1 100)
    (match (process/exit pid :abnormal) true :ok)
    (await-completion done2 200)
    (match (process/exit pid :abnormal) true :ok)
    (await-completion done3 300)))

(deftest ^:parallel flag-trap-exit-returns-old-value
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= true (process/flag :trap-exit false))
                  "setting flag :trap-exit must return its previous value")
              (is (= false (process/flag :trap-exit false))
                  "setting flag :trap-exit must return its previous value")
              (is (= false (process/flag :trap-exit true))
                  "setting flag :trap-exit must return its previous value")
              (is (= true (process/flag :trap-exit true))
                  "setting flag :trap-exit must return its previous value")
              (async/close! done))]
    (process/spawn pfn [] {:flags {:trap-exit true}})
    (await-completion done 300)))

(deftest ^:parallel flag-throws-on-unknown-flag
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (thrown? Exception (process/flag [] false))
                  "flag must throw on unknown flag")
              (is (thrown? Exception (process/flag 1 false))
                  "flag must throw on unknown flag")
              (is (thrown? Exception (process/flag :unknown false))
                  "flag must throw on unknown flag")
              (is (thrown? Exception (process/flag nil false))
                  "flag must throw on unknown flag")
              (is (thrown? Exception (process/flag nil true))
                  "flag must throw on unknown flag")
              (is (thrown? Exception (process/flag :trap-exit1 false))
                  "flag must throw on unknown flag")
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 300)))

(deftest ^:parallel flag-throws-when-called-not-in-process-context
  (is (thrown? Exception (process/flag :trap-exit true))
      "flag must throw when called not in process context")
  (is (thrown? Exception (process/flag :trap-exit false))
      "flag must throw when called not in process context"))

;; ====================================================================
;; (registered [])

(deftest ^:serial registered-returns-empty-seq-when-nothing-registered
  (is (empty? (process/registered))
      "registered must return empty seq of names when nothing registered"))

(deftest ^:serial registered-returns-registered-names
  (let [n1 (uuid-keyword)
        n2 (uuid-keyword)
        n3 (uuid-keyword)
        registered #{n1 n2 n3}
        done (async/chan)
        pfn (proc-fn [_inbox] (await-completion done 300))]
    (process/spawn pfn [] {:register n1})
    (process/spawn pfn [] {:register n2})
    (process/spawn pfn [] {:register n3})
    (is (= registered (process/registered))
      "registered must return registered names")
    (async/close! done)))

(deftest ^:serial registered-returns-empty-seq-after-registered-terminated
  (let [pfn (proc-fn [_inbox])]
    (process/spawn pfn [] {:register (uuid-keyword)})
    (process/spawn pfn [] {:register (uuid-keyword)})
    (process/spawn pfn [] {:register (uuid-keyword)})
    (<!! (async/timeout 50))
    (is (empty? (process/registered))
      (str "registered must return empty seq after all registered processes"
           " had terminated"))))

;; ====================================================================
;; (link [pid])

(deftest ^:parallel link-returns-true
  (let [pid (process/spawn (proc-fn [_]) [] {})
        _ (<!! (async/timeout 50))
        done (async/chan)
        pfn (proc-fn [_inbox]
              (is (= true (process/link pid))
                  "link must return true when called on terminated process")
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 300))
  (let [done1 (async/chan)
        pid (process/spawn (proc-fn [_] (await-completion done1 1000)) [] {})
        done2 (async/chan)
        pfn (proc-fn [_inbox]
              (is (= true (process/link pid))
                  "link must return true when called on alive process")
              (async/close! done1)
              (async/close! done2))]
    (process/spawn pfn [] {})
    (await-completion done2 500))
  (let [done1 (async/chan)
        pid (process/spawn (proc-fn [_] (await-completion done1 1000)) [] {})
        done2 (async/chan)
        pfn (proc-fn [_inbox]
              (is (= true (process/link pid))
                  "link must return true when called on alive process")
              (async/close! done1)
              (async/close! done2))]
    (process/spawn pfn [] {})
    (await-completion done2 500)))

(deftest ^:parallel link-throws-when-called-not-in-process-context
  (is (thrown? Exception (process/link (process/spawn (proc-fn [_]) [] {})))
      "link must throw when called not in process context")
  (let [pfn (proc-fn [_] (<! (async/timeout 100)))]
    (is (thrown? Exception (process/link (process/spawn pfn [] {})))
        "link must throw when called not in process context")))

(deftest ^:parallel link-throws-when-called-with-not-a-pid
  (is (thrown? Exception (process/link nil))
      "link must throw when called with not a pid argument")
  (is (thrown? Exception (process/link 1))
      "link must throw when called with not a pid argument")
  (is (thrown? Exception (process/link "pid1"))
      "link must throw when called with not a pid argument")
  (is (thrown? Exception (process/link '()))
      "link must throw when called with not a pid argument")
  (is (thrown? Exception (process/link []))
      "link must throw when called with not a pid argument")
  (is (thrown? Exception (process/link {}))
      "link must throw when called with not a pid argument")
  (is (thrown? Exception (process/link #{}))
      "link must throw when called with not a pid argument"))

(deftest ^:parallel link-creates-link-with-alive-process-not-trapping-exits
  (let [done1 (async/chan)
        done2 (async/chan)
        pfn2 (proc-fn [inbox]
               (is (= :inbox-closed (<! (await-message inbox 100)))
                   (str "process must exit when linked process exits"
                        " with reason other than :normal"))
               (async/close! done2))
        pid2 (process/spawn pfn2 [] {})
        pfn1 (proc-fn [inbox]
               (process/link pid2)
               (async/close! done1)
               (is (= :inbox-closed (<! (await-message inbox 100)))
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1 [] {})]
    (await-completion done1 100)
    (process/exit pid1 :abnormal)
    (await-completion done2 300)))

(deftest ^:parallel link-creates-link-with-alive-process-trapping-exits
  (let [done1 (async/chan)
        done2 (async/chan)
        ch (async/chan)
        pfn2 (proc-fn [inbox]
               (try
                 (match (async/alts! [ch (async/timeout 100)])
                   [(pid :guard process/pid?) ch]
                   (is (= [:exit [pid :abnormal]]
                          (<! (await-message inbox 100)))
                       (str "process trapping exits must get exit message"
                            " when linked process exits with reason"
                            " other than :normal")))
                 (catch Exception e
                   (is false (str "test failed: " e))))
               (async/close! done2))
        pid2 (process/spawn pfn2 [] {:flags {:trap-exit true}})
        pfn1 (proc-fn [inbox]
               (process/link pid2)
               (async/close! done1)
               (is (= :inbox-closed (<! (await-message inbox 100)))
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1 [] {})]
    (async/>!! ch pid1)
    (async/close! ch)
    (await-completion done1 100)
    (process/exit pid1 :abnormal)
    (await-completion done2 300)))

(deftest ^:parallel link-multiple-times-work-as-single-link
  (let [done1 (async/chan)
        done2 (async/chan)
        pfn2 (proc-fn [inbox]
               (is (= :inbox-closed (<! (await-message inbox 100)))
                   (str "process must exit when linked process exits"
                        " with reason other than :normal"))
               (async/close! done2))
        pid2 (process/spawn pfn2 [] {})
        pfn1 (proc-fn [inbox]
               (process/link pid2)
               (process/link pid2)
               (process/link pid2)
               (async/close! done1)
               (is (= :inbox-closed (<! (await-message inbox 100)))
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1 [] {})]
    (await-completion done1 100)
    (process/exit pid1 :abnormal)
    (await-completion done2 300))
  (let [done1 (async/chan)
        done2 (async/chan)
        ch (async/chan)
        pfn2 (proc-fn
               [inbox]
               (try
                 (match (async/alts! [ch (async/timeout 100)])
                   [(pid :guard process/pid?) ch]
                   (is (= [:exit [pid :abnormal]]
                          (<! (await-message inbox 100)))
                       (str "process trapping exits must get exit message"
                            " when linked process exits with reason"
                            " other than :normal")))
                 (catch Exception e
                   (is false (str "test failed: " e))))
               (async/close! done2))
        pid2 (process/spawn pfn2 [] {:flags {:trap-exit true}})
        pfn1 (proc-fn [inbox]
               (process/link pid2)
               (process/link pid2)
               (process/link pid2)
               (async/close! done1)
               (is (= :inbox-closed (<! (await-message inbox 100)))
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1 [] {})]
    (async/>!! ch pid1)
    (async/close! ch)
    (await-completion done1 500)
    (process/exit pid1 :abnormal)
    (await-completion done2 500)))

(deftest ^:parallel link-creates-exactly-one-link-when-called-multiple-times
  (let [done1 (async/chan)
        done2 (async/chan)
        pfn2 (proc-fn [inbox]
               (is (= :timeout
                      (<! (await-message inbox 100)))
                   (str "process must not exit when linked process was"
                        " unlinked before exit with reason"
                        " other than :normal"))
               (async/close! done2))
        pid2 (process/spawn pfn2 [] {:flags {:trap-exit true}})
        pfn1 (proc-fn [inbox]
               (process/link pid2)
               (process/link pid2)
               (process/link pid2)
               (process/unlink pid2)
               (async/close! done1)
               (is (= :inbox-closed (<! (await-message inbox 100)))
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1 [] {})]
    (await-completion done1 100)
    (process/exit pid1 :abnormal)
    (await-completion done2 300)))

(deftest ^:parallel link-does-not-affect-processes-linked-to-normally-exited-one
  (let [done (async/chan)
        pfn2 (proc-fn [inbox]
               (is (= :timeout
                      (<! (await-message inbox 100)))
                   (str "process must not exit when linked process exits"
                        " with reason :normal"))
               (async/close! done))
        pid2 (process/spawn pfn2 [] {})
        pfn1 (proc-fn [inbox]
               (process/link pid2)
               :normal)
        pid1 (process/spawn pfn1 [] {})]
    (await-completion done 200)))

(deftest ^:parallel linking-to-terminated-process-sends-exit-message
  (let [done (async/chan)
        pfn2 (proc-fn [_inbox])
        pid2 (process/spawn pfn2 [] {})
        _ (<!! (async/timeout 50))
        pfn1 (proc-fn [inbox]
               (try
                 (process/link pid2)
                 (is (= [:exit [pid2 :noproc]]
                        (<! (await-message inbox 50)))
                     (str "linking to terminated process must either"
                          " throw or send exit message to process"
                          " trapping exits"))
                 (catch Exception _e :ok))
               (async/close! done))
        pid1 (process/spawn pfn1 [] {:flags {:trap-exit true}})]
    (await-completion done 200)))

(deftest ^:parallel linking-to-terminated-process-causes-exit
  (let [done (async/chan)
        pfn2 (proc-fn [_inbox])
        pid2 (process/spawn pfn2 [] {})
        _ (<!! (async/timeout 50))
        pfn1 (proc-fn [inbox]
               (try
                 (process/link pid2)
                 (is (= :inbox-closed (<! (await-message inbox 50)))
                     (str "linking to terminated process must either"
                          " throw or close inbox of process"
                          " not trapping exits"))
                 (catch Exception _e :ok))
               (async/close! done))
        pid1 (process/spawn pfn1 [] {})]
    (await-completion done 200)))

(deftest ^:parallel link-to-self-does-not-throw
  (let [done (async/chan)
        pfn (proc-fn [_inbox]
              (is (process/link (process/self))
                  "link to self must not throw when process is alive")
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 200)))

(deftest linked-process-receives-exited-process-pid
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [inbox]
               (is (await-completion done1 200) "test failed"))
        pid1 (process/spawn pfn1 [] {})
        pfn2 (proc-fn [inbox]
               (process/link pid1)
               (<! (async/timeout 100))
               (async/close! done1)
               (is (= [:exit [pid1 :normal]]
                      (<! (await-message inbox 100)))
                   (str "process 1 linked to process 2, must receive exit "
                        " message containing pid of a proces 2, after process 2"
                        " terminated"))
               (async/close! done))]
    (process/spawn pfn2 [] {:flags {:trap-exit true}})
    (await-completion done 300))
  (let [done (async/chan)
        pid (process/spawn (proc-fn [inbox]) [] {})
        pfn (proc-fn [inbox]
              (<! (async/timeout 100))
              (process/link pid)
              (is (= [:exit [pid :noproc]]
                     (<! (await-message inbox 100)))
                  (str "process 1 tried to link terminated process 2, must"
                       " receive exit message containing pid of a proces 2"
                       " when trapping exits"))
              (async/close! done))]
    (process/spawn pfn [] {:flags {:trap-exit true}})
    (await-completion done 300)))

;; ====================================================================
;; (unlink [pid])

(deftest ^:parallel unlink-removes-link-to-alive-process
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [_inbox]
               (is (await-completion done1 200) "test failed")
               (throw (Exception.
                        (str "TEST: terminating abnormally to test"
                             " unlink removes link to alive process"))))
        pid (process/spawn pfn1 [] {})
        pfn2 (proc-fn [inbox]
               (process/link pid)
               (<! (async/timeout 50))
               (process/unlink pid)
               (<! (async/timeout 50))
               (async/close! done1)
               (is (= :timeout (<! (await-message inbox 100)))
                   (str "abnormally failed unlinked process must"
                        " not affect previously linked process"))
               (async/close! done))]
    (process/spawn pfn2 [] {})
    (await-completion done 300)))

(deftest ^:parallel unlink-returns-true-if-link-exists
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [_inbox]
               (is (await-completion done1 100) "test failed"))
        pid (process/spawn pfn1 [] {})
        pfn2 (proc-fn [_inbox]
               (process/link pid)
               (<! (async/timeout 50))
               (is (= true (process/unlink pid)) "unlink must return true")
               (async/close! done1)
               (async/close! done))]
    (process/spawn pfn2 [] {})
    (await-completion done 200)))

(deftest ^:parallel unlink-returns-true-there-is-no-link
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [_inbox]
               (is (await-completion done1 100) "test failed"))
        pid (process/spawn pfn1 [] {})
        pfn2 (proc-fn [_inbox]
               (is (= true (process/unlink pid)) "unlink must return true")
               (async/close! done1)
               (async/close! done))]
    (process/spawn pfn2 [] {})
    (await-completion done 200)))

(deftest ^:parallel unlink-self-returns-true
  (let [done (async/chan)
        pfn (proc-fn [_inbox]
              (is (= true (process/unlink (process/self)))
                  "unlink must return true")
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 200)))

(deftest ^:parallel unlink-terminated-process-returns-true
  (let [done (async/chan)
        pid (process/spawn (proc-fn [_inbox]) [] {})
        pfn2 (proc-fn [inbox]
               (<! (async/timeout 100))
               (is (= true (process/unlink pid)) "unlink must return true")
               (async/close! done))]
    (process/spawn pfn2 [] {})
    (await-completion done 200))
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [_inbox]
               (is (await-completion done1 100) "test failed"))
        pid (process/spawn pfn1 [] {})
        pfn2 (proc-fn [_inbox]
               (process/link pid)
               (async/close! done1)
               (<! (async/timeout 100))
               (is (= true (process/unlink pid)) "unlink must return true")
               (async/close! done))]
    (process/spawn pfn2 [] {:flags {:trap-exit true}})
    (await-completion done 200)))

(deftest ^:parallel unlink-throws-on-not-a-pid
  (let [done (async/chan)
        pfn2 (proc-fn [inbox]
               (is (thrown? Exception (process/unlink nil))
                   "unlink must throw on not a pid argument")
               (is (thrown? Exception (process/unlink 1))
                   "unlink must throw on not a pid argument")
               (is (thrown? Exception (process/unlink "pid1"))
                   "unlink must throw on not a pid argument")
               (is (thrown? Exception (process/unlink {}))
                   "unlink must throw on not a pid argument")
               (is (thrown? Exception (process/unlink #{}))
                   "unlink must throw on not a pid argument")
               (is (thrown? Exception (process/unlink '()))
                   "unlink must throw on not a pid argument")
               (is (thrown? Exception (process/unlink []))
                   "unlink must throw on not a pid argument")
               (async/close! done))]
    (process/spawn pfn2 [] {})
    (await-completion done 200)))

(deftest ^:parallel unlink-throws-when-calld-not-in-process-context
  (is (thrown? Exception
               (process/unlink (process/spawn (proc-fn [_inbox]) [] {})))
      "unlink must throw when called not in process context"))

(deftest ^:parallel unlink-prevents-exit-message-after-linked-process-failed
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [_inbox]
               (is (await-completion done1 100) "test failed")
               (throw (Exception.
                        (str "TEST: terminating abnormally to test unlink"
                             " prevents exit message when previously linked"
                             " process have exited abnormally"))))
        pid (process/spawn pfn1 [] {})
        pfn2 (proc-fn [inbox]
               (process/flag :trap-exit true)
               (process/link pid)
               (<! (async/timeout 50))
               (process/unlink pid)
               (async/close! done1)
               (is (= :timeout
                      (<! (await-message inbox 100)))
                   (str "exit message from abnormally terminated linked"
                        " process, terminated before unlink have been"
                        " called, must not appear in process' inbox after"
                        " unlink have been called"))
               (async/close! done))]
    (process/spawn pfn2 [] {})
    (await-completion done 300)))

(deftest ^:parallel
  unlink-does-not-prevent-exit-message-after-it-has-been-placed-to-inbox
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [_inbox]
               (is (await-completion done1 200) "test failed")
               (process/exit (process/self) :abnormal))
        pid (process/spawn pfn1 [] {})
        pfn2 (proc-fn [inbox]
               (process/flag :trap-exit true)
               (process/link pid)
               (<! (async/timeout 50))
               (async/close! done1)
               (<! (async/timeout 100))
               (process/unlink pid)
               (is (= [:exit [pid :abnormal]]
                      (<! (await-message inbox 100)))
                   (str "exit message from abnormally terminated linked"
                        " process, terminated before unlink have been"
                        " called, must not appear in process' inbox after"
                        " unlink have been called"))
               (async/close! done))]
    (process/spawn pfn2 [] {})
    (await-completion done 300)))

(deftest ^:parallel unlink-does-not-affect-process-when-called-multiple-times
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [inbox]
               (is (= :timeout (<! (await-message inbox 150)))
                   "test failed")
               (is (await-completion done1 100) "test failed")
               (throw (Exception.
                        (str "TEST: terminating abnormally to test unlink"
                             " doesn't affect process when called multiple"
                             " times"))))
        pid (process/spawn pfn1 [] {})
        pfn2 (proc-fn [inbox]
               (process/flag :trap-exit true)
               (process/link pid)
               (<! (async/timeout 50))
               (process/unlink pid)
               (process/unlink pid)
               (process/unlink pid)
               (process/unlink pid)
               (process/unlink pid)
               (async/close! done1)
               (is (= :timeout
                      (<! (await-message inbox 100)))
                   (str "exit message from abnormally terminated linked"
                        " process, terminated before unlink have been"
                        " called, must not appear in process' inbox after"
                        " unlink have been called"))
               (async/close! done))]
    (process/spawn pfn2 [] {})
    (await-completion done 500)))

;; ====================================================================
;; (spawn [proc-fun args options])

(deftest ^:parallel spawn-calls-pfn
  (let [done (async/chan)]
    (process/spawn (proc-fn [_inbox] (async/close! done)) [] {})
    (is (await-completion done 100) "spawn must call process fn")))

(deftest ^:parallel spawn-calls-pfn-with-arguments
  (let [done (async/chan)
        pfn (proc-fn [_inbox a b]
                  (is (and (= :a a) (= 1 b))
                      "spawn must pass process fn params")
                  (async/close! done))]
    (process/spawn pfn [:a 1] {})
    (await-completion done 100)))

(deftest ^:parallel spawn-returns-process-pid
  (let [done (async/chan 1)
        pfn (proc-fn [_inbox] (async/put! done (process/self)))
        pid1 (process/spawn pfn [] {})
        timeout (async/timeout 100)
        pid2 (match (async/alts!! [done timeout])
               [nil timeout] :timeout
               [pid done] pid)]
    (is (= pid1 pid2) (str "spawn must return the same pid as returned"
                           " by self called from started process"))
    (async/close! done)))

(deftest ^:parallel spawn-throws-on-illegal-arguments
  (is (thrown? Exception (process/spawn nil [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn 1 [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn "fn" [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn {} [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn [] [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn #{} [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn '() [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn (proc-fn []) [] {}))
      "spawn must throw if proc-fun's arity is less than 1")
  (is (thrown? Exception (process/spawn (fn [_inbox a b]) [1] {}))
      "spawn must throw if proc-fun's arity doesn't match args")
  (is (thrown? Exception (process/spawn (fn [_inbox]) [1 :a] {}))
      "spawn must throw if proc-fun's arity doesn't match args")
  (is (thrown? Exception (process/spawn (fn [_inbox]) [] {}))
      "spawn must throw if proc-fun doesn't return ReadPort")
  (is (thrown? Exception (process/spawn (proc-fn [_]) 1 {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn (proc-fn [_]) #{} {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn (proc-fn [_]) {} {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn (proc-fn [_]) 1 {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn (proc-fn [_]) "args" {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn (proc-fn [_]) (fn []) {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] nil))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] 1))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] "opts"))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] [1 2 3]))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] '(1)))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] #{}))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] {:flags 1}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] {:flags true}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] {:flags "str"}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] {:flags []}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] {:flags #{}}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] {:flags '()}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Exception
               (process/spawn (proc-fn [_]) [] {:link-to 1}))
      "spawn must throw if :link-to option is not a pid or collection of pids")
  (is (thrown? Exception
               (process/spawn (proc-fn [_]) [] {:link-to [1]}))
      "spawn must throw if :link-to option is not a pid or collection of pids")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] {:inbox-size -1}))
      "spawn must throw if :inbox-size option is not a non-negative integer")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] {:inbox-size 1.1}))
      "spawn must throw if :inbox-size option is not a non-negative integer")
  (is (thrown? Exception (process/spawn (proc-fn [_]) [] {:inbox-size []}))
      "spawn must throw if :inbox-size option is not a non-negative integer"))

(deftest ^:parallel spawn-throws-if-pfn-throws
  (is (thrown? InterruptedException
               (process/spawn (fn [_] (throw (InterruptedException.))) [] {}))
      "spawn must throw when proc-fun throws")
  (is (thrown? RuntimeException
               (process/spawn (fn [_] (throw (RuntimeException.))) [] {}))
      "spawn must throw when proc-fun throws")
  (is (thrown? Error (process/spawn (fn [_] (throw (Error.))) [] {}))
      "spawn must throw when proc-fun throws"))

(deftest ^:parallel spawn-passes-opened-read-port-to-pfn-as-inbox
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= :timeout (<! (await-message inbox 100)))
                  "spawn must pass opened ReadPort as inbox to process fn")
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 200)))

(deftest ^:parallel spawned-process-is-reachable
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= [:message :msg] (<! (await-message inbox 100)))
                  (str "messages sent to spawned process must appear"
                       " in its inbox"))
              (async/close! done))
        pid (process/spawn pfn [] {})]
    (! pid :msg)
    (await-completion done 200)))

; options

(deftest ^:parallel spawned-process-traps-exits-according-options
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= :inbox-closed (<! (await-message inbox 100)))
                  "process spawned with no options must not trap exits")
              (async/close! done))
        pid (process/spawn pfn [] {})]
    (process/exit pid :abnormal)
    (await-completion done 200))
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= [:exit [(process/self) :abnormal]]
                     (<! (await-message inbox 100)))
                  (str "process spawned option :trap-exit set to true"
                       " must trap exits"))
              (async/close! done))
        pid (process/spawn pfn [] {:flags {:trap-exit true}})]
    (process/exit pid :abnormal)
    (await-completion done 200)))

(deftest ^:parallel spawned-process-linked-according-options
  (let [done (async/chan)
        done1 (async/chan)
        pfn (proc-fn [_inbox]
              (is (await-completion done1 100) "test failed")
              (throw (Exception. "TEST")))
        pid (process/spawn pfn [] {})
        pfn1 (proc-fn [inbox]
               (async/close! done1)
               (is (= :inbox-closed (<! (await-message inbox 200)))
                   (str "process spawned with option :link-to must exit"
                        " when linked process exited"))
               (async/close! done))]
    (process/spawn pfn1 [] {:link-to pid})
    (await-completion done 300))
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= :inbox-closed (<! (await-message inbox 250)))
                  "test failed"))
        pid (process/spawn pfn [] {})
        pfn1 (proc-fn [inbox]
               (is (= [:exit [pid :abnormal]]
                      (<! (await-message inbox 200)))
                   (str "process trapping exits and spawned with option"
                        " :link-to must receive exit message when linked"
                        " process exited"))
               (async/close! done))]
    (process/spawn pfn1 [] {:link-to pid :flags {:trap-exit true}})
    (<!! (async/timeout 50))
    (process/exit pid :abnormal)
    (await-completion done 300)))

(deftest ^:parallel spawned-process-registered-according-options
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn (proc-fn [_] (is (await-completion done 100) "test failed"))]
    (is (not ((process/registered) reg-name)) "test failed")
    (process/spawn pfn [] {:register reg-name})
    (is ((process/registered) reg-name)
        "spawn must register proces when called with :register option")
    (async/close! done)))

(deftest ^:parallel spawn-throws-when-reg-name-already-registered
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn (proc-fn [_] (is (await-completion done 100) "test failed"))]
    (process/spawn pfn [] {:register reg-name})
    (is (thrown? Exception
                 (process/spawn (proc-fn [_]) [] {:register reg-name}))
        "spawn must throw when name to register is already registered")
    (async/close! done)))

(deftest ^:parallel spawned-process-does-not-trap-exits-by-default
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= :inbox-closed (<! (await-message inbox 100)))
                  (str "process' inbox must be closed after exit with"
                       " reason other than :normal was called if process"
                       " doesn't trap exits"))
              (async/close! done))
        pid (process/spawn pfn [] {})]
    (process/exit pid :abnormal)
    (await-completion done 200)))

; TODO:
;(deftest spawned-process-available-from-within-process-by-reg-name)
;(deftest there-are-no-residue-of-process-after-proc-fun-throwed)

;; ====================================================================
;; (spawn-link [proc-fun args options])

(deftest ^:parallel spawn-link-links-to-spawned-process
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (process/exit (process/self) :abnormal)
              (is (= :inbox-closed (<! (await-message inbox 100)))))
        pfn1 (proc-fn [inbox]
               (process/spawn-link pfn [] {})
               (is (= :inbox-closed
                      (<! (await-message inbox 200)))
                   (str "process trapping exits and spawned with option"
                        " :link-to must receive exit message when linked"
                        " process exited"))
               (async/close! done))]
    (process/spawn pfn1 [] {})
    (await-completion done 300))
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (process/exit (process/self) :abnormal)
              (is (= :inbox-closed (<! (await-message inbox 100)))
                  "test failed"))
        pfn1 (proc-fn [inbox]
               (let [pid (process/spawn-link pfn [] {})]
                 (is (=  [:exit [pid :abnormal]]
                        (<! (await-message inbox 200)))
                     (str "process trapping exits and spawned with option"
                          " :link-to must receive exit message when linked"
                          " process exited")))
               (async/close! done))]
    (process/spawn pfn1 [] {:flags {:trap-exit true}})
    (await-completion done 300)))

; TODO check if spawn-link works like spawn

;; ====================================================================
;; (monitor [pid-or-name])

(deftest ^:parallel down-message-is-sent-when-monitored-process-exits
  (let [done (async/chan)
        pfn1 (proc-fn [inbox]
               (is (= :inbox-closed (<! (await-message inbox 150)))
                   "test failed"))
        pid1 (process/spawn pfn1 [] {})
        pfn2 (proc-fn [inbox]
               (let [mref (process/monitor pid1)]
                 (<! (async/timeout 50))
                 (process/exit pid1 :abnormal)
                 (is (= [:down [mref pid1 :abnormal]]
                        (<! (await-message inbox 100)))
                     (str "process must receive :DOWN message containing proper"
                          " monitor ref, pid and reason when monitored by pid"
                          " process exits abnormally"))
                 (async/close! done)))]
    (process/spawn pfn2 [] {})
    (await-completion done 300))
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [inbox] (await-completion done1 150))
        pid1 (process/spawn pfn1 [] {})
        pfn2 (proc-fn [inbox]
               (let [mref (process/monitor pid1)]
                 (<! (async/timeout 50))
                 (async/close! done1)
                 (is (= [:down [mref pid1 :normal]]
                        (<! (await-message inbox 100)))
                     (str "process must receive :DOWN message containing proper"
                          " monitor ref, pid and reason when monitored by pid"
                          " process exits normally"))
                 (async/close! done)))]
    (process/spawn pfn2 [] {})
    (await-completion done 300))
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn1 (proc-fn [inbox]
               (is (= :inbox-closed (<! (await-message inbox 150)))
                   "test failed"))
        pid1 (process/spawn pfn1 [] {:register reg-name})
        pfn2 (proc-fn [inbox]
               (let [mref (process/monitor reg-name)]
                 (<! (async/timeout 50))
                 (process/exit pid1 :kill)
                 (is (= [:down [mref reg-name :killed]]
                        (<! (await-message inbox 100)))
                     (str "process must receive :DOWN message containing proper"
                          " monitor ref, pid and reason when monitored by pid"
                          " process is killed"))
                 (async/close! done)))]
    (process/spawn pfn2 [] {})
    (await-completion done 300))
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn1 (proc-fn [inbox]
               (is (= :inbox-closed (<! (await-message inbox 150)))
                   "test failed"))
        pid1 (process/spawn pfn1 [] {:register reg-name})
        pfn2 (proc-fn [inbox]
               (let [mref (process/monitor reg-name)]
                 (<! (async/timeout 50))
                 (process/exit pid1 :abnormal)
                 (is (= [:down [mref reg-name :abnormal]]
                        (<! (await-message inbox 100)))
                     (str "process must receive :DOWN message containing proper"
                          " monitor ref, reg-name and reason when monitored by"
                          " reg-name process exits abnormally"))
                 (async/close! done)))]
    (process/spawn pfn2 [] {})
    (await-completion done 300)))

(deftest ^:parallel monitor-returns-ref
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (process/monitor-ref? (process/monitor (process/self)))
                  (str "monitor must return monitor-ref when called with self"
                       " as argument"))
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 100))
  (let [done (async/chan)
        pfn1 (proc-fn [_inbox] (await-completion done 100))
        pid1 (process/spawn pfn1 [] {})
        pfn (proc-fn [inbox]
              (is (process/monitor-ref? (process/monitor pid1))
                  (str "monitor must return monitor-ref when called with pid"
                       " of alive process as argument"))
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 100))
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn1 (proc-fn [_inbox] (await-completion done 100))
        pid1 (process/spawn pfn1 [] {:register reg-name})
        pfn (proc-fn [inbox]
              (is (process/monitor-ref? (process/monitor reg-name))
                  (str "monitor must return monitor-ref when called with"
                       " reg-name of alive process as argument"))
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 200))
  (let [done (async/chan)
        pid (process/spawn (proc-fn [_inbox]) [] {})
        pfn (proc-fn [inbox]
              (<! (async/timeout 50))
              (is (process/monitor-ref? (process/monitor pid))
                  (str "monitor must return monitor-ref when called with pid"
                       " of terminated process as argument"))
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 200))
  (let [reg-name (uuid-keyword)
        done (async/chan)
        _pid (process/spawn (proc-fn [_inbox]) [] {:register reg-name})
        pfn (proc-fn [inbox]
              (<! (async/timeout 50))
              (is (process/monitor-ref? (process/monitor reg-name))
                  (str "monitor must return monitor-ref when called with"
                       " reg-name of terminated process as argument"))
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 500))
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn (proc-fn [inbox]
              (is (process/monitor-ref? (process/monitor reg-name))
                  (str "monitor must return monitor-ref when called with"
                       " never registered name as argument"))
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 200)))

(deftest ^:parallel monitor-throws-when-called-not-in-process-context
  (is (thrown? Exception (process/monitor (uuid-keyword)))
      (str "monitor must throw when called not in process context with"
           " never registered name"))
  (let [pid (process/spawn (proc-fn [_inbox]) [] {})]
    (<!! (async/timeout 50))
    (is (thrown? Exception (process/monitor pid))
        (str "monitor must throw when called not in process context with"
             " terminated process pid")))
  (let [reg-name (uuid-keyword)
        pid (process/spawn (proc-fn [_inbox]) [] {:register reg-name})]
    (<!! (async/timeout 50))
    (is (thrown? Exception (process/monitor reg-name))
        (str "monitor must throw when called not in process context with"
             " terminated process reg-name")))
  (let [done (async/chan)
        pfn (proc-fn [inbox] (await-completion done 100))
        pid (process/spawn pfn [] {})]
    (is (thrown? Exception (process/monitor pid))
        (str "monitor must throw when called not in process context with"
             " alive process pid"))
    (async/close! done))
  (let [done (async/chan)
        reg-name (uuid-keyword)
        pfn (proc-fn [inbox] (await-completion done 100))
        pid (process/spawn pfn [] {:register reg-name})]
    (is (thrown? Exception (process/monitor reg-name))
        (str "monitor must throw when called not in process context with"
             " alive process reg-name"))
    (async/close! done)))

(deftest ^:parallel
  monitoring-terminated-process-sends-down-message-with-noproc-reason
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (let [reg-name (uuid-keyword)
                    mref (process/monitor reg-name)]
                (is (= [:down [mref reg-name :noproc]]
                       (<! (await-message inbox 50)))
                    (str "process must receive :DOWN message with :noproc"
                         " reason when monitor called with never registered"
                         " name")))
              (let [pid (process/spawn (proc-fn [_inbox]) [] {})
                    _ (<! (async/timeout 50))
                    mref (process/monitor pid)]
                (is (= [:down [mref pid :noproc]]
                       (<! (await-message inbox 50)))
                    (str "process must receive :DOWN message with :noproc"
                         " reason when monitor called with terminated process"
                         " pid")))
              (let [reg-name (uuid-keyword)
                    _pid (process/spawn (proc-fn [_]) [] {:register reg-name})
                    _ (<! (async/timeout 50))
                    mref (process/monitor reg-name)]
                (is (= [:down [mref reg-name :noproc]]
                       (<! (await-message inbox 50)))
                    (str "process must receive :DOWN message with :noproc"
                         " reason when monitor called with terminated process"
                         " reg-name")))
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 500)))

(deftest ^:parallel
  monitored-process-does-not-receive-down-message-when-monitoring-proc-dies
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (let [self (process/self)]
                (process/spawn (proc-fn [_inbox] (process/monitor self)) [] {})
                (is (= :timeout (<! (await-message inbox 100)))
                    (str "monitored process must not receive any message"
                         " when monitoring process dies")))
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 200)))

(deftest ^:parallel monitor-called-multiple-times-creates-as-many-monitors
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [_inbox] (await-completion done1 300))
        pid (process/spawn pfn1 [] {})
        pfn (proc-fn [inbox]
              (let [mrefs (doall (take 3 (repeatedly #(process/monitor pid))))
                    _ (<! (async/timeout 50))
                    _ (async/close! done1)
                    msgs (doall (take 3 (repeatedly #(<!! (await-message inbox 50)))))]
                (is (= (map (fn [mref] [:down [mref pid :normal]])
                            mrefs)
                       msgs)
                    (str "monitoring process must receive one :DOWN message"
                         " per one monitor call")))
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 500)))

(deftest ^:parallel
  down-message-is-not-sent-when-process-trapping-exits-receives-exit-message
  (let [done (async/chan)
        pfn1 (proc-fn [inbox]
               (is (= [:exit [(process/self) :abnormal]]
                      (<! (await-message inbox 100)))
                   "test failed")
               (await-completion done 200))
        pid1 (process/spawn pfn1 [] {:flags {:trap-exit true}})
        pfn (proc-fn [inbox]
              (process/monitor pid1)
              (<! (async/timeout 50))
              (process/exit pid1 :abnormal)
              (is (= :timeout (<! (await-message inbox 100)))
                  (str "process must not receive :DOWN message when monitored"
                       " process trapping exits receives :EXIT message and"
                       " stays alive"))
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 300)))

(deftest ^:parallel monitor-refs-are-not-equal
  (let [done (async/chan)
        pfn1 (proc-fn [_inbox] (await-completion done 150))
        pid (process/spawn pfn1 [] {})
        pfn (proc-fn [inbox]
              (is (= 3 (count (set (take 3 (repeatedly #(process/monitor pid))))))
                  "monitor must return unique monitor refs")
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 150)))

(deftest ^:parallel monitor-self-does-nothing
  (let [done (async/chan)
        done1 (async/chan)
        pfn (proc-fn [inbox]
              (process/monitor (process/self))
              (<! (async/timeout 50))
              (async/close! done1)
              (is (= :inbox-closed (<! (await-message inbox 100)))
                  (str "process called monitor with self as argument must not"
                       " receive :DOWN message when exit is called for the"
                       " process"))
              (async/close! done))
        pid (process/spawn pfn [] {})]
    (await-completion done1 100)
    (process/exit pid :abnormal)
    (await-completion done 200))
  (let [reg-name (uuid-keyword)
        done (async/chan)
        done1 (async/chan)
        pfn (proc-fn [inbox]
              (process/monitor reg-name)
              (<! (async/timeout 50))
              (async/close! done1)
              (is (= :inbox-closed (<! (await-message inbox 100)))
                  (str "process called monitor with self reg-name as argument"
                       " must not receive :DOWN message when exit is called"
                       " for the process"))
              (async/close! done))
        pid (process/spawn pfn [] {:register reg-name})]
    (await-completion done1 100)
    (process/exit pid :abnormal)
    (await-completion done 200)))

;; ====================================================================
;; (demonitor [mref])

(deftest ^:parallel demonitor-stops-monitoring
  (let [done (async/chan)
        done1 (async/chan)
        pid (process/spawn (proc-fn [inbox] (await-completion done1 200)) [] {})
        pfn (proc-fn [inbox]
               (let [mref (process/monitor pid)]
                 (<! (async/timeout 50))
                 (process/demonitor mref)
                 (<! (async/timeout 50))
                 (async/close! done1)
                 (is (= :timeout (<! (await-message inbox 100)))
                     (str "demonitor called after monitored process exited"
                          " must not affect monitoring process"))
                 (async/close! done)))]
    (process/spawn pfn [] {})
    (await-completion done 300)))

(deftest ^:parallel
  demonitor-called-after-monitored-process-exited-does-nothing
  (let [done (async/chan)
        done1 (async/chan)
        pid (process/spawn (proc-fn [inbox] (await-completion done1 100)) [] {})
        pfn (proc-fn [inbox]
               (let [mref (process/monitor pid)]
                 (<! (async/timeout 50))
                 (async/close! done1)
                 (<! (async/timeout 50))
                 (process/demonitor mref)
                 (is (= [:down [mref pid :normal]]
                        (<! (await-message inbox 100)))
                     (str "demonitor called after monitored process exited"
                          " must not affect monitoring process"))
                 (is (= :timeout (<! (await-message inbox 100)))
                     (str "demonitor called after monitored process exited"
                          " must not affect monitoring process"))
                 (async/close! done)))]
    (process/spawn pfn [] {})
    (await-completion done 300)))

(deftest ^:parallel demonitor-called-multiple-times-does-nothing
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (is (= :timeout (<! (await-message inbox 100)))
                  (str "demonitor called multiple times must not affect"
                       " monitored process")))
        pid (process/spawn pfn [] {})
        pfn1 (proc-fn [inbox]
               (let [mref (process/monitor pid)]
                 (is (process/demonitor mref) "test failed")
                 (is (process/demonitor mref)
                     (str "demonitor called multiple times must not affect"
                          " calling process"))
                 (is (process/demonitor mref)
                     (str "demonitor called multiple times must not affect"
                          " calling process"))
                 (is (= :timeout (<! (await-message inbox 100)))
                     (str "demonitor called multiple times must not affect"
                          " monitoring process"))
                 (async/close! done)))]
    (process/spawn pfn1 [] {})
    (await-completion done 200)))

(deftest ^:parallel demonitor-returns-true
  (let [done (async/chan)
        pid (process/spawn (proc-fn [_] (await-completion done 100)) [] {})
        pfn (proc-fn [_inbox]
              (let [mref (process/monitor pid)]
                (is (= true (process/demonitor mref))
                    (str "demonitor must return true when called while"
                         " monitored process is alive"))
                (is (= true (process/demonitor mref))
                    "demonitor must return true when called multiple times")
                (is (= true (process/demonitor mref))
                    "demonitor must return true when called multiple times")
                (async/close! done)))]
    (process/spawn pfn [] {})
    (await-completion done 100))
  (let [done (async/chan)
        pid (process/spawn (proc-fn [_]) [] {})
        pfn (proc-fn [_inbox]
              (let [mref (process/monitor pid)]
                (<! (async/timeout 50))
                (is (= true (process/demonitor mref))
                    (str "demonitor must return true when called when"
                         " monitored process have terminated"))
                (async/close! done)))]
    (process/spawn pfn [] {})
    (await-completion done 200))
  (let [done (async/chan)
        pfn1 (proc-fn [_inbox]
              (is (await-completion done 200) "test failed"))
        pid1 (process/spawn pfn1 [] {})
        pfn2 (proc-fn [inbox]
               (let [[_ mref] (<! (await-message inbox 100))]
                 (is (process/monitor-ref? mref) "test failed")
                 (is (= true (process/demonitor mref))
                     (str "demonitor must return true when called byj"
                          " process other than process called monitor"))
                 (async/close! done)))
        pid2 (process/spawn pfn2 [] {})
        pfn3 (proc-fn [_inbox]
              (let [mref (process/monitor pid1)]
                (<! (async/timeout 50))
                (! pid2 mref)
                (await-completion done 100)))]
    (process/spawn pfn3 [] {})
    (await-completion done 200)))

(deftest ^:parallel demonitor-throws-on-not-a-monitor-ref
  (let [pfn (proc-fn [_]
              (is (thrown? Exception (process/demonitor nil))
                  "demonitor must throw on not a monitor-ref")
              (is (thrown? Exception (process/demonitor 1))
                  "demonitor must throw on not a monitor-ref")
              (is (thrown? Exception (process/demonitor "mref"))
                  "demonitor must throw on not a monitor-ref")
              (is (thrown? Exception (process/demonitor true))
                  "demonitor must throw on not a monitor-ref")
              (is (thrown? Exception (process/demonitor false))
                  "demonitor must throw on not a monitor-ref")
              (is (thrown? Exception (process/demonitor []))
                  "demonitor must throw on not a monitor-ref")
              (is (thrown? Exception (process/demonitor '()))
                  "demonitor must throw on not a monitor-ref")
              (is (thrown? Exception (process/demonitor {}))
                  "demonitor must throw on not a monitor-ref")
              (is (thrown? Exception (process/demonitor #{}))
                  "demonitor must throw on not a monitor-ref"))]
    (process/spawn pfn [] {})))

(deftest ^:parallel demonitor-throws-when-called-not-in-process-context
  (let [done (async/chan)
        mref-chan (async/chan 1)
        pid (process/spawn (proc-fn [_] (await-completion done 100)) [] {})
        pfn (proc-fn [_inbox]
              (>! mref-chan (process/monitor pid))
              (await-completion done 100))]
    (process/spawn pfn [] {})
    (match (async/alts!! [mref-chan (async/timeout 100)])
      [(mref :guard #(process/monitor-ref? %)) mref-chan]
      (is (thrown? Exception (process/demonitor mref))
          (str "demonitor must throw when called not in process context"
               " when both processes are alive")))
    (async/close! mref-chan)
    (async/close! done))
  (let [done (async/chan)
        mref-chan (async/chan 1)
        pid (process/spawn (proc-fn [_inbox]) [] {})
        pfn (proc-fn [_inbox]
              (>! mref-chan (process/monitor pid))
              (await-completion done 100))]
    (process/spawn pfn [] {})
    (match (async/alts!! [mref-chan (async/timeout 100)])
      [(mref :guard #(process/monitor-ref? %)) mref-chan]
      (is (thrown? Exception (process/demonitor mref))
          (str "demonitor must throw when called not in process context"
               " when monitoring processes is alive")))
    (async/close! mref-chan)
    (async/close! done))
  (let [done (async/chan 1)
        mref-chan (async/chan)
        pid (process/spawn (proc-fn [_] (await-completion done 100)) [] {})]
    (process/spawn (proc-fn [_] (>! mref-chan (process/monitor pid))) [] {})
    (<!! (async/timeout 50))
    (match (async/alts!! [mref-chan (async/timeout 1000)])
      [(mref :guard #(process/monitor-ref? %)) mref-chan]
      (is (thrown? Exception (process/demonitor mref))
          (str "demonitor must throw when called not in process context"
               " when monitored processes is alive")))
    (async/close! mref-chan)
    (async/close! done)))

(deftest ^:parallel
  demonitor-does-nothing-when-monitoring-started-by-other-process
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [inbox]
               (let [[_ mref] (<! (await-message inbox 100))]
                 (is (process/monitor-ref? mref) "testf failed")
                 (is (process/demonitor mref)
                     (str "demonitor return true when called with monitor-ref"
                          " of monitoring started by other process"))
                 (<! (async/timeout 50))
                 (async/close! done1)))
        pid1 (process/spawn pfn1 [] {})
        pfn2 (proc-fn [inbox] (is (await-completion done1 200) "test failed"))
        pid2 (process/spawn pfn2 [] {})
        pfn3 (proc-fn [inbox]
              (let [mref (process/monitor pid2)]
                (! pid1 mref)
                (is (= [:down [mref pid2 :normal]]
                       (<! (await-message inbox 200)))
                    (str "demonitor called by process other than process"
                         " started monitoring")))
              (async/close! done))]
    (process/spawn pfn3 [] {})
    (await-completion done 200)))

(deftest ^:parallel demonitor-self-does-nothing
  (let [done (async/chan)
        pfn (proc-fn [inbox]
              (let [mref (process/monitor (process/self))]
                (is (process/demonitor mref)
                    "demonitor self must return true and do nothing"))
              (is (= :timeout (<! (await-message inbox 100)))
                  "demonitor self must do nothing")
              (async/close! done))]
    (process/spawn pfn [] {})
    (await-completion done 200))
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn (proc-fn [inbox]
              (let [mref (process/monitor reg-name)]
                (is (process/demonitor mref)
                    "demonitor self must return true and do nothing"))
              (is (= :timeout (<! (await-message inbox 100)))
                  "demonitor self must do nothing")
              (async/close! done))]
    (process/spawn pfn [] {:register reg-name})
    (await-completion done 200)))
