(ns otplike.gen-server
  (:refer-clojure :exclude [cast get])
  (:require
    [clojure.core.async :as async :refer [<! >! put! go go-loop]]
    [clojure.core.match :refer [match]]
    [otplike.process :as process :refer [!]]))

(defprotocol IGenServer
  (init [_ self args]
    #_[:ok, State]
    #_[:stop, Reason])

  (handle-call [_ self request from state]
    #_[:reply Reply NewState]
    #_[:stop Reason Reply NewState]
    #_[:stop Reason NewState])


  (handle-cast [_ self request state]
    #_[:noreply NewState]
    #_[:stop Reason NewState])


  (handle-info [_ self request state]
    #_[:noreply NewState]
    #_[:stop Reason NewState])


  (terminate [_ self reason state]))

(defn bad-return [impl self state return from]
  ;(process/trace (:name impl) (str "invalid return: " return " from " from ", state: " state))

  (let [reason [:bad-return return from]]
    (terminate impl self reason state) [:terminate reason]))


(defn- cast-or-info [rqtype impl self message state]
  (let [rqfn (case rqtype :cast handle-cast :info handle-info)]
    (match (rqfn impl self message state)
      [:noreply new-state]
        [:recur new-state]

      [:stop reason new-state]
      (do
        (terminate impl self reason new-state)
        [:terminate reason])

      other
      (bad-return impl self state other rqtype))))


(defn- call* [impl self from request state]
  (match (handle-call impl self request from state)
    [:reply reply new-state]
    (do
      (if reply
        (async/put! from reply)
        (async/close! from))

      [:recur new-state])


    [:noreply new-state]
      [:recur new-state]

    [:stop reason reply new-state]
    (do
      (if reply
        (async/put! from reply)
        (async/close! from))

      (terminate impl self reason new-state)
      [:terminate reason])

    [:stop reason new-state]
    (do
      (async/put! from :stopped)
      (terminate impl self reason new-state)
      [:terminate reason])

    other
    (bad-return impl self state other :call)))

(defn put!* [chan value]
  (async/put! chan value)
  (async/close! chan))

(defn- dispatch [impl self state message]
  (match message
    [:call from [:internal :get-state]]
    (do
      (put!* from state)
      [:recur state])

    [:call from request]
    (call* impl self from request state)

    [:cast request]
    (cast-or-info :cast impl self request state)

    [:exit :shutdown]
    (do
      (terminate impl self :shutdown state)
      [:terminate :shutdown])

    _
    (cast-or-info :info impl self message state)))


; TODO take care of exception
(defn call-init [impl self args]
  (init impl self args))


(defn gen-server-proc [__pid inbox impl init-args response]
  (let [self process/*self*]
    (go
      (match (call-init impl self init-args) ;TODO handle wrong return from init
        [:ok initial-state]
        (do
          (put!* response [:ok self])
          (loop [state initial-state]
            (if-let [message (<! inbox)]
              (match (dispatch impl self state message)
                [:recur new-state]
                (recur new-state)

                [:terminate reason]
                reason))))

        [:stop reason]
        (do
          (put!* response [:error reason])
          :normal)))))

; API functions
(defn start [impl args options]
  (let [response (async/chan)]
    (process/spawn gen-server-proc [impl args response] options)
    (let [[value port] (async/alts!! [response (async/timeout 1000)])]
      (if (= port response)
        value [:error :timeout])))) ; TODO timeout on response!

(defn cast [server message]
  (! server [:cast message]))

(defn call
  ([server message]
   (call server message 5000))

  ([server message timeout]
    (let [from (async/chan) timeout (if (= :infinity timeout) (async/chan) (async/timeout timeout))]
      (! server [:call from message])
      (let [[value port] (async/alts!! [from timeout])]
        (if (= port from)
          value :timeout)))))

(def return async/put!)

(defn get [server]
  (call server [:internal :get-state]))

(defn coerce-map [{:keys [init handle-call handle-cast handle-info terminate]}]
  (reify IGenServer
    (init [_ self args]
      (if init
        (init self args)
        [:stop :no-init]))

    (handle-cast [_ self request state]
      (if handle-cast
        (handle-cast self request state)
        [:stop :no-handle-cast state]))

    (handle-call [_ self request from state]
      (if handle-call
        (handle-call self request from state)
        [:stop :no-handle-call state]))

    (handle-info [_ self request state]
      (if handle-info
        (handle-info self request state)
        [:stop :no-handle-info state]))

    (terminate [_ self reason state] ; terminate is optional
      (if terminate
        (terminate self reason state)))))


(defn- ns-function [ns name]
  (if-let [var (ns-resolve ns name)]
    (var-get var)))

(defn coerce-ns-static [ns]
  (coerce-map
    {:init (ns-function ns        'init)
     :handle-call (ns-function ns 'handle-call)
     :handle-cast (ns-function ns 'handle-cast)
     :handle-info (ns-function ns 'handle-info)
     :terminate (ns-function ns   'handle-call)}))

(defn coerce-ns-dynamic [ns]
  (reify IGenServer
    (init [_ self args]
      (if-let [init (ns-function ns 'init)]
        (init self args)
        [:stop :no-init]))

    (handle-cast [_ self request state]
      (if-let [handle-cast (ns-function ns 'handle-cast)]
        (handle-cast self request state)
        [:stop :no-handle-cast state]))

    (handle-call [_ self request from state]
      (if-let [handle-call (ns-function ns 'handle-call)]
        (handle-call self request from state)
        [:stop :no-handle-call state]))

    (handle-info [_ self request state]
      (if-let [handle-info (ns-function ns 'handle-info)]
        (handle-info self request state)
        [:stop :no-handle-info state]))

    (terminate [_ self reason state] ; terminate is optional
      (if-let [terminate (ns-function ns 'terminate)]
        (terminate self reason state)))))

(def coerce-ns coerce-ns-dynamic)

(defmacro coerce-current-ns []
  `(coerce-ns ~*ns*))

;**** tests
(def server
  (reify IGenServer
    (init [_ self [n]]
      (println "init: " n)
      (async/put! self :init-message)
      [:ok n])

    (terminate [_ self reason state]
      (println "terminate: " reason ", state: " state))

    (handle-call [_ self request from state]
      (println "handle-call: " request ", state: " state)
      (match request
        :get-async
        (do
          (async/put! from state)
          [:noreply state])

        :get-sync
        [:reply state state]))

    (handle-cast [_ self message state]
      (println "handle-cast: " message ", state: " state)
      (match message
        :dec
        [:noreply (dec state)]

        :inc
        [:noreply (inc state)]))

    (handle-info [_ self message state]
      (println "handle-info: " message ", state: " state)
      [:noreply state])))


(def server1
  (coerce-map
  {
    :init
    (fn [ self [n]]
      (println "init: " n)
      (async/put! self :init-message)
      [:ok n])

    :terminate
    (fn [self reason state]
      (println "terminate: " reason ", state: " state))

    :handle-call
    (fn [self request from state]
      (println "handle-call: " request ", state: " state)
      (match request
        :get-async
        (do
          (async/put! from state)
          [:noreply state])

        :get-sync
        [:reply state state]))

    :handle-cast
    (fn [self message state]
      (println "handle-cast: " message ", state: " state)
      (match message
        :dec
        [:noreply (dec state)]

        :inc
        [:noreply (inc state)]))

    :handle-info
    (fn [self message state]
      (println "handle-info: " message ", state: " state)
      [:noreply state])}))

(defn test-1 []
  (let [[_ gp] (start server1 [10] {})]
    (cast gp   :inc)
    (cast gp   :dec)

    (println "state-async: "
      (call gp :get-async))

    (println "state-sync: "
      (call gp :get-sync))))




