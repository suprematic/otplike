(ns otplike.kernel.core
  (:require
   [otplike.supervisor :as supervisor]
   [otplike.kernel.logger :as klogger]))

(defn- sup-fn []
  [:ok
   [{:strategy :one-for-one}
    []]])

(defn start [{:keys [logger]}]
  (klogger/configure! logger)
  (supervisor/start-link sup-fn []))



