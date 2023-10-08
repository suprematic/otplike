(ns otplike.test-runner
  (:require [eftest.runner :as ef]))

(defn- run [pred _]
  (ef/run-tests
   (->>
    (ef/find-tests "test")
    (filter
     (comp pred meta))) {:multithread? false}))

(def default
  (partial run (complement :exhaustive)))

(def parallel
  (partial run :parallel))

(def serial
  (partial run :serial))

(def exhaustive
  (partial run :exhaustive))
