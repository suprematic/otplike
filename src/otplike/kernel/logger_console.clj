(ns otplike.kernel.logger-console
  (:require
   [clojure.data.json :as json]
   [clojure.walk :as walk]
   [otplike.process :as p]
   [otplike.util :as u])
  (:import
   [java.time.format DateTimeFormatter]))

(defn- sanitize [message]
  (walk/postwalk
   (fn [node]
     (cond
       (nil? node)
       nil

       (or (string? node) (coll? node))
       node

       (or (keyword? node) (symbol? node))
       (u/strks node)

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

(defn log [config message]
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



