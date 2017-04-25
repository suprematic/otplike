(ns otplike.timer
  "This namespace contains helper functions to perform process-related
  actions (like sending a message, or exit signal) with a delay."
  (:require [otplike.process :as process :refer [!]]
            [clojure.core.match :refer [match]]
            [clojure.core.async :as async :refer [<! >! put! go go-loop]]
            [otplike.gen-server :as gs]))

(defrecord TRef [id]
  Object
  (toString [_this] (str "timer_" id)))

(alter-meta! #'->TRef assoc :no-doc true)
(alter-meta! #'map->TRef assoc :no-doc true)

(def ^:no-doc *tcount
  (atom 0))

(def ^:no-doc *timers
  (atom {}))

(defn- new-tref []
  (TRef. (swap! *tcount inc)))

(defn- action-after
  "Calls f after msecs. Returns the timer reference."
  [msecs pid f]
  (let [tref (new-tref)]
    (swap! *timers assoc tref
           (process/spawn-opt
             (process/proc-fn []
               (process/monitor pid)
               (process/receive!
                 [:DOWN _ _ _ _] :normal
                 (after msecs
                   (f)))
               (swap! *timers dissoc tref))
             []
             {:name (str tref)}))
    tref))

(defn send-after
  "Sends message to process with pid after msecs. Returns the timer
  reference."
  ([msecs message]
    (send-after msecs (process/self) message))
  ([msecs pid message]
    (action-after msecs pid #(! pid message))))

(defn exit-after
  "Exits process with pid with reason after msecs. Returns the timer
  reference."
  ([msecs reason]
    (exit-after msecs (process/self) reason))
  ([msecs pid reason]
    (action-after msecs pid #(process/exit pid reason))))

(defn kill-after
  "Kills process with pid after msecs. Returns the timer reference."
  ([msecs]
   (kill-after msecs (process/self)))
  ([msecs pid]
    (exit-after msecs pid :kill)))

(defn send-interval
  "Sends message to process with pid repeatedly at intervals of msecs.
  Returns the timer reference."
  ([msecs message]
   (send-interval msecs (process/self) message))
  ([msecs pid message]
    (let [tref (new-tref)]
      (swap! *timers assoc tref
             (process/spawn-opt
               (process/proc-fn []
                 (process/monitor pid)
                 (loop []
                   (process/receive!
                     [:DOWN _ _ _ _] (swap! *timers dissoc tref)
                     (after msecs
                       (! pid message)
                       (recur)))))
               []
               {:name (str tref)}))
      tref)))

(defn cancel
  "Cancels a previously requested timeout. tref is a unique timer
  reference returned by the timer function in question."
  [tref]
  (when-let [pid (@*timers tref)]
    (process/exit pid :normal)))
