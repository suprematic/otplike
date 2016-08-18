(ns otplike.process
  (:require [clojure.core.async :as async :refer [<!! <! >! put! go go-loop]]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.core.match :refer [match]]
            [otplike.trace]
            [otplike.util :as u]
            [clojure.core.async.impl.protocols :as impl]))

(def ^:private *pids
  (atom 0))

(def ^:private *refids
  (atom 0))

(def ^:private *processes
  (ref {}))

(def ^:private *registered
  (ref {}))

(def ^:private *registered-reverse
  (ref {}))

(def ^:private *control-timout 100)

(def ^:private ^:dynamic *self* nil)

(def ^:no-doc ^:dynamic *inbox* nil)

(defn- ->nil [x])

(declare pid->str)

(defn- trace [pid message]
  (otplike.trace/send-trace [pid (@*registered-reverse pid)] message))

(defrecord Pid [id name]
  Object
  (toString [self]
    (pid->str self))

  ap/WritePort
  (put! [this val handler]
    (when-let [{:keys [inbox]} (@*processes this)]
      (trace this [:inbound val])
      (ap/put! inbox val handler))))

(alter-meta! #'->Pid assoc :no-doc true)
(alter-meta! #'map->Pid assoc :no-doc true)

(defrecord MonitorRef [id other-pid])

(alter-meta! #'->MonitorRef assoc :no-doc true)
(alter-meta! #'map->MonitorRef assoc :no-doc true)

(defn monitor-ref?
  "Returns true if term is a monitor reference, false otherwise."
  [mref]
  (instance? MonitorRef mref))

(defn pid?
  "Returns true if term is a process identifier, false otherwise."
  [pid]
  (instance? Pid pid))

(defn- new-monitor-ref
  ([]
   (new-monitor-ref nil))
  ([other-pid]
   {:pre [(or (pid? other-pid) (nil? other-pid))]}
   (->MonitorRef (swap! *refids inc) other-pid)))

(defn pid->str
  "Returns a string corresponding to the text representation of pid.
  Throws if pid is not a process identifier.

  Warning: this function is intended for debugging and is not to be
  used in application programs."
  [^Pid {:keys [id name] :as pid}]
  {:post [(string? %)]}
  (u/check-args [(pid? pid)])
  (str "<" (if name (str name "@" id) id) ">"))

(defmethod print-method Pid [o w]
  (print-simple (pid->str o) w))

(defrecord ProcessRecord [pid inbox control monitors exit outbox linked flags])
(alter-meta! #'->ProcessRecord assoc :no-doc true)
(alter-meta! #'map->ProcessRecord assoc :no-doc true)

(defn- new-process [pid inbox control monitors exit outbox linked flags]
  {:pre [(pid? pid)
         (satisfies? ap/ReadPort inbox) (satisfies? ap/WritePort inbox)
         (satisfies? ap/ReadPort control) (satisfies? ap/WritePort control)
         (map? @monitors) (every? vector? @monitors)
         (every? (fn [[pid _]] (pid? pid)) @monitors)
         (satisfies? ap/ReadPort outbox)
         (set? @linked) (every? pid? @linked)
         (map? @flags)]
   :post [(instance? ProcessRecord %)]}
  (->ProcessRecord pid inbox control monitors exit outbox linked flags))

(defn self
  "Returns the process identifier of the calling process.
  Throws when called not in process context."
  []
  {:post [(pid? %)]}
  (or *self* (throw (Exception. "not in process"))))

(defn whereis
  "Returns the process identifier with the registered name reg-name,
  or nil if the name is not registered.
  Throws on nil argument."
  [reg-name]
  {:post [(or (nil? %) (pid? %))]}
  (u/check-args [(some? reg-name)])
  (@*registered reg-name))

(defn- find-process [id]
  {:pre [(some? id)]
   :post [(or (nil? %) (instance? ProcessRecord %))]}
  (if (pid? id)
    (@*processes id)
    (when-let [pid (whereis id)]
      (@*processes pid))))

(defn !
  "Sends a message to dest. dest can be a process identifier, or a
  registered name.
  Returns true if message was sent (process was alive), false otherwise.
  Throws if any of arguments is nil."
  [dest message]
  {:post [(or (true? %) (false? %))]}
  (u/check-args [(some? dest)
                 (some? message)])
  (if-let [{:keys [inbox]} (find-process dest)]
    (do
      (async/put! inbox message)
      true)
    false))

(defn- !control [pid message]
  {:pre [(pid? pid)
         (vector? message) (keyword? (first message))]
   :post [(or (true? %) (false? %))]}
  (if-let [{:keys [control]} (@*processes pid)]
    (do
      (async/put! control message)
      true)
    false))

(defn exit
  "Sends an exit signal with exit reason to the process identified
  by pid.
  If reason is any term, except :normal or :kill:
  - if pid is not trapping exits, pid itself exits with exit reason.
  - if pid is trapping exits, the exit signal is transformed into a
    message [:EXIT from reason] and delivered to the message queue
    of pid. from is the process identifier of the process that sent
    the exit signal.
  If reason is :normal, pid does not exit. If pid is trapping exits,
  the exit signal is transformed into a message
  [:EXIT from :normal] and delivered to its message queue.
  If reason is :kill, an untrappable exit signal is sent to pid,
  which unconditionally exits with reason :killed.
  Returns true if exit signal was sent (process was alive), false
  otherwise.
  Throws if pid is not a pid, or message is nil.

  Process exit means:
  - process' inbox becomes closed
  - future messages do not arrive to the process' inbox
  - all linked/monitoring processes receive exit signal/message
  - process no longer registered"

  [pid reason]
  {:post [(or (true? %) (false? %))]}
  (u/check-args [(pid? pid)
                 (some? reason)])
  (!control pid [:exit pid reason]))

(defn flag
  "Sets the value of a process flag. See description of each flag below.
  Returns the old value of a flag.
  Throws when called not in process context.

  :trap-exit
  When :trap-exit is set to true, exit signals arriving to a process
  are converted to [:EXIT from reason] messages, which can be
  received as ordinary messages. If :trap-exit is set to false, the
  process exits if it receives an exit signal other than :normal and
  the exit signal is propagated to its linked processes. Application
  processes are normally not to trap exits."
  [flag value]
  {:post []}
  (u/check-args [(keyword? flag)])
  (if-let [^ProcessRecord {:keys [flags]} (find-process (self))]
    (dosync
      (let [old-value (flag @flags)]
        (match flag
          :trap-exit (do
                       (alter flags assoc flag (boolean value))
                       (boolean old-value)))))
    (throw (Exception. "stopped"))))

(defn- monitor* [func pid1 pid2]
  (if-let [{:keys [monitors] :as process} (find-process pid2)]
    (do
      (swap! monitors func pid1)
      :ok)))

(defn registered
 "Returns a set of names of the processes that have been registered."
 []
 {:post [(set? %)]}
 (set (keys @*registered)))

(defn- two-phase-start [pid1 pid2 cfn]
  {:pre [(pid? pid1)
         (pid? pid2)
         (not= pid1 pid2)
         (fn? cfn)]
   :post [(or (nil? %) (satisfies? ap/ReadPort %))]}
  (let [complete (async/chan)]
    (when (!control pid1 [:two-phase complete pid2 cfn])
      complete)))

(defn- two-phase [process p1pid p2pid cfn]
  {:pre [(instance? ProcessRecord process)
         (pid? p1pid)
         (pid? p2pid)
         (not= p1pid p2pid)
         (fn? cfn)]
   :post [(satisfies? ap/ReadPort %)]}
  (go
    (let [p1result-chan (async/chan)
          noproc #(->nil (cfn :noproc process p1pid))]
      (if (!control p1pid [:two-phase-p1 p1result-chan p2pid cfn])
        (let [timeout (async/timeout *control-timout)]
          (match (async/alts! [p1result-chan timeout])
            [nil p1result-chan] (->nil (cfn :phase-two process p1pid))
            [nil timeout] (noproc)))
        (noproc)))))

(defn- link-fn [phase {:keys [linked pid] :as process} other-pid]
  {:pre [(instance? ProcessRecord process)
         (pid? pid)
         (pid? other-pid)]}
  (case phase
    :phase-one (do
                 (trace pid [:link-phase-one other-pid])
                 (dosync
                   (alter linked conj other-pid)))
    :phase-two (do
                 (trace pid [:link-phase-two other-pid])
                 (dosync
                   (alter linked conj other-pid)))
    :noproc (do
               (trace pid [:link-timeout other-pid])
               (!control pid [:exit other-pid :noproc])))) ; TODO crash :noproc vs. exit :noproc

(defn link
  "Creates a link between the calling process and another process
  identified by pid, if there is not such a link already. If a
  process attempts to create a link to itself, nothing is done.
  If pid does not exist and the calling process
  1. is trapping exits - an exit signal with reason :noproc is sent
  to the calling process.
  2. is not trapping exits - link closes process' inbox and may throw.
  Returns true.
  Throws when called not in process context, or pid is not a pid."
  [pid]
  {:post [(true? %)]}
  (u/check-args [(pid? pid)])
  (let [s (self)]
    (if (= s pid)
      true
      (if (two-phase-start s pid link-fn)
        true
        (throw (Exception. "stopped"))))))

(defn- unlink-fn [phase {:keys [linked pid] :as process} other-pid]
  {:pre [(instance? ProcessRecord process)
         (pid? pid)
         (pid? other-pid)]}
  (let [p2unlink #(do (trace pid [% other-pid])
                      (dosync
                        (alter linked disj other-pid)))]
    (case phase
      :phase-one (p2unlink :unlink-phase-one)
      :phase-two (p2unlink :unlink-phase-two)
      :noproc (p2unlink :unlink-phase-two))))

(defn unlink
  "Removes the link, if there is one, between the calling process and
  the process referred to by pid.
  Returns true.
  Does not fail if there is no link to pid, if pid is self pid, or
  if pid does not exist.
  Once unlink has returned, it is guaranteed that the link between
  the caller and the entity referred to by pid has no effect on the
  caller in the future (unless the link is setup again).
  If the caller is trapping exits, an [:EXIT pid _] message from
  the link can have been placed in the caller's message queue before
  the call.
  Notice that the [:EXIT pid _] message can be the result of the
  link, but can also be the result of pid calling exit. Therefore,
  it can be appropriate to clean up the message queue when trapping
  exits after the call to unlink.
  Throws when called not in process context, or pid is not a pid."
  [pid]
  {:post [(true? %)]}
  (u/check-args [(pid? pid)])
  (let [s (self)]
    (if (= pid s)
      true
      (if-let [complete (two-phase-start s pid unlink-fn)]
        (do (<!! complete) true)
        (throw (Exception. "stopped"))))))

(defn- monitor-message [mref object reason]
  {:pre [(monitor-ref? mref)]}
  [:DOWN mref :process object reason])

(defn- monitor-fn
  [mref object phase {:keys [monitors pid] :as process} other-pid]
  {:pre [(monitor-ref? mref)
         (keyword? phase)
         (map? @monitors)
         (instance? ProcessRecord process)
         (pid? other-pid)]}
  (case phase
    :phase-one
    (do
      (trace pid [:monitor mref other-pid object])
      (dosync
        (alter monitors assoc mref [other-pid object])))
    :noproc
    (! pid (monitor-message mref object :noproc))
    nil))

(defn- resolve-pid [pid-or-name]
  {:post [(let [[pid _object] %]
            (or (nil? pid) (pid? pid)))]}
  (if (pid? pid-or-name)
    [pid-or-name pid-or-name]
    [(whereis pid-or-name) pid-or-name]))

(defn monitor
  "Sends a monitor request to the entity identified by pid-or-name.
  If the monitored entity does not exist or when it dies,
  the caller of monitor will be notified by a message on the
  following format:

  [tag monitor-ref type object info]

  type can be one of the following keywords: :process.
  A monitor is triggered only once, after that it is removed from
  both monitoring process and the monitored entity. Monitors are
  fired when the monitored process terminates, or does not
  exist at the moment of creation. The monitoring is also turned
  off when demonitor/1 is called.

  When monitoring by name please note, that the registered-name is
  resolved to pid only once at the moment of monitor instantiation,
  later changes to the name registration will not affect the existing
  monitor.

  When a monitor is triggered, a :DOWN message that has the
  following pattern

  [:DOWN monitor-ref type object info]

  is sent to the monitoring process.

  In monitor message monitor-ref and type are the same as described
  earlier, and:
  object
    The monitored entity, which triggered the event. That is the
    argument of monitor call.
  info
    Either the exit reason of the process, or :noproc (process did not
    exist at the time of monitor creation).

  Making several calls to monitor/2 for the same pid-or-name and/or
  type is not an error; it results in as many independent monitoring
  instances.
  Monitoring self does nothing.

  Returns monitor-ref.
  Throws when called not in process context."
  [pid-or-name]
  {:post [(monitor-ref? %)]}
  (let [self (self)
        [other-pid object] (resolve-pid pid-or-name)]
    (if (= other-pid self)
      (new-monitor-ref)
      (if other-pid
        (let [mref (new-monitor-ref other-pid)]
          (two-phase-start self other-pid (partial monitor-fn mref object))
          mref)
        (let [mref (new-monitor-ref)]
          (! self (monitor-message mref object :noproc))
          mref)))))

(defn- demonitor-fn [mref phase {:keys [monitors] :as process} other-pid]
  {:pre [(monitor-ref? mref)
         (keyword? phase)
         (map? @monitors)
         (instance? ProcessRecord process)
         (pid? other-pid)]}
  (case phase
    :phase-one
    (let [[pid object] (@monitors mref)]
      (when (= pid other-pid)
        (trace pid [:demonitor mref other-pid object])
        (dosync
          (alter monitors dissoc mref))))
    nil))

(defn demonitor
  "If mref is a reference that the calling process obtained by
  calling monitor, this monitoring is turned off. If the monitoring
  is already turned off, nothing happens. If mref was created by
  other process, nothing happens.

  Once demonitor has returned, it is guaranteed that no
  [:DOWN monitor-ref _ _ _] message, because of the monitor,
  will be placed in the caller message queue in the future.
  A [:DOWN monitor-ref _ _ _] message can have been placed in
  the caller message queue before the call, though. It is therefore
  usually advisable to remove such a :DOWN message from the message
  queue after monitoring has been stopped.

  Returns true.
  Throws when called not in process context, mref is not a
  monitor-ref."
  [{:keys [other-pid] :as mref}]
  {:post [(= true %)]}
  (u/check-args [(monitor-ref? mref)])
  (let [self (self)]
    (when other-pid
      (let [return (two-phase-start self other-pid (partial demonitor-fn mref))]
        (<!! return)))
    true))

; TODO return new process and exit code
(defn- dispatch-control [{:keys [flags pid linked] :as process} message]
  {:pre [(instance? ProcessRecord process)]
   :post []}
  (trace pid [:control message])
    (let [trap-exit (:trap-exit @flags)]
      (match message
        [:exit xpid :kill] (do
                             (assert (pid? xpid))
                             [::break :killed])
        [:exit xpid :normal] (do
                               (assert (pid? xpid))
                               (when trap-exit
                                 (! pid [:EXIT xpid :normal]))
                               ::continue)
        [:exit xpid reason] (do
                              (assert (pid? xpid))
                              (if trap-exit
                                (do
                                  (! pid [:EXIT xpid reason])
                                  ::continue)
                                [::break reason]))
        [:two-phase
         complete other cfn] (go
                               (let [p1result (two-phase process other pid cfn)]
                                 (<! p1result)
                                 (async/close! complete)
                                 ::continue))
        [:two-phase-p1
         result other-pid cfn] (go
                                 (cfn :phase-one process other-pid)
                                 (async/close! result)
                                 ::continue))))

(defprotocol IClose
  (close! [_]))

(alter-meta! #'IClose assoc :no-doc true)

(defn- outbox [pid inbox]
  {:pre [(pid? pid)
         (satisfies? ap/ReadPort inbox)]
   :post [(satisfies? ap/ReadPort %) (satisfies? IClose %)]}
  (let [outbox (async/chan 1)
        stop (async/chan)]
    (go-loop []
      (let [[value _] (async/alts! [stop inbox] :priority true)]
        (if (some? value)
          (do
            (trace pid [:deliver value])
            (>! outbox value)
            (recur))
          (async/close! outbox))))

    (reify
      ap/ReadPort
      (take! [_ handler]
        (ap/take! outbox handler))

      IClose
      (close! [_]
        (async/close! stop)))))

; TODO check exception thrown from proc-func
(defn- start-process [proc-func args]
  {:pre [(fn? proc-func)
         (sequential? args)]
   :post [(satisfies? ap/ReadPort %)]}
  (match (apply proc-func args)
    (chan :guard #(satisfies? ap/ReadPort %)) chan))

(defn- resolve-proc-func [form]
  {:pre [(or (fn? form) (symbol? form))]
   :post [(fn? %)]}
  (cond
    (fn? form) form
    (symbol? form) (some-> form resolve var-get)))

(defn- sync-register [pid process register]
  (dosync
    (when (some? register)
      (when (@*registered register)
        (throw (Exception. (str "already registered: " register))))
      (alter *registered assoc register pid)
      (alter *registered-reverse assoc pid register))
    (alter *processes assoc pid process)))

(defn- sync-unregister [pid]
  (dosync
    (alter *processes dissoc pid)
    (when-let [register (@*registered-reverse pid)]
      (alter *registered dissoc register)
      (alter *registered-reverse dissoc pid))))

(defn spawn
  "Returns the process identifier of a new process started by the
  application of proc-fun to args.
  options argument is a map of option names (keyword) to its values.

  The following options are allowed:
  :flags - a map of process' flags (e.g. {:trap-exit true})
  :register - any valid name to register process
  :link-to - pid or sequence of pids to link process to
  :inbox-size -
  :name - "
  [proc-func args {:keys [link-to inbox-size flags name register] :as options}]
  {:post [(pid? %)]}
  (u/check-args [(or (fn? proc-func) (symbol? proc-func))
                 (sequential? args)
                 (map? options) ;FIXME check for unknown options
                 (or (nil? link-to) (pid? link-to) (every? pid? link-to))
                 (or (nil? inbox-size)
                     (and (integer? inbox-size) (not (neg? inbox-size))))
                 (or (nil? flags) (map? flags))]) ;FIXME check for unknown flags
  (let [proc-func (resolve-proc-func proc-func)
        id        (swap! *pids inc)
        inbox     (async/chan (or inbox-size 1024))
        pid       (Pid. id (or name (str "proc" id)))
        control   (async/chan 128)
        linked    (ref #{})
        monitors  (ref {})
        flags     (ref (or flags {}))]
    (locking *processes
      (let [outbox  (outbox pid inbox)
            process (new-process
                      pid inbox control monitors exit outbox linked flags)]
        (sync-register pid process register)
        (trace pid [:start (str proc-func) args options])
        ; FIXME bindings from folded binding blocks are stacked, so no values
        ; bound between bottom and top folded binding blocks are garbage
        ; collected; see "ring" benchmark example
        (binding [*self* pid
                  *inbox* outbox] ; workaround for ASYNC-170. once fixed, binding should move to (start-process...)
          (let [return (try
                         (start-process proc-func args)
                         (catch Throwable e
                           (close! outbox)
                           (sync-unregister pid)
                           (throw e)))]
            (go
              (when link-to
                (doseq [link-to (apply hash-set (flatten [link-to]))] ; ??? probably optimize by sending link requests concurently
                  (<! (two-phase process link-to pid link-fn)))) ; wait for protocol to complete
              (let [process (assoc process :return return)]
                (loop []
                  (let [proceed (match (async/alts! [control return])
                                  [val control]
                                  (let [proceed (dispatch-control process val)]
                                    (if (satisfies? ap/ReadPort proceed)
                                      (<! proceed) proceed))

                                  [val return]
                                  (do
                                    (trace pid [:return (or val :nil)])
                                    [::break (if (some? val) val :nil)])

                                  (:or [nil control] [nil return])
                                  nil)]
                    (match proceed
                      ::continue
                      (recur)

                      [::break reason]
                      (do
                        (trace pid [:terminate reason])
                        (close! outbox)
                        (dosync
                          (sync-unregister pid)
                          (doseq [p @linked]
                            (when-let [p (@*processes p)]
                              (alter (:linked p) disj process))))
                        (doseq [p @linked]
                          (!control p [:exit pid reason]))

                        (doseq [[mref [pid object]] @monitors]
                          (! pid (monitor-message mref object reason)))))))))))))
    pid))

(defn spawn-link
  "Returns the process identifier of a new process started by the
  application of proc-fun to args. A link is created between the
  calling process and the new process, atomically. Otherwise works
  like spawn.
  Throws when called not in process context."
  [proc-func args opts]
  {:post [(pid? %)]}
  (u/check-args [(or (nil? opts) (map? opts))])
  (let [opts (update-in opts [:link-to] conj (self))]
    (spawn proc-func args opts)))

(defmacro receive* [park? clauses]
  (if (even? (count clauses))
    `(match (~(if park? `<! `<!!) *inbox*) ~@clauses)
    (match (last clauses)
      (['after
        (ms :guard #(or (symbol? %) (and (integer? %) (not (neg? %)))))
        & body]
       :seq)
      `(let [inbox# *inbox*
             timeout# (async/timeout ~ms)]
         (match (~(if park? `async/alts! `async/alts!!) [inbox# timeout#])
           [nil timeout#] (do ~@body)
           [nil inbox#] (throw (Exception. "stopped"))
           [msg# inbox#] (match msg# ~@(butlast clauses)))))))

(alter-meta! #'receive* assoc :no-doc true)

(defmacro receive! [& clauses]
  `(receive* true ~clauses))

(defmacro receive!! [& clauses]
  `(receive* false ~clauses))

#_(defmacro receive [& clauses]
  `(go (receive! ~clauses)))

(defmacro proc-fn [args & body]
  (assert (vector? args))
  `(fn ~args
     (go
       (try
         (loop ~(vec (interleave args args)) ~@body)
         :normal
         (catch Throwable t#
           [:exception (u/stack-trace t#)])))))

(defmacro defproc [name & args-body]
  `(def ~name (proc-fn ~@args-body)))

(defmacro defn-proc [name args & body]
  `(defn ~name []
     (let [done# (async/chan)]
       (spawn
         (proc-fn
           ~args
           (try
             (let [res# (do ~@body)]
               (when (some? res#) (>! done# res#)))
             (finally
               (async/close! done#))))
         []
         {})
       (<!! done#))))
