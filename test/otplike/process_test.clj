(ns otplike.process-test
  (:require [clojure.test :refer [is deftest]]
            [otplike.process :as process :refer [!]]
            [otplike.trace :as trace]
            [clojure.core.async :as async :refer [<!! <! >! put! go go-loop]]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.core.match :refer [match]]))

(trace/set-trace (fn [_pid _event]))

(defn- uuid-keyword []
  (keyword (str (java.util.UUID/randomUUID))))

(defn- await-message [inbox timeout-ms]
  (let [timeout (async/timeout timeout-ms)]
    (match (async/alts!! [inbox timeout])
      [[:EXIT _ reason] inbox] [:exit-message [:reason reason]]
      [nil inbox] :inbox-closed
      [msg inbox] [:message msg]
      [nil timeout] :timeout)))

;; ====================================================================
;; (self [])
;;   Returns the process identifier of the calling process. Throws when
;;   called not in process context.

(deftest ^:parallel test-self
  (deftest ^:parallel self-returns-process-pid-in-process-context
    (let [timeout (async/timeout 500)]
      (process/spawn
        (fn [inbox]
          (is (process/pid? (process/self))
              "self must return pid when called in process context")
          (is (= (process/self) (process/self))
              "self must return the same pid when called by the same process")
          (! (process/self) :msg)
          (is (= [:message :msg] (await-message inbox 100))
              "message sent to self must appear in inbox")
          (async/close! timeout))
        []
        {})
      (<!! timeout)))

  (deftest ^:parallel self-fails-in-non-process-context
    (is (thrown? Exception (process/self))
        "self must throw when called not in process context")))

;; ====================================================================
;; (pid? [term])
;;   Returns true if term is a process identifier, false otherwise.

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
  (is (process/pid? (process/spawn (fn [_inbox]) [] {}))
      "pid? must return true on pid argument"))

;; ====================================================================
;; (pid->str [pid])
;;   Returns a string corresponding to the text representation of pid.
;;   Throws if pid is not a process identifier.
;;   Warning: this function is intended for debugging and is not to be
;;   used in application programs.

(deftest ^:parallel pid->str-returns-string
  (is (string? (process/pid->str (process/spawn (fn [_inbox]) [] {})))
      "pid->str must return string on pid argument"))

(deftest ^:parallel pid->str-fails-on-non-pid
  (is (thrown? Throwable (process/pid->str nil))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Throwable (process/pid->str 1))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Throwable (process/pid->str "not-a-pid"))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Throwable (process/pid->str []))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Throwable (process/pid->str '()))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Throwable (process/pid->str #{}))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Throwable (process/pid->str {}))
      "pid->str must throw on nonpid arguement"))

;; ====================================================================
;; (whereis [reg-name])
;;   Returns the process identifier with the registered name reg-name,
;;   or nil if the name is not registered. Throws on nil argument.

(deftest ^:parallel whereis-returns-process-pid-on-registered-name
  (let [timeout (async/timeout 300)
        reg-name (uuid-keyword)
        pid (process/spawn
              (fn [_]
                (is (= (process/self) (process/whereis reg-name))
                    "whereis must return process pid on registered name")
                (<!! timeout))
              []
              {:register reg-name})]
    (is (= pid (process/whereis reg-name))
        "whereis must return process pid on registered name")
    (async/close! timeout)))

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
  (is (thrown? Throwable (process/whereis nil))
      "whereis must throw on nil argument"))

;; ====================================================================
;; (! [dest message])
;;   Sends a message to dest. dest can be a process identifier, or a
;;   registered name. Returns true if message was sent (process was
;;   alive), false otherwise. Throws if any of the arguments is nil.

(deftest ^:parallel !-returns-true-sending-to-alive-process-by-pid
  (let [timeout (async/timeout 100)
        pid (process/spawn (fn [_] (<!! timeout)) [] {})]
    (is (= true (! pid :msg)) "! must return true sending to alive process")
    (async/close! timeout)))

(deftest ^:parallel !-returns-true-sending-to-alive-process-by-reg-name
  (let [timeout (async/timeout 100)
        reg-name (uuid-keyword)]
    (process/spawn (fn [_] (<!! timeout)) [] {:register reg-name})
    (is (= true (! reg-name :msg)) "! must return true sending to alive process")
    (async/close! timeout)))

