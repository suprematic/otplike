(ns otplike.timer
  "This namespace contains helper functions to perform process-related
  actions (like sending a message, or exit signal) with a delay."
  (:require [clojure.core.match :refer [match]]
            [clojure.core.async :as async :refer [<! >! put! go go-loop]]
            [clojure.core.async.impl.protocols :as ap]
            [otplike.process :as process :refer [!]]
            [otplike.util :as u]))

(when (and (= 1 (:major *clojure-version*))
           (< (:minor *clojure-version*) 9))
  (require '[clojure.future :refer :all]))

(declare cancel)

;; ====================================================================
;; Internal

(defn- apply-interval*
  [msecs pid f args]
   (u/check-args [(nat-int? msecs)
                  (process/pid? pid)
                  (fn? f)
                  (vector? args)])
  (let [cancel-chan (async/chan)]
     (process/spawn-opt
       (process/proc-fn []
         (let [self (process/self)]
           (process/link pid)
           (go
             (async/<! cancel-chan)
             (! self :cancel)))
         (loop []
           (process/receive!
             :cancel :ok
             [:EXIT _ _] (cancel cancel-chan)
             (after msecs
               (process/spawn (process/proc-fn [] (apply f args)))
               (recur)))))
       []
       {:flags {:trap-exit true} :name "timer-interval"})
      cancel-chan))

;; ====================================================================
;; API

(defn apply-after
  "Applies `f` to `args` after `msecs`.

  Returns the timer reference.

  Throws on bad arguments."
  ([msecs f]
   (apply-after msecs f []))
  ([msecs f args]
   (u/check-args [(nat-int? msecs)
                  (fn? f)
                  (vector? args)])
   (let [cancel (async/chan)
         timeout (async/timeout msecs)]
     (go
       (match (async/alts! [cancel timeout])
         [nil cancel]
         :ok
         [nil timeout]
         (process/spawn-opt
           (process/proc-fn []
             (try (apply f args) (catch Throwable t :ok)))
           []
           {:name "timer-action"})))
     cancel)))

(defn cancel
  "Cancels a previously requested timeout. `tref` is a unique timer
  reference returned by the related timer function.

  Throws if `tref` is not a timer reference."
  [tref]
  (u/check-args [(satisfies? ap/ReadPort tref)])
  (async/close! tref))

(defn send-after
  "Evaluates `(! pid message)` after `msecs`.

  Returns the timer reference.

  Throws on bad arguments."
  ([msecs message]
    (send-after msecs (process/self) message))
  ([msecs pid message]
   (u/check-args [(some? pid)])
   (apply-after msecs #(! pid message))))

(defn exit-after
  "Sends an exit signal with reason `reason` to pid `pid` after `msecs`.
  `pid` can be a pid or a registered name.

  Returns the timer reference.

  Throws on bad arguments."
  ([msecs reason]
   (exit-after msecs (process/self) reason))
  ([msecs pid reason]
   (u/check-args [(some? pid)])
   (apply-after msecs #(process/exit (process/resolve-pid pid) reason))))

(defn kill-after
  "The same as `exit-after` called with reason `:kill`."
  ([msecs]
   (kill-after msecs (process/self)))
  ([msecs pid]
    (exit-after msecs pid :kill)))

(defn apply-interval
  "Evaluates `(! pid message)` repeatedly at intervals of `msecs`.

  Returns the timer reference.

  Throws on bad arguments."
  ([msecs f]
   (apply-interval msecs f []))
  ([msecs f args]
   (apply-interval* msecs (process/self) f args)))

(defn send-interval
  "Evaluates `(! pid message)` repeatedly at intervals of `msecs`.

  Returns the timer reference.

  Throws on bad arguments."
  ([msecs message]
   (send-interval msecs (process/self) message))
  ([msecs pid message]
   (u/check-args [(nat-int? msecs) (some? pid)])
   (if-let [pid (process/resolve-pid pid)]
     (apply-interval* msecs pid ! [pid message])
     (async/chan))))
