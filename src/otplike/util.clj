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

(defn exception
  ([^Throwable e]
   (exception e true))
  ([^Throwable e include-stack-trace?]
   (merge
    {:message (.getMessage e)
     :class (.getName (class e))}

    (if include-stack-trace?
      {:stack-trace (mapv str (.getStackTrace e))})

    (if (instance? clojure.lang.ExceptionInfo e)
      {:data (ex-data e)})
    (if-let [cause (.getCause e)]
      {:cause (exception cause include-stack-trace?)}))))

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

(defn- deep-merge* [l r]
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
      (merge-with deep-merge* l r)

      (and (vector? l) (vector? r))
      (merge-coll l r vector)

      (and (list? l) (list? r))
      (merge-coll l r list)

      (and (set? l) (set? r))
      (merge-coll l r hash-set)

      (or
       (string? r)
       (number? r)
       (boolean? r)
       (keyword? r)
       (symbol? r)) r

      :else l)))

(defn deep-merge [& args]
  (reduce
   (fn [acc e]
     (deep-merge* acc e))
   {}
   args))

#_(deep-merge {:a 1 :b ^:distinct [-1 0]} {:b [0 1 2 3]})

; apply name to every key 
(defn name-keys [m]
  (->>
   m
   (map
    (fn [[k v]]
      [(name k) v]))
   (into {})))

; convert keyword/symbol with optional namespace to string. string arg is returned as-is
(defn strks [k-or-s]
  (when (some? k-or-s)
    (if-not (string? k-or-s)
      (let [name (name k-or-s)]
        (if-let [ns (namespace k-or-s)]
          (str ns "/" name)
          name))
      k-or-s)))
