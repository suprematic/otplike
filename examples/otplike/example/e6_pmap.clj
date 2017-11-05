(ns otplike.example.e6-pmap
  "A naive example of pmap using processes. See question in issue #30."
  (:require [otplike.process :as process :refer [!]]))

(process/proc-defn
  worker [f parent]
  (process/receive!
    [idx v]
    (try
      (! parent [(process/self) [idx (f v)]])
      (catch Throwable e
        (! parent [(process/self) [idx [:error [:exception e]]]]))))
  (recur f parent))

(process/proc-defn
  parent [result-promise f n-workers coll]
  (let [idx-coll (map-indexed vector coll)
        initial-jobs (take n-workers idx-coll)]
    (doseq [idx-v initial-jobs]
      (let [pid (process/spawn-link worker [f (process/self)])]
        (! pid idx-v)))
    (loop [active-workers (count initial-jobs)
           idx-coll (drop n-workers idx-coll)
           results (sorted-map)]
      (process/receive!
        [from [idx result]]
        (if (empty? idx-coll)
          (let [active-workers (dec active-workers)]
            (if (> active-workers 0)
              (recur active-workers idx-coll (assoc results idx result))
              (deliver result-promise (vals (assoc results idx result)))))
          (do
            (! from (first idx-coll))
            (recur
              active-workers (rest idx-coll) (assoc results idx result))))))))

(defn my-pmap [f n-workers coll]
  (if (empty? coll)
    coll
    (let [result-promise (promise)]
      (process/spawn parent [result-promise f n-workers coll])
      @result-promise)))

#_(my-pmap #(do (Thread/sleep (* % 100)) (inc %)) 3 (range 1 (inc 4)))
