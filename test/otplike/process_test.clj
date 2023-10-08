(ns otplike.process-test
  (:require [clojure.test :refer [is deftest]]
            [otplike.process :as process :refer [! proc-fn]]
            [otplike.trace :as trace]
            [otplike.test-util :refer :all]
            [otplike.proc-util :as proc-util]
            [clojure.core.async :as async :refer [<!! <! >! >!!]]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.core.match :refer [match]]))

;; ====================================================================
;; (self [])

(deftest ^:parallel self-returns-process-pid-in-process-context
  (let [done (async/chan)
        pfn (proc-fn []
              (is (process/pid? (process/self))
                  "self must return pid when called in process context")
              (is (= (process/self) (process/self))
                  (str "self must return the same pid when called by the same"
                       " process"))
              (! (process/self) :msg)
              (is (= [:message :msg] (<! (await-message 50)))
                  "message sent to self must appear in inbox")
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 100)))

(deftest ^:parallel self-fails-in-non-process-context
  (is (thrown? Exception (process/self))
      "self must throw when called not in process context"))

(deftest self-fails-when-process-is-exiting)

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
  (is (process/pid? (process/spawn (proc-fn [])))
      "pid? must return true on pid argument"))

;; ====================================================================
;; (pid->str [pid])

(deftest ^:parallel pid->str-returns-string
  (is (string? (process/pid->str (process/spawn (proc-fn []))))
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
        pid (process/spawn-opt
             (proc-fn []
               (is (= (process/self) (process/whereis reg-name))
                   "whereis must return process pid on registered name")
               (is (await-completion! done 100)))
             {:register reg-name})]
    (is (= pid (process/whereis reg-name))
        "whereis must return process pid on registered name")
    (async/close! done)))

(deftest ^:parallel whereis-returns-nil-on-not-registered-name
  (let [pid (process/spawn (proc-fn []))]
    (is (nil? (process/whereis pid))
        "whereis must return nil on not registered name"))
  (is (nil? (process/whereis nil))
      "whereis must return nil on not registered name")
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

;; ====================================================================
;; (! [dest message])

(deftest ^:parallel !-returns-true-sending-to-alive-process-by-pid
  (let [done (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done 100))))]
    (is (= true (! pid :msg)) "! must return true sending to alive process")
    (async/close! done)))

(deftest ^:parallel !-returns-true-sending-to-alive-process-by-reg-name
  (let [done (async/chan)
        reg-name (uuid-keyword)]
    (process/spawn-opt (proc-fn [] (is (await-completion! done 100)))
                       {:register reg-name})
    (is (= true (! reg-name :msg))
        "! must return true sending to alive process")
    (async/close! done)))

(deftest ^:parallel !-returns-false-sending-to-terminated-process-by-reg-name
  (let [reg-name (uuid-keyword)]
    (process/spawn-opt (proc-fn []) {:register reg-name})
    (<!! (async/timeout 50))
    (is (= false (! reg-name :msg))
        "! must return false sending to terminated process")))

(deftest ^:parallel !-returns-false-sending-to-unregistered-name
  (is (= false (! (uuid-keyword) :msg))
      "! must return false sending to unregistered name"))

(deftest ^:parallel !-returns-false-sending-to-terminated-process-by-pid
  (let [done (async/chan)
        pid (process/spawn (proc-fn []))]
    (<!! (async/timeout 50))
    (is (= false (! pid :msg))
        "! must return false sending to terminated process")))

; TODO allow sending nil
(deftest ^:parallel !-throws-on-nil-pid
  (is (thrown? Exception (! nil :msg))
      "! must throw on when dest argument is nil")
  (is (thrown? Exception (! nil nil))
      "! must throw on when both arguments are nil"))

(deftest ^:parallel !-allows-sending-nil
  (let [done (async/chan)
        pid (process/spawn
             (proc-fn [] (process/receive! _ (async/close! done))))]
    (! pid nil)
    (await-completion!! done 100)))

(deftest ^:parallel !-delivers-message-sent-by-pid-to-alive-process
  (let [done (async/chan)
        pfn (proc-fn []
              (is (= [:message :msg] (<! (await-message 50)))
                  "message sent with ! to pid must appear in inbox")
              (async/close! done))
        pid (process/spawn pfn)]
    (! pid :msg)
    (await-completion!! done 100)))

(deftest ^:parallel !-delivers-message-sent-by-registered-name-to-alive-process
  (let [done (async/chan)
        pfn (proc-fn []
              (is (= [:message :msg] (<! (await-message 50)))
                  (str "message sent with ! to registered name"
                       " must appear in inbox"))
              (async/close! done))
        reg-name (uuid-keyword)]
    (process/spawn-opt pfn {:register reg-name})
    (! reg-name :msg)
    (await-completion!! done 100)))

(deftest ^:parallel !-send-a-message-when-called-not-by-process
  (let [done (async/chan)
        pid (process/spawn
             (proc-fn [] (process/receive! :msg (async/close! done))))]
    (! pid :msg)
    (is (await-completion!! done 100))))

#_(def-proc-test ^:parallel process-killed-when-inbox-is-overflowed
    (process/flag :trap-exit true)
    (let [done (async/chan)
          pid (process/spawn-link (proc-fn [] (is (await-completion! done 1000))))]
      (dotimes [_ 2000] (! pid :msg))
      (is (= [:exit [pid :inbox-overflow]]
             (<! (await-message 1000)))
          (str "process must be killed when its control inbox"
               " is overflowed"))
      (async/close! done)))

;; ====================================================================
;; (proc-fn [args & body])

; TODO

;; ====================================================================
;; (proc-defn [fname args & body])

(deftest ^:parallel proc-defn--defines-proc-fn
  (let [sym (gensym)]
    (eval `(process/proc-defn ~sym []))
    (is (sym-bound? sym)
        "process fn must be bound to var with the given name"))
  (let [sym (gensym)]
    (eval `(process/proc-defn ~sym [] :ok))
    (is (sym-bound? sym)
        "process fn must be bound to var with the given name"))
  (let [sym (with-meta (gensym) {::a 1 ::b 2})]
    (eval `(process/proc-defn ~sym [] :ok))
    (is (matches? (meta (resolve sym)) {::a 1 ::b 2})
        "process fn must have the same meta as symbol passed as its name")))

(deftest ^:parallel proc-defn--returns-proc-fn-var
  (let [sym (gensym)
        res (eval `(process/proc-defn ~sym [] :ok))]
    (is (= res (resolve sym))
        "proc-defn must return the bound function")))

(deftest ^:parallel proc-defn--defined-fn-can-be-spawned
  (let [sym (gensym)
        done (async/chan)
        _ (eval `(process/proc-defn ~sym [] ~(async/close! done)))
        pfn (var-get (resolve sym))]
    (process/spawn pfn)
    (is (await-completion!! done 50)
        "proc-defn must return a function wich can be spawned")))

(deftest ^:parallel proc-defn--throws-on-illegar-arguments
  (is (thrown? Exception (eval `(process/proc-defn f))))
  (is (thrown? Exception (eval `(process/proc-defn []))))
  (is (thrown? Exception (eval `(process/proc-defn f {}))))
  (is (thrown? Exception (eval `(process/proc-defn {} [] 3))))
  (is (thrown? Exception (eval `(process/proc-defn f 1 3)))))

(deftest ^:parallel proc-defn--adds-docs-and-arglists
  (let [sym (gensym)
        docs (str "my doc-string for " sym)]
    (eval `(process/proc-defn ~sym ~docs [] :ok))
    (is (= docs (:doc (meta (resolve sym))))
        "defined var must have doc-string meta"))
  (let [sym (gensym)]
    (eval `(process/proc-defn ~sym [~(symbol "a") ~(symbol "b")] :ok))
    (is (= [['a 'b]] (:arglists (meta (resolve sym))))
        "defined var must have arglists meta"))
  (let [sym (gensym)
        docs (str "my doc-string for " sym)]
    (eval `(process/proc-defn ~sym ~docs [~(symbol "a") ~(symbol "b")] :ok))
    (is (matches? (meta (resolve sym)) {:doc docs :arglists ([['a 'b]] :seq)})
        "defined var must have doc-string and arglists meta")))

;; ====================================================================
;; (proc-defn- [fname args & body])

(deftest ^:parallel proc-defn---defines-private-var
  (let [sym (gensym)]
    (eval `(process/proc-defn- ~sym [] :ok))
    (is (:private (meta (resolve sym)))
        "proc-defn- must define a private var")))

;; ====================================================================
;; (exit [reason])

; TODO

;; ====================================================================
;; (exit [pid reason])

(def-proc-test ^:parallel exit-throws-on-nil-reason
  (let [done (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done 50))))]
    (is (thrown? Exception (process/exit pid nil))
        "exit must throw when reason argument is nil")
    (async/close! done)))

