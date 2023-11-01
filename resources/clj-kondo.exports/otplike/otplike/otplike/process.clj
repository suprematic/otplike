(ns otplike.process
  (:require [clj-kondo.hooks-api :as api]))

(defn- symbols [x]
  (cond
    (symbol? x) [x]
    (coll? x) (mapcat symbols x)
    :else []))

(defn- clauses [in]
  (->> in (partition 2)
       (map
        (fn [[pattern body]]
          (let
           [bindings
            (mapcat #(vector % nil) (symbols pattern))]
            `(let [~@bindings]
               ~body))))))

(defmacro receive! [& in]
  (if (even? (count in))
    `(do ~@(clauses in))
    `(do
       ~@(clauses (drop-last in))
       ~(let [[after _ tm] (last in)]
          (when (not= after 'after)
            (api/reg-finding!
             (merge (meta after)
                    {:message (format "Invalid after clause: %s" after)
                     :type :otplike/invalid-after})))

          tm))))