(ns otplike.proc-util
  (:require [clojure.core.async :as async]
            [otplike.process :as process]))

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
