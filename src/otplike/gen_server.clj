(ns otplike.gen-server
  "gen-server behaviour and related functions."
  (:refer-clojure :exclude [cast get])
  (:require
    [clojure.core.async :as async :refer [<! >! put! go go-loop]]
    [clojure.core.match :refer [match]]
    [otplike.util :as u]
    [otplike.process :as process :refer [!]]))

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

(defn- do-terminate [impl reason state]
  (try
    (terminate impl reason state)
    [:terminate reason state]
    (catch Throwable t
      [:terminate (process/ex->reason t) state])))

(defn- cast-or-info [rqtype impl message state]
  (let [rqfn (case rqtype :cast handle-cast :info handle-info)]
    (match (process/ex-catch [:ok (rqfn impl message state)])
      [:ok [:noreply new-state]]
      [:recur new-state]

      [:ok [:stop reason new-state]]
      (do-terminate impl reason new-state)

      [:ok other]
      (do-terminate impl [:bad-return-value other] state)

      [:EXIT reason]
      (do-terminate impl reason state))))

(defn- call* [impl from request state]
  (match (process/ex-catch [:ok (handle-call impl request from state)])
    [:ok [:reply reply new-state]]
    (do
      (async/put! from [::reply reply])
      [:recur new-state])

    [:ok [:noreply new-state]]
    [:recur new-state]

    [:ok [:stop reason reply new-state]]
    (let [ret (do-terminate impl reason new-state)]
      (async/put! from [::reply reply])
      ret)

    [:ok [:stop reason new-state]]
    (let [[_ reason _ :as ret] (do-terminate impl reason new-state)]
      (async/put! from [::terminated reason])
      ret)

    [:ok other]
    (let [reason [:bad-return-value other]
          [_ reason _ :as ret] (do-terminate impl reason state)]
      (async/put! from [::terminated reason])
      ret)

    [:EXIT reason]
    (let [[_ reason _ :as ret] (do-terminate impl reason state)]
      (async/put! from [::terminated reason])
      ret)))

(defn- put!* [chan value]
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
    (do-terminate impl :shutdown state)

    _
    (cast-or-info :info impl message state)))

(defn- call-init [impl args]
  (try
    (init impl args)
    (catch Exception e
      [:stop (u/stack-trace e)])))

(process/proc-defn gen-server-proc [impl init-args response]
  (match (call-init impl init-args)
    [:ok initial-state]
    (do
      (put!* response :ok)
      (loop [state initial-state]
        (process/receive!
          message (match (dispatch impl state message)
                    [:recur new-state] (recur new-state)
                    [:terminate reason _new-state] (process/exit reason)))))

    [:stop reason]
    (put!* response [:error reason])

    other
    (put!* response [:error [:bad-return-value other]])))

(alter-meta! #'gen-server-proc assoc :no-doc true)

(defn- coerce-map [{:keys [init handle-call handle-cast handle-info terminate]}]
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

(defn- coerce-ns-static [impl-ns]
  (coerce-map
    {:init (ns-function impl-ns 'init)
     :handle-call (ns-function impl-ns 'handle-call)
     :handle-cast (ns-function impl-ns 'handle-cast)
     :handle-info (ns-function impl-ns 'handle-info)
     :terminate (ns-function impl-ns 'terminate)}))

(defn- coerce-ns-dynamic [impl-ns]
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

(def ^:private coerce-ns coerce-ns-dynamic)

(defn- ->gen-server [server-impl]
  (match server-impl
    (impl :guard #(satisfies? IGenServer %)) impl

    (impl-map :guard map?) (coerce-map impl-map)

    (impl-ns :guard #(instance? clojure.lang.Namespace %))
    (coerce-ns impl-ns)))

; API functions

(defn start
  "Starts the server, passing args to server's init function.

  Arguments:
  server-impl - IGenServer implementation, or map, or namespace.
  args - any form that is passed as the argument to init function.
  options - options argument of process/spawn passed when starting
  a server process.

  Returns:
  [:ok pid] if server started successfully,
  [:error :no-init] if server implementation doesn't provide init
  function,
  [:error [:bad-return-value value]] if init returns a bad value,
  [:error reason] otherwise.

  Throws on illegal arguments."
  [server-impl args options]
  (let [gs (->gen-server server-impl)
        response (async/chan)
        pid (process/spawn gen-server-proc [gs args response] options)]
    ; TODO allow to override timeout passing it as argument
    ; FIXME handle timeout
    (match (async/alts!! [response (async/timeout 1000)])
      [:ok response] [:ok pid]
      [[:error reason] response] [:error reason])))

(defmacro start-ns [params options]
  "Starts the server, taking current ns as a implementation source.
  See start for more info."
  `(gs/start ~*ns* ~params ~options))

(defn cast [server message]
  (when-not (! server [:cast message])
    (throw (Exception. "noproc"))))

(defn call
  ([server message]
   (call server message 5000))
  ([server message timeout]
   (let [reply-to (async/chan)
         timeout (if (= :infinity timeout)
                   (async/chan)
                   (async/timeout timeout))]
     (when-not (! server [:call reply-to message])
       (throw (Exception. "noproc")))
     (match (async/alts!! [reply-to timeout])
       [[::terminated reason] reply-to]
       (process/exit reason)

       [[::reply value] reply-to] value

       [nil timeout] (throw (Exception. "timeout"))))))

(defn reply [to response]
  (async/put! to [::reply response]))

(defn get [server]
  (call server [:internal :get-state]))
