(ns otplike.supervisor-test
  (:require [clojure.test :refer [is deftest]]
            [clojure.core.match :refer [match]]
            [clojure.pprint :as pprint]
            [clojure.core.async :as async :refer [<!! <! >! >!!]]
            [otplike.process :as process :refer [!]]
            [otplike.trace :as trace]
            [otplike.test-util :refer :all]
            [otplike.supervisor :as sup]
            [otplike.gen-server :as gs])
  (:import [otplike.gen_server IGenServer]))

;(otplike.trace/console-trace [otplike.trace/filter-crashed])

;; ====================================================================
;; (start-link [sup-fn args])

; TODO try different supervisor flags
;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:no-children
  (let [done (async/chan)
        init-fn (fn [_]
                  (async/close! done)
                  [:ok [{} []]])]
    (match (sup/start-link init-fn [nil])
      [:ok pid]
      (do
        (is (process/pid? pid)
            "start-link must return supervisor's pid")
        (is (await-completion done 50)
            "supervisor must call init-fn to get its spec")))))

; TODO try different child spec
; TODO try different supervisor flags
;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:single-child
  (let [sup-init-done (async/chan)
        server-init-done (async/chan)
        sup-flags {}
        server {:init (fn [_]
                        (async/close! server-init-done)
                        [:ok :state])}
        children-spec [{:id 1 :start [#(gs/start server [] {}) []]}]
        sup-spec [sup-flags children-spec]
        init-fn (fn [_]
                  (async/close! sup-init-done)
                  [:ok sup-spec])]
    (match (sup/start-link init-fn [nil])
      [:ok pid]
      (do
        (is (process/pid? pid)
            "start-link must return supervisor's pid")
        (is (await-completion sup-init-done 50)
            "supervisor must call init-fn to get its spec")
        (is (await-completion server-init-done 50)
            "supervisor must call child's start-fn to start it")))))

; TODO try different child spec
; TODO try different supervisor flags
;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:multiple-children
  (let [sup-init-done (async/chan)
        s1-init-done (async/chan)
        s2-init-done (async/chan)
        s3-init-done (async/chan)
        sup-flags {}
        make-child
        (fn [await-chan done-chan id]
          {:id id
           :start
           [#(gs/start
               {:init (fn [_]
                        (is (await-completion await-chan 50)
                            "supervisor must start children in spec's order")
                        (async/close! done-chan)
                        [:ok :state])}
               []
               {})
            []]})
        children-spec (map make-child
                           [sup-init-done s1-init-done s2-init-done]
                           [s1-init-done s2-init-done s3-init-done]
                           (range))
        sup-spec [sup-flags children-spec]
        init-fn (fn [_]
                  (async/close! sup-init-done)
                  [:ok sup-spec])]
    (match (sup/start-link init-fn [nil])
      [:ok pid]
      (do
        (is (process/pid? pid)
            "start-link must return supervisor's pid")
        (is (await-completion sup-init-done 50)
            "supervisor must call init-fn to get its spec")
        (is (await-completion s3-init-done 50)
            "supervisor must call child's start-fn to start it")))))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:duplicate-child-id
  (let [sup-init-done (async/chan)
        sup-flags {}
        make-child (fn [id]
                     {:id id
                      :start [#(gs/start
                                 {:init (fn [_]
                                          (is false "child must not be started")
                                          [:ok :state])}
                                 []
                                 {})
                              []]})
        children-spec (map make-child [:id1 :id2 :id2])
        sup-spec [sup-flags children-spec]
        init-fn (fn [_]
                  (async/close! sup-init-done)
                  [:ok sup-spec])]
    (match (process/ex-catch (sup/start-link init-fn [nil]))
      [:error [:bad-child-specs [:duplicate-child-id :id2]]]
      (is (await-completion sup-init-done 50)
          "supervisor must call init-fn to get its spec"))))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:bad-return:not-allowed-return
  (let [sup-init-done (async/chan)
        init-fn (fn [_]
                  (async/close! sup-init-done)
                  {:ok []})]
    (match (sup/start-link init-fn [nil])
           [:error [:bad-return _]]
           (do
             (is (thrown? Exception (process/self))
                 "process must exit after suprevisor/init error")
             (is (await-completion sup-init-done 50)
                 "supervisor must call init-fn to get its spec")))))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:bad-return:no-child-id
  (let [sup-init-done (async/chan)
        init-fn (fn [_]
                  (async/close! sup-init-done)
                  [:ok [{} [{:start
                             [#(gs/start
                                 {:init (fn [_]
                                          (is false "child must not be started")
                                          [:ok :state])}
                                 []
                                 {})
                              []]}]]])]
    (match (sup/start-link init-fn [nil])
           [:error [:bad-child-specs _]]
           (do
             (is (thrown? Exception (process/self))
                 "process must exit after suprevisor/init error")
             (is (await-completion sup-init-done 50)
                 "supervisor must call init-fn to get its spec")))))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:bad-return:bad-child-start-fn
  (let [sup-init-done (async/chan)
        init-fn (fn [_]
                  (async/close! sup-init-done)
                  [:ok [{} [{:id :child1
                             :start ["not a function" []]}]]])]
    (match (sup/start-link init-fn [nil])
           [:error [:bad-child-specs _]]
           (do
             (is (thrown? Exception (process/self))
                 "process must exit after suprevisor/init error")
             (is (await-completion sup-init-done 50)
                 "supervisor must call init-fn to get its spec")))))