(def-proc-test ^:parallel exit-throws-on-not-a-pid
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

(def-proc-test ^:parallel exit-normal-no-trap-exit
  (let [done (async/chan)
        pfn (proc-fn []
              (is (= :timeout (<! (await-message 100)))
                  "exit with reason :normal must not close process' inbox")
              (async/close! done))
        pid (process/spawn pfn)]
    (process/exit pid :normal)
    (await-completion! done 200)))

(def-proc-test ^:parallel exit-normal-trap-exit
  (let [done (async/chan)
        test-pid (process/self)
        pfn (proc-fn []
              (is (= [:exit [test-pid :normal]]
                     (<! (await-message 50)))
                  (str "exit must send [:EXIT pid :normal] message"
                       " to process trapping exits"))
              (async/close! done))
        pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
    (process/exit pid :normal)
    (await-completion! done 100)))

(deftest ^:parallel exit-normal-registered-process
  (let [done (async/chan)
        reg-name (uuid-keyword)
        pid (process/spawn-opt (proc-fn [] (is (await-completion! done 50)))
                               {:register reg-name})]
    (is ((into #{} (process/registered)) reg-name)
        "registered process must be in list of registered before exit")
    (async/close! done)
    (<!! (async/timeout 50))
    (is (nil? ((into #{} (process/registered)) reg-name))
        "process must not be registered after exit")))

(def-proc-test ^:parallel exit-abnormal-no-trap-exit
  (let [done (async/chan)
        pfn (proc-fn []
              (is (match (<! (await-message 50)) [:noproc _] :ok)
                  (str "exit with reason other than :normal must close"
                       "process' inbox"))
              (async/close! done))
        pid (process/spawn pfn)]
    (process/exit pid :abnormal)
    (await-completion! done 100)))

(def-proc-test ^:parallel exit-abnormal-trap-exit
  (let [done (async/chan)
        test-pid (process/self)
        pfn (proc-fn []
              (is (= [:exit [test-pid :abnormal]] (<! (await-message 50)))
                  "exit must send exit message to process trapping exits")
              (async/close! done))
        pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
    (process/exit pid :abnormal)
    (await-completion! done 100)))

(def-proc-test ^:parallel exit-kill-no-trap-exit
  (let [done (async/chan)
        pfn (proc-fn []
              (is (match (<! (await-message 50)) [:noproc _] :ok)
                  "exit with reason :kill must close process' inbox")
              (async/close! done))
        pid (process/spawn pfn)]
    (process/exit pid :kill)
    (await-completion! done 100)))

(def-proc-test ^:parallel exit-kill-trap-exit
  (let [done (async/chan)
        pfn (proc-fn []
              (is (match (<! (await-message 50)) [:noproc _] :ok)
                  "exit with reason :kill must close process' inbox")
              (async/close! done))
        pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
    (process/exit pid :kill)
    (await-completion! done 100)))

(def-proc-test ^:parallel exit-returns-true-on-alive-process
  (let [pfn (proc-fn [] (<! (async/timeout 50)))]
    (let [pid (process/spawn pfn)]
      (is (= true (process/exit pid :normal))
          "exit must return true on alive process"))
    (let [pid (process/spawn pfn)]
      (is (= true (process/exit pid :abnormal))
          "exit must return true on alive process"))
    (let [pid (process/spawn pfn)]
      (is (= true (process/exit pid :kill))
          "exit must return true on alive process"))
    (let [pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :normal))
          "exit must return true on alive process"))
    (let [pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :abnormal))
          "exit must return true on alive process"))
    (let [pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :kill))
          "exit must return true on alive process"))))

(def-proc-test ^:parallel exit-returns-false-on-terminated-process
  (let [pid (process/spawn (proc-fn []))]
    (<! (async/timeout 50))
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

(def-proc-test ^:parallel exit-self-trapping-exits
  (let [done (async/chan)]
    (process/spawn-opt
     (proc-fn []
       (process/exit (process/self) :normal)
       (is (= [:exit [(process/self) :normal]] (<! (await-message 50)))
           (str "exit with reason :normal must send exit"
                " message to process trapping exits"))
       (async/close! done))
     {:flags {:trap-exit true}})
    (await-completion! done 100))
  (let [done (async/chan)]
    (process/spawn-opt
     (proc-fn []
       (process/exit (process/self) :abnormal-1)
       (is (= [:exit [(process/self) :abnormal-1]]
              (<! (await-message 50)))
           "exit must send exit message to process trapping exits")
       (process/exit (process/self) :abnormal-2)
       (is (= [:exit [(process/self) :abnormal-2]] (<! (await-message 50)))
           "exit must send exit message to process trapping exits")
       (async/close! done))
     {:flags {:trap-exit true}})
    (await-completion! done 150))
  (let [done (async/chan)]
    (process/spawn-opt
     (proc-fn []
       (process/exit (process/self) :kill)
       (is (match (<! (await-message 50)) [:noproc _] :ok)
           "exit with reason :kill must close inbox of process trapping exits")
       (async/close! done))
     {:flags {:trap-exit true}})
    (await-completion! done 100)))

(def-proc-test ^:parallel exit-self-not-trapping-exits
  (let [done (async/chan)]
    (process/spawn
     (proc-fn []
       (process/exit (process/self) :normal)
       (is (= :timeout (<! (await-message 100)))
           (str "exit with reason :normal must do nothing"
                " to process not trapping exits"))
       (async/close! done)))
    (await-completion! done 200))
  (let [done (async/chan)]
    (process/spawn
     (proc-fn []
       (process/exit (process/self) :abnormal)
       (is (match (<! (await-message 50)) [:noproc _] :ok)
           (str "exit with any reason except :normal must close"
                " inbox of proces not trapping exits"))
       (async/close! done)))
    (await-completion! done 100))
  (let [done (async/chan)]
    (process/spawn
     (proc-fn []
       (process/exit (process/self) :kill)
       (is (match (<! (await-message 50)) [:noproc _] :ok)
           (str "exit with reason :kill must close inbox of process"
                " not trapping exits"))
       (async/close! done)))
    (await-completion! done 100)))

;; Excluded from 0.5.0-alpha due to changed behavior of
;; `(exit [pid reason])`. Called on self doesn't ensure the process'
;; exit reason is the same as passed to `exit` (when trap-exit is false).
#_(def-proc-test ^:parallel exit-self-reason-is-process-exit-reason
    (process/flag :trap-exit true)
    (let [pfn (proc-fn [] (process/exit (process/self) :abnormal))
          pid (process/spawn-link pfn)]
      (is (= [:exit [pid :abnormal]] (<! (await-message 100)))
          "process exit reason must be the one passed to exit call")))

; See issue #12 on github
(def-proc-test ^:parallel link-call-does-not-increase-exit-message-delivery-time
  (process/flag :trap-exit true)
  (let [done (async/chan)
        pfn (proc-fn []
              (is (await-completion! done 50))
              (process/exit (process/self) :abnormal)
              (process/receive!
               _ (is false "process must exit")))
        pid (process/spawn pfn)]
    (process/link pid)
    (async/close! done)
    (is (= [:exit [pid :abnormal]] (<! (await-message 100)))
        "process exit reason must be the one passed to exit call")))

(def-proc-test ^:parallel exit-kill-reason-is-killed
  (process/flag :trap-exit true)
  (let [done (async/chan)
        pid (process/spawn-link (proc-fn [] (is (await-completion! done 100))))]
    (process/exit pid :kill)
    (is (= [:exit [pid :killed]] (<! (await-message 50)))
        (str "process exit reason must be :killed when exit is"
             " called with reason :kill"))
    (async/close! done)))

(def-proc-test ^:parallel exit-kill-reason-is-killed
  (process/flag :trap-exit true)
  (let [pid (process/spawn-link
             (proc-fn [] (process/exit (process/self) :kill)))]
    (is (= [:exit [pid :killed]] (<! (await-message 50)))
        (str "process exit reason must be :killed when exit is"
             " called with reason :kill"))))

(def-proc-test ^:parallel exit-kill-reason-is-killed
;(otplike.proc-util/execute-proc!!
  (process/flag :trap-exit true)
  (let [pid (process/spawn-link (proc-fn [] (process/exit :kill)))]
    (is (= [:exit [pid :kill]] (<! (await-message 50)))
        (str "process exit reason must be :killed when exit is"
             " called with reason :kill"))))

; TODO
;(deftest ^:parallel exit-kill-does-not-propagate)

(def-proc-test ^:parallel
  exit-sends-exit-message-containing-pid-of-the-target-process
  (let [done (async/chan)
        test-pid (process/self)
        pfn (proc-fn []
              (is (= [:exit [test-pid :abnormal]]
                     (<! (await-message 50)))
                  (str "trapping exits process must receive exit message"
                       " containing its pid, after exit was called for the"
                       " process"))
              (async/close! done))
        pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
    (process/exit pid :abnormal)
    (await-completion! done 100)))

(deftest exit-fails-when-called-by-exiting-process)

;; ====================================================================
;; (flag [flag value])

(def-proc-test ^:parallel flag-trap-exit-true-makes-process-to-trap-exits
  (let [done1 (async/chan)
        done2 (async/chan)
        test-pid (process/self)
        pfn (proc-fn []
              (process/flag :trap-exit true)
              (async/close! done1)
              (is (= [:exit [test-pid :normal]]
                     (<! (await-message 50)))
                  (str "flag :trap-exit set to true in process must"
                       " make process to trap exits"))
              (async/close! done2))
        pid (process/spawn pfn)]
    (await-completion! done1 50)
    (match (process/exit pid :normal) true :ok)
    (await-completion! done2 100)))

(def-proc-test ^:parallel flag-trap-exit-false-makes-process-not-to-trap-exits
  (let [done1 (async/chan)
        done2 (async/chan)
        pfn (proc-fn []
              (process/flag :trap-exit false)
              (async/close! done1)
              (is (= :timeout (<! (await-message 50)))
                  (str "flag :trap-exit set to false in process must"
                       " make process not to trap exits"))
              (async/close! done2))
        pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
    (await-completion! done1 50)
    (match (process/exit pid :normal) true :ok)
    (await-completion! done2 100)))

(def-proc-test ^:parallel flag-trap-exit-switches-trapping-exit
  (let [done1 (async/chan)
        done2 (async/chan)
        done3 (async/chan)
        test-pid (process/self)
        pfn (proc-fn []
              (process/flag :trap-exit true)
              (async/close! done1)
              (is (= [:exit [test-pid :abnormal]]
                     (<! (await-message 50)))
                  (str "flag :trap-exit set to true in process must"
                       " make process to trap exits"))
              (process/flag :trap-exit false)
              (async/close! done2)
              (is (match (<! (await-message 50)) [:noproc _] :ok)
                  (str "flag :trap-exit switched second time in process"
                       " must make process to switch trapping exits"))
              (async/close! done3))
        pid (process/spawn pfn)]
    (await-completion! done1 50)
    (match (process/exit pid :abnormal) true :ok)
    (await-completion! done2 50)
    (match (process/exit pid :abnormal) true :ok)
    (await-completion! done3 100)))

(deftest ^:parallel flag-trap-exit-returns-old-value
  (let [done (async/chan)
        pfn (proc-fn []
              (is (= true (process/flag :trap-exit false))
                  "setting flag :trap-exit must return its previous value")
              (is (= false (process/flag :trap-exit false))
                  "setting flag :trap-exit must return its previous value")
              (is (= false (process/flag :trap-exit true))
                  "setting flag :trap-exit must return its previous value")
              (is (= true (process/flag :trap-exit true))
                  "setting flag :trap-exit must return its previous value")
              (async/close! done))]
    (process/spawn-opt pfn {:flags {:trap-exit true}})
    (await-completion!! done 50)))

(deftest ^:parallel flag-throws-on-unknown-flag
  (let [done (async/chan)
        pfn (proc-fn []
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
    (process/spawn pfn)
    (await-completion!! done 50)))

(deftest ^:parallel flag-throws-when-called-not-in-process-context
  (is (thrown? Exception (process/flag :trap-exit true))
      "flag must throw when called not in process context")
  (is (thrown? Exception (process/flag :trap-exit false))
      "flag must throw when called not in process context"))

(deftest flag-fails-when-called-by-exiting-process)

;; ====================================================================
;; (registered [])

(deftest ^:eftest/synchronized
  registered-returns-empty-seq-when-nothing-registered
  (is (empty? (process/registered))
      "registered must return empty seq of names when nothing registered"))

(deftest ^:eftest/synchronized registered-returns-registered-names
  (let [n1 (uuid-keyword)
        n2 (uuid-keyword)
        n3 (uuid-keyword)
        registered #{n1 n2 n3}
        done (async/chan)
        pfn (proc-fn [] (is (await-completion! done 50)))]
    (process/spawn-opt pfn {:register n1})
    (process/spawn-opt pfn {:register n2})
    (process/spawn-opt pfn {:register n3})
    (is (= registered (process/registered))
        "registered must return registered names")
    (async/close! done)))

(deftest ^:eftest/synchronized
  registered-returns-empty-seq-after-registered-terminated
  (let [pfn (proc-fn [])]
    (process/spawn-opt pfn {:register (uuid-keyword)})
    (process/spawn-opt pfn {:register (uuid-keyword)})
    (process/spawn-opt pfn {:register (uuid-keyword)})
    (<!! (async/timeout 50))
    (is (empty? (process/registered))
        (str "registered must return empty seq after all registered processes"
             " had terminated"))))

;; ====================================================================
;; (link [pid])

(deftest ^:parallel link-returns-true
  (let [pid (process/spawn (proc-fn []))
        _ (<!! (async/timeout 50))
        done (async/chan)
        pfn (proc-fn []
              (is (= true (process/link pid))
                  "link must return true when called on terminated process")
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 50))
  (let [done1 (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done1 50))))
        done2 (async/chan)
        pfn (proc-fn []
              (is (= true (process/link pid))
                  "link must return true when called on alive process")
              (async/close! done1)
              (async/close! done2))]
    (process/spawn pfn)
    (await-completion!! done2 50))
  (let [done1 (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done1 50))))
        done2 (async/chan)
        pfn (proc-fn []
              (is (= true (process/link pid))
                  "link must return true when called on alive process")
              (async/close! done1)
              (async/close! done2))]
    (process/spawn pfn)
    (await-completion!! done2 50)))

(deftest ^:parallel link-throws-when-called-not-in-process-context
  (is (thrown? Exception (process/link (process/spawn (proc-fn []))))
      "link must throw when called not in process context")
  (let [pfn (proc-fn [] (<! (async/timeout 50)))]
    (is (thrown? Exception (process/link (process/spawn pfn)))
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

(def-proc-test ^:parallel link-creates-link-with-alive-process-not-trapping-exits
  (let [done1 (async/chan)
        done2 (async/chan)
        pfn2 (proc-fn []
               (is (match (<! (await-message 50)) [:noproc _] :ok)
                   (str "process must exit when linked process exits"
                        " with reason other than :normal"))
               (async/close! done2))
        pid2 (process/spawn pfn2)
        pfn1 (proc-fn []
               (process/link pid2)
               (async/close! done1)
               (is (match (<! (await-message 50)) [:noproc _] :ok)
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1)]
    (await-completion! done1 50)
    (process/exit pid1 :abnormal)
    (await-completion! done2 50)))

(def-proc-test ^:parallel link-creates-link-with-alive-process-trapping-exits
  (let [done1 (async/chan)
        done2 (async/chan)
        ch (async/chan)
        pfn2 (proc-fn []
               (is
                (match (async/alts! [ch (async/timeout 50)])
                  [(pid :guard process/pid?) ch]
                  (is (= [:exit [pid :abnormal]]
                         (<! (await-message 50)))
                      (str "process trapping exits must get exit message"
                           " when linked process exits with reason"
                           " other than :normal"))))
               (async/close! done2))
        pid2 (process/spawn-opt pfn2 {:flags {:trap-exit true}})
        pfn1 (proc-fn []
               (process/link pid2)
               (async/close! done1)
               (is (match (<! (await-message 100)) [:noproc _] :ok)
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1)]
    (async/>! ch pid1)
    (async/close! ch)
    (await-completion! done1 50)
    (process/exit pid1 :abnormal)
    (await-completion! done2 50)))

(def-proc-test ^:parallel link-multiple-times-work-as-single-link
  (let [done1 (async/chan)
        done2 (async/chan)
        pfn2 (proc-fn []
               (is (match (<! (await-message 100)) [:noproc _] :ok)
                   (str "process must exit when linked process exits"
                        " with reason other than :normal"))
               (async/close! done2))
        pid2 (process/spawn pfn2)
        pfn1 (proc-fn []
               (process/link pid2)
               (process/link pid2)
               (process/link pid2)
               (async/close! done1)
               (is (match (<! (await-message 50)) [:noproc _] :ok)
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1)]
    (await-completion! done1 50)
    (process/exit pid1 :abnormal)
    (await-completion! done2 100))
  (let [done1 (async/chan)
        done2 (async/chan)
        ch (async/chan)
        pfn2 (proc-fn []
               (is
                (match (async/alts! [ch (async/timeout 100)])
                  [(pid :guard process/pid?) ch]
                  (is (= [:exit [pid :abnormal]]
                         (<! (await-message 100)))
                      (str "process trapping exits must get exit message"
                           " when linked process exits with reason"
                           " other than :normal"))))
               (async/close! done2))
        pid2 (process/spawn-opt pfn2 {:flags {:trap-exit true}})
        pfn1 (proc-fn []
               (process/link pid2)
               (process/link pid2)
               (process/link pid2)
               (async/close! done1)
               (is (match (<! (await-message 50)) [:noproc _] :ok)
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1)]
    (async/>! ch pid1)
    (async/close! ch)
    (await-completion! done1 50)
    (process/exit pid1 :abnormal)
    (await-completion! done2 50)))

(def-proc-test ^:parallel
  link-creates-exactly-one-link-when-called-multiple-times
  (let [done1 (async/chan)
        done2 (async/chan)
        pfn2 (proc-fn []
               (is (= :timeout (<! (await-message 100)))
                   (str "process must not exit when linked process was"
                        " unlinked before exit with reason"
                        " other than :normal"))
               (async/close! done2))
        pid2 (process/spawn-opt pfn2 {:flags {:trap-exit true}})
        pfn1 (proc-fn []
               (process/link pid2)
               (process/link pid2)
               (process/link pid2)
               (process/unlink pid2)
               (async/close! done1)
               (is (match (<! (await-message 50)) [:noproc _] :ok)
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1)]
    (await-completion! done1 50)
    (process/exit pid1 :abnormal)
    (await-completion! done2 150)))

(deftest ^:parallel link-does-not-affect-processes-linked-to-normally-exited-one
  (let [done (async/chan)
        pfn2 (proc-fn []
               (is (= :timeout (<! (await-message 100)))
                   (str "process must not exit when linked process exits"
                        " with reason :normal"))
               (async/close! done))
        pid2 (process/spawn pfn2)
        pfn1 (proc-fn [] (process/link pid2))
        pid1 (process/spawn pfn1)]
    (await-completion!! done 200)))

(deftest ^:parallel linking-to-terminated-process-sends-exit-message
  (let [done (async/chan)
        pid2 (process/spawn (proc-fn []))
        _ (<!! (async/timeout 50))
        pfn1 (proc-fn []
               (try
                 (process/link pid2)
                 (is (= [:exit [pid2 :noproc]]
                        (<! (await-message 50)))
                     (str "linking to terminated process must either"
                          " throw or send exit message to process"
                          " trapping exits"))
                 (catch Exception _e :ok))
               (async/close! done))
        pid1 (process/spawn-opt pfn1 {:flags {:trap-exit true}})]
    (await-completion!! done 100)))

(deftest ^:parallel linking-to-terminated-process-causes-exit
  (let [done (async/chan)
        pfn2 (proc-fn [])
        pid2 (process/spawn pfn2)
        _ (<!! (async/timeout 50))
        pfn1 (proc-fn []
               (try
                 (process/link pid2)
                 (is (match (<! (await-message 50)) [:noproc _] :ok)
                     (str "linking to terminated process must either"
                          " throw or close inbox of process"
                          " not trapping exits"))
                 (catch Exception _e :ok))
               (async/close! done))
        pid1 (process/spawn pfn1)]
    (await-completion!! done 100)))

(deftest ^:parallel link-to-self-does-not-throw
  (let [done (async/chan)
        pfn (proc-fn []
              (is (process/link (process/self))
                  "link to self must not throw when process is alive")
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 50)))

(deftest linked-process-receives-exited-process-pid
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [] (is (await-completion! done1 100)))
        pid1 (process/spawn pfn1)
        pfn2 (proc-fn []
               (process/link pid1)
               (<! (async/timeout 50))
               (async/close! done1)
               (is (= [:exit [pid1 :normal]] (<! (await-message 50)))
                   (str "process 1 linked to process 2, must receive exit "
                        " message containing pid of a proces 2, after process 2"
                        " terminated"))
               (async/close! done))]
    (process/spawn-opt pfn2 {:flags {:trap-exit true}})
    (await-completion!! done 100))
  (let [done (async/chan)
        pid (process/spawn (proc-fn []))
        pfn (proc-fn []
              (<! (async/timeout 50))
              (process/link pid)
              (is (= [:exit [pid :noproc]]
                     (<! (await-message 50)))
                  (str "process 1 tried to link terminated process 2, must"
                       " receive exit message containing pid of a proces 2"
                       " when trapping exits"))
              (async/close! done))]
    (process/spawn-opt pfn {:flags {:trap-exit true}})
    (await-completion!! done 100)))

(deftest link-fails-when-called-by-exiting-process)

;; ====================================================================
;; (unlink [pid])

(deftest ^:parallel unlink-removes-link-to-alive-process
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [] (is (await-completion! done1 200))
               (throw (Exception.
                       (str "TEST: terminating abnormally to test"
                            " unlink removes link to alive process"))))
        pid (process/spawn pfn1)
        pfn2 (proc-fn []
               (process/link pid)
               (<! (async/timeout 50))
               (process/unlink pid)
               (<! (async/timeout 50))
               (async/close! done1)
               (is (= :timeout (<! (await-message 100)))
                   (str "abnormally failed unlinked process must"
                        " not affect previously linked process"))
               (async/close! done))]
    (process/spawn pfn2)
    (await-completion!! done 300)))

(deftest ^:parallel unlink-returns-true-if-link-exists
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [] (is (await-completion! done1 100)))
        pid (process/spawn pfn1)
        pfn2 (proc-fn []
               (process/link pid)
               (<! (async/timeout 50))
               (is (= true (process/unlink pid)) "unlink must return true")
               (async/close! done1)
               (async/close! done))]
    (process/spawn pfn2)
    (await-completion!! done 200)))

(deftest ^:parallel unlink-returns-true-there-is-no-link
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [] (is (await-completion! done1 50)))
        pid (process/spawn pfn1)
        pfn2 (proc-fn []
               (is (= true (process/unlink pid)) "unlink must return true")
               (async/close! done1)
               (async/close! done))]
    (process/spawn pfn2)
    (await-completion!! done 100)))

(deftest ^:parallel unlink-self-returns-true
  (let [done (async/chan)
        pfn (proc-fn []
              (is (= true (process/unlink (process/self)))
                  "unlink must return true")
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 50)))

(deftest ^:parallel unlink-terminated-process-returns-true
  (let [done (async/chan)
        pid (process/spawn (proc-fn []))
        pfn2 (proc-fn []
               (<! (async/timeout 50))
               (is (= true (process/unlink pid)) "unlink must return true")
               (async/close! done))]
    (process/spawn pfn2)
    (await-completion!! done 100))
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [] (is (await-completion! done1 50)))
        pid (process/spawn pfn1)
        pfn2 (proc-fn []
               (process/link pid)
               (async/close! done1)
               (<! (async/timeout 50))
               (is (= true (process/unlink pid)) "unlink must return true")
               (async/close! done))]
    (process/spawn-opt pfn2 {:flags {:trap-exit true}})
    (await-completion!! done 100)))

