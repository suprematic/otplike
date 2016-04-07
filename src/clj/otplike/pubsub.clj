(ns otplike.pubsub
  (:require
    [clojure.core.async :as async :refer [<! >! put! go go-loop]]
    [clojure.core.match :refer [match]]
    [otplike.process :as process :refer [!]]
    [otplike.gen-server :as gs :refer [ok reply noreply stop]]))

(defn init [self [parent]]
  (process/flag self :trap-exit true) ; ???
  (ok {:parent parent :subs #{}}))

(defn terminate [self reason {:keys [subs]}]
  (doseq [p subs]
    (! p [:pubsub-down self reason])))

(defn handle-info [_self request {:keys [parent subs] :as state}]
  (match request
    [:down parent _]
    (stop :normal state)

    [:down pid _]
    (noreply (update-in state [:subs] disj pid))

    _
    (do
      (doseq [pid subs]
        (! pid request))
      (noreply state))))

(defn handle-call [self request _from state]
  (match request
    [:sub pid]
    (do
      (process/monitor self pid)
      (reply :ok (update-in state [:subs] conj pid)))

    [:unsub pid]
    (do
      (process/demonitor self pid)
      (reply :ok (update-in state [:subs] disj pid)))))

(defn start [parent]
  (gs/start (gs/coerce-current-ns) [parent] {:link-to parent :name :pubsub}))

(defn sub [pid target]
  (gs/call pid [:sub target]))

(defn unsub [pid target]
  (gs/call pid [:unsub target]))

