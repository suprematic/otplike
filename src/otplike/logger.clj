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

(def my-ns *ns*)

(defn- json-safe [input]
  (walk/postwalk
    (fn [node]
      (cond
        (nil? node)
        nil

        (or (string? node) (coll? node))
        node

        (keyword? node)
        (.substring (str node) 1)

        (symbol? node)
        (str node)

        (instance? java.lang.Throwable node)
        (json-safe (util/exception node))

        (instance? java.time.ZonedDateTime node)
        (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME node)

        (otplike.process/pid? node)
        (otplike.process/pid->str node)

        :else
        (str node)))
    input))

(defn- output [{:keys [pprint? mask-keys]} input]


  (let
    [pprint?
     (if (some? pprint?)
       pprint?
       (some? (System/console)))]
    (try
      (let
        [input
         (walk/postwalk
           (fn [node]
             (cond
               (map? node)
               (->> node
                 (map
                   (fn [[k v]]
                     (if-not (contains? mask-keys k)
                       [k v]
                       [k "*********"])))
                 (into {}))
               :else node))
           input)

         input
         (json-safe input)

         to-print
         (if pprint?
           (with-out-str
             (json/pprint input))
           (json/write-str input))]

      ; maybe use single thread executor with limited queue
        (let [out (System/out)]
          (locking out
            (if-not pprint?
              (.println out to-print)
              (.print out to-print)))))

      (catch Throwable t
        (let [out (System/out)] ; safe enough (EDN, no pprint or conversions)
          (locking out
            (let
              [input
               {:in
                (str my-ns)
                :when
                (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME
                  (java.time.ZonedDateTime/now))
                :level :error
                :log :event
                :result :error
                :text (.getMessage t)
                :pid
                (or otplike.process/*self* "noproc")
                :details
                {:input (str input)
                 :exception (util/exception t)}}
               input
               (json-safe input)

               to-print
               (if pprint?
                 (with-out-str
                   (json/pprint input))
                 (json/write-str input))]

              (if-not pprint?
                (.println out to-print)
                (.print out to-print)))))))))

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
             (cond
               (or (keyword? in#) (symbol? in#))
               (str (or (namespace in#) ~ns) "/" (name in#))

               (string? in#)
               in#

               :else
               ~ns)))
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
