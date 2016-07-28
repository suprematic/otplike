(ns otplike.gen-server
  (:refer-clojure :exclude [cast get])
  (:require
    [clojure.core.async :as async :refer [<! >! put! go go-loop]]
    [clojure.core.match :refer [match]]
    [otplike.process :as process :refer [! defproc]]))

(defprotocol IGenServer
  (init [_ args]
    #_[:ok, State]
    #_[:stop, Reason])

  (handle-call [_ request from state]
    #_[:reply Reply NewState]
    #_[:stop Reason Reply NewState]
    #_[:stop Reason NewState])


  (handle-cast [_ request state]
    #_[:noreply NewState]
    #_[:stop Reason NewState])


  (handle-info [_ request state]
    #_[:noreply NewState]
    #_[:stop Reason NewState])


  (terminate [_ reason state]))

(defn- bad-return [impl state return from]
  ;(process/trace (:name impl) (str "invalid return: " return " from " from ", state: " state))
  (let [reason [:bad-return return from]]
    (terminate impl reason state) [:terminate reason]))


(defn- cast-or-info [rqtype impl message state]
  (let [rqfn (case rqtype :cast handle-cast :info handle-info)]
    (match (rqfn impl message state)
      [:noreply new-state]
        [:recur new-state]

      [:stop reason new-state]
      (do
        (terminate impl reason new-state)
        [:terminate reason])

      other
      (bad-return impl state other rqtype))))


(defn- call* [impl from request state]
  (match (handle-call impl request from state)
    [:reply reply new-state]
    (do
      (if (some? reply)
        (async/put! from reply)
        (async/close! from))

      [:recur new-state])


    [:noreply new-state]
      [:recur new-state]

    [:stop reason reply new-state]
    (do
      (if (some? reply)
        (async/put! from reply)
        (async/close! from))

      (terminate impl reason new-state)
      [:terminate reason])

    [:stop reason new-state]
    (do
      (async/put! from :stopped)
      (terminate impl reason new-state)
      [:terminate reason])

    other
    (bad-return impl state other :call)))

(defn put!* [chan value]
  (async/put! chan value)
  (async/close! chan))

(defn- dispatch [impl state message]
  (match message
    [:call from [:internal :get-state]]
    (do
      (put!* from state)
      [:recur state])

    [:call from request]
    (call* impl from request state)

    [:cast request]
    (cast-or-info :cast impl request state)

    [:EXIT _ :shutdown]
    (do
      (terminate impl :shutdown state)
      [:terminate :shutdown])

    _
    (cast-or-info :info impl message state)))


; TODO take care of exception
(defn call-init [impl args]
  (init impl args))


(defproc gen-server-proc [inbox impl init-args response]
  (match (call-init impl init-args) ;TODO handle wrong return from init
    [:ok initial-state]
    (do
      (put!* response :ok)
      (loop [state initial-state]
        (if-let [message (<! inbox)]
          (match (dispatch impl state message)
            [:recur new-state]
            (recur new-state)

            [:terminate reason]
            reason))))

    [:stop reason]
    (put!* response [:error reason])))

; API functions
(defn start [impl args options]
  (let [response (async/chan)
        pid (process/spawn gen-server-proc [impl args response] options)]
    (match (async/alts!! [response (async/timeout 1000)]) ; TODO allow to override timeout passing it as argument
      [:ok response]
      [:ok pid]

      [[:error reason] response]
      [:error reason]))) ; TODO timeout on response!

(defn cast [server message]
  (! server [:cast message]))

(defn call
  ([server message]
   (call server message 5000))

  ([server message timeout]
    (let [from (async/chan)
          timeout (if (= :infinity timeout) (async/chan) (async/timeout timeout))]
      (! server [:call from message])
      (match (async/alts!! [from timeout])
        [value port] value
        [nil timeout] (throw (Exception. "timeout"))))))

(def reply async/put!)

(defn get [server]
  (call server [:internal :get-state]))

(defn coerce-map [{:keys [init handle-call handle-cast handle-info terminate]}]
  (reify IGenServer
    (init [_ args]
      (if init
        (init args)
        [:stop :no-init]))

    (handle-cast [_ request state]
      (if handle-cast
        (handle-cast request state)
        [:stop :no-handle-cast state]))

    (handle-call [_ request from state]
      (if handle-call
        (handle-call request from state)
        [:stop :no-handle-call state]))

    (handle-info [_ request state]
      (if handle-info
        (handle-info request state)
        [:stop :no-handle-info state]))

    (terminate [_ reason state] ; terminate is optional
      (if terminate
        (terminate reason state)))))


(defn- ns-function [fun-ns fun-name]
  (if-let [fun-var (ns-resolve fun-ns fun-name)]
    (var-get fun-var)))

(defn coerce-ns-static [impl-ns]
  (coerce-map
    {:init (ns-function impl-ns 'init)
     :handle-call (ns-function impl-ns 'handle-call)
     :handle-cast (ns-function impl-ns 'handle-cast)
     :handle-info (ns-function impl-ns 'handle-info)
     :terminate (ns-function impl-ns 'handle-call)}))

(defn coerce-ns-dynamic [impl-ns]
  (reify IGenServer
    (init [_ args]
      (if-let [init (ns-function impl-ns 'init)]
        (init args)
        [:stop :no-init]))

    (handle-cast [_ request state]
      (if-let [handle-cast (ns-function impl-ns 'handle-cast)]
        (handle-cast request state)
        [:stop :no-handle-cast state]))

    (handle-call [_ request from state]
      (if-let [handle-call (ns-function impl-ns 'handle-call)]
        (handle-call request from state)
        [:stop :no-handle-call state]))

    (handle-info [_ request state]
      (if-let [handle-info (ns-function impl-ns 'handle-info)]
        (handle-info request state)
        [:stop :no-handle-info state]))

    (terminate [_ reason state] ; terminate is optional
      (if-let [terminate (ns-function impl-ns 'terminate)]
        (terminate reason state)))))

(def coerce-ns coerce-ns-dynamic)

(defmacro coerce-current-ns []
  `(coerce-ns ~*ns*))
