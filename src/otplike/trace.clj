(ns otplike.trace
  "Examples:

  1. Print all events about processes exited abnormally:

  ```
  (otplike.trace/crashed #(println %))
  ```"
  (:require
    [otplike.process :as process]))

;; ====================================================================
;; Internal

(defn- pid=? [pid {pid1 :pid}]
  (= pid pid1))

(defn- reg-name=? [reg-name {reg-name1 :reg-name}]
  (= reg-name reg-name1))

(defn- kind=? [kind {kind1 :kind}]
  (= kind kind1))

;; ====================================================================
;; API

(defn crashed? [{:keys [kind extra]}]
  (and (= kind :exiting)
       (not (#{:normal :shutdown} (:reason extra)))))

(defn pid [pid handler]
  (process/trace (partial pid=? pid) handler))

(defn reg-name [reg-name handler]
  (process/trace (partial reg-name=? reg-name) handler))

(defn kind [kind handler]
  (process/trace (partial kind=? kind) handler))

(defn crashed [handler]
  (process/trace crashed? handler))
