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

(def level-string
  {:emergency "EMERGENCY"
   :alert "ALERT"
   :critical "CRITICAL"
   :error "ERROR"
   :warn "WARN"
   :notice "NOTICE"
   :info "INFO"
   :debug "DEBUG"})

(def level-values
  {:emergency 0
   :alert 1
   :critical 2
   :error 3
   :warn 4
   :notice 5
   :info 6
   :debug 7})

(def config
  (atom
   '{:format "%1$tH:%1$tM:%1$tS.%1$tL [%2$s] %3$s %4$s - %5$s\n %6$s\n"
     :threshold :notice
     :pprint? true
     :extended? true
     :namespaces
     {otplike.core
      {:threshold :info}}}))

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
           (str/starts-with? (name ns') ns)))
        (first))]
       (select-keys
        (merge config (get-in config [:namespaces match])) [:format :threshold :pprint? :extended?])))))

(let [guard (Object.)]
  (defn- output [{:keys [format pprint?]} timestamp pid level category message args]
    (when format
      (let
       [to-print
        (clojure.core/format
         format
         timestamp pid (get level-string level) category message
         (if pprint?
           (with-out-str
             (pprint args))
           (str args)))]
        (locking guard
          (print to-print))))))

(defmacro log* [level message & args]
  (assert (even? (count args)) "args count is not even")
  (assert (#{:emergency :alert :critical :error :warn :notice :info :debug} level))

  `(when @config
     (let
      [ns-config# (lookup @config (str *ns*))]
       (when (<= (get level-values ~level 999) (get level-values (get ns-config# :threshold) -1))
         (let
          [timestamp# (java.util.Date.)
           pid#
           (if-let [self-pid# otplike.process/*self*]
             (otplike.process/pid->str self-pid#)
             "noproc")
           message# (str ~@(interpolate message))]
           (output
            ns-config# timestamp# pid#  ~level *ns* message#
            (merge
             ~(apply hash-map args)
             (when (get ns-config# :extended?)
               {::level ~level ::ns *ns* ::pid pid# ::timestamp timestamp#}))))))))

(defmacro emergency [& args]
  `(log* :emergency ~@args))

(defmacro alert [& args]
  `(log* :alert ~@args))

(defmacro critical [& args]
  `(log* :critical ~@args))

(defmacro error [& args]
  `(log* :error ~@args))

(defmacro warn [& args]
  `(log* :warn ~@args))

(defmacro notice [& args]
  `(log* :notice ~@args))

(defmacro info [& args]
  `(log* :info ~@args))

(defmacro debug [& args]
  `(log* :debug ~@args))

(let [a 10]
  (error "blah ~(+ a 3)" :key 2 :key2 {:hello [1 2 3]}))

