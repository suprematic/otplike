(ns otplike.logger
  (:require
   [otplike.kernel.logger :as logger]))

(defn j-enabled? [category level]
  (logger/enabled? level category))

(defn j-log [category level message exception]
  (let [category (str category)]
    (logger/log
     category level
     (merge
      {:message message :in category :src :slf4j}
      (when exception
        {:exception exception})))))

(defmacro log [level input]
  `(logger/log ~*ns* ~level ~input))

#_(log :error {:message "error" :details {:password "123"}})

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