(def-proc-test ^:parallel start-link:bad-return:bad-supervisor-flags
  (let [make-child (fn [id]
                     {:id id
                      :start [#(gs/start
                                 {:init (fn [_]
                                          (is false "child must not be started")
                                          [:ok :state])}
                                 []
                                 {})
                              []]})]
    (otplike.proc-util/execute-proc
      (let [sup-init-done (async/chan)
            sup-flags {:strategy "one-for-one"}
            children-spec (map make-child [:id1])
            sup-spec [sup-flags children-spec]
            init-fn (fn [_]
                      (async/close! sup-init-done)
                      [:ok sup-spec])]
        (match (is (sup/start-link init-fn [nil]))
               [:error [:bad-supervisor-flags _]]
               (is (await-completion sup-init-done 50)
                   "supervisor must call init-fn to get its spec"))))
    (otplike.proc-util/execute-proc
      (let [sup-init-done (async/chan)
            sup-flags {:intensity "2"}
            children-spec (map make-child [:id1])
            sup-spec [sup-flags children-spec]
            init-fn (fn [_]
                      (async/close! sup-init-done)
                      [:ok sup-spec])]
        (match (is (sup/start-link init-fn [nil]))
               [:error [:bad-supervisor-flags _]]
               (is (await-completion sup-init-done 50)
                   "supervisor must call init-fn to get its spec"))))
    (otplike.proc-util/execute-proc
      (let [sup-init-done (async/chan)
            sup-flags {:period :1}
            children-spec (map make-child [:id1])
            sup-spec [sup-flags children-spec]
            init-fn (fn [_]
                      (async/close! sup-init-done)
                      [:ok sup-spec])]
        (match (is (sup/start-link init-fn [nil]))
               [:error [:bad-supervisor-flags _]]
               (is (await-completion sup-init-done 50)
                   "supervisor must call init-fn to get its spec"))))
    (otplike.proc-util/execute-proc
      (let [sup-init-done (async/chan)
            sup-flags {:strategy "one-for-one"
                       :intensity "2"
                       :period :1}
            children-spec (map make-child [:id1])
            sup-spec [sup-flags children-spec]
            init-fn (fn [_]
                      (async/close! sup-init-done)
                      [:ok sup-spec])]
        (match (is (sup/start-link init-fn [nil]))
               [:error [:bad-supervisor-flags _]]
               (is (await-completion sup-init-done 50)
                   "supervisor must call init-fn to get its spec"))))))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:invalid-arguments
  (is (thrown? Exception (sup/start-link "not-fn" [nil]))
      "start-link must throw on invalid arguments")
  (is (thrown? Exception (sup/start-link (fn [_] [:ok [{} []]]) :arg1))
      "start-link must throw on invalid arguments"))

