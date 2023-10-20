(ns otplike.logger
  (:require
   [otplike.kernel.logger :as klogger]
   [otplike.kernel.logger-console :as console]))

; FIXME move to kernel.console-logger
(defn j-enabled? [category level]
  (console/enabled? level category))

(defn j-log [category level message _]
  (let [category (str category)]
    (klogger/log* category level {:text message :in category})))

(defmacro log [level input]
  `(klogger/log* ~*ns* ~level ~input))

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

#_(log :debug {:in :zzzz :x 1 :y :x})
