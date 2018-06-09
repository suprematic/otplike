(ns otplike.process
  "This namespace implements core process concepts like spawning,
  linking, monitoring, message passing, exiting, and other.

  ### Process context

  All calls made from process function directly or indirectly after
  it has been spawned are made in process context.
  Note: for now process context exists until process function finishes
  its execution and isn't bound to process exit.

  ### Process exit

  - process' inbox becomes closed, so no future messages appear in it
    (but those alredy in inbox can be received)
  - all linked/monitoring processes receive exit/down signal
  - process can not be reached by its pid
  - process is no longer registered

  As there is no way to force process function to stop execution after
  its process has exited, there can be cases when exited process tries
  to communicate with other processes. If some function behaves
  different in such cases, it should be said in its documentation.

  ### Signals (control messages)

  Signals are used internally to manage processes. Exiting, monitoring,
  linking and some other operations require sending signals.
  Sometimes a lot of signals must be sent to a process simultaneously
  (e.g. a process monitors 1000 linked processes and one of them exits).
  In such cases control message queue of a process can overflow. When
  it happens, the process exits immediately with reason
  `:control-overflow`."
  (:require [clojure.core.async :as async :refer [<!! <! >! put! go go-loop]]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.core.match :refer [match]]
            [clojure.spec.alpha :as spec]
            [otplike.util :as u]))

