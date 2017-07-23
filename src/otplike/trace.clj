(ns otplike.trace
  "Examples:
  1. Print all events about processes exited abnormally:
    (console-trace [(filter (filter-event crashed?))])
    or just:
    (console-trace [filter-crashed])

  See console-trace code for trace function usage example."
  (:require
    [clojure.core.match :refer [match]]))

;; ====================================================================
;; Internal

(def ^:no-doc *handlers
  (atom {}))

(def ^:private *t-ref
  (atom 0))

(defn- pid=? [pid {pid1 :pid}]
  (= pid pid1))

(defn- reg-name=? [reg-name {reg-name1 :reg-name}]
  (= reg-name reg-name1))

(defn- kind=? [kind {kind1 :kind}]
  (= kind kind1))

;; ====================================================================
;; API

(defn crashed? [{:keys [kind extra]}]
  (and (= kind :terminate)
       (not (#{:normal :shutdown} (:reason extra)))))

(defn event [pred handler]
  (let [t-ref (swap! *t-ref inc)
        handler #(if (pred %) (handler %))]
    (swap! *handlers assoc t-ref handler)
    t-ref))

(defn pid [pid handler]
  (event (partial pid=? pid) handler))

(defn reg-name [reg-name handler]
  (event (partial reg-name=? reg-name) handler))

(defn kind [kind handler]
  (event (partial kind=? kind) handler))

(defn untrace [t-ref]
  (swap! *handlers dissoc t-ref))
