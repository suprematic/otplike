(ns otplike.logger
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [otplike.process]))

; based on https://github.com/clojure/core.incubator/blob/master/src/main/clojure/clojure/core/strint.clj
; by Chas Emerick <cemerick @snowtide.com>
(defn- silent-read [s]
  (try
    (let [r (-> s java.io.StringReader. java.io.PushbackReader.)]
      [(read r) (slurp r)])
    (catch Exception _)))

(defn- interpolate
  ([s atom?]
   (lazy-seq
    (if-let [[form rest] (silent-read (subs s (if atom? 2 1)))]
      (cons form (interpolate (if atom? (subs rest 1) rest)))
      (cons (subs s 0 2) (interpolate (subs s 2))))))
  ([^String s]
   (if-let
    [start
     (->>
      ["~{" "~("]
      (map #(.indexOf s ^String %))
      (remove #(== -1 %))
      sort
      first)]
     (lazy-seq
      (cons
       (subs s 0 start)
       (interpolate (subs s start) (= \{ (.charAt s (inc start))))))
     [s])))

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
   '{:format "%1$tH:%1$tM:%1$tS.%1$tL [%2$s] %3$s %4$s - %5$s\n %6$s\n"
     :threshold :notice
     :pprint? true
     :extended? true
     :namespaces {}}))

(defn set-config! [config']
  (reset! config config'))

(def lookup
  (memoize
   (fn [config ns]
     (let
      [match
       (->>
        (get config :namespaces)
        (keys)
        (filter
         (fn [ns']
           (str/starts-with? ns (name ns'))))
        (first))]
       (-> config
           (merge
            (get-in config [:namespaces match]))
           (select-keys [:format :threshold :pprint? :extended?])
           (update
            :threshold
            #(get level-codes % -1)))))))

(defn- output [{:keys [format pprint?]} timestamp pid level ns message args]
  (when format
    (let
     [to-print
      (clojure.core/format
       format
       timestamp pid (get level-string level) ns message
       (if pprint?
         (with-out-str
           (pprint args))
         (str args)))]

      (let [out (System/out)]
        (locking out
          (.print out to-print))))))

(defn log* [ns-str level message args]
  (when @config
    (let
     [{:keys [threshold extended?] :as ns-config} (lookup @config ns-str)]
      (when (<= level threshold)
        (let
         [timestamp (java.util.Date.)
          pid
          (if-let [self-pid otplike.process/*self*]
            (otplike.process/pid->str self-pid)
            "noproc")]
          (output
           ns-config timestamp pid level ns-str message
           (merge
            {}
            args
            (when extended?
              {::level level ::ns ns-str ::pid pid ::timestamp timestamp}))))))))

(defn enabled?* [ns-str level]
  (let [{:keys [threshold]} (lookup @config ns-str)]
    (<= level threshold)))

(defmacro log [level message & args]
  (assert (even? (count args)) "args count is not even")
  (assert (#{:emergency :alert :critical :error :warn :notice :info :debug} level))
  `(log* ~(str *ns*) ~(get level-codes level 999) (str ~@(interpolate message)) ~(apply hash-map args)))

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