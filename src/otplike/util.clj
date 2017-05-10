(ns ^:no-doc otplike.util
  (:require [clojure.future :refer :all]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.core.async :as async]))

(defmacro check-args [exprs]
  (assert (sequential? exprs))
  (when-let [expr (first exprs)]
    `(if ~expr
      (check-args ~(rest exprs))
      (throw (IllegalArgumentException.
               (str "require " '~expr " to be true"))))))

(defn stack-trace
  ([^Throwable e]
   (let [s-trace (stack-trace e '())]
     (reduce (fn [acc x] (assoc x :cause acc)) (first s-trace) (rest s-trace))))
  ([^Throwable e acc]
   (if e
     (recur (.getCause e)
            (conj acc (merge {:message (.getMessage e)
                              :class (.getName (class e))
                              :stack-trace (mapv str (.getStackTrace e))}
                             (if (instance? clojure.lang.ExceptionInfo e)
                               {:data (ex-data e)}))))
     acc)))


(defn timeout-chan [timeout]
  (cond
    (= :infinity timeout) (async/chan)
    (nat-int? timeout) (async/timeout timeout)
    (satisfies? ap/ReadPort timeout) timeout
    :else (throw (Exception.
                   (str "unsupported receive timeout " (pr-str timeout))))))
