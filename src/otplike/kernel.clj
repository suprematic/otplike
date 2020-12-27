(ns otplike.kernel
  (:require
   [otplike.supervisor :as supervisor]))

(defn- sup-fn []
  [:ok
   [{:strategy :one-for-all}
    []]])

(defn start [_env]
  (supervisor/start-link sup-fn []))