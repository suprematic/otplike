(ns otplike.gen-server-ns-test
  (:require [clojure.test :refer [is deftest]]
            [clojure.core.match :refer [match]]
            [otplike.process :as process :refer [!]]
            [clojure.core.async :as async]
            [otplike.test-util :refer :all]
            [otplike.gen-server :as gs]))

(defn init [[n done :as state]]
  (is (= 0 n))
  [:ok state])

(defn terminate [reason [_ done]]
  (is (= :normal reason))
  (async/close! done))

(defn handle-call [request from [n _ :as state]]
  (match request
    :get-async (do
                 (gs/reply from n)
                 [:noreply state])
    :get-sync [:reply n state]
    :stop [:stop :normal :ok state]))

(defn handle-cast [message [n done]]
  (match message
    :dec [:noreply [(dec n) done]]
    :inc [:noreply [(inc n) done]]))

(defn handle-info [message state]
  [:noreply state])

;(def-proc-test gen-server-ns
(otplike.proc-util/execute-proc!!
  (let [done (async/chan)]
    (match (gs/start-ns! [[0 done]])
      [:ok pid]
      (do
        (gs/cast pid :inc)
        (is (= 1 (gs/call! pid :get-async)))
        (is (= 1 (gs/call! pid :get-sync)))
        (gs/cast pid :dec)
        (is (= 0 (gs/call! pid :get-async)))
        (is (= 0 (gs/call! pid :get-sync)))
        (is (= :ok (gs/call! pid :stop)))
        (await-completion done 50)))))
