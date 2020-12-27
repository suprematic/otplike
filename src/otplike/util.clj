(ns ^:no-doc otplike.util
  (:require [clojure.core.async.impl.protocols :as ap]
            [clojure.core.async :as async]
            [clojure.set :as set]))

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

(defn deep-merge [l r]
  (letfn
   [(merge-coll [l r coll-fn]
      (if (-> r meta :override)
        r
        (apply
         coll-fn
         ((if (-> l meta :distinct) distinct identity)
          (concat l r)))))]
    (cond
      (nil? l) r
      (nil? r) l

      (and (map? l) (map? r))
      (merge-with deep-merge l r)

      (and (vector? l) (vector? r))
      (merge-coll l r vector)

      (and (list? l) (list? r))
      (merge-coll l r list)

      (and (set? l) (set? r))
      (set/union l r)

      (or
       (string? r)
       (number? r)
       (boolean? r)
       (keyword? r)
       (symbol? r)) r

      :else
      (throw (ex-info "cannot merge values: " {:left l :right r})))))

#_(deep-merge {:a 1 :b ^:distinct [-1 0]} {:b [0 1 2 3]})