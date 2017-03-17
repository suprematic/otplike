(ns otplike.proc-util
  (:require [clojure.core.async :as async]
            [clojure.core.match :refer [match]]
            [otplike.process :as process]))

(defmacro execute-proc
  "Executes body in newly created process context."
  [& body]
  `(let [done# (async/chan)]
     (process/spawn
       (process/proc-fn
         []
         (try
           (let [res# (do ~@body)]
             (async/put! done# [:ok res#]))
           (catch Throwable t#
             (async/put! done# [:ex t#]))))
       []
       {})
     (match (async/<!! done#)
       [:ok res#] res#
       [:ex e#] (do
                  (println ">ex")
                  (throw e#)))))

(defmacro defn-proc
  "Defines function with name fname, arguments args, which body is
  executed in newly created process context."
  [fname args & body]
  `(defn ~fname []
     (let [done# (async/chan)]
       (process/spawn
         (process/proc-fn
           ~args
           (try
             (let [res# (do ~@body)]
               (when (some? res#) (async/>! done# res#)))
             (finally
               (async/close! done#))))
         []
         {})
       (async/<!! done#))))