(deftest ^:parallel unlink-throws-on-not-a-pid
  (let [done (async/chan)
        pfn2 (proc-fn []
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
    (process/spawn pfn2)
    (await-completion!! done 50)))

(deftest ^:parallel unlink-throws-when-calld-not-in-process-context
  (is (thrown? Exception
               (process/unlink (process/spawn (proc-fn []))))
      "unlink must throw when called not in process context"))

(deftest ^:parallel unlink-prevents-exit-message-after-linked-process-failed
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn []
               (is (await-completion! done1 100))
               (throw (Exception.
                       (str "TEST: terminating abnormally to test unlink"
                            " prevents exit message when previously linked"
                            " process have exited abnormally"))))
        pid (process/spawn pfn1)
        pfn2 (proc-fn []
               (process/flag :trap-exit true)
               (process/link pid)
               (<! (async/timeout 50))
               (process/unlink pid)
               (async/close! done1)
               (is (= :timeout (<! (await-message 100)))
                   (str "exit message from abnormally terminated linked"
                        " process, terminated before unlink have been"
                        " called, must not appear in process' inbox after"
                        " unlink have been called"))
               (async/close! done))]
    (process/spawn pfn2)
    (await-completion!! done 200)))

(def-proc-test ^:parallel
  unlink-does-not-prevent-exit-message-after-it-has-been-placed-into-inbox
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn []
               (is (await-completion! done1 100))
               (process/exit :abnormal))
        pid (process/spawn pfn1)
        pfn2 (proc-fn []
               (process/flag :trap-exit true)
               (process/link pid)
               (<! (async/timeout 50))
               (async/close! done1)
               (<! (async/timeout 50))
               (process/unlink pid)
               (is (= [:exit [pid :abnormal]] (<! (await-message 50)))
                   (str "exit message from abnormally terminated linked"
                        " process, terminated before unlink have been"
                        " called, must not appear in process' inbox after"
                        " unlink have been called"))
               (async/close! done))]
    (process/spawn pfn2)
    (await-completion! done 200)))

