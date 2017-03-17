(ns otplike.gen-server-test
  (:require [clojure.test :refer [is deftest]]
            [clojure.future :refer :all]
            [clojure.core.match :refer [match]]
            [otplike.process :as process :refer [!]]
            [clojure.core.async :as async :refer [<!! <! >! >!!]]
            [otplike.test-util :refer :all]
            [otplike.gen-server :as gs])
  (:import [otplike.gen_server IGenServer]))

; TODO test external exit of server process (trap-exit true and false)
; TODO test bad callback (bad arity or not a function at all)
; TODO test everything with both map and ns server
; TODO test gen-server unlinks starting process on init timeout
; FIXME check exit reason as soon as process/link or process/spawn will
; allow to wait until linking is finished

;; ====================================================================
;; (start [server-impl args options])

(deftest ^:parallel start.no-trap-exit.linked-parent-exits-abnormally
  (let [ready (async/chan)
        pid-chan (async/chan)
        parent-exit-chan (async/chan)
        server-exit-chan (async/chan)
        server {:init (constantly [:ok :state])
                :handle-info
                (fn [request _state]
                  (is (not (any? request))
                      "handle-info must not be called on parent exit")
                  [:stop :TEST-FAILED])
                :terminate
                (fn [reason _state]
                  (is (not (any? reason))
                      "terminate must not be called on parent exit")) }
        parent (process/proc-fn []
                 (match (gs/start server nil {:link-to (process/self)})
                        [:ok pid] (async/put! pid-chan pid))
                 (await-completion ready 50)
                 (process/exit :abnormal))
        parent-pid (process/spawn parent)]
    (match (await-completion pid-chan 50)
      [:ok server-pid]
      (do
        (notify-on-process-exit parent-pid parent-exit-chan 50)
        (notify-on-process-exit server-pid server-exit-chan 50)
        (async/close! ready)
        (is (match (await-completion parent-exit-chan 50)
                   [:ok [:ok _reason]] :ok)
            "gen-server must exit on bad return from handle-call")
        (is (match (await-completion server-exit-chan 50)
                   [:ok [:ok _reason]] :ok)
            "gen-server must exit on bad return from handle-call")))))

(deftest ^:parallel start.trap-exit.linked-parent-exits-normally
  (let [terminate-chan (async/chan)
        ready (async/chan)
        pid-chan (async/chan)
        parent-exit-chan (async/chan)
        server-exit-chan (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info
                (fn [request state]
                  (is (not (any? request))
                      "handle-info must not be called on parent exit")
                  [:stop :TEST-FAILED])
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must contain"
                                      " the value returned from handle-call"))
                             (async/close! terminate-chan))}
        parent (process/proc-fn []
                 (match (gs/start server nil {:link-to (process/self)
                                              :flags {:trap-exit true}})
                        [:ok pid] (async/put! pid-chan pid))
                 (await-completion ready 500))
        parent-pid (process/spawn parent)]
    (match (await-completion pid-chan 500)
      [:ok server-pid]
      (do
        (notify-on-process-exit parent-pid parent-exit-chan 500)
        (notify-on-process-exit server-pid server-exit-chan 500)
        (async/close! ready)
        (is (match (await-completion parent-exit-chan 500)
                   [:ok [:ok _reason]] :ok)
            "gen-server must exit on bad return from handle-call")
        (is (await-completion terminate-chan 500)
            "gen-server must exit on bad return from handle-call")
        (is (match (await-completion server-exit-chan 500)
                   [:ok [:ok _reason]] :ok)
            "gen-server must exit on bad return from handle-call")))))