(when (and (= 1 (:major *clojure-version*))
           (< (:minor *clojure-version*) 9))
  (require '[clojure.future :refer :all]))

(declare pid->str pid? self whereis monitor-ref? ! ex->reason exit async?)

(deftype Pid [^Long id ^String pname]
  Object
  (toString [self]
    (pid->str self))
  (hashCode [_self]
    (.hashCode id))
  (equals [^Pid self other]
    (and (= (type self) (type other))
         (= (.id self) (.id ^Pid other)))))
(alter-meta! #'->Pid assoc :no-doc true)

(defn- pid?* [pid]
  (instance? Pid pid))

(defrecord Async [chan])
(alter-meta! #'->Async assoc :no-doc true)
(alter-meta! #'map->Async assoc :no-doc true)

;; ====================================================================
;; Specs

(spec/def ::pid pid?*)

(spec/def ::async #(instance? Async %))

;; ====================================================================
;; Internal

(def ^:private *global-lock)

(def ^:private *next-pid
  (atom 0))

(def ^:private *refids
  (atom 0))

(def ^:private *trace-handlers
  (atom {}))

(def ^:private *processes
  (atom {}))

(def ^:private *registered
  (atom {}))

(def ^:private *registered-reverse
  (atom {}))

(def ^:private *control-timeout 100)

(def ^:private ^:dynamic *self* nil)

(def ^:dynamic ^:no-doc *message-context* (atom {}))

(defn- ->nil [x])

(defn ^:no-doc send-trace-event [kind extra]
  (doseq [handler (vals @*trace-handlers)]
    (try
      (let [pid (self)]
        (handler {:pid pid
                  :reg-name (@*registered-reverse pid)
                  :kind kind
                  :extra extra}))
      (catch Throwable _))))

(defrecord MonitorRef [id self-pid other-pid])

(alter-meta! #'->MonitorRef assoc :no-doc true)
(alter-meta! #'map->MonitorRef assoc :no-doc true)

(defmethod print-method Pid [o w]
  (print-simple (pid->str o) w))

(definterface IProcess
  (getMonitors [])
  (setMonitors [x])
  (updateMonitors [f])
  (getLinked [])
  (setLinked [x])
  (updateLinked [ f])
  (getFlags [])
  (updateFlags [f]))

(deftype TProcess
  [pid
   initial-call
   message-chan
   message-q
   control-chan
   control-q
   exit-reason
   ^:unsynchronized-mutable monitors
   ^:unsynchronized-mutable linked
   ^:unsynchronized-mutable flags]
  IProcess
  (getMonitors [_] monitors)
  (setMonitors [_ x] (set! monitors x))
  (updateMonitors [_ f] (set! monitors (f monitors)))
  (getLinked [_] linked)
  (setLinked [_ x] (set! linked x))
  (updateLinked [_ f] (set! linked (f linked)))
  (getFlags [_] flags)
  (updateFlags [_ f] (set! flags (f flags))))

(defn- new-process [pname initial-call flags]
  {:pre [(or (nil? pname) (string? pname))
         (map? flags)]
   :post [(instance? TProcess %)]}
  (let [id (swap! *next-pid inc)
        pname (or pname (str "proc" id))
        pid (Pid. id pname)
        message-chan (async/chan (async/sliding-buffer 1))
        message-q (atom (u/queue))
        control-chan (async/chan (async/sliding-buffer 1))
        control-q (atom (u/queue))
        exit-reason (atom nil)
        monitors {}
        linked #{}]
    (TProcess.
     pid
     initial-call
     message-chan
     message-q
     control-chan
     control-q
     exit-reason
     monitors
     linked
     flags)))

(defn self-process
  "Returns the process identifier of the calling process.
  Throws when called not in process context."
  []
  {:post [(instance? TProcess %)]}
  (or (@*processes *self*)
      (throw (Exception. "noproc"))))

(defn- new-monitor-ref
  ([]
   (new-monitor-ref nil))
  ([other-pid]
   {:pre [(or (pid? other-pid) (nil? other-pid))]}
   (->MonitorRef (swap! *refids inc) (self) other-pid)))

(defn- find-process [id]
  {:pre [(some? id)]
   :post [(or (nil? %) (instance? TProcess %))]}
  (if (pid? id)
    (@*processes id)
    (when-let [pid (whereis id)]
      (@*processes pid))))

(defn- !control [pid message]
  {:pre [(pid? pid)
         (vector? message) (keyword? (first message))]
   :post [(or (true? %) (false? %))]}
  (if-let [^TProcess process (@*processes pid)]
    (do
      (swap! (.control-q process) conj message)
      (async/put! (.control-chan process) :go))
    false))

(defn- monitor-message [mref object reason]
  {:pre [(monitor-ref? mref)]}
  [:DOWN mref :process object reason])

(defn- dispatch-control [^TProcess process message]
  {:pre [(instance? TProcess process)]
   :post []}
  (let [trap-exit (:trap-exit (.getFlags process))
        pid (.pid process)
        k (message 0)]
    (cond
      (identical? k :stop)
      (let [reason (message 1)]
        reason)

      (identical? k :exit)
      (let [xpid (message 1)
            reason (message 2)]
        (if trap-exit
          (do
            (! pid [:EXIT xpid reason])
            ::continue)
          (case reason
            :normal ::continue
            reason)))

      (identical? k :linked-exit)
      (let [xpid (message 1)
            reason (message 2)]
        (if (locking *global-lock
              (and (.getLinked process)
                   (.updateLinked process #(disj % xpid))))
          (if trap-exit
            (do
              (! pid [:EXIT xpid reason])
              ::continue)
            (case reason
              :normal ::continue
              reason))
          ::continue))

      :else
      (throw (Exception. "Unexpected control message")))))

(defn- start-process [pid proc-func args]
  {:pre [(fn? proc-func)
         (pid? pid)
         (sequential? args)]
   :post [(satisfies? ap/ReadPort %)]}
  ;; FIXME bindings from folded binding blocks are stacked, so no values
  ;; bound between bottom and top folded binding blocks are garbage
  ;; collected; see "ring" benchmark example
  (binding [*self* pid
            *message-context* (atom @*message-context*)]
    (try
      (apply proc-func args)
      (catch Throwable t
        (let [ch (async/chan 1)]
          (async/put! ch (ex->reason t))
          ch)))))

(defn- resolve-proc-func [form]
  {:pre [(or (fn? form) (symbol? form))]
   :post [(fn? %)]}
  (cond
    (fn? form) form
    (symbol? form) (some-> form resolve var-get)))

(defn- sync-register [^TProcess process register link?]
  {:pre [(instance? TProcess process)]
   :post []}
  (let [pid (.pid process)]
    (when link?
      (locking *global-lock
        (let [^TProcess other-process (self-process)
              other-pid (.pid other-process)]
          (.updateLinked process #(conj % other-pid))
          (or (.updateLinked other-process #(if % (conj % pid)))
              (throw (Exception. "noproc"))))))
    (swap! *processes assoc pid process)
    (when (some? register)
      (when (@*registered register)
        (throw (Exception. (str "already registered: " register))))
      (swap! *registered assoc register pid)
      (swap! *registered-reverse assoc pid register))))

(defn- sync-unregister [pid]
  {:pre [(pid? pid)]
   :post []}
  (swap! *processes dissoc pid)
  (when-let [register (@*registered-reverse pid)]
    (swap! *registered dissoc register)
    (swap! *registered-reverse dissoc pid)))

(defn- spawn*
  [proc-func
   args
   {:keys [flags link register] pname :name :as options}]
  {:post [(pid? %)]}
  (u/check-args [(or (fn? proc-func) (symbol? proc-func))
                 (sequential? args)
                 (map? options) ;FIXME check for unknown options
                 (or (nil? link) (boolean? link))
                 (or (nil? flags) (map? flags)) ;FIXME check for unknown flags
                 (not (pid? register))])
  (let [proc-func (resolve-proc-func proc-func)
        flags (or flags {})
        ^TProcess process (new-process pname [proc-func args] flags)
        pid (.pid process)
        control-chan (.control-chan process)
        control-q (.control-q process)
        exit-reason (.exit-reason process)
        message-chan (.message-chan process)]
    (sync-register process register link)
    (send-trace-event :spawn {:fn proc-func :args args :options options})
    (go
      (let [result-ch (start-process pid proc-func args)]
        (loop []
          (let [proceed?
                (loop []
                  (if-let [reason @exit-reason]
                    reason
                    (if-let [m (peek @control-q)]
                      (do
                        (swap! control-q pop)
                        (dispatch-control process m))
                      (let [[v ch :as r] (async/alts! [control-chan result-ch])]
                        (if (identical? result-ch ch)
                          (if (some? v) v :nil)
                          (recur))))))]
            (case proceed?
              ::continue
              (recur)

              (let [reason proceed?]
                (send-trace-event :terminate {:reason reason})
                (sync-unregister pid)
                (async/close! control-chan)
                (async/close! message-chan)
                (let [[linked monitors]
                      (locking *global-lock
                        (let [linked-val (.getLinked process)
                              mrefs (.getMonitors process)]
                          (.setLinked process nil)
                          (.setMonitors process nil)
                          [linked-val mrefs]))]
                  (doseq [p linked]
                    (!control p [:linked-exit pid reason]))
                  (doseq [[mref [pid object]] monitors]
                    (! pid (monitor-message mref object reason))))))))))
    pid))

(defn ^:no-doc update-message-context! [context]
  (swap! *message-context* merge context))

(defmacro ^:no-doc with-message-context [context & body]
  `(binding [*message-context* (atom ~context)]
     ~@body))

(defn ^:no-doc message-context []
  @*message-context*)

(defn ^:no-doc message-q* [^TProcess p]
  (.message-q p))

(defn ^:no-doc message-chan* [^TProcess p]
  (.message-chan p))

(defmacro ^:no-doc select-message [timeout match-clauses or-body]
  (let [patterns (take-nth 2 match-clauses)
        select-clauses (mapcat list patterns (range))
        select-clauses (case (last patterns)
                         :else select-clauses
                         (concat select-clauses [:else :else]))
        msg-sym (gensym "msg")
        by-clause-clauses (partition 2 match-clauses)
        by-clause-matches (map #(concat [`match msg-sym] %) by-clause-clauses)
        case-clauses (mapcat list (range) by-clause-matches)]
    `(let [^TProcess process# (self-process)
           timeout-chan# (u/timeout-chan ~timeout)
           message-q# (message-q* process#)
           message-chan# (message-chan* process#)
           [msg# clause-n# new-mq#]
           (loop [new-mq# (u/queue)]
             (if-let [[_# msg# :as m#] (peek @message-q#)]
               (let [res# (match msg# ~@select-clauses)]
                 (swap! message-q# pop)
                 (if (identical? res# :else)
                   (recur (conj new-mq# m#))
                   [m# res# new-mq#]))
               (let [[res# ch#] (async/alts! [message-chan# timeout-chan#])]
                 (if res#
                   (recur new-mq#)
                   (if (identical? ch# message-chan#)
                     (throw (Exception. "noproc"))
                     [:timeout nil new-mq#])))))]
       (swap! message-q# #(into new-mq# %))
       (if (identical? msg# :timeout)
         (do ~@or-body)
         (let [[context# ~msg-sym] msg#]
           (send-trace-event :receive {:message ~msg-sym})
           (update-message-context! context#)
           (case clause-n#
             ~@case-clauses))))))

(defmacro ^:no-doc select-message-infinitely [match-clauses]
  (let [patterns (take-nth 2 match-clauses)
        select-clauses (mapcat list patterns (range))
        select-clauses (case (last patterns)
                         :else select-clauses
                         (concat select-clauses [:else :else]))
        msg-sym (gensym "msg")
        by-clause-clauses (partition 2 match-clauses)
        by-clause-matches (map #(concat [`match msg-sym] %) by-clause-clauses)
        case-clauses (mapcat list (range) by-clause-matches)]
    `(let [^TProcess process# (self-process)
           message-q# (message-q* process#)
           message-chan# (message-chan* process#)
           [msg# clause-n# new-mq#]
           (loop [new-mq# (u/queue)]
             (if-let [[_# msg# :as m#] (peek @message-q#)]
               (let [res# (match msg# ~@select-clauses)]
                 (swap! message-q# pop)
                 (if (identical? res# :else)
                   (recur (conj new-mq# m#))
                   [m# res# new-mq#]))
               (if-let [res# (<! message-chan#)]
                 (recur new-mq#)
                 (throw (Exception. "noproc")))))]
       (swap! message-q# #(into new-mq# %))
       (let [[context# ~msg-sym] msg#]
         (send-trace-event :receive {:message ~msg-sym})
         (update-message-context! context#)
         (case clause-n#
           ~@case-clauses)))))

(defmacro ^:no-doc select-message-or [match-clauses or-body]
  (let [patterns (take-nth 2 match-clauses)
        select-clauses (mapcat list patterns (range))
        select-clauses (case (last patterns)
                         :else select-clauses
                         (concat select-clauses [:else :else]))
        msg-sym (gensym "msg")
        by-clause-clauses (partition 2 match-clauses)
        by-clause-matches (map #(concat [`match msg-sym] %) by-clause-clauses)
        case-clauses (mapcat list (range) by-clause-matches)]
    `(let [^TProcess process# (self-process)
           message-q# (message-q* process#)
           [mq# _#](reset-vals! message-q# (u/queue))
           res# (loop [mq# mq#
                       new-mq# (u/queue)]
                  (if-let [[_# msg# :as m#] (peek mq#)]
                    (let [res# (match msg# ~@select-clauses)
                          mq# (pop mq#)]
                      (if (identical? res# :else)
                        (recur mq# (conj new-mq# m#))
                        [m# res# (into new-mq# mq#)]))
                    :miss))]
       (if (identical? res# :miss)
         (do ~@or-body)
         (let [[[context# ~msg-sym] clause-n# new-mq#] res#]
           (swap! message-q# #(into new-mq# %))
           (send-trace-event :receive {:message ~msg-sym})
           (update-message-context! context#)
           (case clause-n#
             ~@case-clauses))))))

(defmacro ^:no-doc selective-receive* [clauses]
  (assert (> (count clauses) 1)
          "Receive requires one or more message patterns")
  (if (even? (count clauses))
    `(select-message-infinitely ~clauses)
    (match (last clauses)
      (['after timeout & timeout-body] :seq)
      (let [match-clauses (butlast clauses)]
        (case timeout
          0 `(select-message-or ~match-clauses ~timeout-body)
          :infinity `(select-message-infinitely ~match-clauses)
          `(let [timeout# ~timeout]
             (case timeout#
               0 (select-message-or ~match-clauses ~timeout-body)
               :infinity (select-message-infinitely ~match-clauses)
               (select-message timeout# ~match-clauses ~timeout-body))))))))

(defmacro ^:no-doc take-message-or [match-clauses or-body]
  `(let [^TProcess process# (self-process)
         message-q# (message-q* process#)]
     (if-let [[context# msg#] (peek @message-q#)]
       (do
         (swap! message-q# pop)
         (send-trace-event :receive {:message msg#})
         (update-message-context! context#)
         (match msg# ~@match-clauses))
       (do ~@or-body))))

(defmacro ^:no-doc receive-message-infinitely [park? match-clauses]
  (let [msg-sym (gensym "msg")
        context-sym (gensym "context")
        mchan-sym (gensym "message-chan")
        take (if park? `async/<! `async/<!!)
        alts (if park? `async/alts! `async/alts!!)]
    `(let [^TProcess process# (self-process)
           ~mchan-sym (message-chan* process#)
           mq# (message-q* process#)
           res# (loop []
                  (if-let [res# (peek @mq#)]
                    (do
                      (swap! mq# pop)
                      res#)
                    (if (nil? (~take ~mchan-sym))
                      :noproc
                      (recur))))]
       (if (identical? res# :noproc)
         (throw (Exception. "noproc"))
         (let [[~context-sym ~msg-sym] res#]
           (send-trace-event :receive {:message ~msg-sym})
           (update-message-context! ~context-sym)
           (match ~msg-sym ~@match-clauses))))))

(defmacro ^:no-doc receive-message [park? timeout match-clauses timeout-body]
  (let [msg-sym (gensym "msg")
        context-sym (gensym "context")
        timeout-sym (gensym "timeout")
        mchan-sym (gensym "message-chan")
        take (if park? `async/<! `async/<!!)
        alts (if park? `async/alts! `async/alts!!)]
    `(let [^TProcess process# (self-process)
           ~mchan-sym (message-chan* process#)
           mq# (message-q* process#)
           ~timeout-sym (u/timeout-chan ~timeout)
           res# (loop []
                  (if-let [res# (peek @mq#)]
                    (do
                      (swap! mq# pop)
                      res#)
                    (let [[m# ch#] (~alts [~mchan-sym ~timeout-sym])]
                      (match [m# ch#]
                        [nil ~mchan-sym] :noproc
                        [nil ~timeout-sym] :timeout
                        :else (recur)))))]
       (cond
         (identical? res# :timeout)
         (do ~@timeout-body)

         (identical? res# :noproc)
         (throw (Exception. "noproc"))

         :else
         (let [[~context-sym ~msg-sym] res#]
           (send-trace-event :receive {:message ~msg-sym})
           (update-message-context! ~context-sym)
           (match ~msg-sym ~@match-clauses))))))

(defmacro ^:no-doc receive* [park? clauses]
  (assert (> (count clauses) 1) "Receive requires one or more message patterns")
  (if (even? (count clauses))
    `(receive-message-infinitely ~park? ~clauses)
    (match (last clauses)
      (['after timeout & timeout-body] :seq)
      (let [match-clauses (butlast clauses)]
        (case timeout
          0 `(take-message-or ~match-clauses ~timeout-body)
          :infinity `(receive-message-infinitely ~park? ~match-clauses)
          `(let [timeout# ~timeout]
             (case timeout#
               0 (take-message-or ~match-clauses ~timeout-body)
               :infinity (receive-message-infinitely ~park? ~match-clauses)
               (receive-message
                ~park?
                timeout#
                ~match-clauses
                ~timeout-body))))))))

(defmacro ^:no-doc proc-fn*
  [fname args & body]
  (assert (vector? args)
          (format "Parameter declaration %s should be a vector" args))
  (assert (not (some #{'&} args))
          (format "Variadic arguments are not supported" args))
  (let [arg-names (vec (repeatedly (count args) #(gensym "argname")))]
    `(fn ~@(if fname [fname arg-names] [arg-names])
       (go
         (try
           (loop ~(vec (interleave args arg-names))
             ~@body)
           :normal
           (catch Throwable t#
             (ex->reason t#)))))))

(defmacro ^:no-doc await* [park? x]
  (let [take (if park? `<! `<!!)
        k-sym (gensym "k")
        res-sym (gensym "res")]
    `(let [a# ~x]
       (when-not (async? a#)
         (throw (IllegalArgumentException. "argument must be 'async' value")))
       (let [[~k-sym ~res-sym] (~take (.chan a#))]
         (case ~k-sym
           :ok
           ~res-sym

           :EXIT
           (exit ~res-sym))))))

(defn- !*
  [dest message]
  {:post [(or (true? %) (false? %))]}
  (u/check-args [(some? dest)
                 (some? message)])
  (send-trace-event :send {:destination dest :message message})
  (if-let [^TProcess process (find-process dest)]
    (do
      (swap! (.message-q process) conj message)
      (async/put! (.message-chan process) :go))
    false))

;; ====================================================================
;; API

(defn monitor-ref?
  "Returns `true` if `mref` is a monitor reference, `false` otherwise."
  [mref]
  (instance? MonitorRef mref))

(defn ex->reason
  "Creates exit reason from exception."
  [^Throwable e]
  (or (::exit-reason (ex-data e))
      [:exception (u/stack-trace e)]))

(defmacro ex-catch
  "Executes `expr`. Returns either result of execution or
  `[:EXIT reason]`."
  [expr]
  `(try
     ~expr
     (catch Throwable t#
       [:EXIT (ex->reason t#)])))

(defn pid?
  "Returns `true` if `pid` is a process identifier, `false` otherwise."
  [pid]
  (pid?* pid))

(defn resolve-pid
  "If `pid-or-name` is a pid - returns pid. If a registered name -
  returns the pid of registered process. Else returns `nil`."
  [pid-or-name]
  {:post [(or (nil? %) (pid? %))]}
  (if (pid? pid-or-name)
    pid-or-name
    (whereis pid-or-name)))

(defn pid->str
  "Returns a string corresponding to the text representation of `pid`.

  Throws if `pid` is not a process identifier.

  **Warning:** this function is intended for debugging and is not to be
  used in application programs."
  [^Pid pid]
  {:post [(string? %)]}
  (u/check-args [(pid? pid)])
  (let [pname (.pname pid)
        id (.id pid)]
    (str "<" (if pname (str pname "@" id) id) ">")))

(defn self
  "Returns the process identifier of the calling process.
  Throws when called not in process context."
  []
  {:post [(pid? %)]}
  (if (@*processes *self*)
    *self*
    (throw (Exception. "noproc"))))

(defn whereis
  "Returns the process identifier with the registered name `reg-name`,
  or `nil` if the name is not registered."
  [reg-name]
  {:post [(or (nil? %) (pid? %))]}
  (@*registered reg-name))

(defn !
  "Sends a `message` to `dest`. `dest` can be a process identifier, or a
  registered name.

  If sending results in `dest`'s inbox overflow, `dest` exits with reason
  `:inbox-overflow`.

  Returns `true` if `message` was sent (process was alive), false
  otherwise.

  Throws if any of arguments is `nil`."
  [dest message]
  (u/check-args [(some? message)])
  (!* dest [(if (bound? #'*message-context*) @*message-context* {}) message]))

(defn exit
  "Sends an exit signal with the reason `reason` to the process
  identified by `pid`. If `pid` is not provided exits the calling
  process immediately.

  If reason is any term, except `:normal` or `:kill`:

  - if `pid` is not trapping exits, `pid` itself exits with exit reason.
  - if `pid` is trapping exits, the exit signal is transformed into a
    message `[:EXIT from reason]` and delivered to the message queue
    of `pid`. `from` is the process identifier of the process that sent
    the exit signal.

  If reason is `:normal`, `pid` does not exit. If `pid` is trapping
  exits, the exit signal is transformed into a message
  `[:EXIT from :normal]` and delivered to its message queue.

  If reason is `:kill`, an untrappable exit signal is sent to pid,
  which unconditionally exits with reason `:killed`.

  Returns `true` if exit signal was sent (process was alive), `false`
  otherwise.

  Throws when called not in process context, if `pid` is not a pid, or
  reason is `nil`."
  ([reason] ;FIXME docs
   (throw (ex-info "exit" {::exit-reason reason})))
  ([pid reason]
   {:post [(or (true? %) (false? %))]}
   (u/check-args [(pid? pid)
                  (some? reason)])
   (let [self-pid (self)]
     (case reason
       :kill (if-let [^TProcess process (@*processes pid)]
               (do
                 (swap! (.exit-reason process) #(if (some? %) % :killed))
                 (async/close! (.control-chan process))
                 true)
               false)
       (!control pid [:exit self-pid reason])))))

(defn flag
  "Sets the value of a process' flag. See description of each flag
  below.

  Flags:

  - `:trap-exit`. When set to `true`, exit signals arriving to a
  process are converted to `[:EXIT from reason]` messages, which can
  be received as ordinary messages. If is set to `false`, the process
  exits if it receives an exit signal other than `:normal` and the exit
  signal is propagated to its linked processes.

  Returns the old value of a `flag`.

  Throws when called not in process context."
  [flag value]
  {:post []}
  (u/check-args [(keyword? flag)])
  (if-let [^TProcess process (self-process)]
    (case flag
      :trap-exit (locking *global-lock
                   (let [old-value (flag (.getFlags process))]
                     (.updateFlags process #(assoc % flag (boolean value)))
                     (boolean old-value))))
    (throw (Exception. "noproc"))))

(defn registered
 "Returns a set of names of the processes that have been registered."
 []
 {:post [(set? %)]}
 (set (keys @*registered)))

(defn link
  "Creates a link between the calling process and another process
  identified by `pid`, if there is not such a link already. If a
  process attempts to create a link to itself, nothing is done.

  If pid does not exist and the calling process

  1. is trapping exits - the calling process receives message
  `[:EXIT pid :noproc]`.
  2. is not trapping exits - process exits with reason `:noproc`.

  Returns `true`.

  Throws when called not in process context, or by exited process,
  or `pid` is not a pid."
  [pid]
  {:post [(true? %)]}
  (u/check-args [(pid? pid)])
  (let [^TProcess my-process (self-process)
        my-pid (.pid my-process)]
    (if (identical? my-pid pid)
      true
      (if-let [^TProcess other-process (@*processes pid)]
        (try
          (locking *global-lock
            (or (.updateLinked my-process #(if % (conj % pid)))
                (throw (ex-info "" {:proc :self})))
            (or (.updateLinked other-process #(if % (conj % my-pid)))
                (throw (ex-info "" {:proc :other}))))
          (catch clojure.lang.ExceptionInfo e
            (case (:proc (ex-data e))
              :self (throw (Exception. "noproc"))
              :other (!control my-pid [:exit pid :noproc]))))
        (!control my-pid [:exit pid :noproc])))
    true))

(defn unlink
  "Removes the link, if there is one, between the calling process and
  the process referred to by `pid`.

  Returns `true`.

  Does not fail if there is no link to `pid`, if `pid` is self pid, or
  if `pid` does not exist.

  Once unlink has returned, it is guaranteed that the link between
  the caller and the entity referred to by `pid` has no effect on the
  caller in the future (unless the link is setup again).

  If the caller is trapping exits, an `[:EXIT pid _]` message from
  the link can have been placed in the caller's message queue before
  the call.

  Notice that the `[:EXIT pid _]` message can be the result of the
  link, but can also be the result of pid calling exit. Therefore,
  it can be appropriate to clean up the message queue when trapping
  exits after the call to unlink.

  Throws when called not in process context, or called by exited
  process, or `pid` is not a pid."
  [pid]
  {:post [(true? %)]}
  (u/check-args [(pid? pid)])
  (let [^TProcess my-process (self-process)
        my-pid (.pid my-process)]
    (if (not= pid my-pid)
      (if-let [^TProcess other-process (@*processes pid)]
        (locking *global-lock
          (.updateLinked my-process #(disj % pid))
          (.updateLinked other-process #(disj % my-pid)))))
    true))

(defmacro selective-receive!
  "The same as `receive!` but doesn't crash if the first message
  doesn't match. Instead waits for the matching message, removes it
  from the mailbox leaving all the rest messages in the original order.

  When the timeout is `0`, checks all the messages in the mailbox
  and not the first one only."
  [& clauses]
  `(selective-receive* ~clauses))

(defn monitor
  "Sends a monitor request to the entity identified by `pid-or-name`.
  If the monitored entity does not exist or when it dies,
  the caller of monitor will be notified by a message of the
  following format:

  ```
  [tag monitor-ref type object info]
  ```

  `type` can be one of the following keywords: `:process`.

  A monitor is triggered only once, after that it is removed from
  both monitoring process and the monitored entity. Monitors are
  fired when the monitored process terminates, or does not
  exist at the moment of creation. The monitoring is also turned
  off when `demonitor` is called.

  When monitoring by name please note, that the registered-name is
  resolved to pid only once at the moment of monitor instantiation,
  later changes to the name registration will not affect the existing
  monitor.

  When a monitor is triggered, a `:DOWN` message that has the
  following pattern

  ```
  [:DOWN monitor-ref type object info]
  ```

  is sent to the monitoring process.

  In monitor message `monitor-ref` and `type` are the same as described
  earlier, and:

  - `object` - the monitored entity, which triggered the event. That is the
    argument of monitor call.
  - `info` - either the exit reason of the process, or `:noproc`
    (process did not exist at the time of monitor creation).

  Making several calls to `monitor` for the same `pid-or-name` is not
  an error; it results in as many independent monitoring instances.

  Monitoring self does nothing.

  Returns `monitor-ref`.

  Throws when called not in process context."
  [pid-or-name]
  {:post [(monitor-ref? %)]}
  (let [my-pid (self)]
    (if-let [^TProcess other-process (@*processes (resolve-pid pid-or-name))]
      (let [other-pid (.pid other-process)]
        (if (identical? my-pid other-pid)
          (new-monitor-ref)
          (let [mref (new-monitor-ref other-pid)]
            (if (locking *global-lock
                  (.updateMonitors
                    other-process
                    #(if % (assoc % mref [my-pid pid-or-name]))))
              mref
              (let [empty-mref (new-monitor-ref)]
                (! my-pid (monitor-message empty-mref pid-or-name :noproc))
                empty-mref)))))
      (let [empty-mref (new-monitor-ref)]
        (! my-pid (monitor-message empty-mref pid-or-name :noproc))
        empty-mref))))

(defn demonitor
  "If `mref` is a reference that the calling process obtained by
  calling monitor, this monitoring is turned off. If the monitoring
  is already turned off, nothing happens. If `mref` is created by
  other process, nothing happens.

  Once demonitor has returned, it is guaranteed that no
  `[:DOWN monitor-ref _ _ _]` message, because of the monitor,
  will be placed in the caller message queue in the future.
  A `[:DOWN monitor-ref _ _ _]` message can have been placed in
  the caller message queue before the call, though. It is therefore
  usually advisable to remove such a `:DOWN` message from the message
  queue after monitoring has been stopped.
  `(demonitor mref {:flush true})` can be used instead of
  `(demonitor mref)` if this cleanup is wanted.

  When `:flush` option is `true`, removes (one) `:DOWN` message,
  if there is one, from the caller message queue after monitoring
  has been stopped. This is equivalent to the following:
  ```
  (demonitor mref)
  (selective-receive!
    [_ mref _ _ _] true
    (after 0
      true))
  ```

  Returns `true`.

  Throws when called not in process context, `mref` is not a
  monitor-ref."
  ([mref]
   (demonitor mref {}))
  ([{:keys [self-pid other-pid] :as mref} {flush? :flush}]
   {:post [(= true %)]}
   (u/check-args [(monitor-ref? mref)])
   (if (and (= self-pid (self)) other-pid)
     (if-let [^TProcess other-process (@*processes other-pid)]
       (locking *global-lock (.updateMonitors other-process #(dissoc % mref)))))
   (if flush?
     (selective-receive!
      [:DOWN mref _1 _2 _3] :ok
      (after 0 :ok)))
   true))

(defn spawn-opt
  "Returns the process identifier of a new process started by the
  application of `proc-fun` to `args`.

  `options` argument is a map of option names (keywords) to their
  values.

  The following options are allowed:

  - `:flags` - a map of process' flags (e.g. `{:trap-exit true}`)
  - `:link` - if `true`, sets a link to the parent process
  - `:register` - name to register the process, can not be pid, if name is
    `nil` process will not be registered"
  ([proc-func opts]
   (spawn-opt proc-func [] opts))
  ([proc-func args opts]
   (spawn* proc-func args opts)))

(defn spawn
  "Returns the process identifier of a new process started by the
  application of `proc-fun` to `args`."
  ([proc-func]
   (spawn proc-func []))
  ([proc-func args]
   (spawn-opt proc-func args {})))

(defn spawn-link
  "Returns the process identifier of a new process started by the
  application of `proc-fun` to `args`. A link is created between the
  calling process and the new process, atomically. Otherwise works
  like `spawn`.

  Throws when called not in process context."
  ([proc-func]
   (spawn-link proc-func []))
  ([proc-func args]
   (spawn-opt proc-func args {:link true})))

(defmacro receive!
  "Receives and removes from the inbox the first message sent to the
  process using the `!` function:

  ```
  (receive!
    pattern1 pattern-expr1
    pattern2 pattern-expr2
    ...)
  ```

  The message is matched using `clojure.core.match/match` against the
  patterns. If a match succeeds, the corresponding expression is
  evaluated, otherwise throws. It is illegal to use a `receive!` with
  no patterns.

  If there are no messages in the inbox, the execution is suspended,
  possibly indefinitely, until the first message arrives.

  The receive expression can be augmented with a timeout:

  ```
  (receive!
    pattern pattern-expr
    ...
    (after timeout
      timeout-expr))
  ```

  There are two special cases for the `timeout` value:
  `:infinity` - the process is to wait indefinitely for a matching
    message. This is the same as not using a timeout. This can be
    useful for timeout values that are calculated at runtime.
  `0` - if there is no messages in the mailbox, or the first message
   doesn't match, the timeout occurs immediately.

  Returns the value of the evaluated expression."
  [& clauses]
  `(receive* true ~clauses))

(defmacro receive!!
  "The same as `receive!` but blocks."
  [& clauses]
  `(receive* false ~clauses))

(defmacro proc-fn
  "Creates process function which can be passed to `spawn`."
  [args & body]
  `(proc-fn* nil ~args ~@body))

(defmacro proc-defn
  "The same as `(def fname (proc-fn args body))`."
  {:arglists '([fname doc-string? args & body])}
  [fname docs-or-args & more]
  (let [[doc-string args body] (if (string? docs-or-args)
                                 [docs-or-args (first more) (rest more)]
                                 [nil docs-or-args more])
        arglists (list 'quote (list args))
        fname (vary-meta fname assoc :arglists arglists)
        fname (if doc-string (vary-meta fname assoc :doc doc-string) fname)]
    `(def ~fname (proc-fn* ~fname ~args ~@body))))

(defmacro proc-defn-
  "The same as proc-defn, but defines a private var."
  [fname args & body]
  `(proc-defn ~(vary-meta fname assoc :private true) ~args ~@body))

(defmacro async
  "Executes body asynchronously. Like go-block but propagates
  exceptions.

  The returned value is to be passed to `await!`."
  [& body]
  `(->Async (go (ex-catch [:ok (do ~@body)] ))))

(defmacro await!
  "Returns the value of the async operation represented by `x` or exits
  with the same reason the operation exited. Parks until the operation is
  completed if required.

  It is illegal to pass the same async value to `await!` more than once.

  Throws if `x` is not async value (i.e. is not returned by `async`)."
  [x]
  `(await* true ~x))

(defn await!!
  "The same as `await!` but blocks."
  [x]
  (await* false x))

(defn async?
  "Returns `true` if `x` is async value (i.e. is returned by `async`),
  otherwise returns `false`."
  [x]
  (instance? Async x))

(defmacro async?-value!
  "If `x` is returned by `async`, returns the value of the corresponding
  async operation (parks if needed). If `x` is a regular value, returns
  `x`."
  [x]
  `(let [res# ~x]
     (cond
       (async? res#) (await! res#)
       :else res#)))

(defn processes []
  (keys @*processes))

(defn process-info [pid]
  (u/check-args [(pid? pid)])
  (if-let [^TProcess process (@*processes pid)]
    (let [mq @(.message-q process)]
      {:links (.getLinked process)
       ;; :monitors TODO
       :monitored-by (->> (.getMonitors process) (vals) (map first))
       :registered-name (@*registered-reverse pid)
       :initial-call (.initial-call process)
       :message-queue-len (count mq)
       :messages (map second mq)
       :flags (.getFlags process)})))

(defn trace [pred handler]
  (let [t-ref (swap! *refids inc)
        handler #(if (pred %) (handler %))]
    (swap! *trace-handlers assoc t-ref handler)
    t-ref))

(defn untrace [t-ref]
  (swap! *trace-handlers dissoc t-ref))
