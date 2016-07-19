(ns otplike.trace-test
  (:require [clojure.test :refer [is deftest]]
            [otplike.trace :as trace]
            [otplike.test-util :refer :all]
            [otplike.process :as process]
            [clojure.core.match :refer [match]]
            [clojure.core.async :as async :refer [<!]]))

(deftest spawn-terminate-normal
  (let [result (trace/trace-collector [:p1])
        pid (process/spawn (process/proc-fn [_inbox]) [] {:name :p1})
        trace (result 1000)]
    (is (match trace
          [[_ (_ :guard #(= pid %)) [:start _ [] {:name :p1}]]
           [_ (_ :guard #(= pid %)) [:return :normal]]
           [_ (_ :guard #(= pid %)) [:terminate :normal]]] true))))

; FIXME
#_(deftest spawn-terminate-abnormal
  (let [result (trace/trace-collector [:p1])
        pfn (process/proc-fn [_inbox] (process/exit (process/self) :abnormal))
        pid (process/spawn pfn [] {:name :p1})]
    (let [trace (result 1000)]
      (is (match trace
            [[_ (_ :guard #(= pid %)) [:start _ [] {:name :p1}]]
             [_ (_ :guard #(= pid %)) [:return :abnormal]]
             [_ (_ :guard #(= pid %)) [:terminate :abnormal]]] true)))))

#_(deftest link-to-normal
  (let [result (trace/trace-collector [:p1 :p2])
        done (async/chan)
        p1-fn (process/proc-fn [_inbox]
                (is (await-completion done 100) "test failed")
                (process/exit (process/self) :blah))
        p2-fn (process/proc-fn [inbox]
                (async/close! done)
                (is (= :inbox-closed (<! (await-message inbox 100)))
                    "test failed"))
        p1 (process/spawn p1-fn [] {:name :p1})
        p2 (process/spawn p2-fn [] {:name :p2 :link-to p1})
        trace (result 1000)]
    (is (trace/terminated? trace p1 :blah))
    (is (trace/terminated? trace p2 :blah))))

#_(deftest link-to-terminated
  (let [result (trace/trace-collector [:p1 :p2])
        p1 (process/spawn (process/proc-fn [_inbox]) [] {:name :p1})
        _  (async/<!! (async/timeout 50))
        pfn2 (process/proc-fn [inbox]
               (is (= :inbox-closed (<! (await-message inbox 100)))
                   "test failed"))
        p2 (process/spawn pfn2 [] {:name :p2 :link-to p1})
        trace (result 1000)]
    (is (trace/terminated? trace p1 :normal))
    (is (trace/terminated? trace p2 :noproc))))
