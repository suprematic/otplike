(ns otplike.gen-server
  "gen-server behaviour and related functions."
  (:refer-clojure :exclude [cast get])
  (:require [clojure.core.async :as async]
            [clojure.core.match :refer [match]]
            [clojure.spec.alpha :as spec]
            [otplike.util :as u]
            [otplike.process :as process :refer [!]]
            [otplike.kernel.tracing :as tracing]))

(when (and (= 1 (:major *clojure-version*))
           (< (:minor *clojure-version*) 9))
  (require '[clojure.future :refer :all]))

(declare reply)

(defprotocol IGenServer
  (init [_ args]
    #_[:ok state]
    #_[:ok state timeout]
    #_[:stop reason])

  (handle-call [_ request from state]
    #_[:reply reply new-state]
    #_[:reply reply new-state timeout]
    #_[:noreply new-state]
    #_[:noreply new-state timeout]
    #_[:stop reason reply new-state]
    #_[:stop reason new-state])

  (handle-cast [_ request state]
    #_[:noreply new-state]
    #_[:noreply new-state timeout]
    #_[:stop reason new-state])

  (handle-info [_ request state]
    #_[:noreply new-state]
    #_[:noreply new-state timeout]
    #_[:stop reason new-state])

  (terminate [_ reason state]))

;; ====================================================================
;; Specs

(spec/def ::from any?)

;; ====================================================================
;; Internal

(defn- do-terminate [impl reason state]
  (process/async
   (match (process/ex-catch
           [:ok (process/await?! (terminate impl reason state))])
     [:ok _] [:terminate reason state]
     [:EXIT exit-reason] [:terminate exit-reason state])))

(defn- cast-or-info [rqtype impl message state]
  (process/async
   (let [[rqfn rqtype] (case rqtype
                         ::cast [handle-cast 'handle-cast]
                         ::info [handle-info 'handle-info])]
     (match (process/ex-catch
             [:ok (process/await?! (rqfn impl message state))])
       [:ok [:noreply new-state]]
       [:recur new-state :infinity]

       [:ok  [:noreply new-state timeout]]
       [:recur new-state timeout]

       [:ok [:stop reason new-state]]
       (process/await! (do-terminate impl reason new-state))

       [:ok other]
       (process/await!
        (do-terminate impl [:bad-return-value rqtype other] state))

       [:EXIT reason]
       (process/await! (do-terminate impl reason state))))))

(defn- do-handle-call [impl from request state]
  (process/async
   (match (process/ex-catch
           [:ok (process/await?! (handle-call impl request from state))])
     [:ok [:reply response new-state]]
     (do
       (reply from response)
       [:recur new-state :infinity])

     [:ok  [:reply response new-state timeout]]
     (do
       (reply from response)
       [:recur new-state timeout])

     [:ok [:noreply new-state]]
     [:recur new-state :infinity]

     [:ok  [:noreply new-state timeout]]
     [:recur new-state timeout]

     [:ok [:stop reason response new-state]]
     (let [ret (process/await! (do-terminate impl reason new-state))]
       (reply from response)
       ret)

     [:ok [:stop reason new-state]]
     (let [[_ reason _ :as ret]
           (process/await! (do-terminate impl reason new-state))]
       ret)

     [:ok other]
     (let [reason [:bad-return-value 'handle-call other]
           [_ reason _ :as ret]
           (process/await! (do-terminate impl reason state))]
       ret)

     [:EXIT reason]
     (let [[_ reason _ :as ret]
           (process/await! (do-terminate impl reason state))]
       ret))))

(defn- put!* [chan value]
  (async/put! chan value)
  (async/close! chan))

(defn- dispatch [impl parent state message]
  (match message
    [::call from ::get-state]
    (do
      (reply from state)
      (process/async-value :recur))

    [::call from request tracing-context]
    (tracing/with-context tracing-context
      #(do-handle-call impl from request state))

    [::cast request tracing-context]
    (tracing/with-context tracing-context
      #(cast-or-info ::cast impl request state))

    [:EXIT parent reason]
    (do-terminate impl reason state)

    _
    (cast-or-info ::info impl message state)))

(defn- enter-loop [impl parent state timeout]
  (process/async
   (loop [state state
          timeout timeout]
     (let [timeout (if (pos-int? timeout)
                     (async/timeout timeout)
                     timeout)
           message (process/receive!
                    message message
                    (after timeout :timeout))]
       (match (process/await! (dispatch impl parent state message))
         :recur (recur state timeout)
         [:recur new-state new-timeout] (recur new-state new-timeout)
         [:terminate :normal _new-state] :ok
         [:terminate reason _new-state] (process/exit reason))))))

(process/proc-defn gen-server-proc [impl init-args parent response]
  (match (process/ex-catch
          [:ok (process/await?! (init impl init-args))])
    [:ok [:ok initial-state]]
    (do
      (put!* response :ok)
      (process/await! (enter-loop impl parent initial-state :infinity)))

    [:ok [:ok initial-state timeout]]
    (do
      (put!* response :ok)
      (process/await! (enter-loop impl parent initial-state timeout)))

    [:ok [:stop reason]]
    (do
      (put!* response [:error reason])
      (process/exit reason))

    [:ok other]
    (let [reason [:bad-return-value 'init other]]
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

(defn- coerce-map
  [{:keys [init handle-call handle-cast handle-info terminate]}]
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

(defn- coerce-ns-static [impl-ns]
  (coerce-map
   {:init (u/ns-function impl-ns 'init)
    :handle-call (u/ns-function impl-ns 'handle-call)
    :handle-cast (u/ns-function impl-ns 'handle-cast)
    :handle-info (u/ns-function impl-ns 'handle-info)
    :terminate (u/ns-function impl-ns 'terminate)}))

(defn- coerce-ns-dynamic [impl-ns]
  (reify IGenServer
    (init [_ args]
      (call-init (u/ns-function impl-ns 'init) args))

    (handle-cast [_ request state]
      (call-handle-cast (u/ns-function impl-ns 'handle-cast) request state))

    (handle-call [_ request from state]
      (call-handle-call (u/ns-function impl-ns 'handle-call) request from state))

    (handle-info [_ request state]
      (call-handle-info (u/ns-function impl-ns 'handle-info) request state))

    (terminate [_ reason state] ; terminate is optional
      (call-terminate (u/ns-function impl-ns 'terminate) reason state))))

(def ^:private coerce-ns coerce-ns-dynamic)

(defn- ->gen-server [server-impl]
  (match server-impl
    (impl :guard #(satisfies? IGenServer %)) impl

    (impl-map :guard map?) (coerce-map impl-map)

    (impl-ns :guard #(instance? clojure.lang.Namespace %))
    (coerce-ns impl-ns)))

(defn ^:no-doc call* [server request tracing-context timeout-ms args]
  (process/async
   (let [mref (process/monitor server)]
     (! server [::call [mref (process/self)] request tracing-context])
     (process/selective-receive!
      [mref resp]
      (do
        (process/demonitor mref {:flush true})
        resp)

      [:DOWN mref _ _ reason]
      (process/exit [reason [`call args]])

      (after timeout-ms
             (process/demonitor mref {:flush true})
             (process/exit [:timeout [`call args]]))))))

(defn- start*
  [server
   args
   {:keys [timeout spawn-opt] :or {timeout :infinity spawn-opt {}}}]
  (process/async
   (let [gs (->gen-server server)
         response (async/chan)
         parent (process/self)
         timeout (u/timeout-chan timeout)
         exit-or-pid
         (process/ex-catch
          (process/spawn-opt
           gen-server-proc [gs args parent response] spawn-opt))]
     (match exit-or-pid
       [:EXIT reason]
       [:error reason]

       pid
       (match (async/alts! [response timeout])
         [:ok response] [:ok pid]
         [[:error reason] response] [:error reason]
         [nil timeout] (do (process/unlink pid)
                           (process/exit pid :kill)
                           [:error :timeout]))))))

;; ====================================================================
;; API

(defn start
  "The same as `start!` but returns async value."
  ([server]
   (start server []))
  ([server args]
   (start server args {}))
  ([server args options]
   (start* server args options))
  ([reg-name server args options]
   (start server args (assoc-in options [:spawn-opt :register] reg-name))))

(defmacro start!
  "Starts the server, passing `args` to server's `init` function.

  Arguments:
  `server-impl` - `IGenServer` implementation, or map, or namespace.
  `args` - any form that is passed as the argument to init function.

  Options:
  `:timeout` - time in milliseconds gen-server is allowed to spend
    initializing  or it is terminated and the start function returns
    `[:error :timeout]`.
  `:spawn-opt` - options used to spawn the gen-server process (see
    `process/spawn-opt`)

  Returns:
  `[:ok pid]` if server started successfully,
  `[:error :no-init]` if server implementation doesn't provide `init`
  function,
  `[:error [:bad-return-value value]]` if `init` returns a bad value,
  `[:error reason]` otherwise.

  Throws on invalid arguments, or when the name is already registered."
  ([server]
   `(start! ~server [] {}))
  ([server args]
   `(start! ~server ~args {}))
  ([server args options]
   `(process/await! (start ~server ~args ~options)))
  ([reg-name server args options]
   `(start!
     ~server ~args (assoc-in ~options [:spawn-opt :register] ~reg-name))))

(defn start-link
  "The same as `start-link!` but returns async value."
  ([server]
   (start-link server [] {}))
  ([server args]
   (start-link server args {}))
  ([server args options]
   (start server args (assoc-in options [:spawn-opt :link] true)))
  ([reg-name server args options]
   (start-link server args (assoc-in options [:spawn-opt :register] reg-name))))

(defmacro start-link!
  "The same as `start!` but atomically links caller to started process."
  ([server]
   `(start-link! ~server [] {}))
  ([server args]
   `(start-link! ~server ~args {}))
  ([server args options]
   `(start! ~server ~args (assoc-in ~options [:spawn-opt :link] true)))
  ([reg-name server args options]
   `(start-link!
     ~server ~args (assoc-in ~options [:spawn-opt :register] ~reg-name))))

(defmacro start-ns
  "The same as `start-ns!` but returns async value."
  ([]
   `(start-ns [] {}))
  ([args]
   `(start-ns ~args {}))
  ([args options]
   `(start ~*ns* ~args ~options))
  ([reg-name args options]
   `(start ~reg-name ~*ns* ~args ~options)))

(defmacro start-ns!
  "Starts the server, taking current ns as an implementation source.
  See `start!` for more info."
  ([]
   `(start-ns! [] {}))
  ([args]
   `(start-ns! ~args {}))
  ([args options]
   `(start! ~*ns* ~args ~options))
  ([reg-name args options]
   `(start! ~reg-name ~*ns* ~args ~options)))

(defmacro start-link-ns
  "The same as `start-link-ns!` but returns async value."
  ([]
   `(start-link-ns [] {}))
  ([args]
   `(start-link-ns ~args {}))
  ([args options]
   `(start-link ~*ns* ~args ~options))
  ([reg-name args options]
   `(start-link ~reg-name ~*ns* ~args ~options)))

(defmacro start-link-ns!
  "The same as `start-ns!` but atomically links caller to started
  process."
  ([]
   `(start-link-ns! [] {}))
  ([args]
   `(start-link-ns! ~args {}))
  ([args options]
   `(start-link! ~*ns* ~args ~options))
  ([reg-name args options]
   `(start-link! ~reg-name ~*ns* ~args ~options)))

(defmacro call!
  "Makes a synchronous call to a `server` by sending a `request` and
  waiting until a reply arrives or a time-out occurs. The `handle-call`
  callback of the gen-server is called to handle the request.

  `server` can be a pid or a registered name.

  `request` is any form that is passed as the `request` arguments to
  `handle-call`.

  `timeout-ms` is an integer greater than zero that specifies how many
  milliseconds to wait for a reply, or the keyword `:infinity` to wait
  indefinitely. Defaults to 5000. If no reply is received within the
  specified time, the function call fails.
  If the caller catches the failure and continues running, and the server
  is just late with the reply, it can arrive at any time later into the
  message queue of the caller. The caller must in this case be prepared
  for this and discard any such garbage messages that are two element
  tuples with a reference as the first element.

  The return value is defined in the return value of `handle-call`.

  The call can fail for many reasons, including time-out and the called
  gen-server process dying before or during the call."
  ([server request]
   `(let [server# ~server
          request# ~request
          context# (tracing/resolve-context)]
      (process/await! (call* server# request# context# 5000 [server# request#]))))
  ([server request timeout-ms]
   `(let [server# ~server
          request# ~request
          context# (tracing/resolve-context)
          timeout-ms# ~timeout-ms]
      (process/await!
       (call* server# request# context# timeout-ms# [server# request# timeout-ms#])))))

(defn call
  "The same as `call!` but returns async value."
  ([server request]
   (call* server request (tracing/resolve-context) 5000 [server request]))
  ([server request timeout-ms]
   (call* server request (tracing/resolve-context) timeout-ms [server request timeout-ms])))

(defn cast
  "Sends an asynchronous request to the `server` and returns immediately,
  ignoring if the `server` process does not exist. The `handle-cast`
  callback of the gen-server is called to handle the request.

  `request` is any form that is passed as the `request` argument to
  `handle-cast`."
  [server request]
  (! server [::cast request (tracing/resolve-context)]))

(defn reply
  "This function can be used to explicitly send a reply to a client
  that called `call*` or, when the reply cannot be defined in the
  return value of `handle-call`. This allows processing `call*` requests
  asynchronously.

  Client must be the `from` argument provided to the `handle-call`
  callback. `reply` is given back to the client as the return value
  of `call*`.

  The return value is not further defined, and is always to be ignored."
  [[mref pid :as _from] response]
  (! pid [mref response]))

(defmacro ^:no-doc get! [server]
  `(call! ~server ::get-state))
