(ns otplike.process
  "This namespace implements core process concepts like spawning,
  linking, monitoring, message passing, exiting, and other.

  Process context

  All calls made from process function directly or indirectly after
  it has been spawned are made in process context.
  Note: for now process context exists until process function finishes
  its execution and isn't bound to process exit.

  Process exit

  - process' inbox becomes closed, so no future messages appear in it
    (but those alredy in inbox can be received)
  - all linked/monitoring processes receive exit/down signal
  - process can not be reached by its pid
  - process is no longer registered

  As there is no way to force process function to stop execution after
  its process has exited, there can be cases when exited process tries
  to communicate with other processes. If some function behaves
  different in such cases, it should be said in its documentation.

  Signals (control messages)

  Signals are used internally to manage processes. Exiting, monitoring,
  linking and some other operations require sending signals.
  Sometimes a lot of signals must be sent to a process simultaneously
  (e.g. a process monitors 1000 linked processes and one of them exits).
  In such cases control message queue of a process can overflow. When
  it happens, the process exits immediately with reason
  :control-overflow."
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

(def ^:private *control-timeout 100)

(def ^:private ^:dynamic *self* nil)

(def ^:no-doc ^:dynamic *inbox* nil)

(defn- ->nil [x])

(declare pid->str)

(defn- trace [pid message]
  (otplike.trace/send-trace [pid (@*registered-reverse pid)] message))

(defrecord Pid [id pname]
  Object
  (toString [self]
    (pid->str self)))

(alter-meta! #'->Pid assoc :no-doc true)
(alter-meta! #'map->Pid assoc :no-doc true)

(defrecord MonitorRef [id other-pid])

(alter-meta! #'->MonitorRef assoc :no-doc true)
(alter-meta! #'map->MonitorRef assoc :no-doc true)

(defn monitor-ref?
  "Returns true if term is a monitor reference, false otherwise."
  [mref]
  (instance? MonitorRef mref))

(defn ex->reason
  "Makes exit reason from exception."
  [^Throwable e]
  (or (::exit-reason (ex-data e))
      [:exception (u/stack-trace e)]))

(defmacro ex-catch
  "Executes expr. Returns either result of execution or exit reason."
  [expr]
  `(try
     ~expr
     (catch Exception e#
       [:EXIT (ex->reason e#)])))

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
  [^Pid {:keys [id pname] :as pid}]
  {:post [(string? %)]}
  (u/check-args [(pid? pid)])
  (str "<" (if pname (str pname "@" id) id) ">"))

(defmethod print-method Pid [o w]
  (print-simple (pid->str o) w))

(defrecord ProcessRecord
  [pid inbox control kill monitors exit outbox linked flags])
(alter-meta! #'->ProcessRecord assoc :no-doc true)
(alter-meta! #'map->ProcessRecord assoc :no-doc true)

(defn- new-process [pid inbox control kill monitors exit outbox linked flags]
  {:pre [(pid? pid)
         (satisfies? ap/ReadPort inbox) (satisfies? ap/WritePort inbox)
         (satisfies? ap/ReadPort control) (satisfies? ap/WritePort control)
         (satisfies? ap/ReadPort kill) (satisfies? ap/WritePort kill)
         (map? @monitors) (every? vector? @monitors)
         (every? (fn [[pid _]] (pid? pid)) @monitors)
         (satisfies? ap/ReadPort outbox)
         (set? @linked) (every? pid? @linked)
         (map? @flags)]
   :post [(instance? ProcessRecord %)]}
  (->ProcessRecord pid inbox control kill monitors exit outbox linked flags))

(defn self
  "Returns the process identifier of the calling process.
  Throws when called not in process context."
  []
  {:post [(pid? %)]}
  (or *self* (throw (Exception. "noproc"))))

(defn whereis
  "Returns the process identifier with the registered name reg-name,
  or nil if the name is not registered."
  [reg-name]
  {:post [(or (nil? %) (pid? %))]}
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
  If sending results in dest's inbox overflow, dest exits with reason
  :inbox-overflow.
  Returns true if message was sent (process was alive), false otherwise.
  Throws if any of arguments is nil."
  [dest message]
  {:post [(or (true? %) (false? %))]}
  (u/check-args [(some? dest)
                 (some? message)])
  (match (find-process dest)
    {:inbox inbox :kill kill} (or (async/offer! inbox message)
                                  (do
                                    (async/put! kill :inbox-overflow)
                                    false))
    nil false))

(defn- !control [pid message]
  {:pre [(pid? pid)
         (vector? message) (keyword? (first message))]
   :post [(or (true? %) (false? %))]}
  (match (@*processes pid)
    {:control control :kill kill} (or (async/offer! control message)
                                      (do
                                        (async/put! kill :control-overflow)
                                        false))
    nil false))

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
  Throws if pid is not a pid, or reason is nil."
  ([reason] ;FIXME docs
   (throw (ex-info "exit" {::exit-reason reason})))
  ([pid reason]
   {:post [(or (true? %) (false? %))]}
   (u/check-args [(pid? pid)
                  (some? reason)])
   (!control pid [:exit (self) reason])))

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
    (throw (Exception. "noproc"))))

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

(defn- two-phase [{:keys [pid] :as process} other-pid cfn]
  {:pre [(instance? ProcessRecord process)
         (pid? pid)
         (pid? other-pid)
         (not= pid other-pid)
         (fn? cfn)]
   :post [(satisfies? ap/ReadPort %)]}
  (cfn :phase-one process other-pid)
  (go
    (let [other-result (async/chan)
          noproc #(->nil (cfn :noproc process other-pid))]
      (if (!control other-pid [:two-phase-p2 other-result pid cfn])
        (let [timeout (async/timeout *control-timeout)]
          (match (async/alts! [other-result timeout])
            [nil other-result] nil
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
              (dosync
                (alter linked disj other-pid))
              (trace pid [:link-timeout other-pid])
              ; TODO crash :noproc vs. exit :noproc
              (!control pid [:exit other-pid :noproc]))))

(defn link
  "Creates a link between the calling process and another process
  identified by pid, if there is not such a link already. If a
  process attempts to create a link to itself, nothing is done.
  If pid does not exist and the calling process
  1. is trapping exits - the calling process receives message
  [:EXIT pid :noproc].
  2. is not trapping exits - process exits with reason :noproc.
  Returns true.
  Throws when called not in process context, or by exited process,
  or pid is not a pid."
  [pid]
  {:post [(true? %)]}
  (u/check-args [(pid? pid)])
  (let [s (self)]
    (if (= s pid)
      true
      (if (two-phase-start s pid link-fn)
        true
        (throw (Exception. "noproc"))))))

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
  Throws when called not in process context, or called by exited
  process, or pid is not a pid."
  [pid]
  {:post [(true? %)]}
  (u/check-args [(pid? pid)])
  (let [s (self)]
    (if (= pid s)
      true
      (if-let [complete (two-phase-start s pid unlink-fn)]
        (do (<!! complete) true)
        (throw (Exception. "noproc"))))))

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
    :phase-two
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
    :phase-two
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
      [:stop reason] [::break reason]
      [:exit (xpid :guard pid?) :kill] [::break :killed]
      [:exit (xpid :guard pid?) :normal] (do
                                           (when trap-exit
                                             (! pid [:EXIT xpid :normal]))
                                           ::continue)
      [:exit (xpid :guard pid?) reason] (do
                                          (if trap-exit
                                            (do
                                              (! pid [:EXIT xpid reason])
                                              ::continue)
                                            [::break reason]))
      [:two-phase
       complete other cfn] (do
                             (let [p1result (two-phase process other cfn)]
                               (go
                                 (<! p1result)
                                 (async/close! complete)))
                             ::continue)
      [:two-phase-p2
       result other-pid cfn] (do
                               (cfn :phase-two process other-pid)
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

(defn- start-process [pid proc-func args]
  {:pre [(fn? proc-func)
         (pid? pid)
         (sequential? args)]
   :post [(satisfies? ap/ReadPort %)]}
  (go
    (try
      (match (apply proc-func args)
        (chan :guard #(satisfies? ap/ReadPort %))
        (let [exit-reason (<! chan)]
          (!control pid [:stop exit-reason])
          exit-reason)
        exit-reason
        (do
          (!control pid [:stop exit-reason])
          exit-reason))
      (catch Throwable t
        (let [exit-reason (ex->reason t)]
          (!control pid [:stop exit-reason])
          exit-reason)))))

(defn- resolve-proc-func [form]
  {:pre [(or (fn? form) (symbol? form))]
   :post [(fn? %)]}
  (cond
    (fn? form) form
    (symbol? form) (some-> form resolve var-get)))

(defn- sync-register [pid process register]
  {:pre [(pid? pid)
         (instance? ProcessRecord process)]
   :post []}
  (dosync
    (when (some? register)
      (when (@*registered register)
        (throw (Exception. (str "already registered: " register))))
      (alter *registered assoc register pid)
      (alter *registered-reverse assoc pid register))
    (alter *processes assoc pid process)))

(defn- sync-unregister [pid]
  {:pre [(pid? pid)]
   :post []}
  (dosync
    (alter *processes dissoc pid)
    (when-let [register (@*registered-reverse pid)]
      (alter *registered dissoc register)
      (alter *registered-reverse dissoc pid))))

(defn spawn
  "Returns the process identifier of a new process started by the
  application of proc-fun to args.
  options argument is a map of option names (keyword) to its values.

  When :link-to contains pid(s) of already exited process(es), spawned
  process exits with reason :noproc just after the start.

  The default process' inbox is blocking buffered channel of size 1024.
  The :inbox option allows providing a custom channel.

  The following options are allowed:
  :flags - a map of process' flags (e.g. {:trap-exit true})
  :register - name to register the process, can not be pid, if name is
    nil process will not be registered
  :link-to - a pid or a sequence of pids to link process to
  :inbox - the channel to be used as a process' inbox"
  ([proc-func]
   (spawn proc-func [] {}))
  ([proc-func args-or-opts]
   (if (map? args-or-opts)
     (spawn proc-func [] args-or-opts)
     (spawn proc-func args-or-opts {})))
  ([proc-func args
    {:keys [link-to inbox flags register] pname :name :as options}]
   {:post [(pid? %)]}
   (u/check-args [(or (fn? proc-func) (symbol? proc-func))
                  (sequential? args)
                  (map? options) ;FIXME check for unknown options
                  (or (nil? link-to) (pid? link-to) (every? pid? link-to))
                  (or (nil? inbox)
                      (and (satisfies? ap/ReadPort inbox)
                           (satisfies? ap/WritePort inbox)))
                  (or (nil? flags) (map? flags)) ;FIXME check for unknown flags
                  (not (pid? register))])
   (let [proc-func (resolve-proc-func proc-func)
         id        (swap! *pids inc)
         inbox     (or inbox (async/chan 1024))
         pid       (Pid. id (or pname (str "proc" id)))
         control   (async/chan 128)
         kill      (async/chan)
         linked    (ref #{})
         monitors  (ref {})
         flags     (ref (or flags {}))
         outbox    (outbox pid inbox)
         process (new-process
                   pid inbox control kill monitors exit outbox linked flags)]
     (sync-register pid process register)
     (trace pid [:start (str proc-func) args options])
     ; FIXME bindings from folded binding blocks are stacked, so no values
     ; bound between bottom and top folded binding blocks are garbage
     ; collected; see "ring" benchmark example
     ; FIXME workaround for ASYNC-170. once fixed, binding should move to
     ; (start-process...)
     (binding [*self* pid
               *inbox* outbox]
       (go
         (when link-to
           (doseq [link-to (set (flatten [link-to]))]
             (<! (two-phase process link-to link-fn))))
         (start-process pid proc-func args)
         (loop []
           (let [proceed (match (async/alts! [kill control] :priority true)
                                [val control]
                                (dispatch-control process val)

                                [val kill]
                                (do
                                  (trace pid [:kill (or val :nil)])
                                  [::break (if (some? val) val :nil)]))]
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
                        (! pid (monitor-message mref object reason)))))))))
     pid)))

(defn spawn-link
  "Returns the process identifier of a new process started by the
  application of proc-fun to args. A link is created between the
  calling process and the new process, atomically. Otherwise works
  like spawn.

  When called by exited process, spawned process exits with reason
  :noproc just after the start.

  Throws when called not in process context."
  [proc-func args opts]
  {:post [(pid? %)]}
  (u/check-args [(or (nil? opts) (map? opts))])
  (let [opts (update-in opts [:link-to] conj (self))]
    (spawn proc-func args opts)))

(defmacro receive* [park? clauses]
  (if (even? (count clauses))
    `(if-let [msg# (~(if park? `<! `<!!) *inbox*)]
       (match msg# ~@clauses)
       (throw (Exception. "noproc")))
    (match (last clauses)
      (['after
        (ms :guard #(or (symbol? %) (and (integer? %) (not (neg? %)))))
        & body]
       :seq)
      `(let [inbox# *inbox*
             timeout# (async/timeout ~ms)]
         (match (~(if park? `async/alts! `async/alts!!) [inbox# timeout#])
           [nil timeout#] (do ~@body)
           [nil inbox#] (throw (Exception. "noproc"))
           [msg# inbox#] (match msg# ~@(butlast clauses)))))))

(alter-meta! #'receive* assoc :no-doc true)

(defmacro receive! [& clauses]
  `(receive* true ~clauses))

(defmacro receive!! [& clauses]
  `(receive* false ~clauses))

(defmacro proc-fn
  "Creates process function which can be passed to spawn."
  [args & body]
  (assert (vector? args))
  `(fn ~args
     (go
       (try
         (loop ~(vec (interleave args args)) ~@body)
         :normal
         (catch Throwable t#
           (ex->reason t#))))))

(defmacro proc-defn
  "The same as proc-fn but also binds created function to a var with
  the name fname."
  [fname & args-body]
  `(let [f# (proc-fn ~@args-body)]
     (def ~fname f#)
     f#))
