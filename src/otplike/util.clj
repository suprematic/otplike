(ns ^:no-doc otplike.util
  (:require [clojure.core.async.impl.protocols :as ap]
            [clojure.core.async :as async]))

(when (and (= 1 (:major *clojure-version*))
           (< (:minor *clojure-version*) 9))
  (require '[clojure.future :refer :all]))

;; Overload the printer for queues so they look like other collections
(defmethod print-method clojure.lang.PersistentQueue [q w]
  (print-method '< w)
  (print-method (vec q) w)
  (print-method '= w))

;; ====================================================================
;; API

(defmacro check-args [exprs]
  (assert (sequential? exprs))
  (when-let [expr (first exprs)]
    `(if ~expr
      (check-args ~(rest exprs))
      (throw (IllegalArgumentException.
               (str "require " '~expr " to be true"))))))

(defn stack-trace [^Throwable e]
  (merge
    {:message (.getMessage e)
     :class (.getName (class e))
     :stack-trace (mapv str (.getStackTrace e))}
    (if (instance? clojure.lang.ExceptionInfo e)
      {:data (ex-data e)})
    (if-let [cause (.getCause e)]
      {:cause (stack-trace cause)})))

(defn timeout-chan [timeout]
  (cond
    (= :infinity timeout) (async/chan)
    (nat-int? timeout) (async/timeout timeout)
    (satisfies? ap/ReadPort timeout) timeout
    :else (throw (Exception.
                   (str "unsupported receive timeout " (pr-str timeout))))))

(defn channel? [x]
  (satisfies? ap/ReadPort x))

(defn queue []
  (clojure.lang.PersistentQueue/EMPTY))

(defn ns-function [fun-ns fun-name]
  (if-let [fun-var (ns-resolve fun-ns fun-name)]
    (var-get fun-var)))
