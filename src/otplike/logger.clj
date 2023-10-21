(ns otplike.logger
  (:require
   [otplike.kernel.logger :as logger]))

; FIXME move to kernel.console-logger
(defn j-enabled? [category level]
  true
  #_(console/enabled? level category))

(defn j-log [category level message _]
  (let [category (str category)]
    (logger/log* category level {:text message :in category :src :j-log})))

(defmacro log [level input]
  `(logger/log* ~*ns* ~level ~input))

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


