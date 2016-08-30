(ns otplike.gen-server-test
  (:require [clojure.test :refer [is deftest]]
            [clojure.core.match :refer [match]]
            [otplike.process :as process :refer [!]]
            [otplike.gen-server :as gen-server])
  (:import [otplike.gen_server IGenServer]))

(def server
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


(def server1
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

(deftest test-1
  (match (gen-server/start server1 10 {})
    [:ok pid] (do
                (gen-server/cast pid :inc)
                (gen-server/cast pid :dec)
                (println "state-async: " (gen-server/call pid :get-async))
                (println "state-sync: " (gen-server/call pid :get-sync))
                (gen-server/call pid :stop))))
