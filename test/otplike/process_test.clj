(ns otplike.process-test
  (:require [clojure.test :refer [is deftest]]
            [otplike.process :as process]
            [otplike.trace :as trace]
            [clojure.core.async :as async :refer [<!! <! >! put! go go-loop]]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.core.match :refer [match]]))

(deftest spawn-terminate-normal []
  (let [result (trace/trace-collector [:p1])]
    (process/spawn
      (fn [inbox p1 p2 & other]
        (is (process/pid? (process/self)) "self must be instance of Pid")
        (is (satisfies? ap/ReadPort inbox) "inbox must be a ReadPort")
        (is (and (= p1 :p1) (= p2 :p2) "formal parameters must match actuals"))
        (is (= (count other) 0) "no extra parameters")
        :normal)
      [:p1 :p2]
      {:name :p1})
    (let [trace (result 1000)]
      (is
        (match trace
          [[_ [:start _ _ _]]
           [_ [:return :normal]]
           [_ [:terminate :normal]]] true)))))

(deftest spawn-terminate-nil []
  (let [result (trace/trace-collector [:p1])]
    (process/spawn (fn [_inbox]) [] {:name :p1})
    (let [trace (result 1000)]
      (is
        (match trace
          [[_ [:start _ _ _]]
           [_ [:return :nil]]
           [_ [:terminate :nil]]] true)))))

(defn- link-to-normal* []
  (let [p1-fn (fn [_inbox]
                (go
                  (async/<! (async/timeout 500))
                  :blah))
        p2-fn (fn [inbox] (go (<! inbox)))
        p1 (process/spawn p1-fn [] {:name :p1})
        p2 (process/spawn p2-fn [] {:name :p2 :link-to p1})] [p1 p2]))

(deftest link-to-normal []
  (let [result (trace/trace-collector [:p1 :p2])
        [p1 p2] (link-to-normal*)
        trace (result 1000)]
    (is (trace/terminated? trace p1 :blah))
    (is (trace/terminated? trace p2 :blah))))

(defn- link-to-terminated* []
  (let [p1 (process/spawn (fn [_inbox]) [] {:name :p1})
        _  (async/<!! (async/timeout 500))
        p2 (process/spawn (fn [inbox] (go (<! inbox))) [] {:name :p2 :link-to p1})]
    [p1 p2]))

(deftest link-to-terminated []
  (let [result (trace/trace-collector [:p1 :p2])
        [p1 p2] (link-to-terminated*)
        trace (result 1000)]
    (is (trace/terminated? trace p1 :nil))
    (is (trace/terminated? trace p2 :noproc))))