(deftest ^:parallel unlink-does-not-affect-process-when-called-multiple-times
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn []
               (is (= :timeout (<! (await-message 100))))
               (is (await-completion! done1 100))
               (throw (Exception.
                       (str "TEST: terminating abnormally to test unlink"
                            " doesn't affect process when called multiple"
                            " times"))))
        pid (process/spawn pfn1)
        pfn2 (proc-fn []
               (process/flag :trap-exit true)
               (process/link pid)
               (<! (async/timeout 50))
               (process/unlink pid)
               (process/unlink pid)
               (process/unlink pid)
               (process/unlink pid)
               (process/unlink pid)
               (async/close! done1)
               (is (= :timeout (<! (await-message 100)))
                   (str "exit message from abnormally terminated linked"
                        " process, terminated before unlink have been"
                        " called, must not appear in process' inbox after"
                        " unlink have been called"))
               (async/close! done))]
    (process/spawn pfn2)
    (await-completion!! done 200)))

(deftest unlink-fails-when-called-by-exiting-process)

;; ====================================================================
;; (spawn-opt [proc-fun args options])

; TODO test spawn linking to calling process called not by process
; TODO test registering process with nil name

(deftest ^:parallel spawn-calls-pfn
  (let [done (async/chan)]
    (process/spawn-opt (proc-fn [] (async/close! done)) [] {})
    (is (await-completion!! done 50) "spawn must call process fn")))

(deftest ^:parallel spawn-calls-pfn-with-arguments
  (let [done (async/chan)
        pfn (proc-fn [a b]
              (is (and (= :a a) (= 1 b))
                  "spawn must pass process fn params")
              (async/close! done))]
    (process/spawn-opt pfn [:a 1] {})
    (await-completion!! done 50)))

(deftest ^:parallel spawn-returns-process-pid
  (let [done (async/chan 1)
        pfn (proc-fn [] (async/put! done (process/self)))
        pid1 (process/spawn-opt pfn [] {})
        pid2 (match (async/alts!! [done (async/timeout 50)])
               [pid done] pid)]
    (is (= pid1 pid2) (str "spawn must return the same pid as returned"
                           " by self called from started process"))
    (async/close! done)))

(deftest ^:parallel spawn-throws-on-illegal-arguments
  (is (thrown? Exception (process/spawn-opt nil [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn-opt 1 [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn-opt "fn" [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn-opt {} [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn-opt [] [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn-opt #{} [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn-opt '() [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) 1 {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) #{} {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) {} {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) 1 {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) "args" {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) (fn []) {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] nil))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] 1))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] "opts"))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] [1 2 3]))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] '(1)))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] #{}))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] {:flags 1}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] {:flags true}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] {:flags "str"}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] {:flags []}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] {:flags #{}}))
      "spawn must throw if :flags option is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] {:flags '()}))
      "spawn must throw if :flags option is not a map")
  (let [pid (process/spawn-opt (proc-fn []) [] {})]
    (is (thrown? Exception (process/spawn-opt (proc-fn []) [] {:register pid}))
        "spawn must throw if :register name is a pid")))

(defn- process-exits-abnormally-when-pfn-throws* [ex]
  (proc-util/execute-proc!!
   (process/flag :trap-exit true)
   (let [done (async/chan)
         ex-class-name (.getName (class ex))
         pid (process/spawn-opt (proc-fn [] (throw ex)) [] {:link true})]
     (is (match (<! (await-message 50))
           [:exit [pid [:exception {:class ex-class-name}]]]
           :ok)
         "process must exit when its proc-fn throws any exception"))))

(deftest ^:parallel process-exits-abnormally-when-pfn-throws
  (process-exits-abnormally-when-pfn-throws* (InterruptedException.))
  (process-exits-abnormally-when-pfn-throws* (RuntimeException.))
  (process-exits-abnormally-when-pfn-throws* (Error.)))

(defn- process-exits-abnormally-when-pfn-arity-doesnt-match-args* [pfn args]
  (proc-util/execute-proc!!
   (process/flag :trap-exit true)
   (let [pid (process/spawn-opt pfn args {:link true})]
     (is (match (<! (await-message 50)) [:exit [pid [:exception _]]] :ok)
         "process must exit when arguments to its proc-fn dont match arity"))))

(deftest ^:parallel process-exits-abnormally-when-pfn-arity-doesnt-match-args
  (process-exits-abnormally-when-pfn-arity-doesnt-match-args*
   (proc-fn []) [:a 1])
  (process-exits-abnormally-when-pfn-arity-doesnt-match-args*
   (proc-fn [a b]) [])
  (process-exits-abnormally-when-pfn-arity-doesnt-match-args*
   (proc-fn [b]) [:a :b]))

;(proc-util/execute-proc!!
(def-proc-test ^:parallel process-is-linked-when-proc-fn-starts
  (process/flag :trap-exit true)
  (let [pid (process/spawn-opt (proc-fn []) {:link true})]
    (is (= [:exit [pid :normal]] (<! (await-message 50)))
        "spawn-linked process must be linked before its proc-fn starts")))

(deftest ^:parallel spawn-passes-opened-read-port-to-pfn-as-inbox
  (let [done (async/chan)
        pfn (proc-fn []
              (is (= :timeout (<! (await-message 100)))
                  "spawn must pass opened ReadPort as inbox to process fn")
              (async/close! done))]
    (process/spawn-opt pfn [] {})
    (await-completion!! done 150)))

(deftest ^:parallel spawned-process-is-reachable
  (let [done (async/chan)
        pfn (proc-fn []
              (is (= [:message :msg] (<! (await-message 50)))
                  (str "messages sent to spawned process must appear"
                       " in its inbox"))
              (async/close! done))
        pid (process/spawn-opt pfn [] {})]
    (! pid :msg)
    (await-completion!! done 50)))

; options

(def-proc-test ^:parallel spawned-process-traps-exits-according-options
  (let [done (async/chan)
        pfn (proc-fn []
              (is (match (<! (await-message 50)) [:noproc _] :ok)
                  (str "process not trapping exits must exit"
                       " when it receives exit signal"))
              (async/close! done))
        pid (process/spawn-opt pfn {})]
    (process/exit pid :abnormal)
    (await-completion! done 100))
  (let [done (async/chan)
        test-pid (process/self)
        pfn (proc-fn []
              (is (= [:exit [test-pid :abnormal]]
                     (<! (await-message 50)))
                  (str "process spawned option :trap-exit set to true"
                       " must trap exits"))
              (async/close! done))
        pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
    (process/exit pid :abnormal)
    (await-completion! done 50)))

(deftest ^:parallel spawned-process-registered-according-options
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn (proc-fn [] (is (await-completion! done 50)))]
    (is (not ((process/registered) reg-name)))
    (process/spawn-opt pfn [] {:register reg-name})
    (is ((process/registered) reg-name)
        "spawn must register proces when called with :register option")
    (async/close! done)))

(deftest ^:parallel spawn-opt--throws-when-already-registered
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn (proc-fn [] (is (await-completion! done 50)))]
    (process/spawn-opt pfn [] {:register reg-name})
    (is
     (thrown? Exception
              (process/spawn-opt (proc-fn []) [] {:register reg-name})))
    (async/close! done)))

(deftest ^:parallel spawn-opt--doesnt-call-proc-fn-when-already-registered
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn (proc-fn [] (is (await-completion! done 100)))]
    (process/spawn-opt pfn [] {:register reg-name})
    (process/ex-catch
     (process/spawn-opt
      (proc-fn []
        (is false
            "proc fn must not be called if the name is already registered"))
      []
      {:register reg-name}))
    (async/timeout 50)
    (async/close! done)))

(deftest ^:eftest/synchronized spawn-opt--doesnt-start-when-already-registered
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn (proc-fn [] (is (await-completion! done 50)))]
    (process/spawn-opt pfn [] {:register reg-name})
    (let [procs (process/processes)]
      (process/ex-catch
       (process/spawn-opt (proc-fn []) [] {:register reg-name}))
      (is (= procs (process/processes))))
    (async/close! done)))

(def-proc-test ^:parallel spawned-process-does-not-trap-exits-by-default
  (let [done (async/chan)
        pfn (proc-fn []
              (is (match (<! (await-message 50)) [:noproc _] :ok)
                  (str "process' inbox must be closed after exit with"
                       " reason other than :normal was called if process"
                       " doesn't trap exits"))
              (async/close! done))
        pid (process/spawn-opt pfn [] {})]
    (process/exit pid :abnormal)
    (await-completion! done 50)))

(deftest ^:parallel spawn-opt--spawned-process-receives-parent-exit-reason
  (let [done (async/chan)
        pfn1 (proc-fn []
               (is (match (<! (await-message 50))
                     [:exit [(_ :guard process/pid?) :normal]] :ok)
                   (str "spawn-link(ed) process must receive exit message"
                        " with the reason of parent's exit"))
               (async/close! done))
        pfn2 (proc-fn []
               (is (process/spawn-opt
                    pfn1 {:link true :flags {:trap-exit true}})
                   "test failed"))]
    (process/spawn pfn2)
    (await-completion!! done 200))
  (let [done (async/chan)
        pfn1 (proc-fn []
               (is (match (<! (await-message 50))
                     [:exit [(_ :guard process/pid?) :abnormal]] :ok)
                   (str "spawn-link(ed) process must receive exit message"
                        " with the reason of parent's exit"))
               (async/close! done))
        pfn2 (proc-fn []
               (is (process/spawn-opt
                    pfn1 {:link true :flags {:trap-exit true}})
                   "test failed")
               (process/exit :abnormal))]
    (process/spawn pfn2)
    (await-completion!! done 200))
  (let [done (async/chan)
        pfn1 (proc-fn []
               (is (match (<! (await-message 50))
                     [:exit [(_ :guard process/pid?) :killed]] :ok)
                   (str "spawn-link(ed) process must receive exit message"
                        " with the reason of parent's exit"))
               (async/close! done))
        pfn2 (proc-fn []
               (is (process/spawn-opt
                    pfn1 {:link true :flags {:trap-exit true}})
                   "test failed")
               (process/exit (process/self) :kill))]
    (process/spawn pfn2)
    (await-completion!! done 200)))

; TODO:
;(deftest ^:parallel process-exit-reason-is-proc-fn-return-value) ?
;(deftest ^:parallel spawned-process-available-from-within-process-by-reg-name)
;(deftest ^:parallel there-are-no-residue-of-process-after-proc-fun-throws)

;; ====================================================================
;; (spawn-link [proc-fun args options])

(def-proc-test ^:parallel spawn-link-links-to-spawned-process
  (let [done (async/chan)
        pfn (proc-fn [] (process/exit :abnormal))
        pfn1 (proc-fn []
               (process/spawn-link pfn [])
               (is (match (<! (await-message 50)) [:noproc _] :ok :else nil)
                   (str "process #1 not trapping exits and spawned process #2"
                        " with spawn-link must exit after process #2 exited"))
               (async/close! done))]
    (process/spawn pfn1)
    (await-completion! done 100))
  (let [done (async/chan)
        pfn (proc-fn [] (process/exit :abnormal))
        pfn1 (proc-fn []
               (let [pid (process/spawn-link pfn)]
                 (is (=  [:exit [pid :abnormal]] (<! (await-message 50)))
                     (str "process #1 trapping exits and spawned process #2"
                          " with spawn-link must receive exit message after"
                          " process #2 exited")))
               (async/close! done))]
    (process/spawn-opt pfn1 {:flags {:trap-exit true}})
    (await-completion! done 100)))

(def-proc-test ^:parallel spawn-link-throws-when-called-from-exited-process
  (let [done (async/chan)
        pfn (proc-fn [] (is false "process must not be started"))
        pfn1 (proc-fn []
               (process/exit (process/self) :abnormal)
               (<! (async/timeout 50))
               (is (thrown? Exception (process/spawn-link pfn)))
               (async/close! done))]
    (process/spawn pfn1)
    (await-completion! done 200)))

(deftest spawn-link-fails-when-called-by-exiting-process)

; TODO check if spawn-link works like spawn

;; ====================================================================
;; (monitor [pid-or-name])

(def-proc-test ^:parallel down-message-is-sent-when-monitored-process-exits
  (let [done (async/chan)
        pfn1 (proc-fn [] (is (match (<! (await-message 100)) [:noproc _] :ok)))
        pid1 (process/spawn pfn1)
        pfn2 (proc-fn []
               (let [mref (process/monitor pid1)]
                 (<! (async/timeout 50))
                 (process/exit pid1 :abnormal)
                 (is (= [:down [mref pid1 :abnormal]]
                        (<! (await-message 50)))
                     (str "process must receive :DOWN message containing proper"
                          " monitor ref, pid and reason when monitored by pid"
                          " process exits abnormally"))
                 (async/close! done)))]
    (process/spawn pfn2)
    (await-completion! done 150))
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [] (is (await-completion! done1 100)))
        pid1 (process/spawn pfn1)
        pfn2 (proc-fn []
               (let [mref (process/monitor pid1)]
                 (<! (async/timeout 50))
                 (async/close! done1)
                 (is (= [:down [mref pid1 :normal]] (<! (await-message 50)))
                     (str "process must receive :DOWN message containing proper"
                          " monitor ref, pid and reason when monitored by pid"
                          " process exits normally"))
                 (async/close! done)))]
    (process/spawn pfn2)
    (await-completion! done 150))
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn1 (proc-fn [] (is (match (<! (await-message 100)) [:noproc _] :ok)))
        pid1 (process/spawn-opt pfn1 {:register reg-name})
        pfn2 (proc-fn []
               (let [mref (process/monitor reg-name)]
                 (<! (async/timeout 50))
                 (process/exit pid1 :kill)
                 (is (= [:down [mref reg-name :killed]]
                        (<! (await-message 50)))
                     (str "process must receive :DOWN message containing proper"
                          " monitor ref, pid and reason when monitored by pid"
                          " process is killed"))
                 (async/close! done)))]
    (process/spawn pfn2)
    (await-completion! done 150))
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn1 (proc-fn [] (is (match (<! (await-message 100)) [:noproc _] :ok)))
        pid1 (process/spawn-opt pfn1 {:register reg-name})
        pfn2 (proc-fn []
               (let [mref (process/monitor reg-name)]
                 (<! (async/timeout 50))
                 (process/exit pid1 :abnormal)
                 (is (= [:down [mref reg-name :abnormal]]
                        (<! (await-message 50)))
                     (str "process must receive :DOWN message containing proper"
                          " monitor ref, reg-name and reason when monitored by"
                          " reg-name process exits abnormally"))
                 (async/close! done)))]
    (process/spawn pfn2)
    (await-completion! done 150)))

