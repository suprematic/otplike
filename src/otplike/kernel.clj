(ns otplike.kernel
  (:require
   [otplike.supervisor :as supervisor]
   [otplike.logger :as logger]))

(defn- sup-fn []
  [:ok
   [{:strategy :one-for-all}
    []]])

(defn start [{:keys [logger]}]
  (logger/set-config! logger)
  (supervisor/start-link sup-fn []))