(defn test-start-link:child-init-returns-error [init-fn reason]
  (let [sup-init-done (async/chan)
        sup-flags {}
        children-spec [{:id :child-id
                        :start [#(gs/start {:init init-fn} [] {}) []]}]
        sup-spec [sup-flags children-spec]
        init-fn (fn [_]
                  (async/close! sup-init-done)
                  [:ok sup-spec])]
    (match (sup/start-link init-fn [nil])
           [:error [:shutdown [:failed-to-start-child :child-id reason]]]
           (is (await-completion sup-init-done 50)
               "supervisor must call init-fn to get its spec"))))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:child-init-returns-error:exit-normal
  (test-start-link:child-init-returns-error
    (fn [_] (process/exit :normal))
    :normal))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:child-init-returns-error:exit-abnormal
  (test-start-link:child-init-returns-error
    (fn [_] (process/exit :abnormal))
    :abnormal))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:child-init-returns-error:stop-with-reason
  (test-start-link:child-init-returns-error
    (fn [_] [:stop :some-reason])
    :some-reason))

;(def-proc-test ^:parallel
; start-link:multiple-children:first-child-init-returns-error
(otplike.proc-util/execute-proc
  (let [child1-done (async/chan)
        sup-init-done (async/chan)
        sup-flags {}
        error-child-init (fn [_]
                           (async/close! child1-done)
                           [:stop :abnormal])
        healthy-child-init (fn [_]
                              (is false "child must not be started")
                              [:ok :state])
        child-spec (fn [id init-fn]
                     {:id id
                      :start [#(gs/start {:init init-fn} [] {}) []]})
        children-spec [(child-spec :id1 error-child-init)
                       (child-spec :id2 healthy-child-init)
                       (child-spec :id3 healthy-child-init)]
        sup-spec [sup-flags children-spec]
        init-fn (fn [_]
                  (async/close! sup-init-done)
                  [:ok sup-spec])]
    (match (sup/start-link init-fn [nil])
           [:error [:shutdown [:failed-to-start-child :id1 :abnormal]]]
           (do
             (is (await-completion child1-done 50)
                 "first child must be started")
             (is (await-completion sup-init-done 50)
                 "supervisor must call init-fn to get its spec")))))

(def-proc-test ^:parallel
  start-link:multiple-children:middle-child-init-returns-error
;(otplike.proc-util/execute-proc
  (let [child1-init-done (async/chan)
        child1-terminate-done (async/chan)
        error-child-done (async/chan)
        sup-init-done (async/chan)
        sup-flags {}
        healthy-child1-init (fn [_]
                              (async/close! child1-init-done)
                              [:ok :state])
        child1-terminate
        (fn [reason _]
          (async/close! child1-terminate-done)
          (is (= :shutdown reason)
              "child must be stopped with :shutdown reason"))
        error-child-init (fn [_]
                           (async/close! error-child-done)
                           [:stop :abnormal])
        healthy-child2-init (fn [_]
                              (is false "child must not be started")
                              [:ok :state])
        child-spec (fn [id init-fn terminate-fn]
                     {:id id
                      :start [#(gs/start {:init init-fn
                                          :terminate terminate-fn}
                                         []
                                         {:flags {:trap-exit true}})
                              []]})
        children-spec [(child-spec :id1 healthy-child1-init child1-terminate)
                       (child-spec :id2 error-child-init nil)
                       (child-spec :id3 healthy-child2-init nil)]
        sup-spec [sup-flags children-spec]
        init-fn (fn [_]
                  (async/close! sup-init-done)
                  [:ok sup-spec])]
    (match (sup/start-link init-fn [nil])
           [:error [:shutdown [:failed-to-start-child :id2 :abnormal]]]
           (do
             (is (await-completion child1-init-done 50)
                 "first child must be started")
             (is (await-completion error-child-done 50)
                 "error child must be started")
             (is (await-completion child1-terminate-done 50)
                 "first child must be terminated")
             (is (await-completion sup-init-done 50)
                 "supervisor must call init-fn to get its spec")))))

(def-proc-test ^:parallel
  start-link:multiple-children:last-child-init-returns-error
;(otplike.proc-util/execute-proc
  (let [child1-init-done (async/chan)
        child1-terminate-done (async/chan)
        child2-init-done (async/chan)
        child2-terminate-done (async/chan)
        error-child-done (async/chan)
        sup-init-done (async/chan)
        sup-flags {}
        healthy-child1-init (fn [_]
                              (async/close! child1-init-done)
                              [:ok :state])
        child1-terminate
        (fn [reason _]
          (is (await-completion child2-terminate-done 50)
              "second child must be terminated before the first")
          (async/close! child1-terminate-done)
          (is (= :shutdown reason)
              "child must be stopped with :shutdown reason"))
        error-child-init (fn [_]
                           (async/close! error-child-done)
                           [:stop :abnormal])
        healthy-child2-init (fn [_]
                              (async/close! child2-init-done)
                              [:ok :state])
        child2-terminate
        (fn [reason _]
          (async/close! child2-terminate-done)
          (is (= :shutdown reason)
              "child must be stopped with :shutdown reason"))
        child-spec (fn [id init-fn terminate-fn]
                     {:id id
                      :start [#(gs/start {:init init-fn
                                          :terminate terminate-fn}
                                         []
                                         {:flags {:trap-exit true}})
                              []]})
        children-spec [(child-spec :id1 healthy-child1-init child1-terminate)
                       (child-spec :id2 healthy-child2-init child2-terminate)
                       (child-spec :id3 error-child-init nil)]
        sup-spec [sup-flags children-spec]
        init-fn (fn [_]
                  (async/close! sup-init-done)
                  [:ok sup-spec])]
    (match (sup/start-link init-fn [nil])
           [:error [:shutdown [:failed-to-start-child :id3 :abnormal]]]
           (do
             (is (await-completion child1-init-done 50)
                 "first child must be started")
             (is (await-completion child2-init-done 50)
                 "second child must be started")
             (is (await-completion error-child-done 50)
                 "error child must be started")
             (is (await-completion child1-terminate-done 50)
                 "first child must be terminated")
             (is (await-completion sup-init-done 50)
                 "supervisor must call init-fn to get its spec")))))

;; ====================================================================
;; one-for-one

; TODO
; number of children - 3
; child restart-type - 3
; which child exits - 3
; child exit reason - 3 (normal, abnormal, shutdown)
; child shutdown type - 2
; child exit timeout - 2
; child type - 2
; allowed restarts number exceeded - 2
; child start timeout - 2
;(* 3 3 3 3 2 2 2 2 2)

;; ====================================================================
;; one-for-all

;; ====================================================================
;; rest-for-one

;; ====================================================================
;; exit message

;TODO check defaults

#_(do
    (otplike.proc-util/execute-proc
      (let [done0 (doto (async/chan) (async/close!))
            done1 (async/chan)
            done2 (async/chan)
            done3 (async/chan)
            buggy-child-name :3
            sup-flags {:period 200
                       ;:strategy :one-for-one
                       :strategy :one-for-all
                       ;:strategy :rest-for-one
                       }
            child (fn [await-chan done sname restart-type]
                    (let [server {:init (fn [_]
                                          (printf "server %s init %s%n" sname (rem (System/currentTimeMillis) 10000))
                                          (await-completion await-chan 50)
                                          (async/close! done)
                                          [:ok :state])
                                  :handle-call (fn [req _ state] [:stop :normal state])
                                  :terminate (fn [reason _state]
                                               (printf "server %s terminate %s, reason %s%n" sname (rem (System/currentTimeMillis) 10000) reason))}]
                      {:id sname
                       :start [#(gs/start server [] {:link-to (process/self) :register sname}) []]
                       :restart restart-type}))
            children-spec [(child done0 done1 :1 :permanent)
                           (child done1 done2 :2 :permanent)
                           (child done2 done3 :3 :transient)]
            sup-spec [sup-flags children-spec]]
        (match (sup/start-link (fn [_] [:ok sup-spec]) [nil]) [:ok pid] :ok)
        (await-completion done3 100)
        (printf "server started %s%n" (rem (System/currentTimeMillis) 10000))
        (printf "call 1 %s%n" (rem (System/currentTimeMillis) 10000))
        (match (process/ex-catch (gs/call buggy-child-name 1)) [:EXIT _] :ok)
        (printf "call 1 done %s%n" (rem (System/currentTimeMillis) 10000))
        (<! (async/timeout 500))
        (printf "call 2 %s%n" (rem (System/currentTimeMillis) 10000))
        (match (process/ex-catch (gs/call buggy-child-name 1)) [:EXIT _] :ok)
        (printf "call 2 done %s%n" (rem (System/currentTimeMillis) 10000))
        (<! (async/timeout 500))))
    (<!! (async/timeout 1000)))