(deftest ^:parallel monitor-returns-ref
  (let [done (async/chan)
        pfn (proc-fn []
              (is (process/ref? (process/monitor (process/self)))
                  (str "monitor must return ref when called with self"
                       " as argument"))
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 50))
  (let [done (async/chan)
        pfn1 (proc-fn [] (is (await-completion! done 50)))
        pid1 (process/spawn pfn1)
        pfn (proc-fn []
              (is (process/ref? (process/monitor pid1))
                  (str "monitor must return ref when called with pid"
                       " of alive process as argument"))
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 50))
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn1 (proc-fn [] (is (await-completion! done 50)))
        pid1 (process/spawn-opt pfn1 {:register reg-name})
        pfn (proc-fn []
              (is (process/ref? (process/monitor reg-name))
                  (str "monitor must return ref when called with"
                       " reg-name of alive process as argument"))
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 50))
  (let [done (async/chan)
        pid (process/spawn (proc-fn []))
        pfn (proc-fn []
              (<! (async/timeout 50))
              (is (process/ref? (process/monitor pid))
                  (str "monitor must return ref when called with pid"
                       " of terminated process as argument"))
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 100))
  (let [reg-name (uuid-keyword)
        done (async/chan)
        _pid (process/spawn-opt (proc-fn []) {:register reg-name})
        pfn (proc-fn []
              (<! (async/timeout 50))
              (is (process/ref? (process/monitor reg-name))
                  (str "monitor must return ref when called with"
                       " reg-name of terminated process as argument"))
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 100))
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn (proc-fn []
              (is (process/ref? (process/monitor reg-name))
                  (str "monitor must return ref when called with"
                       " never registered name as argument"))
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 50)))

(deftest ^:parallel monitor-throws-when-called-not-in-process-context
  (is (thrown? Exception (process/monitor (uuid-keyword)))
      (str "monitor must throw when called not in process context with"
           " never registered name"))
  (let [pid (process/spawn (proc-fn []))]
    (<!! (async/timeout 50))
    (is (thrown? Exception (process/monitor pid))
        (str "monitor must throw when called not in process context with"
             " terminated process pid")))
  (let [reg-name (uuid-keyword)
        pid (process/spawn-opt (proc-fn []) {:register reg-name})]
    (<!! (async/timeout 50))
    (is (thrown? Exception (process/monitor reg-name))
        (str "monitor must throw when called not in process context with"
             " terminated process reg-name")))
  (let [done (async/chan)
        pfn (proc-fn [] (is (await-completion! done 50)))
        pid (process/spawn pfn)]
    (is (thrown? Exception (process/monitor pid))
        (str "monitor must throw when called not in process context with"
             " alive process pid"))
    (async/close! done))
  (let [done (async/chan)
        reg-name (uuid-keyword)
        pfn (proc-fn [] (is (await-completion! done 50)))
        pid (process/spawn-opt pfn {:register reg-name})]
    (is (thrown? Exception (process/monitor reg-name))
        (str "monitor must throw when called not in process context with"
             " alive process reg-name"))
    (async/close! done)))

(deftest ^:parallel
  monitoring-terminated-process-sends-down-message-with-noproc-reason
  (let [done (async/chan)
        pfn (proc-fn []
              (let [reg-name (uuid-keyword)
                    mref (process/monitor reg-name)]
                (is (= [:down [mref reg-name :noproc]]
                       (<! (await-message 50)))
                    (str "process must receive :DOWN message with :noproc"
                         " reason when monitor called with never registered"
                         " name")))
              (let [pid (process/spawn (proc-fn []))
                    _ (<! (async/timeout 50))
                    mref (process/monitor pid)]
                (is (= [:down [mref pid :noproc]]
                       (<! (await-message 50)))
                    (str "process must receive :DOWN message with :noproc"
                         " reason when monitor called with terminated process"
                         " pid")))
              (let [reg-name (uuid-keyword)
                    _pid (process/spawn-opt (proc-fn []) {:register reg-name})
                    _ (<! (async/timeout 50))
                    mref (process/monitor reg-name)]
                (is (= [:down [mref reg-name :noproc]]
                       (<! (await-message 50)))
                    (str "process must receive :DOWN message with :noproc"
                         " reason when monitor called with terminated process"
                         " reg-name")))
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 300)))

(deftest ^:parallel
  monitored-process-does-not-receive-down-message-when-monitoring-proc-dies
  (let [done (async/chan)
        pfn (proc-fn []
              (let [self (process/self)]
                (process/spawn (proc-fn [] (process/monitor self)))
                (is (= :timeout (<! (await-message 100)))
                    (str "monitored process must not receive any message"
                         " when monitoring process dies")))
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 200)))

(deftest ^:parallel monitor-called-multiple-times-creates-as-many-monitors
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn [] (is (await-completion! done1 100)))
        pid (process/spawn pfn1)
        pfn (proc-fn []
              (let [mrefs (doall (take 3 (repeatedly #(process/monitor pid))))
                    _ (<! (async/timeout 50))
                    _ (async/close! done1)
                    msgs []
                    msgs (conj msgs (<! (await-message 50)))
                    msgs (conj msgs (<! (await-message 50)))
                    msgs (conj msgs (<! (await-message 50)))]
                (is (= (set (map (fn [mref] [:down [mref pid :normal]])
                                 mrefs))
                       (set msgs))
                    (str "monitoring process must receive one :DOWN message"
                         " per one monitor call")))
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 200)))

(def-proc-test ^:parallel
  down-message-is-not-sent-when-process-trapping-exits-receives-exit-message
  (let [done (async/chan)
        pid-chan (async/chan)
        test-pid (process/self)
        pfn1 (proc-fn []
               (match (await-completion! pid-chan 100)
                 [:ok pid]
                 (do
                   (is (= [:exit [pid :abnormal]]
                          (<! (await-message 100))))
                   (is (await-completion! done 200)))))
        pid1 (process/spawn-opt pfn1 {:flags {:trap-exit true}})
        pfn (proc-fn []
              (async/put! pid-chan (process/self))
              (process/monitor pid1)
              (<! (async/timeout 50))
              (process/exit pid1 :abnormal)
              (is (= :timeout (<! (await-message 100)))
                  (str "process must not receive :DOWN message when monitored"
                       " process trapping exits receives :EXIT message and"
                       " stays alive"))
              (async/close! done))]
    (process/spawn pfn)
    (await-completion! done 200)))

(deftest ^:parallel monitor-refs-are-not-equal
  (let [done (async/chan)
        pfn1 (proc-fn [] (is (await-completion! done 50)))
        pid (process/spawn pfn1)
        pfn (proc-fn []
              (is
               (= 3 (count (set (take 3 (repeatedly #(process/monitor pid))))))
               "monitor must return unique monitor refs")
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 50)))

(def-proc-test ^:parallel monitor-self-does-nothing
  (let [done (async/chan)
        done1 (async/chan)
        pfn (proc-fn []
              (process/monitor (process/self))
              (<! (async/timeout 50))
              (async/close! done1)
              (is (match (<! (await-message 50)) [:noproc _] :ok)
                  (str "process called monitor with self as argument must not"
                       " receive :DOWN message when exit is called for the"
                       " process"))
              (async/close! done))
        pid (process/spawn pfn)]
    (await-completion! done1 100)
    (process/exit pid :abnormal)
    (await-completion! done 50))
  (let [reg-name (uuid-keyword)
        done (async/chan)
        done1 (async/chan)
        pfn (proc-fn []
              (process/monitor reg-name)
              (<! (async/timeout 50))
              (async/close! done1)
              (is (match (<! (await-message 50)) [:noproc _] :ok)
                  (str "process called monitor with self reg-name as argument"
                       " must not receive :DOWN message when exit is called"
                       " for the process"))
              (async/close! done))
        pid (process/spawn-opt pfn {:register reg-name})]
    (await-completion! done1 100)
    (process/exit pid :abnormal)
    (await-completion! done 50)))

(deftest monitor-fails-when-called-by-exiting-process)

;; ====================================================================
;; (demonitor [mref])

(deftest ^:parallel demonitor-stops-monitoring
  (let [done (async/chan)
        done1 (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done1 150))))
        pfn (proc-fn []
              (let [mref (process/monitor pid)]
                (<! (async/timeout 50))
                (process/demonitor mref)
                (<! (async/timeout 50))
                (async/close! done1)
                (is (= :timeout (<! (await-message 100)))
                    (str "demonitor called after monitored process exited"
                         " must not affect monitoring process"))
                (async/close! done)))]
    (process/spawn pfn)
    (await-completion!! done 300)))

(deftest ^:parallel
  demonitor-called-after-monitored-process-exited-does-nothing
  (let [done (async/chan)
        done1 (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done1 100))))
        pfn (proc-fn []
              (let [mref (process/monitor pid)]
                (<! (async/timeout 50))
                (async/close! done1)
                (<! (async/timeout 50))
                (process/demonitor mref)
                (is (= [:down [mref pid :normal]] (<! (await-message 100)))
                    (str "demonitor called after monitored process exited"
                         " must not affect monitoring process"))
                (is (= :timeout (<! (await-message 100)))
                    "no messages expected")
                (async/close! done)))]
    (process/spawn pfn)
    (await-completion!! done 300)))

(deftest ^:parallel demonitor-called-multiple-times-does-nothing
  (let [done (async/chan)
        pfn (proc-fn []
              (is (= :timeout (<! (await-message 100)))
                  (str "demonitor called multiple times must not affect"
                       " monitored process")))
        pid (process/spawn pfn)
        pfn1 (proc-fn []
               (let [mref (process/monitor pid)]
                 (is (process/demonitor mref))
                 (is (process/demonitor mref)
                     (str "demonitor called multiple times must not affect"
                          " calling process"))
                 (is (process/demonitor mref)
                     (str "demonitor called multiple times must not affect"
                          " calling process"))
                 (is (= :timeout (<! (await-message 100)))
                     (str "demonitor called multiple times must not affect"
                          " monitoring process"))
                 (async/close! done)))]
    (process/spawn pfn1)
    (await-completion!! done 200)))

(deftest ^:parallel demonitor-returns-true
  (let [done (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done 50))))
        pfn (proc-fn []
              (let [mref (process/monitor pid)]
                (is (= true (process/demonitor mref))
                    (str "demonitor must return true when called while"
                         " monitored process is alive"))
                (is (= true (process/demonitor mref))
                    "demonitor must return true when called multiple times")
                (is (= true (process/demonitor mref))
                    "demonitor must return true when called multiple times")
                (async/close! done)))]
    (process/spawn pfn)
    (await-completion!! done 50))
  (let [done (async/chan)
        pid (process/spawn (proc-fn []))
        pfn (proc-fn []
              (let [mref (process/monitor pid)]
                (<! (async/timeout 50))
                (is (= true (process/demonitor mref))
                    (str "demonitor must return true when called when"
                         " monitored process have terminated"))
                (async/close! done)))]
    (process/spawn pfn)
    (await-completion!! done 150))
  (let [done (async/chan)
        pfn1 (proc-fn [] (is (await-completion! done 150)))
        pid1 (process/spawn pfn1)
        pfn2 (proc-fn []
               (let [[_ mref] (<! (await-message 100))]
                 (is (process/ref? mref))
                 (is (= true (process/demonitor mref))
                     (str "demonitor must return true when called byj"
                          " process other than process called monitor"))
                 (async/close! done)))
        pid2 (process/spawn pfn2)
        pfn3 (proc-fn []
               (let [mref (process/monitor pid1)]
                 (<! (async/timeout 50))
                 (! pid2 mref)
                 (is (await-completion! done 100))))]
    (process/spawn pfn3)
    (await-completion!! done 200)))

(deftest ^:parallel demonitor-throws-on-not-a-monitor-ref
  (let [pfn (proc-fn []
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
    (process/spawn pfn)))

(deftest ^:parallel demonitor-throws-when-called-not-in-process-context
  (let [done (async/chan)
        mref-chan (async/chan 1)
        pid (process/spawn (proc-fn [] (is (await-completion! done 100))))
        pfn (proc-fn []
              (>! mref-chan (process/monitor pid))
              (is (await-completion! done 100)))]
    (process/spawn pfn)
    (match (async/alts!! [mref-chan (async/timeout 100)])
      [(mref :guard #(process/ref? %)) mref-chan]
      (is (thrown? Exception (process/demonitor mref))
          (str "demonitor must throw when called not in process context"
               " when both processes are alive")))
    (async/close! mref-chan)
    (async/close! done))
  (let [done (async/chan)
        mref-chan (async/chan 1)
        pid (process/spawn (proc-fn []))
        pfn (proc-fn []
              (>! mref-chan (process/monitor pid))
              (is (await-completion! done 100)))]
    (process/spawn pfn)
    (match (async/alts!! [mref-chan (async/timeout 100)])
      [(mref :guard #(process/ref? %)) mref-chan]
      (is (thrown? Exception (process/demonitor mref))
          (str "demonitor must throw when called not in process context"
               " when monitoring processes is alive")))
    (async/close! mref-chan)
    (async/close! done))
  (let [done (async/chan 1)
        mref-chan (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done 100))))]
    (process/spawn (proc-fn [] (>! mref-chan (process/monitor pid))))
    (<!! (async/timeout 50))
    (match (async/alts!! [mref-chan (async/timeout 100)])
      [(mref :guard #(process/ref? %)) mref-chan]
      (is (thrown? Exception (process/demonitor mref))
          (str "demonitor must throw when called not in process context"
               " when monitored processes is alive")))
    (async/close! mref-chan)
    (async/close! done)))

(deftest ^:parallel
  demonitor-does-nothing-when-monitoring-started-by-other-process
  (let [done (async/chan)
        done1 (async/chan)
        pfn1 (proc-fn []
               (let [[_ mref] (<! (await-message 100))]
                 (is (process/ref? mref) "testf failed")
                 (is (process/demonitor mref)
                     (str "demonitor return true when called with ref"
                          " of monitoring started by other process"))
                 (<! (async/timeout 50))
                 (async/close! done1)))
        pid1 (process/spawn pfn1)
        pfn2 (proc-fn [] (is (await-completion! done1 200)))
        pid2 (process/spawn pfn2)
        pfn3 (proc-fn []
               (let [mref (process/monitor pid2)]
                 (! pid1 mref)
                 (is (= [:down [mref pid2 :normal]]
                        (<! (await-message 100)))
                     (str "demonitor called by process other than process"
                          " started monitoring")))
               (async/close! done))]
    (process/spawn pfn3)
    (await-completion!! done 200)))

(deftest ^:parallel demonitor-self-does-nothing
  (let [done (async/chan)
        pfn (proc-fn []
              (let [mref (process/monitor (process/self))]
                (is (process/demonitor mref)
                    "demonitor self must return true and do nothing"))
              (is (= :timeout (<! (await-message 100)))
                  "demonitor self must do nothing")
              (async/close! done))]
    (process/spawn pfn)
    (await-completion!! done 150))
  (let [reg-name (uuid-keyword)
        done (async/chan)
        pfn (proc-fn []
              (let [mref (process/monitor reg-name)]
                (is (process/demonitor mref)
                    "demonitor self must return true and do nothing"))
              (is (= :timeout (<! (await-message 100)))
                  "demonitor self must do nothing")
              (async/close! done))]
    (process/spawn-opt pfn {:register reg-name})
    (await-completion!! done 150)))

(deftest demonitor-fails-when-called-by-exiting-process)

;; ====================================================================
;; (demonitor [mref opts])

(deftest ^:parallel demonitor-flushes-down-message
  (let [done (async/chan)
        done1 (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done1 100))))
        pfn (proc-fn []
              (let [mref (process/monitor pid)]
                (async/close! done1)
                (<! (async/timeout 50))
                (process/demonitor mref {:flush true})
                (is (= :timeout (<! (await-message 100)))
                    (str "demonitor called after monitored process exited"
                         " must not affect monitoring process"))
                (async/close! done)))]
    (process/spawn pfn)
    (await-completion!! done 300)))

(deftest ^:parallel demonitor-doesnot-flush-when-flush-is-false
  (let [done (async/chan)
        done1 (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done1 100))))
        pfn (proc-fn []
              (let [mref (process/monitor pid)]
                (async/close! done1)
                (<! (async/timeout 50))
                (process/demonitor mref {:flush false})
                (is (= [:down [mref pid :normal]]
                       (<! (await-message 100)))
                    (str "demonitor called after monitored process exited"
                         " must not affect monitoring process"))
                (async/close! done)))]
    (process/spawn pfn)
    (await-completion!! done 300)))

(deftest ^:parallel demonitor-works-when-there-is-no-message-to-flush
  (let [done (async/chan)
        done1 (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done1 300))))
        pfn (proc-fn []
              (let [mref (process/monitor pid)]
                (process/demonitor mref {:flush true})
                (async/close! done1)
                (is (= :timeout (<! (await-message 100)))
                    (str "demonitor called after monitored process exited"
                         " must not affect monitoring process"))
                (async/close! done)))]
    (process/spawn pfn)
    (await-completion!! done 300))
  (let [done (async/chan)
        done1 (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done1 300))))
        pfn (proc-fn []
              (let [mref (process/monitor pid)]
                (process/demonitor mref)
                (async/close! done1)
                (process/demonitor mref {:flush true})
                (is (= :timeout (<! (await-message 100)))
                    (str "demonitor called after monitored process exited"
                         " must not affect monitoring process"))
                (async/close! done)))]
    (process/spawn pfn)
    (await-completion!! done 300)))

