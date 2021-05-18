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
      (when (<= (get level-codes level 999) threshold)
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
    (<= (get level-codes level 999) threshold)))

(defn j-log [category level message _]
  (log* {:level level :text message :in category}))

(defmacro log [level input]
  (assert (#{:emergency :alert :critical :error :warning :notice :info :debug} level))
  (let [ns (str *ns*)]
    `(log*
       (merge
         {:at ~ns}
         (update ~input :in
           (fn [in#]
             (if (some? in#)
               (str in#) ~ns)))
         {:level ~level}))))

(defmacro emergency [& args]
  `(log :emergency ~@args))

(defmacro alert [& args]
  `(log :alert ~@args))

(defmacro critical [& args]
  `(log :critical ~@args))

(defmacro error [& args]
  `(log :error ~@args))

(defmacro warning [& args]
  `(log :warning ~@args))

(defmacro notice [& args]
  `(log :notice ~@args))

(defmacro info [& args]
  `(log :info ~@args))

(defmacro debug [& args]
  `(log :debug ~@args))