(deftest ^:parallel !-returns-false-sending-to-terminated-process-by-reg-name
  (let [reg-name (uuid-keyword)]
    (process/spawn (fn [_]) [] {:register reg-name})
    (<!! (async/timeout 300))
    (is (= false (! reg-name :msg))
        "! must return false sending to terminated process")))

(deftest ^:parallel !-returns-false-sending-to-unregistered-name
  (is (= false (! (uuid-keyword) :msg))
      "! must return false sending to unregistered name"))

(deftest ^:parallel !-returns-false-sending-to-terminated-process-by-pid
  (let [timeout (async/timeout 100)
        pid (process/spawn (fn [_] (async/close! timeout)) [] {})]
    (<!! timeout)
    (<!! (async/timeout 10))
    (is (= false (! pid :msg))
        "! must return false sending to terminated process")))

(deftest ^:parallel !-throws-on-nil-arguments
  (is (thrown? Throwable (! nil :msg))
      "! must throw on when dest argument is nil")
  (is (thrown? Throwable (! (process/spawn (fn [_]) [] {}) nil))
      "! must throw on when message argument is nil")
  (is (thrown? Throwable (! nil nil))
      "! must throw on when both arguments are nil"))

(deftest ^:parallel !-delivers-message-sent-by-pid-to-alive-process
  (let [timeout (async/timeout 300)
        proc-fn (fn [inbox]
                  (is (= [:message :msg] (await-message inbox 100))
                      "message sent with ! to pid must appear in inbox")
                  (async/close! timeout))
        pid (process/spawn proc-fn [] {})]
    (! pid :msg)
    (<!! timeout)))

(deftest ^:parallel !-delivers-message-sent-by-registered-name-to-alive-process
  (let [timeout (async/timeout 200)
        proc-fn (fn [inbox]
                  (is (= [:message :msg] (await-message inbox 100))
                      (str "message sent with ! to registered name"
                           " must appear in inbox"))
                  (async/close! timeout))
        reg-name (uuid-keyword)]
    (process/spawn proc-fn [] {:register reg-name})
    (! reg-name :msg)
    (<!! timeout)))

;; ====================================================================
;; (exit [pid reason])
;;   Sends an exit signal with exit reason to the process identified
;;   by pid.
;;   If reason is any term, except :normal or :kill:
;;   - if pid is not trapping exits, pid itself exits with exit reason.
;;   - if pid is trapping exits, the exit signal is transformed into a
;;     message [:EXIT, from, reason] and delivered to the message queue
;;     of pid. from is the process identifier of the process that sent
;;     the exit signal.
;;   If reason is :normal, pid does not exit. If pid is trapping exits,
;;   the exit signal is transformed into a message
;;   [:EXIT, from, :normal] and delivered to its message queue.
;;   If reason is :kill, an untrappable exit signal is sent to pid,
;;   which unconditionally exits with reason :killed.
;;   Returns true if exit signal was sent (process was alive), false
;;   otherwise.
;;   Throws if pid is not a pid, or message is nil.

    ; inbox becomes closed
    ; future messages do not arrive to the process' inbox
    ; all linked/monitoring processes receive exit signal/message
    ; process no longer registered

(deftest ^:parallel exit-throws-on-nil-reason
  (let [timeout (async/timeout 100)
        pid (process/spawn (fn [_] (<!! timeout)) [] {})]
    (is (thrown? Throwable (process/exit pid nil))
        "exit must throw when reason argument is nil")
    (async/close! timeout)))