(deftest ^:parallel start.trap-exit.linked-parent-exits-abnormally
  (let [terminate-chan (async/chan)
        ready (async/chan)
        pid-chan (async/chan)
        parent-exit-chan (async/chan)
        server-exit-chan (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info
                (fn [request state]
                  (is (not (any? request))
                      "handle-info must not be called on parent exit")
                  [:stop :TEST-FAILED])
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must contain"
                                      " the value returned from handle-call"))
                             (async/close! terminate-chan))}
        parent (process/proc-fn []
                 (match (gs/start server nil {:link-to (process/self)
                                              :flags {:trap-exit true}})
                        [:ok pid] (async/put! pid-chan pid))
                 (await-completion ready 500)
                 (process/exit :abnormal))
        parent-pid (process/spawn parent)]
    (match (await-completion pid-chan 500)
      [:ok server-pid]
      (do
        (notify-on-process-exit parent-pid parent-exit-chan 500)
        (notify-on-process-exit server-pid server-exit-chan 500)
        (async/close! ready)
        (is (match (await-completion parent-exit-chan 500)
                   [:ok [:ok _reason]] :ok)
            "gen-server must exit on bad return from handle-call")
        (is (await-completion terminate-chan 500)
            "gen-server must exit on bad return from handle-call")
        (is (match (await-completion server-exit-chan 500)
                   [:ok [:ok _reason]] :ok)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel start.illegal-arguments
  (is (thrown? Exception (gs/start 1 [] {})))
  (is (thrown? Exception (gs/start "server" [] {})))
  (is (thrown? Exception (gs/start [] [] {})))
  (is (thrown? Exception (gs/start #{} [] {})))
  (let [done (async/chan)
        server {:init (fn [args] [:ok args])
                :terminate (fn [_ _] (async/close! done))}]
    (is (thrown? Exception (gs/start server [] {:inbox "inbox"})))
    (is (thrown? Exception (await-completion done 50))
        (str "terminate must not be called when illegal arguments were passed"
             " to start"))))

(def-proc-test ^:parallel start.start-returns-pid
  (let [server {:init (fn [args] [:ok args])}]
    (match (gs/start server [] {})
      [:ok pid] (match (process/exit pid :abnormal) true :ok))))

(def-proc-test ^:parallel init.start-calls-init
  (let [done (async/chan)
        server {:init (fn [_] (async/close! done) [:ok nil])}]
    (gs/start server [] {})
    (match (gs/start server nil {})
      [:ok pid]
      (do
        (await-completion done 50)
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel init.start-passes-arguments
  (let [done (async/chan)
        server {:init
                (fn [args]
                  (is (= [:a 1 "str" {:a 1 :b 2} '()] args)
                      "args passed to init must be the same as passed to start")
                  (async/close! done)
                  [:ok args])}]
    (match (gs/start server [:a 1 "str" {:a 1 :b 2} '()] {})
      [:ok pid]
      (do
        (await-completion done 50)
        (match (process/exit pid :abnormal) true :ok))))
  (let [done (async/chan)
        server {:init
                (fn [args]
                  (is (= nil args)
                      "args passed to init must be the same as passed to start")
                  (async/close! done)
                  [:ok args])}]
    (match (gs/start server nil {})
      [:ok pid]
      (do
        (await-completion done 50)
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel init.undefined-callback
  (is (= [:error [:undef ['init [1]]]] (gs/start {} 1 {})))
  (is (= [:error [:undef ['init [1]]]] (gs/start (create-ns 'test-ns) 1 {})))
  (let [done (async/chan)
        server {:terminate (fn [_ _] (async/put! done :val))}]
    (is (= [:error [:undef ['init [1]]]] (gs/start server 1 {})))
    (is (= nil (async/poll! done))
        "terminate must not be called if init is undefined")))

(def-proc-test ^:parallel init.callback-throws
  (let [done (async/chan)
        server {:init (fn [_] (throw (Exception. "TEST")))
                :terminate (fn [_ _] (async/put! done :val))}]
    (is (match (gs/start server [] {})
          [:error [:exception {:message "TEST" :class "java.lang.Exception"}]]
          :ok)
        "error returned by start must contain exception thrown from callback")
    (is (= nil (async/poll! done))
        "terminate must not be called if init throws")))

(def-proc-test ^:parallel init.bad-return
  (let [done (async/chan)
        server {:init (fn [_] :bad-return)
                :terminate (fn [_ _] (async/put! done :val))}]
    (is (match (gs/start server [] {})
          [:error [:bad-return-value :bad-return]] :ok)
        "error returned by start must contain value returned by callback")
    (is (= nil (async/poll! done))
        "terminate must not be called if init returns bad value")))

(def-proc-test ^:parallel init.timeout
  (let [done (async/chan)
        server {:init (fn [_] (async/<!! (async/timeout 2000)) [:ok nil])
                :terminate (fn [_ _] (async/put! done :val))}]
    (is (match (gs/start server [] {}) [:error :timeout] :ok)
        "error returned by start must contain :timeout")
    (is (= nil (async/poll! done))
        "terminate must not be called if init returns bad value")))

;; ====================================================================
;; (handle-call [request from state])

; TODO test process exit reason

(def-proc-test ^:parallel handle-call.call-delivers-message
  (let [server {:init (fn [_] [:ok :state])
                :handle-call
                (fn [x _  state]
                  (is (= x 123)
                      "handle-call must receive message passed to call")
                  [:reply :ok state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (gs/call pid 123 50)
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call.undefined-callback
  (let [done1 (async/chan)
        done2 (async/chan)
        done3 (async/chan)
        server {:init (fn [_] [:ok :state])
                :terminate (fn [reason _] (async/put! done2 [:reason reason]))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done3 [:reason reason])
              (after 50 (async/put! done3 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (match (process/ex-catch [:ok (gs/call pid 1 50)])
               [:EXIT [[:undef ['handle-call [1 _ :state]]]
                       [`gs/call [pid 1 50]]]]
               :ok)
            "call must exit on absent handle-call callback")
        (is (match (await-completion done2 50)
                   [:ok [:reason [:undef ['handle-call [1 _ :state]]]]] :ok)
          (str "terminate must be called on bad return from handle-call"
               " with reason containing name and arguments of handle-call"))
        (is (match (await-completion done3 50)
                   [:ok [:reason [:undef ['handle-call [1 _ :state]]]]] :ok)
            (str "gen-server must exit on bad return from handle-call with"
                 " reason containing name and arguments of handle-call"))))))

(def-proc-test ^:parallel handle-call.bad-return
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ _ _] :bad-return)
                :terminate (fn [reason _]
                             (is (= [:bad-return-value :bad-return] reason)
                                 (str "reason passed to terminate must contain"
                                      " the value returned from handle-call"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= [:EXIT [[:bad-return-value :bad-return] [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call pid nil 50)]))
            "call must exit on bad return from handle-call")
        (is (await-completion done 50)
            "terminate must be called on bad return from handle-call")
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call.callback-throws
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ _ _] (throw (ex-info "TEST" {:test 1})))
                :terminate (fn [[reason ex] _]
                             (is (= [:exception
                                     {:message "TEST"
                                      :class "clojure.lang.ExceptionInfo"
                                      :data {:test 1}}]
                                    [reason (dissoc ex :stack-trace)])
                                 (str "reason passed to terminate must contain"
                                      " exception thrown from handle-call"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:test 1}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            "call must exit after exit called in handle-call")
        (is (await-completion done 50)
            "terminate must be called on bad return from handle-call")
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call.exit-abnormal
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ _ _] (process/exit :abnormal))
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as passed to exit in handle-call"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= [:EXIT [:abnormal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call pid nil 50)]))
            "call must exit after exit called in handle-call")
        (is (await-completion done 50)
            "terminate must be called after exit called in  handle-call")
        (is (await-process-exit pid 50)
            "gen-server must exit after exit called in handle-call")))))

(def-proc-test ^:parallel handle-call.exit-normal
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ _ _] (process/exit :normal))
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as passed to exit in handle-call"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= [:EXIT [:normal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call pid nil 50)]))
            "call must exit after exit called in handle-call")
        (is (await-completion done 50)
            "terminate must be called after exit called in handle-call")
        (is (await-process-exit pid 50)
            "gen-server must exit after exit called in handle-call")))))

(def-proc-test ^:parallel handle-call.stop-normal
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ _ state] [:stop :normal state])
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-call"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= [:EXIT [:normal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call pid nil 50)]))
            "call must exit if :stop returned by handle-call")
        (is (await-completion done 50)
            "terminate must be called after :stop returned by handle-call")
        (is (await-process-exit pid 100)
            "gen-server must exit after :stop returned by handle-call")))))

(def-proc-test ^:parallel handle-call.stop-abnormal
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ _ state] [:stop :abnormal state])
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-call"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= [:EXIT [:abnormal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call pid nil 50)]))
            "call must exit if :stop returned by handle-call")
        (is (await-completion done 50)
            "terminate must be called after :stop returned by handle-call")
        (is (await-process-exit pid 50)
            "gen-server must exit after :stop returned by handle-call")))))

(def-proc-test ^:parallel handle-call.return-reply
  (let [server {:init (fn [_] [:ok nil])
                :handle-call (fn [x _from state] [:reply (inc x) state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= 2 (gs/call pid 1 50)) "call must return response from server")
        (is (= 5 (gs/call pid 4 50)) "call must return response from server")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call.nil-return-reply
  (let [server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ _from state] [:reply nil state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= nil (gs/call pid nil 50))
            "call must return response from server")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call.delayed-reply-before-return
  (let [server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ from state]
                               (gs/reply from :ok)
                               [:noreply state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= :ok (gs/call pid nil 50))
            "call must return response from server")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call.return-reply-after-delayed-reply
  (let [server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ from state]
                               (gs/reply from :ok)
                               [:reply :error state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= :ok (gs/call pid nil 50))
            "call must return first response from server")
        (is (= :ok (gs/call pid nil 50))
            "call must return first response from server")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call.nil-delayed-reply
  (let [server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ from state]
                               (gs/reply from nil)
                               [:noreply state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= nil (gs/call pid nil 50))
            "call must return response from server")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call.delayed-reply-after-return
  (let [done (async/chan)
        done1 (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-call (fn [x from state]
                               (match [x state]
                                 [1 nil] (do (async/close! done)
                                             [:noreply from])
                                 [2 from1] (do (gs/reply from1 :ok1)
                                               [:reply :ok2 nil])))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (is (= :ok1 (gs/call pid 1 50))
                "call must return response from server")
            (async/close! done1)))
        (is (await-completion done 50))
        (is (= :ok2 (gs/call pid 2 50))
            "call must return response from server")
        (is (await-completion done1 50))
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call.stop-normal-reply
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-call (fn [x _ state] [:stop :normal (inc x) state])
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-call"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= 2 (gs/call pid 1 50)) "call must return response from server")
        (is (await-completion done 50)
            "terminate must be called after :stop returned by handle-call")
        (is (await-process-exit pid 50)
            "gen-server must exit after :stop returned by handle-call")))))

(def-proc-test ^:parallel handle-call.stop-abnormal-reply
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-call (fn [x _ state] [:stop :abnormal (inc x) state])
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-call"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= 2 (gs/call pid 1 50)) "call must return response from server")
        (is (await-completion done 50)
            "terminate must be called after :stop returned by handle-call")
        (is (await-process-exit pid 50)
            "gen-server must exit after :stop returned by handle-call")))))

(def-proc-test ^:parallel handle-call.call-to-exited-pid
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-call (fn [x _ state] [:stop :normal :ok state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (match (gs/call pid nil 50) :ok :ok)
        (match (await-process-exit pid 50) [:ok reason] :ok)
        (is (= [:EXIT [:noproc [`gs/call [pid nil 10]]]]
               (process/ex-catch [:ok (gs/call pid nil 10)]))
            "call to exited server must exit with :noproc reason")))))

(def-proc-test ^:parallel handle-call.timeout
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-call (fn [x _ state] (<!! (async/timeout 50)))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= [:EXIT [:timeout [`gs/call [pid nil 10]]]]
               (process/ex-catch [:ok (gs/call pid nil 10)]))
            "call must return response from server")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call.update-state
  (let [server {:init (fn [_] [:ok 1])
                :handle-call
                (fn [[old-state new-state] _from state]
                  (is (= old-state state)
                      "return from handle-call must update server state")
                  [:reply :ok new-state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= :ok (gs/call pid [1 2] 50))
            "call must return response from server")
        (is (= :ok (gs/call pid [2 4] 50))
            "call must return response from server")
        (is (= :ok (gs/call pid [4 0] 50))
            "call must return response from server")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call.bad-return.terminate-throws
  (let [server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ _ _] :bad-return)
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:a 1}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " terminate"))
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call.callback-throws.terminate-throws
  (let [server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ _ _] (throw (ex-info "TEST" {:a 1})))
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:b 2})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:b 2}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " terminate"))
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call.exit-abnormal.terminate-throws
  (let [server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ _ _] (process/exit :abnormal))
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:a 1}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " terminate"))
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call.exit-normal.terminate-throws
  (let [server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ _ _] (process/exit :normal))
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:a 1}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " terminate"))
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call.stop-normal.terminate-throws
  (let [server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ _ state] [:stop :normal state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:a 1}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " terminate"))
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call.stop-abnormal.terminate-throws
  (let [server {:init (fn [_] [:ok nil])
                :handle-call (fn [_ _ state] [:stop :abnormal state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:a 1}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " terminate"))
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call.stop-normal-reply.terminate-throws
  (let [server {:init (fn [_] [:ok nil])
                :handle-call (fn [x _ state] [:stop :normal (inc x) state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= 2 (gs/call pid 1 50))
            "call must return response even if terminate throws")
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call.stop-abnormal-reply.terminate-throws
  (let [server {:init (fn [_] [:ok nil])
                :handle-call (fn [x _ state] [:stop :abnormal (inc x) state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= 2 (gs/call pid 1 50))
            "call must return response even if terminate throws")
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call.undefined-callback.terminate-throws
  (let [server {:init (fn [_] [:ok :state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:a 1}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " terminate"))
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call.bad-return.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-call (fn [_ _ _] :bad-return)}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= [:EXIT [[:bad-return-value :bad-return] [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call pid nil 50)]))
            (str "call must exit with reason containing bad-value returned from"
                 " handle-call"))
        (is (match (await-completion done2 50)
              [:ok [:reason [:bad-return-value :bad-return]]] :ok)
            (str "gen-server must exit with reason containing bad value"
                 "returned from handle-call"))))))

(def-proc-test ^:parallel handle-call.callback-throws.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-call (fn [_ _ _] (throw (ex-info "TEST" {:b 2})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:b 2}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " handle-call"))
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:b 2}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from handle-call"))))))

(def-proc-test ^:parallel handle-call.exit-abnormal.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-call (fn [_ _ _] (process/exit :abnormal))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= [:EXIT [:abnormal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call pid nil 50)]))
            (str "call must exit with reason containing reason passed to exit"
                 " in handle-call"))
        (is (match (await-completion done2 50) [:ok [:reason :abnormal]] :ok)
            (str "gen-server must exit with reason passed to exit in"
                 " handle-call"))))))

(def-proc-test ^:parallel handle-call.exit-normal.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-call (fn [_ _ _] (process/exit :normal))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= [:EXIT [:normal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call pid nil 50)]))
            (str "call must exit with reason containing reason passed to exit"
                 " in handle-call"))
        (is (match (await-completion done2 50) [:ok [:reason :normal]] :ok)
            (str "gen-server must exit with reason passed to exit in"
                 " handle-call"))))))

(def-proc-test ^:parallel handle-call.stop-normal.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-call (fn [_ _ state] [:stop :normal state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= [:EXIT [:normal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call pid nil 50)]))
            (str "call must exit with reason containing reason returned by"
                 "  handle-call"))
        (is (match (await-completion done2 50) [:ok [:reason :normal]] :ok)
            "gen-server must exit with reason returned by handle-call")))))

(def-proc-test ^:parallel handle-call.stop-abnormal.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-call (fn [_ _ state] [:stop :abnormal state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= [:EXIT [:abnormal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call pid nil 50)]))
            (str "call must exit with reason containing reason returned by"
                 "  handle-call"))
        (is (match (await-completion done2 50) [:ok [:reason :abnormal]] :ok)
            "gen-server must exit with reason returned by handle-call")))))

(def-proc-test ^:parallel handle-call.undefined-callback.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (match (process/ex-catch [:ok (gs/call pid nil 50)])
                   [:EXIT [[:undef ['handle-call [nil _ :state]]]
                           [`gs/call [pid nil 50]]]] :ok)
            (str "call must exit with reason containing arguments passed to"
                 " handle-call"))
        (is (match (await-completion done2 50)
              [:ok [:reason [:undef ['handle-call [nil _ :state]]]]] :ok)
            (str "gen-server must exit with reason containing arguments passed"
                 " to handle-call"))))))

;; ====================================================================
;; (handle-cast [request state])

(def-proc-test ^:parallel handle-cast.cast-delivers-message
  (let [done (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-cast
                (fn [x state]
                  (is (= x 123)
                      "handle-cast must receive message passed to cast")
                  (async/close! done)
                  [:reply :ok state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (gs/cast pid 123)
        (await-completion done 50)
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-cast.undefined-callback
  (let [done (async/chan)
        server {:init (fn [_] [:ok :state])
                :terminate (fn [reason _]
                             (is (= [:undef ['handle-cast [1 :state]]] reason)
                                 (str "reason passed to terminate must contain"
                                      " name and arguments of handle-cast"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= true (gs/cast pid 1))
            "cast must return true if server is alive")
        (is (await-completion done 50)
            "terminate must be called on undefined handle-cast callback")
        (is (await-process-exit pid 50)
            "gen-server must exit on  undefined handle-cast callback")))))

(def-proc-test ^:parallel handle-cast.bad-return
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-cast (fn [_ _] :bad-return)
                :terminate (fn [reason _]
                             (is (= [:bad-return-value :bad-return] reason)
                                 (str "reason passed to terminate must contain"
                                      " the value returned from handle-cast"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (await-completion done 50)
            "terminate must be called on bad return from handle-cast")
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-cast")))))

(def-proc-test ^:parallel handle-cast.callback-throws
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-cast (fn [_ _] (throw (ex-info "TEST" {:test 1})))
                :terminate (fn [[reason ex] _]
                             (is (= [:exception
                                     {:message "TEST"
                                      :class "clojure.lang.ExceptionInfo"
                                      :data {:test 1}}]
                                    [reason (dissoc ex :stack-trace)])
                                 (str "reason passed to terminate must contain"
                                      " exception thrown from handle-cast"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (await-completion done 50)
            "terminate must be called on bad return from handle-cast")
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-cast")))))

(def-proc-test ^:parallel handle-cast.exit-abnormal
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-cast (fn [_ _] (process/exit :abnormal))
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as passed to exit in handle-cast"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (await-completion done 50)
            "terminate must be called after exit called in  handle-cast")
        (is (await-process-exit pid 50)
            "gen-server must exit after exit called in handle-cast")))))

(def-proc-test ^:parallel handle-cast.exit-normal
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-cast (fn [_ _] (process/exit :normal))
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as passed to exit in handle-cast"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (await-completion done 50)
            "terminate must be called after exit called in handle-cast")
        (is (await-process-exit pid 50)
            "gen-server must exit after exit called in handle-cast")))))

(def-proc-test ^:parallel handle-cast.stop-normal
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-cast (fn [_ state] [:stop :normal state])
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-cast"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (await-completion done 50)
            "terminate must be called after :stop returned by handle-cast")
        (is (await-process-exit pid 50)
            "gen-server must exit after :stop returned by handle-cast")))))

(def-proc-test ^:parallel handle-cast.stop-abnormal
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-cast (fn [_ state] [:stop :abnormal state])
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-cast"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (await-completion done 50)
            "terminate must be called after :stop returned by handle-cast")
        (is (await-process-exit pid 50)
            "gen-server must exit after :stop returned by handle-cast")))))

(def-proc-test ^:parallel handle-cast.cast-to-exited-pid
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-cast (fn [_ state] [:stop :normal state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (match (await-process-exit pid 100) [:ok reason] :ok)
        (is (= false (gs/cast pid nil))
            "cast must return false if server is not alive")))))

(def-proc-test ^:parallel handle-cast.update-state
  (let [server {:init (fn [_] [:ok 1])
                :handle-cast
                (fn [[old-state new-state] state]
                  (is (= old-state state)
                      "return from handle-cast must update server state")
                  [:noreply new-state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (is (= true (gs/cast pid [1 2]))
            "cast must return true if server is alive")
        (is (= true (gs/cast pid [2 4]))
            "cast must return true if server is alive")
        (is (= true (gs/cast pid [4 0]))
            "cast must return true if server is alive")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-cast.bad-return.terminate-throws
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-cast (fn [_ _] :bad-return)
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-cast.callback-throws.terminate-throws
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-cast (fn [_ _] (throw (ex-info "TEST" {:b 2})))
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-cast.exit-abnormal.terminate-throws
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-cast (fn [_ _] (process/exit :abnormal))
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-cast.exit-normal.terminate-throws
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-cast (fn [_ _] (process/exit :normal))
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-cast.stop-normal.terminate-throws
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-cast (fn [_ state] [:stop :normal state])
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-cast.stop-abnormal.terminate-throws
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-cast (fn [_ state] [:stop :abnormal state])
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-cast.undefined-callback.terminate-throws
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-cast.bad-return.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-cast (fn [_ _] :bad-return)}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion done2 50)
              [:ok [:reason [:bad-return-value :bad-return]]] :ok)
            (str "gen-server must exit with reason containing bad value"
                 "returned from handle-cast"))))))

(def-proc-test ^:parallel handle-cast.callback-throws.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-cast (fn [_ _] (throw (ex-info "TEST" {:b 2})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:b 2}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from handle-cast"))))))

(def-proc-test ^:parallel handle-cast.exit-abnormal.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-cast (fn [_ _] (process/exit :abnormal))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion done2 50) [:ok [:reason :abnormal]] :ok)
            (str "gen-server must exit with reason passed to exit in"
                 " handle-cast"))))))

(def-proc-test ^:parallel handle-cast.exit-normal.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-cast (fn [_ _] (process/exit :normal))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion done2 50) [:ok [:reason :normal]] :ok)
            (str "gen-server must exit with reason passed to exit in"
                 " handle-cast"))))))

(def-proc-test ^:parallel handle-cast.stop-normal.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-cast (fn [_ state] [:stop :normal state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion done2 50) [:ok [:reason :normal]] :ok)
            "gen-server must exit with reason returned by handle-cast")))))

(def-proc-test ^:parallel handle-cast.stop-abnormal.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-cast (fn [_ state] [:stop :abnormal state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion done2 50) [:ok [:reason :abnormal]] :ok)
            "gen-server must exit with reason returned by handle-cast")))))

(def-proc-test ^:parallel handle-cast.undefined-callback.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion done2 50)
              [:ok [:reason [:undef ['handle-cast [nil :state]]]]] :ok)
            (str "gen-server must exit with reason containing arguments passed"
                 " to handle-cast"))))))

;; ====================================================================
;; (handle-info [message state])

(def-proc-test ^:parallel handle-call.call-delivers-message
  (let [done (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info
                (fn [x state]
                  (is (= x 123)
                      "handle-info must receive message passed to !")
                  (async/close! done)
                  [:reply :ok state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (! pid 123)
        (await-completion done 50)
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-info.undefined-callback
  (let [done (async/chan)
        server {:init (fn [_] [:ok :state])
                :terminate (fn [reason _]
                             (is (= [:undef ['handle-info [1 :state]]] reason)
                                 (str "reason passed to terminate must contain"
                                      " name and arguments of handle-info"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (await-completion done 50)
            "terminate must be called on undefined handle-info callback")
        (is (await-process-exit pid 500)
            "gen-server must exit on undefined handle-info callback")))))

(def-proc-test ^:parallel handle-info.bad-return
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-info (fn [_ _] :bad-return)
                :terminate (fn [reason _]
                             (is (= [:bad-return-value :bad-return] reason)
                                 (str "reason passed to terminate must contain"
                                      " the value returned from handle-info"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (await-completion done 50)
            "terminate must be called on bad return from handle-info")
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-info")))))

(def-proc-test ^:parallel handle-info.callback-throws
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-info (fn [_ _] (throw (ex-info "TEST" {:test 1})))
                :terminate (fn [[reason ex] _]
                             (is (= [:exception
                                     {:message "TEST"
                                      :class "clojure.lang.ExceptionInfo"
                                      :data {:test 1}}]
                                    [reason (dissoc ex :stack-trace)])
                                 (str "reason passed to terminate must contain"
                                      " exception thrown from handle-info"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (await-completion done 50)
            "terminate must be called on bad return from handle-info")
        (is (await-process-exit pid 50)
            "gen-server must exit on bad return from handle-info")))))

(def-proc-test ^:parallel handle-info.exit-abnormal
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-info (fn [_ _] (process/exit :abnormal))
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as passed to exit in handle-info"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (await-completion done 50)
            "terminate must be called after exit called in  handle-info")
        (is (await-process-exit pid 50)
            "gen-server must exit after exit called in handle-info")))))

(def-proc-test ^:parallel handle-info.exit-normal
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-info (fn [_ _] (process/exit :normal))
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as passed to exit in handle-info"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (await-completion done 50)
            "terminate must be called after exit called in handle-info")
        (is (await-process-exit pid 500)
            "gen-server must exit after exit called in handle-info")))))

(def-proc-test ^:parallel handle-info.stop-normal
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-info (fn [_ state] [:stop :normal state])
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-info"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (await-completion done 50)
            "terminate must be called after :stop returned by handle-info")
        (is (await-process-exit pid 50)
            "gen-server must exit after :stop returned by handle-info")))))

(def-proc-test ^:parallel handle-info.stop-abnormal
  (let [done (async/chan)
        server {:init (fn [_] [:ok nil])
                :handle-info (fn [_ state] [:stop :abnormal state])
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-info"))
                             (async/close! done))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (await-completion done 50)
            "terminate must be called after :stop returned by handle-info")
        (is (await-process-exit pid 50)
            "gen-server must exit after :stop returned by handle-info")))))

(def-proc-test ^:parallel handle-info.update-state
  (let [server {:init (fn [_] [:ok 1])
                :handle-info
                (fn [[old-state new-state] state]
                  (is (= old-state state)
                      "return from handle-info must update server state")
                  [:noreply new-state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (match (! pid [1 2]) true :ok)
        (match (! pid [2 4]) true :ok)
        (match (! pid [4 0]) true :ok)
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-info.bad-return.terminate-throws
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info (fn [_ _] :bad-return)
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (match (! pid 1) true :ok)
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-info.callback-throws.terminate-throws
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info (fn [_ _] (throw (ex-info "TEST" {:b 2})))
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (match (! pid 1) true :ok)
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-info.exit-abnormal.terminate-throws
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info (fn [_ _] (process/exit :abnormal))
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (match (! pid 1) true :ok)
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-info.exit-normal.terminate-throws
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info (fn [_ _] (process/exit :normal))
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (match (! pid 1) true :ok)
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-info.stop-normal.terminate-throws
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info (fn [_ state] [:stop :normal state])
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (match (! pid 1) true :ok)
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-info.stop-abnormal.terminate-throws
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info (fn [_ state] [:stop :abnormal state])
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (match (! pid 1) true :ok)
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-info.undefined-callback.terminate-throws
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (match (! pid 1) true :ok)
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-info.bad-return.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info (fn [_ _] :bad-return)}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (match (! pid 1) true :ok)
        (is (match (await-completion done2 50)
              [:ok [:reason [:bad-return-value :bad-return]]] :ok)
            (str "gen-server must exit with reason containing bad value"
                 "returned from handle-info"))))))

(def-proc-test ^:parallel handle-info.callback-throws.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info (fn [_ _] (throw (ex-info "TEST" {:b 2})))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (match (! pid 1) true :ok)
        (is (match (await-completion done2 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:b 2}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from handle-info"))))))

(def-proc-test ^:parallel handle-info.exit-abnormal.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info (fn [_ _] (process/exit :abnormal))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (match (! pid 1) true :ok)
        (is (match (await-completion done2 50) [:ok [:reason :abnormal]] :ok)
            (str "gen-server must exit with reason passed to exit in"
                 " handle-info"))))))

(def-proc-test ^:parallel handle-info.exit-normal.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info (fn [_ _] (process/exit :normal))}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (match (! pid 1) true :ok)
        (is (match (await-completion done2 50) [:ok [:reason :normal]] :ok)
            (str "gen-server must exit with reason passed to exit in"
                 " handle-info"))))))

(def-proc-test ^:parallel handle-info.stop-normal.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info (fn [_ state] [:stop :normal state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (match (! pid 1) true :ok)
        (is (match (await-completion done2 50) [:ok [:reason :normal]] :ok)
            "gen-server must exit with reason returned by handle-info")))))

(def-proc-test ^:parallel handle-info.stop-abnormal.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])
                :handle-info (fn [_ state] [:stop :abnormal state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (match (! pid 1) true :ok)
        (is (match (await-completion done2 50) [:ok [:reason :abnormal]] :ok)
            "gen-server must exit with reason returned by handle-info")))))

(def-proc-test ^:parallel handle-info.undefined-callback.terminate-undefined
  (let [done1 (async/chan)
        done2 (async/chan)
        server {:init (fn [_] [:ok :state])}]
    (match (gs/start server [] {})
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (async/close! done1)
            (process/receive!
              [:EXIT pid reason] (async/put! done2 [:reason reason])
              (after 50 (async/put! done2 :timeout))))
          {:link-to pid :flags {:trap-exit true}})
        (await-completion done1 50)
        (match (! pid 1) true :ok)
        (is (match (await-completion done2 50)
              [:ok [:reason [:undef ['handle-info [1 :state]]]]] :ok)
            (str "gen-server must exit with reason containing arguments"
                 " passed to handle-info"))))))

; FIXME: 2 processes survive after all tests execution
