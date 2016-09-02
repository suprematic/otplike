(ns otplike.gen-server-test
  (:require [clojure.test :refer [is deftest]]
            [clojure.core.match :refer [match]]
            [otplike.process :as process :refer [!]]
            [clojure.core.async :as async :refer [<!! <! >! >!!]]
            [otplike.test-util :refer :all]
            [otplike.gen-server :as gs])
  (:import [otplike.gen_server IGenServer]))

;; ====================================================================
;; (start [])

(deftest ^:parallel start-starts-the-process
  (let [done (async/chan)
        server (reify IGenServer
                 (init [_ _]
                   (async/close! done)
                   [:ok []])
                 (terminate [_ reason state]
                   :ok)
                 (handle-call [_ request from state]
                   [:stop :handle-call-must-not-be-called state])
                 (handle-cast [_ message state]
                   [:stop :handle-cast-must-not-be-called state])
                 (handle-info [_ message state]
                   [:stop :handle-info-must-not-be-called state]))]
    (gs/start server [] {})
    (is (await-completion done 50))))

(deftest ^:parallel start-passes-arguments-to-init
  (let [done (async/chan)
        server (reify IGenServer
                 (init [_ args]
                   (is
                     (match args
                       [:a 1 "str" ({:a 1 :b 2} :only [:a :b]) ([] :seq)]
                       :ok))
                   (async/close! done)
                   [:ok args])
                 (terminate [_ reason state]
                   :ok)
                 (handle-call [_ request from state]
                   [:stop :handle-call-must-not-be-called state])
                 (handle-cast [_ message state]
                   [:stop :handle-cast-must-not-be-called state])
                 (handle-info [_ message state]
                   [:stop :handle-info-must-not-be-called state]))]
    (gs/start server [:a 1 "str" {:a 1 :b 2} '()] {})
    (await-completion done 50))
  (let [done (async/chan)
        server (reify IGenServer
                 (init [_ args]
                   (is (match args nil :ok))
                   (async/close! done)
                   [:ok args])
                 (terminate [_ reason state]
                   :ok)
                 (handle-call [_ request from state]
                   [:stop :handle-call-must-not-be-called state])
                 (handle-cast [_ message state]
                   [:stop :handle-cast-must-not-be-called state])
                 (handle-info [_ message state]
                   [:stop :handle-info-must-not-be-called state]))]
    (gs/start server nil {})
    (await-completion done 50)))

(deftest ^:parallel start-throws-on-illegal-arguments
  (is (thrown? Exception (gs/start 1 [] {})))
  (is (thrown? Exception (gs/start "server" [] {})))
  (is (thrown? Exception (gs/start [] [] {})))
  (is (thrown? Exception (gs/start {} [] {})))
  (let [server (reify IGenServer
                 (init [_ args]
                   [:ok args])
                 (terminate [_ reason state]
                   :ok)
                 (handle-call [_ request from state]
                   [:stop :handle-call-must-not-be-called state])
                 (handle-cast [_ message state]
                   [:stop :handle-cast-must-not-be-called state])
                 (handle-info [_ message state]
                   [:stop :handle-info-must-not-be-called state]))]
    (is (thrown? Exception (gs/start server [] {:inbox "inbox"})))))

(deftest ^:parallel start-throws-if-server-does-not-implement-init
  (is (thrown? Exception (gs/start (reify IGenServer) [] {}))))

(deftest ^:parallel start-returns-pid-on-successful-start
  (let [server (reify IGenServer
                 (init [_ args]
                   [:ok args])
                 (terminate [_ reason state]
                   :ok)
                 (handle-call [_ request from state]
                   [:stop :handle-call-must-not-be-called state])
                 (handle-cast [_ message state]
                   [:stop :handle-cast-must-not-be-called state])
                 (handle-info [_ message state]
                   [:stop :handle-info-must-not-be-called state]))]
    (is (match (gs/start server [] {})
          [:ok (_pid :guard process/pid?)] :ok))))

;(deftest ^:parallel start-...when-init-throws)

;(deftest ^:parallel start-...when-init-returns-illegal-value)

;; ====================================================================
;; (handle-call [request from state])

; TODO
;(deftest ^:parallel undefined-callback)
;(deftest ^:parallel illegal-return-value)
;(deftest ^:parallel callback-throws)

;; ====================================================================
;; (handle-cast [request state])

; TODO
;(deftest ^:parallel undefined-callback)
;(deftest ^:parallel illegal-return-value)
;(deftest ^:parallel callback-throws)

;; ====================================================================
;; (handle-info [message state])

; TODO
;(deftest ^:parallel undefined-callback)
;(deftest ^:parallel illegal-return-value)
;(deftest ^:parallel callback-throws)

;; ====================================================================
;; (terminate [reason state])

; TODO
;(deftest ^:parallel undefined-callback)
;(deftest ^:parallel illegal-return-value)
;(deftest ^:parallel callback-throws)

;--------------

#_(def server
  (reify IGenServer
    (init [_ n]
      (println "init: " n)
      (! (process/self) :init-message)
      [:ok n])

    (terminate [_ reason state]
      (println "terminate: " reason ", state: " state))

    (handle-call [_ request from state]
      (println "handle-call: " request ", state: " state)
      (match request
        :get-async (do
                     (gen-server/reply from state)
                     [:noreply state])
        :get-sync [:reply state state]
        :stop [:stop :normal state]))

    (handle-cast [_ message state]
      (println "handle-cast: " message ", state: " state)
      (match message
        :dec [:noreply (dec state)]
        :inc [:noreply (inc state)]))

    (handle-info [_ message state]
      (println "handle-info: " message ", state: " state)
      [:noreply state])))


#_(def server1
  (gen-server/coerce-map
    {:init
     (fn [n]
       (println "init: " n)
       (! (process/self) :init-message)
       [:ok n])

     :terminate
     (fn [reason state]
       (println "terminate: " reason ", state: " state))

     :handle-call
     (fn [request from state]
       (println "handle-call: " request ", state: " state)
       (match request
         :get-async (do
                      (gen-server/reply from state)
                      [:noreply state])
         :get-sync [:reply state state]
         :stop [:stop :normal state]))

     :handle-cast
     (fn [message state]
       (println "handle-cast: " message ", state: " state)
       (match message
         :dec [:noreply (dec state)]
         :inc [:noreply (inc state)]))

     :handle-info
     (fn [message state]
       (println "handle-info: " message ", state: " state)
       [:noreply state])}))

#_(deftest test-1
  (match (gen-server/start server1 10 {})
    [:ok pid] (do
                (gen-server/cast pid :inc)
                (gen-server/cast pid :dec)
                (println "state-async: " (gen-server/call pid :get-async))
                (println "state-sync: " (gen-server/call pid :get-sync))
                (gen-server/call pid :stop))))
