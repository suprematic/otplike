(ns otplike.gen-server
  "gen-server behaviour and related functions."
  (:refer-clojure :exclude [cast get])
  (:require
    [clojure.future :refer :all]
    [clojure.core.async :as async :refer [<! >! put! go go-loop]]
    [clojure.core.match :refer [match]]
    [clojure.spec :as spec]
    [otplike.util :as u]
    [otplike.process :as process :refer [!]]))

(defprotocol IGenServer
  (init [_ args]
    #_[:ok State]
    #_[:stop Reason])

  (handle-call [_ request from state]
    #_[:reply Reply NewState]
    #_[:noreply NewState]
    #_[:stop Reason Reply NewState]
    #_[:stop Reason NewState])

  (handle-cast [_ request state]
    #_[:noreply NewState]
    #_[:stop Reason NewState])

  (handle-info [_ request state]
    #_[:noreply NewState]
    #_[:stop Reason NewState])

  (terminate [_ reason state]))

;; ====================================================================
;; Specs

(spec/def ::from any?)

;; ====================================================================
;; Internal

(defn- do-terminate [impl reason state]
  (try
    (terminate impl reason state)
    [:terminate reason state]
    (catch Throwable t
      [:terminate (process/ex->reason t) state])))

(defn- cast-or-info [rqtype impl message state]
  (let [rqfn (case rqtype ::cast handle-cast ::info handle-info)]
    (match (process/ex-catch [:ok (rqfn impl message state)])
      [:ok [:noreply new-state]]
      [:recur new-state]

      [:ok [:stop reason new-state]]
      (do-terminate impl reason new-state)

      [:ok other]
      (do-terminate impl [:bad-return-value other] state)

      [:EXIT reason]
      (do-terminate impl reason state))))

(defn- do-handle-call [impl from request state]
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

(defn- dispatch [impl parent state message]
  (match message
    [::call from [::get-state]]
    (do
      (put!* from [::reply state])
      [:recur state])

    [::call from request]
    (do-handle-call impl from request state)

    [::cast request]
    (cast-or-info ::cast impl request state)

    [:EXIT parent reason]
    (do-terminate impl reason state)

    _
    (cast-or-info ::info impl message state)))

(process/proc-defn gen-server-proc [impl init-args parent response]
  (match (process/ex-catch [:ok (init impl init-args)])
    [:ok [:ok initial-state]]
    (do
      (put!* response :ok)
      (loop [state initial-state]
        (process/receive!
          message (match (dispatch impl parent state message)
                    [:recur new-state] (recur new-state)
                    [:terminate :normal _new-state] :ok
                    [:terminate reason _new-state] (process/exit reason)))))

    [:ok [:stop reason]]
    (do
      (put!* response [:error reason])
      (process/exit reason))

    [:ok other]
    (let [reason [:bad-return-value other]]
      (put!* response [:error reason])
      (process/exit reason))

    [:EXIT reason]
    (do
      (put!* response [:error reason])
      (process/exit reason))))

(alter-meta! #'gen-server-proc assoc :no-doc true)

(defn- call-init [init args]
  (if init
    (apply init args)
    (process/exit [:undef ['init [args]]])))

(defn- call-handle-call [handle-call request from state]
  (if handle-call
    (handle-call request from state)
    (process/exit [:undef ['handle-call [request from state]]])))

(defn- call-handle-cast [handle-cast request state]
  (if handle-cast
    (handle-cast request state)
    (process/exit [:undef ['handle-cast [request state]]])))

(defn- call-handle-info [handle-info request state]
  (if handle-info
    (handle-info request state)
    (process/exit [:undef ['handle-info [request state]]])))

(defn- call-terminate [terminate reason state]
  (if terminate
    (terminate reason state)))

(defn- coerce-map [{:keys [init handle-call handle-cast handle-info terminate]}]
  (reify IGenServer
    (init [_ args]
      (call-init init args))

    (handle-cast [_ request state]
      (call-handle-cast handle-cast request state))

    (handle-call [_ request from state]
      (call-handle-call handle-call request from state))

    (handle-info [_ request state]
      (call-handle-info handle-info request state))

    (terminate [_ reason state] ; terminate is optional
      (call-terminate terminate reason state))))

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
      (call-init (ns-function impl-ns 'init) args))

    (handle-cast [_ request state]
      (call-handle-cast (ns-function impl-ns 'handle-cast) request state))

    (handle-call [_ request from state]
      (call-handle-call (ns-function impl-ns 'handle-call) request from state))

    (handle-info [_ request state]
      (call-handle-info (ns-function impl-ns 'handle-info) request state))

    (terminate [_ reason state] ; terminate is optional
      (call-terminate (ns-function impl-ns 'terminate) reason state))))

(def ^:private coerce-ns coerce-ns-dynamic)

(defn- ->gen-server [server-impl]
  (match server-impl
    (impl :guard #(satisfies? IGenServer %)) impl

    (impl-map :guard map?) (coerce-map impl-map)

    (impl-ns :guard #(instance? clojure.lang.Namespace %))
    (coerce-ns impl-ns)))

; API functions

(defn- call* [server message timeout-ms]
  (let [reply-to (async/chan)
        timeout (if (= :infinity timeout-ms)
                  (async/chan)
                  (async/timeout timeout-ms))]
    (if-not (! server [::call reply-to message])
      [:error :noproc]
      (match (async/alts!! [reply-to timeout]) ;TODO make call to be macro and use alts! ?
        [[::terminated reason] reply-to] [:error reason]
        [[::reply value] reply-to] [:ok value]
        [nil timeout] [:error :timeout]))))

(defn- start*
  [server args {:keys [timeout spawn-opt] :or {timeout :infinity spawn-opt {}}}]
  (let [gs (->gen-server server)
        response (async/chan)
        parent (process/self)
        timeout (u/timeout-chan timeout)
        pid (process/spawn-opt
              gen-server-proc [gs args parent response] spawn-opt)]
    ; TODO allow to override timeout passing it as argument
    (match (async/alts!! [response timeout])
           [:ok response] [:ok pid]
           [[:error reason] response] [:error reason]
           [nil timeout] (do (process/unlink pid)
                             (process/exit pid :kill)
                             [:error :timeout]))))

;; ====================================================================
;; API

(defn start
  "Starts the server, passing args to server's init function.

  Arguments:
  server-impl - IGenServer implementation, or map, or namespace.
  args - any form that is passed as the argument to init function.

  Options:
  :timeout - time in milliseconds gen-server is allowed to spend
    initializing  or it is terminated and the start function returns
    [:error :timeout].
  :spawn-opt - options used to spawn the gen-server process (see
    process/spawn-opt)

  Returns:
  [:ok pid] if server started successfully,
  [:error :no-init] if server implementation doesn't provide init
  function,
  [:error [:bad-return-value value]] if init returns a bad value,
  [:error reason] otherwise.

  Throws on illegal arguments."
  ([server]
   (start server [] {}))
  ([server args]
   (start server args {}))
  ([server args options]
   (start* server args options))
  ([reg-name server args options]
   (start* server args (assoc-in options [:spawn-opt :register] reg-name))))

(defn start-link
  ([server]
   (start-link server [] {}))
  ([server args]
   (start-link server args {}))
  ([server args options]
   (start* server args (assoc-in options [:spawn-opt :link] true)))
  ([reg-name server args options]
   (start-link
     server args (assoc-in options [:spawn-opt :register] reg-name))))

(defmacro start-ns
  "Starts the server, taking current ns as a implementation source.
  See start for more info."
  ([]
   `(start-ns [] {}))
  ([args]
   `(start-ns ~args {}))
  ([args options]
   `(start ~*ns* ~args ~options))
  ([reg-name args options]
   `(start ~reg-name ~*ns* ~args ~options)))

(defmacro start-link-ns
  ([]
   `(start-link-ns [] {}))
  ([args]
   `(start-link-ns ~args {}))
  ([args options]
   `(start-link ~*ns* ~args ~options))
  ([reg-name args options]
   `(start-link ~reg-name ~*ns* ~args ~options)))

(defn call
  ([server message]
   (match (call* server message 5000)
     [:ok ret] ret
     [:error reason] (process/exit [reason [`call [server message]]])))
  ([server message timeout-ms]
   (match (call* server message timeout-ms)
     [:ok ret] ret
     [:error reason] (process/exit
                       [reason [`call [server message timeout-ms]]]))))

(defn cast [server message]
  (! server [::cast message]))

(defn reply [to response]
  (async/put! to [::reply response]))

(defn ^:no-doc get [server]
  (call server [::get-state]))