;; ====================================================================
;; (receive! [clauses])

(deftest ^:parallel receive-receives-message
  (let [done (async/chan)
        pfn (proc-fn []
              (is (= :msg (process/receive! msg msg))
                  "receive! must receive message sent to a process")
              (async/close! done))
        pid (process/spawn pfn)]
    (! pid :msg)
    (await-completion!! done 150)))

(deftest ^:parallel receive-receives-nil
  (let [done (async/chan)
        pfn (proc-fn []
              (is (nil? (process/receive! msg msg))
                  "receive! must receive nil sent to a process")
              (async/close! done))
        pid (process/spawn pfn)]
    (! pid nil)
    (await-completion!! done 150))
  (let [done (async/chan)
        pfn (proc-fn []
              (process/receive! nil :ok)
              (async/close! done))
        pid (process/spawn pfn)]
    (! pid nil)
    (is (await-completion!! done 150)
        "receive! must receive nil sent to a process")))

(def-proc-test ^:parallel receive-throws-if-process-exited
  (let [done (async/chan)
        pfn (proc-fn []
              (process/exit (process/self) :abnormal)
              (is (thrown? Exception (process/receive! _ :ok)))
              (async/close! done))
        pid (process/spawn pfn)]
    (await-completion! done 150)))

(deftest ^:parallel receive-executes-after-clause
  (let [done (async/chan)
        pfn (proc-fn []
              (is (= :timeout (process/receive!
                               _ :error
                               (after 10
                                      :timeout)))
                  "receive! must execute 'after' clause on timeout")
              (async/close! done))
        pid (process/spawn pfn)]
    (await-completion!! done 150)))

