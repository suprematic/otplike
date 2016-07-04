(ns otplike.process-test
  (:require [clojure.test :refer [is deftest]]
            [otplike.process :as process :refer [!]]
            [otplike.trace :as trace]
            [clojure.core.async :as async :refer [<!! <! >! put! go go-loop]]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.core.match :refer [match]]))

;; ====================================================================
;; (self [])
;;   Returns the process identifier of the calling process. Throws when
;;   called not in process context.

(deftest self-returns-process-pid-in-process-context
  (process/spawn
    (fn [inbox]
      (is (process/pid? (process/self))
          "self must return pid when called in process context")
      (is (= (process/self) (process/self))
          "self must return the same pid when called by the same process")
      (! (process/self) :test_msg)
      (match (async/alts!! [inbox (async/timeout 50)])
        [msg inbox] (is (= msg :test_msg)
                        "message sent to self must appear in inbox")
        [nil _] (is (not :timeout)
                    "message sent to self must appear in inbox"))
      :normal)
    []
    {}))

(deftest self-fails-in-non-process-context
  (is (thrown? Exception (process/self))
      "self must throw when called not in process context"))

;; ====================================================================
;; (pid? [term])
;;   Returns true if term is a process identifier, false otherwise.

(deftest pid?-returns-false-on-non-pid
  (is (not (process/pid? nil)) "pid? must return false on nonpid arguement")
  (is (not (process/pid? 1)) "pid? must return false on nonpid arguement")
  (is (not (process/pid? "not-a-pid"))
      "pid? must return false on nonpid arguement")
  (is (not (process/pid? [])) "pid? must return false on nonpid arguement")
  (is (not (process/pid? '())) "pid? must return false on nonpid arguement")
  (is (not (process/pid? #{})) "pid? must return false on nonpid arguement")
  (is (not (process/pid? {})) "pid? must return false on nonpid arguement"))

(deftest pid?-returns-true-on-pid
  (is (process/pid? (process/spawn (fn [_] :normal) [] {}))
      "pid? must return true on pid argument"))

;; ====================================================================
;; (pid->str [pid])
;;   Returns a string corresponding to the text representation of pid.
;;   Throws if pid is not a process identifier.
;;   Warning: this function is intended for debugging and is not to be
;;   used in application programs.

(deftest pid->str-returns-string
  (is (string? (process/pid->str (process/spawn (fn [_] :normal) [] {})))
      "pid->str must return string on pid argument"))

(deftest pid->str-fails-on-non-pid
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

(deftest whereis-returns-process-pid-on-registered-name
  (let [reg-name :test-process
        pid (process/spawn
              (fn [_]
                (is (= (process/self) (process/whereis reg-name))
                    "whereis must return process pid on registered name")
                (<!! (async/timeout 50)))
              []
              {:register reg-name})]
    (is (= pid (process/whereis reg-name))
        "whereis must return process pid on registered name")))

(deftest whereis-returns-nil-on-not-registered-name
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

(deftest whereis-throws-on-nil-argument
  (is (thrown? Throwable (process/whereis nil))
      "whereis must throw on nil argument"))

;; ====================================================================
;; (! [dest message])
;;   Sends a message to dest. dest can be a process identifier, or a
;;   registered name. Returns true if message was sent (process was
;;   alive), false otherwise. Throws if any of the arguments is nil.

(deftest !-returns-true-sending-to-alive-process-by-pid
  (is (! (process/spawn (fn [_] (<!! (async/timeout 50))) [] {}) :msg)
      "! must return true sending to alive process"))

(deftest !-returns-true-sending-to-alive-process-by-reg-name
  (let [reg-name :test-process2]
    (process/spawn (fn [_] (<!! (async/timeout 50))) [] {:register reg-name})
    (is (! reg-name :msg) "! must return true sending to alive process")))

(deftest !-returns-false-sending-to-terminated-process-by-reg-name
  (let [reg-name :test-process3]
    (process/spawn (fn [_]) [] {:register reg-name})
    (<!! (async/timeout 50))
    (is (not (! reg-name :msg))
        "! must return false sending to terminated process")))

(deftest !-returns-false-sending-to-unregistered-name
  (is (not (! :test-process4 :msg))
      "! must return false sending to unregistered name"))

(deftest !-returns-false-sending-to-terminated-process-by-pid
  (let [pid (process/spawn (fn [_]) [] {})]
    (<!! (async/timeout 50))
    (is (not (! pid :msg))
        "! must return false sending to terminated process")))

(deftest !-throws-on-nil-arguments
  (is (thrown? Throwable (! nil :msg))
      "! must throw on when dest argument is nil")
  (is (thrown? Throwable (! (process/spawn (fn [_]) [] {}) nil))
      "! must throw on when message argument is nil")
  (is (thrown? Throwable (! nil nil))
      "! must throw on when both arguments are nil"))

(deftest !-delivers-message-sent-by-pid-to-alive-process
  (let [pid (process/spawn
              (fn [inbox]
                (match (async/alts!! [inbox (async/timeout 50)])
                  [msg inbox] (is (= msg :test_msg)
                                  "message sent with ! must appear in inbox")
                  [nil _] (is (not :timeout)
                              "message sent with ! must appear in inbox")))
              []
              {})]
    (! pid :test_msg)))

(deftest !-delivers-message-sent-by-registered-name-to-alive-process
  (let [reg-name :test-process5]
    (process/spawn
      (fn [inbox]
        (match (async/alts!! [inbox (async/timeout 50)])
          [msg inbox] (is (= msg :test_msg)
                          "message sent with ! must appear in inbox")
          [nil _] (is (not :timeout)
                      "message sent with ! must appear in inbox")))
      []
      {:register reg-name})
    (! reg-name :test_msg)))

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

(deftest exit-throws-on-nil-arguments)
(deftest exit-normal-no-trap-exit)
(deftest exit-normal-trap-exit)
(deftest exit-abnormal-no-trap-exit)
(deftest exit-abnormal-trap-exit)
(deftest exit-kill-no-trap-exit)
(deftest exit-kill-trap-exit)
(deftest exit-returns-true-on-alive-process)
(deftest exit-returns-false-on-terminated-process)
(deftest exit-throws-on-not-a-pid)
(deftest exit-throws-on-nil-message)

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

(deftest flag-trap-exit-true-makes-process-to-trap-exits)
(deftest flag-trap-exit-false-makes-process-not-to-trap-exits)
(deftest flag-trap-exit-returns-old-value)
(deftest flag-throws-on-unknown-flag)
(deftest flag-throws-when-called-not-in-process-context)

;; ====================================================================
;; (registered [])
;;   Returns a list of names of the processes that have been registered.

(deftest registered-returns-empty-seq-when-nothing-registered)
(deftest registered-returns-registered-names)
(deftest registered-returns-empty-seq-after-registered-terminated)

;; ====================================================================
;; (link [pid])
;;   Creates a link between the calling process and another process
;;   identified by pid, if there is not such a link already. If a
;;   process attempts to create a link to itself, nothing is done.
;;   If pid does not exist and the calling process is trapping exits,
;;   an exit signal with reason :noproc is sent to the calling process.
;;   Returns true.
;;   Throws when called not in process context.

(deftest link-return-true)
(deftest link-creates-link-with-alive-process-not-trapping-exits)
(deftest link-creates-link-with-alive-process-trapping-exits)
(deftest link-creates-one-link-only-when-called-multiple-times)
(deftest link-creates-link-with-terminated-process)
(deftest link-throws-when-called-not-in-process-context)
(deftest link-return-true)

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

(deftest spawn-terminate-normal []
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

(deftest spawn-terminate-nil []
  (let [result (trace/trace-collector [:p1])]
    (process/spawn (fn [_inbox]) [] {:name :p1})
    (let [trace (result 1000)]
      (is
        (match trace
          [[_ _ [:start _ _ _]]
           [_ _ [:return :nil]]
           [_ _ [:terminate :nil]]] true)))))

(defn- link-to-normal* []
  (let [p1-fn (fn [_inbox]
                (go
                  (async/<! (async/timeout 500))
                  :blah))
        p2-fn (fn [inbox] (go (<! inbox)))
        p1 (process/spawn p1-fn [] {:name :p1})
        p2 (process/spawn p2-fn [] {:name :p2 :link-to p1})] [p1 p2]))

(deftest link-to-normal []
  (let [result (trace/trace-collector [:p1 :p2])
        [p1 p2] (link-to-normal*)
        trace (result 1000)]
    (is (trace/terminated? trace p1 :blah))
    (is (trace/terminated? trace p2 :blah))))

(defn- link-to-terminated* []
  (let [p1 (process/spawn (fn [_inbox]) [] {:name :p1})
        _  (async/<!! (async/timeout 500))
        p2 (process/spawn (fn [inbox] (go (<! inbox))) [] {:name :p2 :link-to p1})]
    [p1 p2]))

(deftest link-to-terminated []
  (let [result (trace/trace-collector [:p1 :p2])
        [p1 p2] (link-to-terminated*)
        trace (result 1000)]
    (is (trace/terminated? trace p1 :nil))
    (is (trace/terminated? trace p2 :noproc))))
