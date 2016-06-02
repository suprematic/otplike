(ns otplike.timer
  (:require [otplike.process :as process]
            [clojure.core.match :refer [match]]
            [clojure.core.async :as async :refer [<! >! put! go go-loop]]
            [otplike.gen-server :as gs]))

(defrecord TRef [id]
  Object
  (toString [_this] (str "timer_" id)))

(def *tcount
  (atom 0))

(def *timers
  (atom {}))

(defn- new-tref []
  (TRef. (swap! *tcount inc)))

(defn- action-after
  "Calls f after msecs. Returns the timer reference."
  [msecs pid f]
  (let [tref (new-tref)]
    (swap! *timers assoc tref
           (process/spawn
             (fn [inbox]
               (process/monitor process/*self* pid)
               (go
                 (let [[_ port] (async/alts! [inbox (async/timeout msecs)])]
                   (when (not= port inbox)
                     (f)))
                 (swap! *timers dissoc tref)
                 :normal))
             []
             {:flags {:trap-exit true}
              :name (str tref)}))
    tref))

(defn send-after
  "Sends message to process with pid after msecs. Returns the timer
  reference."
  [msecs pid message]
  (action-after msecs pid #(async/put! pid message)))

(defn exit-after
  "Exits process with pid with reason after msecs. Returns the timer
  reference."
  [msecs pid reason]
  (action-after msecs pid #(process/exit pid reason)))

(defn cast-after
  "Casts message to gen-server with pid after msecs. Returns the timer
  reference."
  [msecs pid message]
  (action-after msecs pid #(gs/cast pid message)))

(defn kill-after [msecs pid]
  "Kills process with pid after msecs. Returns the timer reference."
  (exit-after msecs pid :kill))

(defn send-interval
  "Sends message to process with pid repeatedly at intervals of msecs.
  Returns the timer reference."
  [msecs pid message]
  (let [tref (new-tref)]
    (swap! *timers assoc tref
           (process/spawn
             (fn [inbox]
               (process/monitor process/*self* pid)
               (go
                 (loop []
                   (let [timeout (async/timeout msecs)]
                     (match (async/alts! [inbox timeout])
                            [nil timeout]
                            (do
                              (async/put! pid message)
                              (recur))
                            [_ inbox]
                            (do
                              (swap! *timers dissoc tref)
                              :normal))))))
             []
             {:flags {:trap-exit true}
              :name (str tref)}))
    tref))

(defn cancel
  "Cancels a previously requested timeout. tref is a unique timer
  reference returned by the timer function in question."
  [tref]
  (when-let [pid (@*timers tref)]
    (process/exit pid :normal)))


;******* tests
(defn test-1 []
  (let [process (process/spawn
                  (fn [inbox]
                    ;(send-after 5000 self :timer-message)
                    (go
                      (loop []
                        (let [message (<! inbox)]
                          #_(process/trace "user" (str "message: " message))
                          (when (not= message :stop)
                            (recur))))
                      :normal))
                  []
                  {:name "user"
                   :flags {:trap-exit false}})]
    (let [tref (send-after 1000 process :cancelled)]
      (cancel tref))
    (let [tref (send-interval 1000 process :interval)]
      #_(cancel tref))
    (send-after 5000 process :stop)
    ;(async/put! process :msg)
    )
  :ok)
