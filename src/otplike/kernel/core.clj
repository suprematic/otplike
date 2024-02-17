(ns otplike.kernel.core
  (:require
   [otplike.supervisor :as supervisor]
   [otplike.kernel.logger :as klogger]
   [otplike.kernel.logger-console :as clogger]))

(defn- sup-fn []
  [:ok
   [{:strategy :one-for-one}
    []]])

(defn start [{:keys [logger]}]
  (klogger/register-backend!
   :console
   (klogger/make-backend logger clogger/log))
  (supervisor/start-link sup-fn []))



