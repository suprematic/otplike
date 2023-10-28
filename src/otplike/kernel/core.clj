(ns otplike.kernel.core
  (:require
   [otplike.process :as p]
   [otplike.supervisor :as supervisor]
   [otplike.kernel.logger :as klogger]
   [otplike.kernel.logger-console :as console]
   [otplike.kernel.logger-otel :as otel]))

(defn- sup-fn [{:keys [logger]}]
  [:ok
   [{:strategy :one-for-one}
    [(let [config (:console logger)]
       {:id :logger-console
        :start
        [(fn []
           [:ok (p/spawn-link console/p-log [config klogger/console-tap])]) []]})
     (let [config (:otel logger)]
       {:id :logger-otel
        :start
        [(fn []
           [:ok (p/spawn-link otel/p-log [config klogger/otel-tap])]) []]})]]])

(defn start [config]
  (supervisor/start-link sup-fn [config]))


