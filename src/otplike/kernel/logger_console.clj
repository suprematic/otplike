(ns otplike.kernel.logger-console
  (:require
   [clojure.string :as str]
   [clojure.data.json :as json]
   [clojure.walk :as walk]
   [otplike.process]
   [otplike.util :as util])
  (:import
   [java.time.format DateTimeFormatter]))

(defonce config
  (atom nil))

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

(defn- lookup [config in]
  (let
   [match
    (->>
     (get config :namespaces)
     (keys)
     (map str)
     (sort-by count #(compare %2 %1))
     (filter #(str/starts-with? in %))
     (first))]
    (->
     config
     (merge
      (get-in config [:namespaces match]))
     (dissoc :namespaces)
     (update
      :threshold
      #(get level-codes % -1)))))

(defonce cache (atom {}))

(defn- in-config [in]
  (if-let [config (get @cache in)]
    config
    (let [config (lookup @config in)]
      (swap! cache assoc in config)
      config)))

(defn- sanitize [message]
  (walk/postwalk
   (fn [node]
     (cond
       (nil? node)
       nil

       (or (string? node) (coll? node))
       node

       (keyword? node)
       (name node)

       (symbol? node)
       (name node)

       (instance? java.lang.Throwable node)
       (sanitize (util/exception node))

       (instance? java.time.ZonedDateTime node)
       (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME node)

       (otplike.process/pid? node)
       (otplike.process/pid->str node)

       :else
       (str node)))
   message))

(defn- mask [{:keys [mask-keys]} message]
  (walk/postwalk
   (fn [node]
     (cond
       (map? node)
       (->>
        node
        (map
         (fn [[k v]]
           (if (contains? mask-keys k)
             [k "*********"]
             [k v])))
        (into {}))
       :else
       node))
   message))

(defn- json-print [{:keys [pprint?]} message]
  (let
   [message
    (sanitize message)
    pprint?
    (if (some? pprint?)
      (boolean pprint?)
      (some? (System/console)))
    to-print
    (if pprint?
      (with-out-str
        (json/pprint message))
      (json/write-str message))
    out (System/out)]
    (locking out
      (if-not pprint?
        (.println out to-print)
        (.print out to-print)))))

(def my-ns
  (str *ns*))

(defn enabled? [level in]
  (let [{:keys [threshold]} (in-config in)]
    (<= (get level-codes level 999) threshold)))

(defn output [message]
  (let [message (mask @config message)]
    (try
      (json-print config message)
      (catch Throwable t
        (json-print ; must be safe
         config
         {:at my-ns
          :in (str my-ns "/output")
          :when
          (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME (java.time.ZonedDateTime/now))
          :level :error
          :log :event
          :result :error
          :text (.getMessage t)
          :pid
          (or otplike.process/*self* "noproc")
          :details
          {:input (str message)
           :exception (util/exception t)}})))))