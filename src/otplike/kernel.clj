(ns otplike.kernel
  (:require
   [otplike.supervisor :as supervisor]))


(defn sup-fn []
  [:ok
   [{:strategy :one-for-all}
    []]])




(defn start []
  (Thread/sleep 6000)
  (supervisor/start-link sup-fn []))


(defn stop [xx]
  nil
  )