(deftest ^:parallel exit-throws-on-not-a-pid
  (is (thrown? Throwable (process/exit nil :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Throwable (process/exit 1 :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Throwable (process/exit "pid1" :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Throwable (process/exit [] :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Throwable (process/exit '() :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Throwable (process/exit {} :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Throwable (process/exit #{} :normal))
      "exit must throw on not a pid argument"))

(deftest ^:parallel exit-normal-no-trap-exit
  (let [timeout (async/timeout 500)
        proc-fn (fn [inbox]
                  (is (= :timeout (await-message inbox 100))
                      "exit with reason :normal must not close process' inbox")
                  (async/close! timeout))
        pid (process/spawn proc-fn [] {})]
    (process/exit pid :normal)
    (<!! timeout))
  (let [timeout (async/timeout 500)
        proc-fn (fn [inbox]
                  (go
                    (is (= :timeout (await-message inbox 100))
                      "exit with reason :normal must not close process' inbox")
                    (async/close! timeout)))
        pid (process/spawn proc-fn [] {})]
    (process/exit pid :normal)
    (<!! timeout)))

(deftest ^:parallel exit-normal-trap-exit
  #_(let [timeout (async/timeout 500)
          proc-fn (fn [inbox]
                    (is (= [:exit-message [:reason :normal]]
                           (await-message inbox 100))
                        (str "exit must send [:EXIT pid :normal] message"
                             " to process trapping exits"))
                    (async/close! timeout))
          pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
      (process/exit pid :normal))
  (let [timeout (async/timeout 500)
        proc-fn (fn [inbox]
                  (go
                    (is (= [:exit-message [:reason :normal]]
                           (await-message inbox 100))
                        (str "exit must send [:EXIT pid :normal] message"
                             " to process trapping exits"))
                    (async/close! timeout)))
        pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
    (process/exit pid :normal)
    (<!! timeout)))

#_(deftest ^:parallel exit-normal-registered-process
  (let [timeout (async/timeout 300)
        reg-name (uuid-keyword)
        pid (process/spawn (fn [_] (<!! timeout))
                           []
                           {:register reg-name})]
    (is ((into #{} (process/registered)) reg-name)
        "registered process must be in list of registered before exit")
    (process/exit pid :abnormal)
    (<!! (async/timeout 100))
    (is (nil? ((into #{} (process/registered)) reg-name))
        "process must not be registered after exit")))

#_(deftest ^:parallel exit-normal-registered-process
  (let [timeout (async/timeout 500)
        reg-name (uuid-keyword)
        pid (process/spawn (fn [_] (<!! timeout))
                           []
                           {:register reg-name})]
    (is ((into #{} (process/registered)) reg-name)
        "registered process must be in list of registered before exit")
    (async/close! timeout)
    (<!! (async/timeout 100))
    (is (nil? ((into #{} (process/registered)) reg-name))
        "process must not be registered after exit")))

(deftest ^:parallel exit-abnormal-no-trap-exit
  #_(let [timeout (async/timeout 500)
          proc-fn (fn [inbox]
                    (is (= :inbox-closed (await-message inbox 100))
                        (str "exit with reason other than :normal must close"
                             "process' inbox"))
                    (async/close! timeout))
          pid (process/spawn proc-fn [] {})]
      (process/exit pid :abnormal)
      (<!! timeout))
  (let [timeout (async/timeout 500)
        proc-fn (fn [inbox]
                  (go
                    (is (= :inbox-closed (await-message inbox 100))
                        (str "exit with reason other than :normal must close"
                             "process' inbox"))
                    (async/close! timeout)))
        pid (process/spawn proc-fn [] {})]
    (process/exit pid :abnormal)
    (<!! timeout)))

(deftest ^:parallel exit-abnormal-trap-exit
  #_(let [timeout (async/timeout 500)
          proc-fn (fn [inbox]
                    (is (= [:exit-message [:reason :abnormal]]
                           (await-message inbox 100))
                        (str "exit must send [:EXIT _ reason] message"
                             " to process trapping exits"))
                    (async/close! timeout))
          pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
      (process/exit pid :abnormal)
      (<!! timeout))
  (let [timeout (async/timeout 500)
        proc-fn (fn [inbox]
                  (go
                    (is (= [:exit-message [:reason :abnormal]]
                           (await-message inbox 100))
                        (str "exit must send [:EXIT _ reason] message"
                             " to process trapping exits"))
                    (async/close! timeout)))
        pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
    (process/exit pid :abnormal)
    (<!! timeout)))

(deftest ^:parallel exit-kill-no-trap-exit
  #_(let [timeout (async/timeout 500)
          proc-fn (fn [inbox]
                    (is (= :inbox-closed (await-message inbox 300))
                        "exit with reason :kill must close process' inbox")
                    (async/close! timeout))
          pid (process/spawn proc-fn [] {})]
      (process/exit pid :kill)
      (<!! timeout))
  (let [timeout (async/timeout 500)
        proc-fn (fn [inbox]
                  (go
                    (is (= :inbox-closed (await-message inbox 300))
                        "exit with reason :kill must close process' inbox")
                    (async/close! timeout)))
        pid (process/spawn proc-fn [] {})]
    (process/exit pid :kill)
    (<!! timeout)))

(deftest ^:parallel exit-kill-trap-exit
  #_(let [timeout (async/timeout 500)
          proc-fn (fn [inbox]
                    (is (= :inbox-closed (await-message inbox 300))
                        "exit with reason :kill must close process' inbox")
                    (async/close! timeout))
          pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
      (process/exit pid :kill)
      (<!! timeout))
  (let [timeout (async/timeout 500)
        proc-fn (fn [inbox]
                  (go
                    (is (= :inbox-closed (await-message inbox 300))
                        "exit with reason :kill must close process' inbox")
                    (async/close! timeout)))
        pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
    (process/exit pid :kill)
    (<!! timeout)))

(deftest ^:parallel exit-returns-true-on-alive-process
  (let [proc-fn (fn [_inbox] (<!! (async/timeout 100)))]
    (let [pid (process/spawn proc-fn [] {})]
      (is (= true (process/exit pid :normal))
          "exit must return true on alive process"))
    (let [pid (process/spawn proc-fn [] {})]
      (is (= true (process/exit pid :abnormal))
          "exit must return true on alive process"))
    (let [pid (process/spawn proc-fn [] {})]
      (is (= true (process/exit pid :kill))
          "exit must return true on alive process"))
    (let [pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :normal))
          "exit must return true on alive process"))
    (let [pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :abnormal))
          "exit must return true on alive process"))
    (let [pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :kill))
          "exit must return true on alive process"))))

(deftest ^:parallel exit-returns-false-on-terminated-process
  (let [proc-fn (fn [_inbox])]
    (let [pid (process/spawn proc-fn [] {})]
      (<!! (async/timeout 100))
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
          "exit must return false on terminated process"))))

(deftest ^:parallel exit-self
  (let [timeout (async/timeout 500)]
    (process/spawn
      (fn [inbox]
        (go
          (process/exit (process/self) :normal)
          (is (= [:exit-message [:reason :normal]]
                 (await-message inbox 100))
              (str "exit with reason :normal must send [:EXIT pid :normal]"
                   " message to process trapping exits"))
          (async/close! timeout)))
      []
      {:flags {:trap-exit true}})
    (<!! timeout))
  (let [timeout (async/timeout 1000)]
    (process/spawn
      (fn [inbox]
        (go
          (process/exit (process/self) :abnormal-1)
          (is (= [:exit-message [:reason :abnormal-1]]
                 (await-message inbox 300))
              (str "exit must send [:EXIT pid reason]"
                   " message to process trapping exits"))
          (process/exit (process/self) :abnormal-2)
          (is (= [:exit-message [:reason :abnormal-2]]
                 (await-message inbox 300))
              (str "exit must send [:EXIT pid reason]"
                   " message to process trapping exits"))
          (async/close! timeout)))
      []
      {:flags {:trap-exit true}})
    (<!! timeout))
  (let [timeout (async/timeout 500)]
    (process/spawn
      (fn [inbox]
        (go
          (process/exit (process/self) :kill)
          (is (= :inbox-closed (await-message inbox 300))
              "exit with reason :kill must close inbox of process trapping exits")
          (async/close! timeout)))
      []
      {:flags {:trap-exit true}})
    (<!! timeout))
  (let [timeout (async/timeout 500)]
    (process/spawn
      (fn [inbox]
        (go
          (process/exit (process/self) :normal)
          (is (= :timeout (await-message inbox 300))
              (str "exit with reason :normal must do nothing"
                   " to process not trapping exits"))
          (async/close! timeout)))
      []
      {})
    (<!! timeout))
  (let [timeout (async/timeout 500)]
    (process/spawn
      (fn [inbox]
        (go
          (process/exit (process/self) :abnormal)
          (is (= :inbox-closed (await-message inbox 300))
              (str "exit with any reason except :normal must close"
                   " inbox of proces not trapping exits"))
          (async/close! timeout)))
      []
      {})
    (<!! timeout))
  (let [timeout (async/timeout 500)]
    (process/spawn
      (fn [inbox]
        (go
          (process/exit (process/self) :kill)
          (is (= :inbox-closed (await-message inbox 300))
              (str "exit with reason :kill must close inbox of process"
                   " not trapping exits"))
          (async/close! timeout)))
      []
      {})
    (<!! timeout)))

; TODO
(deftest ^:parallel exit-kill-reason-killed) ; use link or monitor to test the reason

;; ====================================================================
;; (flag [flag value])
;;   :trap-exit
;;   When :trap-exit is set to true, exit signals arriving to a process
;;   are converted to [:EXIT, from, reason] messages, which can be
;;   received as ordinary messages. If :trap-exit is set to false, the
;;   process exits if it receives an exit signal other than :normal and
;;   the exit signal is propagated to its linked processes. Application
;;   processes are normally not to trap exits.
;;   :other-flag
;;   ...
;;   Returns the old value of the flag.
;;   Throws when called not in process context.

#_(deftest ^:parallel flag-trap-exit-true-makes-process-to-trap-exits
    (let [timeout (async/timeout 500)
          proc-fn (fn [inbox]
                    (go
                      (process/flag :trap-exit true)
                      (is (= [:exit-message [:reason :normal]]
                             (await-message inbox 300))
                          (str "flag :trap-exit set to true in process must"
                               " make process to trap exits"))
                      (async/close! timeout)))
          pid (process/spawn proc-fn [] {})]
      (match (process/exit pid :normal) true :ok)
      (<!! timeout))
    (let [timeout (async/timeout 500)
          proc-fn (fn [inbox]
                    (process/flag :trap-exit true)
                    (is (= [:exit-message [:reason :normal]]
                           (await-message inbox 300))
                        (str "flag :trap-exit set to true in process must"
                             " make process to trap exits"))
                    (async/close! timeout))
          pid (process/spawn proc-fn [] {})]
      (match (process/exit pid :normal) true :ok)
      (<!! timeout)))

#_(deftest ^:parallel flag-trap-exit-false-makes-process-not-to-trap-exits
  (let [timeout (async/timeout 500)
        proc-fn (fn [inbox]
                  (process/flag :trap-exit false)
                  (is (= :timeout (await-message inbox 300))
                      (str "flag :trap-exit set to false in process must"
                           " make process not to trap exits"))
                  (async/close! timeout))
        pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
    (match (process/exit pid :normal) true :ok)
    (<!! timeout))
  (let [timeout (async/timeout 500)
        proc-fn (fn [inbox]
                  (go
                    (process/flag :trap-exit false)
                    (is (= :timeout (await-message inbox 300))
                        (str "flag :trap-exit set to false in process must"
                             " make process not to trap exits"))
                    (async/close! timeout)))
        pid (process/spawn proc-fn [] {:flags {:trap-exit true}})]
    (match (process/exit pid :normal) true :ok)
    (<!! timeout)))

#_(deftest ^:parallel flag-trap-exit-switches-trapping-exit
  (let [timeout1 (async/timeout 500)
        timeout2 (async/timeout 500)
        proc-fn (fn [inbox]
                  (process/flag :trap-exit true)
                  (is (= [:EXIT [:reason :abnormal]]
                         (await-message inbox 300))
                      (str "flag :trap-exit set to true in process must"
                           " make process to trap exits"))
                  (process/flag :trap-exit false)
                  (async/close! timeout1)
                  (is (= :inbox-closed (await-message inbox 300))
                      (str "flag :trap-exit switched second time  in process"
                           " must make process to switch trapping exits"))
                  (async/close! timeout2))
        pid (process/spawn proc-fn [] {})]
    (match (process/exit pid :abnormal) true :ok)
    (<!! timeout1)
    (match (process/exit pid :abnormal) true :ok)
    (<!! timeout2))
  (let [timeout1 (async/timeout 500)
        timeout2 (async/timeout 500)
        proc-fn (fn [inbox]
                  (go
                    (process/flag :trap-exit true)
                    (is (= [:EXIT [:reason :abnormal]]
                           (await-message inbox 300))
                        (str "flag :trap-exit set to true in process must"
                             " make process to trap exits"))
                    (process/flag :trap-exit false)
                    (async/close! timeout1)
                    (is (= :inbox-closed (await-message inbox 300))
                        (str "flag :trap-exit switched second time  in process"
                             " must make process to switch trapping exits"))
                    (async/close! timeout2)))
        pid (process/spawn proc-fn [] {})]
    (match (process/exit pid :abnormal) true :ok)
    (<!! timeout1)
    (match (process/exit pid :abnormal) true :ok)
    (<!! timeout2)))

(deftest ^:parallel flag-trap-exit-returns-old-value
  (let [timeout (async/timeout 300)
        proc-fn (fn [inbox]
                  (go
                    (is (= true (process/flag :trap-exit false))
                        "setting flag :trap-exit must return its previous value")
                    (is (= false (process/flag :trap-exit false))
                        "setting flag :trap-exit must return its previous value")
                    (is (= false (process/flag :trap-exit true))
                        "setting flag :trap-exit must return its previous value")
                    (is (= true (process/flag :trap-exit true))
                        "setting flag :trap-exit must return its previous value")
                    (async/close! timeout)))]
    (process/spawn proc-fn [] {:flags {:trap-exit true}})
    (<!! timeout))
  (let [timeout (async/timeout 300)
        proc-fn (fn [inbox]
                  (is (= true (process/flag :trap-exit false))
                      "setting flag :trap-exit must return its previous value")
                  (is (= false (process/flag :trap-exit false))
                      "setting flag :trap-exit must return its previous value")
                  (is (= false (process/flag :trap-exit true))
                      "setting flag :trap-exit must return its previous value")
                  (is (= true (process/flag :trap-exit true))
                      "setting flag :trap-exit must return its previous value")
                  (async/close! timeout))]
    (process/spawn proc-fn [] {:flags {:trap-exit true}})
    (<!! timeout)))

(deftest ^:parallel flag-throws-on-unknown-flag
  (let [timeout (async/timeout 300)
        proc-fn (fn [inbox]
                  (is (thrown? Throwable (process/flag [] false))
                      "flag must throw on unknown flag")
                  (is (thrown? Throwable (process/flag 1 false))
                      "flag must throw on unknown flag")
                  (is (thrown? Throwable (process/flag :unknown false))
                      "flag must throw on unknown flag")
                  (is (thrown? Throwable (process/flag nil false))
                      "flag must throw on unknown flag")
                  (is (thrown? Throwable (process/flag nil true))
                      "flag must throw on unknown flag")
                  (is (thrown? Throwable (process/flag :trap-exit1 false))
                      "flag must throw on unknown flag")
                  (async/close! timeout))]
    (process/spawn proc-fn [] {})
    (<!! timeout)))

(deftest ^:parallel flag-throws-when-called-not-in-process-context
  (is (thrown? Exception (process/flag :trap-exit true))
      "flag must throw when called not in process context")
  (is (thrown? Exception (process/flag :trap-exit false))
      "flag must throw when called not in process context"))

;; ====================================================================
;; (registered [])
;;   Returns a set of names of the processes that have been registered.

(deftest ^:serial registered-returns-empty-seq-when-nothing-registered
  (is (empty? (process/registered))
      "registered must return empty seq of names when nothing registered"))

(deftest ^:serial registered-returns-registered-names
  (let [n1 (uuid-keyword)
        n2 (uuid-keyword)
        n3 (uuid-keyword)
        registered #{n1 n2 n3}
        timeout (async/timeout 300)
        proc-fn (fn [_inbox] (<!! timeout))]
    (process/spawn proc-fn [] {:register n1})
    (process/spawn proc-fn [] {:register n2})
    (process/spawn proc-fn [] {:register n3})
    (is (= registered (into #{} (process/registered)))
      "registered must return registered names")
    (async/close! timeout)))

(deftest ^:serial registered-returns-empty-seq-after-registered-terminated
  (let [proc-fn (fn [_inbox])]
    (process/spawn proc-fn [] {:register (uuid-keyword)})
    (process/spawn proc-fn [] {:register (uuid-keyword)})
    (process/spawn proc-fn [] {:register (uuid-keyword)})
    (<!! (async/timeout 100))
    (is (empty? (process/registered))
      (str "registered must return empty seq after all registered processes"
           " had terminated"))))

;; ====================================================================
;; (link [pid])
;;   Creates a link between the calling process and another process
;;   identified by pid, if there is not such a link already. If a
;;   process attempts to create a link to itself, nothing is done.
;;   If pid does not exist and the calling process is trapping exits,
;;   an exit signal with reason :noproc is sent to the calling process.
;;   Returns true.
;;   Throws when called not in process context.

(deftest ^:parallel link-return-true)
(deftest ^:parallel link-creates-link-with-alive-process-not-trapping-exits)
(deftest ^:parallel link-creates-link-with-alive-process-trapping-exits)
(deftest ^:parallel link-creates-exactly-one-link-when-called-multiple-times)
(deftest ^:parallel link-creates-link-with-terminated-process)
(deftest ^:parallel link-throws-when-called-not-in-process-context)
(deftest ^:parallel link-return-true)

;; ====================================================================
;; (unlink [pid])
;;   Removes the link, if there is one, between the calling process and
;;   the process or port referred to by pid.
;;   Returns true and does not fail if there is no link to pid, or if
;;   pid does not exist.
;;   Once unlink has returned, it is guaranteed that the link between
;;   the caller and the entity referred to by pid has no effect on the
;;   caller in the future (unless the link is setup again).
;;   If the caller is trapping exits, an [:EXIT, pid, _] message from
;;   the link can have been placed in the caller's message queue before
;;   the call.
;;   Notice that the [:EXIT, pid, _] message can be the result of the
;;   link, but can also be the result of Id calling exit. Therefore,
;;   it can be appropriate to clean up the message queue when trapping
;;   exits after the call to unlink.



;; ====================================================================
;; (spawn [proc-fun args options])
;;   Returns the process identifier of a new process started by the
;;   application of proc-fun to args.
;;   The following options are allowed:
;;   ...

(deftest ^:parallel process-does-not-trap-exits-by-default)

;; ====================================================================
;; (spawn-link [proc-fun args options])
;;   Returns the process identifier of a new process started by the
;;   application of proc-fun to args. A link is created between the
;;   calling process and the new process, atomically. Otherwise works
;;   like spawn/3.
;;   Throws when called not in process context.


;; ====================================================================
;; (monitor [pid])
;;   ;



;; ====================================================================
;; (demonitor [pid])
;;   ;



; -----------

#_(deftest spawn-terminate-normal []
  (let [result (trace/trace-collector [:p1])]
    (process/spawn
      (fn [inbox p1 p2 & other]
        (is (process/pid? (process/self)) "self must be instance of Pid")
        (is (satisfies? ap/ReadPort inbox) "inbox must be a ReadPort")
        (is (and (= p1 :p1) (= p2 :p2) "formal parameters must match actuals"))
        (is (= (count other) 0) "no extra parameters")
        :normal)
      [:p1 :p2]
      {:name :p1})
    (let [trace (result 1000)]
      (is
        (match trace
          [[_ _ [:start _ _ _]]
           [_ _ [:return :normal]]
           [_ _ [:terminate :normal]]] true)))))

#_(deftest spawn-terminate-nil []
  (let [result (trace/trace-collector [:p1])]
    (process/spawn (fn [_inbox]) [] {:name :p1})
    (let [trace (result 1000)]
      (is
        (match trace
          [[_ _ [:start _ _ _]]
           [_ _ [:return :nil]]
           [_ _ [:terminate :nil]]] true)))))

#_(defn- link-to-normal* []
  (let [p1-fn (fn [_inbox]
                (go
                  (async/<! (async/timeout 500))
                  :blah))
        p2-fn (fn [inbox] (go (<! inbox)))
        p1 (process/spawn p1-fn [] {:name :p1})
        p2 (process/spawn p2-fn [] {:name :p2 :link-to p1})] [p1 p2]))

#_(deftest link-to-normal []
  (let [result (trace/trace-collector [:p1 :p2])
        [p1 p2] (link-to-normal*)
        trace (result 1000)]
    (is (trace/terminated? trace p1 :blah))
    (is (trace/terminated? trace p2 :blah))))

#_(defn- link-to-terminated* []
  (let [p1 (process/spawn (fn [_inbox]) [] {:name :p1})
        _  (async/<!! (async/timeout 500))
        p2 (process/spawn (fn [inbox] (go (<! inbox))) [] {:name :p2 :link-to p1})]
    [p1 p2]))

#_(deftest link-to-terminated []
  (let [result (trace/trace-collector [:p1 :p2])
        [p1 p2] (link-to-terminated*)
        trace (result 1000)]
    (is (trace/terminated? trace p1 :nil))
    (is (trace/terminated? trace p2 :noproc))))
