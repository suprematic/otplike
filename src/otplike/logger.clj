(ns otplike.logger
  (:require
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [clojure.data.json :as json]
    [clojure.walk :as walk]
    [otplike.process]
    [otplike.util :as util])
  (:import
    [java.time.format DateTimeFormatter]))

(def level-codes
  {:emergency 0
   :alert 1
   :critical 2
   :error 3
   :warn 4
   :notice 5
   :info 6
   :debug 7
   :trace 8})

(def level-string
  {0 "EMERGENCY"
   1 "ALERT"
   2 "CRITICAL"
   3 "ERROR"
   4 "WARN"
   5 "NOTICE"
   6 "INFO"
   7 "DEBUG"
   8 "TRACE"})

(defonce config
  (atom
    '{:threshold :notice
      :pprint? true
      :mask-keys #{}
      :namespaces {}}))

(defn set-config! [config']
  (reset! config config'))

(def in-config
  (memoize
    (fn [config ns]
      (let
        [match
         (->> (get config :namespaces)
           (keys)
           (map str)
           (sort-by count
             #(compare %2 %1))
           (filter
             #(str/starts-with? ns %))
           (first))]
        (-> config
          (merge
            (get-in config [:namespaces match]))
          (dissoc :namespaces)
          (update
            :threshold
            #(get level-codes % -1)))))))

(defn- output [{:keys [pprint? format mask-keys]} input]
  (try
    (let
      [input
       (-> input
         (update :level level-string))

       input
       (walk/postwalk
         (fn [node]
           (cond
             (instance? java.lang.Throwable node)
             (util/stack-trace node)

             (instance? java.time.ZonedDateTime node)
             (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME node)

             (map? node)
             (->> node
               (map
                 (fn [[k v]]
                   (if-not (contains? mask-keys k)
                     [k v]
                     [k "*********"])))
               (into {}))
             :else
             node))
         input)

       to-print
       (if (not= format :json)
         (if pprint?
           (with-out-str
             (pprint/pprint input))
           (str input))

         (if pprint?
           (with-out-str
             (json/pprint input))
           (json/write-str input)))]

      ; maybe use single thread executor with limited queue
      (let [out (System/out)]
        (locking out
          (.print out to-print))))

    (catch Throwable t
      (let [out (System/out)] ; safe enough (EDN, no pprint or conversions)
        (locking out
          (.print out
            {:in
             (str *ns*)
             :when
             (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME
               (java.time.ZonedDateTime/now))
             :level
             :error
             :log :event
             :result :error
             :details
             (util/stack-trace t)}))))))

(defn id []
  (str (java.util.UUID/randomUUID)))

(defn log* [{:keys [in level] :as input}]
  (when @config
    (let
      [{:keys [threshold] :as ns-config} (in-config @config in)]
      (when (<= level threshold)
        (let
          [when
           (java.time.ZonedDateTime/now)

           pid
           (or (some-> otplike.process/*self* otplike.process/pid->str) "noproc")]
          (output ns-config
            (merge input
              {:pid pid :when when :id (id)})))))))

(defn j-enabled? [category level]
  (let [{:keys [threshold]} (in-config @config category)]
    (<= level threshold)))

(defn j-log [category level message _]
  (log* {:level level :text message :in category}))

(defmacro log [level input]
  (assert (#{:emergency :alert :critical :error :warn :notice :info :debug} level))
  `(log*
     (merge
       {:at ~(str *ns*)}
       ~(update input :in
          (fn [in]
            (str
              (cond
                (nil? in) *ns*
                (keyword? in) (str (or (namespace in) (str *ns*)) "/" (name in))
                :else in))))
       {:level
        ~(get level-codes level 999)})))

(defmacro emergency [& args]
  `(log :emergency ~@args))

(defmacro alert [& args]
  `(log :alert ~@args))

(defmacro critical [& args]
  `(log :critical ~@args))

(defmacro error [& args]
  `(log :error ~@args))

(defmacro warn [& args]
  `(log :warn ~@args))

(defmacro notice [& args]
  `(log :notice ~@args))

(defmacro info [& args]
  `(log :info ~@args))

(defmacro debug [& args]
  `(log :debug ~@args))