(deftest ^:parallel receive!-requires-one-or-more-message-patterns
  (if-clojure-version [10]
                      (try
                        (eval `(process/receive!))
                        (catch clojure.lang.Compiler$CompilerException e
                          (let [cause (.getCause e)
                                msg (.getMessage cause)]
                            (is (re-find #"requires one or more message patterns" msg)
                                "exception must contain an expanation"))))
                      (try
                        (eval `(process/receive! (after 10 :ok)))
                        (catch clojure.lang.Compiler$CompilerException e
                          (let [cause (.getCause e)
                                msg (.getMessage cause)]
                            (is (re-find #"requires one or more message patterns" msg)
                                "exception must contain an expanation")))))
  (if-clojure-version [8 9]
                      (is (thrown-with-msg?
                           AssertionError
                           #"requires one or more message patterns"
                           (eval `(process/receive!))))
                      (is (thrown-with-msg?
                           AssertionError
                           #"requires one or more message patterns"
                           (eval `(process/receive! (after 10 :ok)))))))

(deftest ^:parallel receive-accepts-infinity-timeout
  (let [done (async/chan)
        pfn (proc-fn []
              (process/receive!
               :done (async/close! done)
               (after :infinity
                      (is false
                          (str "expression for infinite timeout must never"
                               " be executed")))))
        pid (process/spawn pfn)]
    (<!! (async/timeout 100))
    (! pid :done)
    (await-completion!! done 100)))

(deftest ^:parallel receive-accepts-0-timeout
  (let [done (async/chan)
        pfn (proc-fn []
              (process/receive!
               _ (is false
                     (str "expression for message must not be executed"
                          " when there is no message in inbox"))
               (after 0
                      (async/close! done))))
        pid (process/spawn pfn)]
    (await-completion!! done 50))
  (let [done1 (async/chan)
        done2 (async/chan)
        pfn (proc-fn []
              (await-completion! done1 150)
              (process/receive!
               :done (async/close! done2)
               (after 0
                      (is false
                          (str "expression for 0 timeout must not be executed"
                               " when there is a message in inbox")))))
        pid (process/spawn pfn)]
    (! pid :done)
    (<!! (async/timeout 50))
    (async/close! done1)
    (await-completion!! done2 50)))

(deftest receive!-fails-when-called-by-exiting-process)

;; ====================================================================
;; (async [& body]) / (await x)

(deftest ^:parallel async-returns-async-value
  (is (process/async? (process/async)))
  (is (process/async? (process/async 1)))
  (is (process/async? (process/async (throw (ex-info "test" {}))))))

(deftest ^:parallel async-executes-body-asynchronously
  (let [done (async/chan)
        done1 (async/chan)]
    (process/async
     (is (await-completion! done 50)
         "async's body must observe changes made after async was called")
     (async/close! done1))
    (async/close! done)
    (await-completion!! done1 50)))

(deftest ^:parallel async-allows-parking
  (let [done (async/chan)
        done1 (async/chan)]
    (process/async
     (let [timeout-ms 50
           timeout (async/timeout timeout-ms)]
       (match (async/alts! [done timeout])
         [_ done] (async/close! done1)
         [nil timeout] (is false (format "timeout %d" timeout-ms)))))
    (async/close! done)
    (await-completion!! done1 50)))

(deftest ^:parallel await-returns-value-of-async-expr
  (is (= 123 (process/await!! (process/async 123)))
      "await!! must return the value of async's expression")
  (is (nil? (process/await!! (process/async nil)))
      "await!! must return the value of async's expression")
  (let [done (async/chan)]
    (async/go
      (is (= 123 (process/await! (process/async 123)))
          "await! must return the value of async's expression")
      (is (nil? (process/await! (process/async nil)))
          "await! must return the value of async's expression")
      (async/close! done))
    (await-completion!! done 50)))

(deftest ^:parallel await-propagates-exceptions-of-async-expr
  (let [async (process/async (throw (ex-info "msg 123" {})))]
    (is (thrown?
         clojure.lang.ExceptionInfo #"^msg 123$"
         (process/await!! async))))
  (let [async (process/async (throw (ex-info "msg 123" {})))]
    (let [done (async/go
                 (is (thrown?
                      clojure.lang.ExceptionInfo #"^msg 123$"
                      (process/await! async))))]
      (await-completion!! done 50))))

(deftest ^:parallel await-throws-on-illegal-argument
  (is (thrown? IllegalArgumentException (process/await!! 1)))
  (is (thrown? IllegalArgumentException (process/await!! nil)))
  (is (thrown? IllegalArgumentException (process/await!! {})))
  (is (thrown? IllegalArgumentException (process/await!! [])))
  (is (thrown? IllegalArgumentException (process/await!! '())))
  (is (thrown? IllegalArgumentException (process/await!! "str")))
  (is (thrown? IllegalArgumentException (process/await!! (Object.))))
  (is (thrown? IllegalArgumentException (process/await!! 'a)))
  (is (thrown? IllegalArgumentException (process/await!! :a)))
  (let [done (async/chan)]
    (async/go
      (is (thrown? IllegalArgumentException (process/await! 1)))
      (is (thrown? IllegalArgumentException (process/await! nil)))
      (is (thrown? IllegalArgumentException (process/await! {})))
      (is (thrown? IllegalArgumentException (process/await! [])))
      (is (thrown? IllegalArgumentException (process/await! '())))
      (is (thrown? IllegalArgumentException (process/await! "str")))
      (is (thrown? IllegalArgumentException (process/await! (Object.))))
      (is (thrown? IllegalArgumentException (process/await! 'a)))
      (is (thrown? IllegalArgumentException (process/await! :a)))
      (async/close! done))
    (await-completion!! done 50)))

(deftest ^:parallel await?!-returns-value-of-async-expr
  (let [done
        (async/go
          (is (= 123 (process/await! (process/async 123)))
              "await?! must return the value of async's expression")
          (is (nil? (process/await! (process/async nil)))
              "await?! must return the value of async's expression"))]
    (await-completion!! done 150)))

(deftest ^:parallel await?!-returns-regular-value
  (let [done (async/go
               (is (= 123 (process/await?! 123))
                   "await?! must return regular value")
               (is (nil? (process/await?! nil))
                   "await?! must return regular value"))]
    (await-completion!! done 150)))

(deftest ^:parallel await?!-propagates-exceptions-of-async-expr
  (let [async (process/async (throw (ex-info "msg 123" {})))]
    (let [done (async/go
                 (is (thrown?
                      clojure.lang.ExceptionInfo #"^msg 123$"
                      (process/await?! async))))]
      (await-completion!! done 50))))

;; ====================================================================
;; (selective-receive! [clauses])

(deftest ^:parallel selective-receive!-receives-message
  (let [done (async/chan)
        pfn (proc-fn []
              (is (= :msg (process/selective-receive! msg msg))
                  "selective-receive! must receive message sent to a process")
              (async/close! done))
        pid (process/spawn pfn)]
    (! pid :msg)
    (await-completion!! done 150)))

(deftest ^:parallel selective-receive!-receives-nil
  (let [done (async/chan)
        pfn (proc-fn []
              (is (nil? (process/selective-receive! msg msg))
                  "selective-receive! must receive nil sent to a process")
              (async/close! done))
        pid (process/spawn pfn)]
    (! pid nil)
    (await-completion!! done 150))
  (let [done (async/chan)
        pfn (proc-fn []
              (process/selective-receive! nil :ok)
              (async/close! done))
        pid (process/spawn pfn)]
    (! pid nil)
    (is (await-completion!! done 150)
        "selective-receive! must receive nil sent to a process"))
  (let [done (async/chan)
        pfn (proc-fn []
              (process/selective-receive! nil :ok)
              (async/close! done))
        pid (process/spawn pfn)]
    (! pid :msg)
    (! pid nil)
    (is (await-completion!! done 150)
        "selective-receive! must receive nil sent to a process")))

(def-proc-test ^:parallel selective-receive!-throws-if-process-exited
  (let [done (async/chan)
        pfn (proc-fn []
              (process/exit (process/self) :abnormal)
              (is (thrown? Exception (process/selective-receive! _ :ok)))
              (async/close! done))
        pid (process/spawn pfn)]
    (await-completion! done 150)))

(deftest ^:parallel selective-receive!-executes-after-clause
  (let [done (async/chan)
        pfn (proc-fn []
              (is (= :timeout (process/selective-receive!
                               _ :error
                               (after 10
                                      :timeout)))
                  "selective-receive! must execute 'after' clause on timeout")
              (async/close! done))
        pid (process/spawn pfn)]
    (await-completion!! done 150)))

(deftest ^:parallel selective-receive!-requires-one-or-more-message-patterns
  (if-clojure-version [10]
                      (try
                        (eval `(process/selective-receive!))
                        (catch clojure.lang.Compiler$CompilerException e
                          (let [cause (.getCause e)
                                msg (.getMessage cause)]
                            (is (re-find #"requires one or more message patterns" msg)
                                "exception must contain an explanation"))))
                      (try
                        (eval `(process/selective-receive! (after 10 :ok)))
                        (catch clojure.lang.Compiler$CompilerException e
                          (let [cause (.getCause e)
                                msg (.getMessage cause)]
                            (is (re-find #"requires one or more message patterns" msg)
                                "exception must contain an explanation")))))
  (if-clojure-version [8 9]
                      (is (thrown-with-msg?
                           AssertionError
                           #"requires one or more message patterns"
                           (eval `(process/selective-receive!))))
                      (is (thrown-with-msg?
                           AssertionError
                           #"requires one or more message patterns"
                           (eval `(process/selective-receive! (after 10 :ok)))))))

(deftest ^:parallel selective-receive!-accepts-infinity-timeout
  (let [done (async/chan)
        pfn (proc-fn []
              (process/selective-receive!
               :done (async/close! done)
               (after :infinity
                      (is false
                          (str "expression for infinite timeout must never"
                               " be executed")))))
        pid (process/spawn pfn)]
    (<!! (async/timeout 100))
    (! pid :done)
    (await-completion!! done 100)))

(deftest ^:parallel selective-receive!-accepts-0-timeout
  (let [done (async/chan)
        pfn (proc-fn []
              (process/selective-receive!
               _ (is false
                     (str "expression for message must not be executed"
                          " when there is no message in inbox"))
               (after 0
                      (async/close! done))))
        pid (process/spawn pfn)]
    (await-completion!! done 50))
  (let [done1 (async/chan)
        done2 (async/chan)
        pfn (proc-fn []
              (await-completion! done1 150)
              (process/selective-receive!
               :done (async/close! done2)
               (after 0
                      (is false
                          (str "expression for 0 timeout must not be executed"
                               " when there is a message in inbox")))))
        pid (process/spawn pfn)]
    (! pid :done)
    (<!! (async/timeout 50))
    (async/close! done1)
    (await-completion!! done2 50)))

(deftest ^:parallel selective-receive!-leaves-non-matching-messages-untouched
  (let [done (async/chan)
        pfn (proc-fn []
              (is (process/selective-receive! :msg3 :ok)
                  "selective-receive! must receive the matching message")
              (is (= :msg1 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (is (= :msg2 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (is (= :msg4 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (is (= :timeout (<! (await-message 50)))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (async/close! done))
        pid (process/spawn pfn)]
    (! pid :msg1)
    (! pid :msg2)
    (! pid :msg3)
    (! pid :msg4)
    (await-completion!! done 150))

  (let [done (async/chan)
        done1 (async/chan)
        pfn (proc-fn []
              (is (await-completion! done1 50))
              (is (process/selective-receive! :msg3 :ok
                                              (after 0 false))
                  "selective-receive! must receive the matching message")
              (is (= :msg1 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (is (= :msg2 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (is (= :msg4 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (is (= :timeout (<! (await-message 50)))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (async/close! done))
        pid (process/spawn pfn)]
    (! pid :msg1)
    (! pid :msg2)
    (! pid :msg3)
    (! pid :msg4)
    (async/close! done1)
    (await-completion!! done 150))

  (let [done (async/chan)
        pfn (proc-fn []
              (is (process/selective-receive!
                   :unmatching false
                   (after 100
                          :ok))
                  "selective-receive! must not receive unmatching message")
              (is (= :msg1 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (is (= :msg2 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (is (= :msg3 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (async/close! done))
        pid (process/spawn pfn)]
    (! pid :msg1)
    (! pid :msg2)
    (! pid :msg3)
    (await-completion!! done 250))

  (let [done (async/chan)
        done1 (async/chan)
        pfn (proc-fn []
              (is (await-completion! done1 50))
              (is (process/selective-receive!
                   :unmatching false
                   (after 0 :ok))
                  "selective-receive! must not receive unmatching message")
              (is (= :msg1 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (is (= :msg2 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (is (= :msg3 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (async/close! done))
        pid (process/spawn pfn)]
    (! pid :msg1)
    (! pid :msg2)
    (! pid :msg3)
    (async/close! done1)
    (await-completion!! done 250)))

(deftest selective-receive!-fails-when-called-by-exiting-process)

;; ====================================================================
;; (process-info pid key-or-keys)

(deftest process-info-returns-the-info-item
  (let [done (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done 1000))))]
    (is (= [:status :running] (process/process-info pid :status))
        (str "process-info must return tagged info tuple"
             " when argument is info key"))
    (async/close! done)))

(deftest process-info-returns-info-items-ordered-as-keys
  (let [info-keys [:status :registered-name :messages :monitors]
        done (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done 1000))))
        info-items (process/process-info pid info-keys)]
    (is (= info-keys (map first info-items))
        (str "process-info must return tagged info tuples in the same order"
             " as info keys passed as argument"))
    (async/close! done)))

(deftest process-info-accepts-duplicate-info-keys
  (let [info-keys [:status
                   :status
                   :registered-name
                   :status
                   :messages
                   :monitors
                   :messages]
        done (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done 1000))))
        info-items (process/process-info pid info-keys)]
    (is (= info-keys (map first info-items))
        (str "process-info must return tagged info tuples in the same order"
             " as info keys passed as argument"))
    (async/close! done)))

(deftest process-info-returns-correct-info
  ;; TODO
  ;; empty links on no links
  ;; links when pid initiated linking
  ;; links when other pid initiated linking

  ;; empty monitors on no monitoring
  ;; monitors on monitoring
  ;; duplicate monitors on multiple monitoring of the same process

  ;; empty monitored-by when is not monitored
  ;; monitored-by when is monitored
  ;; repeated pids in monitored-by when monitored multiple times by the same process

  ;; reg-name when is registered
  ;; nil reg-name when is not resitered

  ;; running status
  ;; waiting status
  ;; exiting status
  ;; status changes

  ;; correct life time ms

  ;; intial call: when proc-defn is used
  ;; intial call: when proc-fn is used
  ;; intial call: when named proc-fn is used
  ;; intial call: args

  ;; correct message queue len
  ;; message queue len changes

  ;; no messages
  ;; some messages
  ;; messages change on receive!
  ;; messages change on selective-receive!

  ;; correct flags
  ;; flags change

  [:links
   :monitors
   :monitored-by
   :registered-name
   :status
   :life-time-ms
   :initial-call
   :message-queue-len
   :messages
   :flags])

(deftest process-info-returns-nil-on-exited-process-pid
  (let [pid (process/spawn (proc-fn []))]
    (Thread/sleep 50)
    (is (= nil (process/process-info pid [:status]))
        "process-info must return nil on exited process pid")
    (is (= nil (process/process-info pid :status))
        "process-info must return nil on exited process pid")
    (is (= nil (process/process-info pid :message-queue-len))
        "process-info must return nil on exited process pid")
    (is (= nil (process/process-info pid :monitored-by))
        "process-info must return nil on exited process pid")))

(deftest process-info-throws-on-illegal-arguments
  (let [done (async/chan)
        pid (process/spawn (proc-fn [] (is (await-completion! done 1000))))]
    (is (thrown? Exception (process/process-info nil [:status]))
        "process-info must throw on nil pid")
    (is (thrown? Exception (process/process-info pid nil))
        "process-info must throw on nil info-key(s)")
    (is (thrown? Exception (process/process-info pid :unexisting-info-key))
        "process-info must throw on unexisting info-key(s)")
    (is (thrown? Exception (process/process-info pid 11))
        "process-info must throw on unexisting info-key(s)")
    ;; ensure that arguments are checked even on exited process pid
    (async/close! done)
    (Thread/sleep 50)
    (is (thrown? Exception (process/process-info pid [[]]))
        "process-info must throw on unexisting info-key(s)")
    (is (thrown? Exception (process/process-info pid "unexisting-info-key"))
        "process-info must throw on unexisting info-key(s)")
    (is (thrown? Exception (process/process-info pid [:unexisting-info-key]))
        "process-info must throw on unexisting info-key(s)")
    (is (thrown? Exception (process/process-info pid [1]))
        "process-info must throw on unexisting info-key(s)")
    (is (thrown? Exception
                 (process/process-info
                  pid [:status :unexisting-info-key]))
        "process-info must throw on unexisting info-key(s)")
    (is (thrown? Exception
                 (process/process-info
                  pid [:status :unexisting-info-key :unexisting-info-key]))
        "process-info must throw on unexisting info-key(s)")))

;; ====================================================================
;; (processes)

(deftest processes-returns-a-list-of-pids-including-alive-process-pid
  (let [done (async/chan)
        pid1 (process/spawn (proc-fn [] (is (await-completion! done 1000))))
        pid2 (process/spawn (proc-fn [] (is (await-completion! done 1000))))
        pids (set (process/processes))]
    (is (contains? pids pid1)
        (str "list of pids returned by (processes) must contain"
             " the pid of alive process"))
    (is (contains? pids pid2)
        (str "list of pids returned by (processes) must contain"
             " the pid of alive process"))
    (async/close! done)))

(deftest processes-doesnot-return-exited-process-pids
  (let [done (async/chan)
        pid1 (process/spawn (proc-fn []))
        pid2 (process/spawn (proc-fn []))
        _ (Thread/sleep 50)
        pids (set (process/processes))]
    (is (not (contains? pids pid1))
        (str "list of pids returned by (processes) must not contain"
             " the pid of existed process"))
    (is (not (contains? pids pid2))
        (str "list of pids returned by (processes) must not contain"
             " the pid of exited process"))
    (async/close! done)))

(def-proc-test processes-returns-a-list-with-exiting-process-pids-included
  (let [done (async/chan)
        pid1 (process/spawn (proc-fn [] (is (await-completion! done 1000))))
        pid2 (process/spawn (proc-fn [] (is (await-completion! done 1000))))]
    (process/exit pid1 :test)
    (process/exit pid2 :test)
    (<! (async/timeout 50))
    (let [pids (set (process/processes))]
      (is (contains? pids pid1)
          (str "list of pids returned by (processes) must contain"
               " the pid of exiting process"))
      (is (contains? pids pid2)
          (str "list of pids returned by (processes) must contain"
               " the pid of exiting process")))
    (async/close! done)))

;; ====================================================================
;; (alive?)

(def-proc-test alive?-returns-information-about-current-process
  (let [done1 (async/chan)
        done2 (async/chan)
        done (async/chan)
        pid (process/spawn
             (proc-fn []
               (is (process/alive?)
                   "alive? must return true when process is alive")
               (async/close! done1)
               (is (await-completion! done2 100))
               (async/<! (async/timeout 50))
               (is (not (process/alive?))
                   "alive? must return false when process is exiting")
               (async/close! done)))]
    (await-completion! done1 100)
    (process/exit pid :test)
    (async/close! done2)
    (await-completion! done 1000)))

;; ====================================================================
;; (alive? pid)

(def-proc-test alive?-returns-information-about-pid
  (let [done (async/chan)
        pid (process/spawn (proc-fn []
                             (is (await-completion! done 1000))))]
    (is (process/alive? pid) "alive? must return true when process is alive")
    (process/exit pid :test)
    (async/<! (async/timeout 50))
    (is (not (process/alive? pid))
        "alive? must return false when process is exiting")
    (async/close! done)
    (async/<! (async/timeout 50))
    (is (not (process/alive? pid))
        "alive? must return false when process has exited")))

;; ====================================================================
;; (map-async f async-value)

(deftest map-async-returns-async-value
  (let [av (process/async 0)]
    (is (process/async? (process/map-async inc av))
        "map-async must return async value")))

(deftest map-async-returns-value-transforming-the-result
  (let [av (process/async 0)
        av2 (process/map-async inc av)]
    (is (= 1 (process/await!! av2))
        "map async must transform the result applying provided functions"))
  (let [av (process/async 0)
        av2 (process/map-async inc av)
        av3 (process/map-async #(+ 2 %) av2)]
    (is (= 3 (process/await!! av3))
        "map async must transform the result applying provided functions")))

(deftest map-async-returns-value-applying-transformations-in-the-right-order
  (let [av (process/async 0)
        av2 (process/map-async inc av)
        av3 (process/map-async #(cons % [2 3]) av2)
        av4 (process/map-async #(apply str %) av3)]
    (is (= "123" (process/await!! av4))
        "map async must apply transformations in the right order")))

(deftest map-async-propagates-exceptions
  (let [av (process/async 0)
        av2 (process/map-async inc av)
        av3 (process/map-async (fn [_] (process/exit :test)) av2)
        av4 (process/map-async #(apply str %) av3)]
    (is (= [:EXIT :test] (process/ex-catch (process/await!! av4)))
        "map async must transform the result applying provided functions"))
  (let [av (process/async 0)
        av2 (process/map-async inc av)
        av3 (process/map-async #(throw (Exception. "test")) av2)
        av4 (process/map-async #(apply str %) av3)]
    (is (thrown? Exception "test" (process/await!! av4))
        "map async must transform the result applying provided functions")))

(deftest map-async-doesnot-change-the-initial-async-value
  (let [av (process/async 0)
        av2 (process/map-async inc av)
        _ (process/map-async #(process/exit :test) av2)]
    (is (= 1 (process/await!! av2))
        "map async must not change the initial async value")))

;; ====================================================================
;; (with-async bindings & body)

(deftest with-async-returns-async-value
  (let [av (process/async 0)]
    (is (process/async? (process/with-async [res av] (inc res)))
        "with-async must return async value"))
  (is (process/async?
       (process/with-async [res (process/async 0)]
         (inc res)))
      "with-async must return async value"))

(deftest with-async-returns-value-transforming-the-result
  (let [av (process/with-async [av (process/async 0)] (inc av))]
    (is (= 1 (process/await!! av))
        "map async must transform the result applying provided functions"))
  (let [av (process/with-async [res (process/async 0)] (inc res))
        av2 (process/with-async [res av] (+ 2 res))]
    (is (= 3 (process/await!! av2))
        "map async must transform the result applying provided functions")))

(deftest with-async-propagates-exceptions
  (let [av (process/with-async [res (process/async 0)] (inc res))
        av2 (process/with-async [_ av] (process/exit :test))
        av3 (process/with-async [res av2] (apply str res))]
    (is (= [:EXIT :test] (process/ex-catch (process/await!! av3)))
        "map async must transform the result applying provided functions"))
  (let [av (process/with-async [res (process/async 0)] (inc res))
        av2 (process/with-async [_ av] (throw (Exception. "test")))
        av3 (process/with-async [res av2] (apply str res))]
    (is (thrown? Exception "test" (process/await!! av3))
        "map async must transform the result applying provided functions")))
