(ns otplike.kernel.logger-filter
  (:require
   [clojure.string :as str]))

; as in RFC-5424
(def level-codes
  {:emergency 0
   :alert 1
   :critical 2
   :error 3
   :warning 4
   :notice 5
   :info 6
   :debug 7})

(defn- lookup [{:keys [namespaces namespaces-keys] :as config} in]
  (let
   [match
    (->> namespaces-keys
         (filter #(str/starts-with? in %))
         (first))]

    (-> config
        (merge
         (get namespaces match))
        (dissoc :namespaces :namespaces-keys :mask-keys))))

(defn- prepare-config [config]
  (letfn
   [(encode-threshhold [th]
      (get level-codes th -1))]
    (let
     [namespaces
      (->> config :namespaces
           (map
            (fn [[k v]]
              [(name k) (update v :threshold encode-threshhold)]))
           (into {}))

      namespaces-keys
      (->> namespaces keys (sort-by count #(compare %2 %1)))]

      (-> config
          (merge
           {:namespaces namespaces
            :namespaces-keys namespaces-keys})
          (update :threshold encode-threshhold)))))

(defn make-filter-fn [config]
  (let [config (prepare-config config)]
    (memoize
     (fn [level in]
       (let [{:keys [threshold]} (lookup config in)]
         (<= (get level-codes level 999) threshold))))))

#_(let
   [ffn
    (make-filter-fn
     {:threshold :warning #_4
      :namespaces
      {"a.b.c" {:threshold :error #_3}
       "a.b" {:threshold :critical #_2}}})]

    (ffn :warning "a.b.c"))





