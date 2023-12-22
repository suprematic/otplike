(ns otplike.kernel.logger-console
  (:require
   [clojure.string :as str]
   [clojure.core.match :refer [match]]
   [clojure.data.json :as json]
   [clojure.walk :as walk]
   [clojure.core.async :as async]
   [otplike.process :as p]
   [otplike.proc-util :as pu]
   [otplike.util :as u]
   [otplike.kernel.logger :as klogger])
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

(defn- in-config* [config in]
  (let
   [config'
    (->>
     (get config :namespaces)
     (u/name-keys))
    in (name in)
    match
    (->>
     config'
     (keys)
     (sort-by count #(compare %2 %1))
     (filter #(str/starts-with? in %))
     (first))]
    (->
     config
     (merge
      (get config' match))
     (dissoc :namespaces :mask-keys)
     (update
      :threshold
      #(get level-codes % -1)))))

(def ^:private in-config (memoize in-config*))

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
       (sanitize (u/exception node))

       (instance? java.time.ZonedDateTime node)
       (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME node)

       (otplike.process/pid? node)
       (otplike.process/pid->str node)

       :else
       (str node)))
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
        (.print out to-print))
      (.flush out))))

(def ^:private my-ns
  (str *ns*))

(defn- output [message]
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
         :exception (u/exception t)}}))))

(defn- filter-fn [config {:keys [level in]}]
  (let [{:keys [threshold]} (in-config config in)]
    (<= (get level-codes level 999) threshold)))

(p/proc-defn p-log [config ch]
  (p/flag :trap-exit true)
  (pu/!chan ch {:close? true :close-reason :normal})

  (loop [timeout :infinity reason nil]
    (p/receive!
     [:EXIT _ reason']
     (recur 1000 reason')

     message
     (do
       (let [message (klogger/mask config message)]
         (when (filter-fn config message)
           (output message)))
       (recur timeout reason))
     (after timeout (p/exit reason)))))