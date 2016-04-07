(ns otplike.timer
  (:require [otplike.process :as process]
            [clojure.core.match :refer [match]]
            [clojure.core.async :as async :refer [<! >! put! go go-loop]]))

(defrecord TRef [id])

(def *tcount
  (atom 0))

(def *timers
  (atom {}))

(defn- action-after [delay process afn] ; -> tref
  (let [tid (str "timer" (swap! *tcount inc)) tref (TRef. tid)]
    (swap! *timers assoc tref
      (process/spawn
        (fn [pid inbox]
          (process/monitor pid process)
          (go
            (let [[_ port] (async/alts! [inbox (async/timeout delay)])]
              (when (not= port inbox)
                (afn)))
            (swap! *timers dissoc tref)
            :normal)) [] {:flags {:trap-exit true} :name tid})) tref))

(defn send-after [delay process message]
  (action-after delay process
    #(async/put! process message)))

(defn exit-after [delay process reason] ; -> tref
  (action-after delay process
    #(process/exit process reason)))

(defn kill-after [delay process]
  (exit-after delay process :kill))

(defn send-interval [period process message]
  (let [tid (str "timer" (swap! *tcount inc)) tref (TRef. tid)]
    (swap! *timers assoc tref
      (process/spawn
        (fn [pid inbox]
          (process/monitor pid process)
          (go
            (loop []
              (let [timeout (async/timeout period)]
                (match (async/alts! [inbox timeout])
                  [nil timeout]
                  (do
                    (async/put! process message)
                    (recur))

                  [_ inbox]
                  (do
                    (swap! *timers dissoc tref)
                    :normal)))))) [] {:flags {:trap-exit true} :name tid})) tref))

(defn cancel [tref]
  (when-let [proc (@*timers tref)]
    (process/exit proc :normal)))


(defn test-1 []
  (let [process (process/spawn
                  (fn [self]
                    ;(send-after 5000 self :timer-message)
                    (go
                      (loop []
                        (let [message (<! self)]
                          #_(process/trace "user" (str "message: " message))

                          (when (not= message :stop)
                            (recur)))) :normal)) [] {:name "user" :flags {:trap-exit false}})]

    (let [tref (send-after 1000 process :cancelled)]
      (cancel tref))

    (let [tref (send-interval 1000 process :interval)]
      #_(cancel tref))


    (send-after 5000 process :stop)

      ;(async/put! process :msg)

    ) :ok)



