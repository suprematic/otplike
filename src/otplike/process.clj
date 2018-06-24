(ns otplike.process
  "This namespace implements core process concepts such as spawning,
  linking, monitoring, message passing and exiting.

  ### Process context

  All the calls made from process function directly or indirectly after
  it has been spawned happen in the context of the process (i.e., are
  issued by the process).

  ### Process exit

  A process exits when:

  - it receives exit signal with reason `:kill`,
  - it receives exit signal with the reason other than `:kill`, and it
    doesn't trap exits,
  - its initial function ends (returning a value or with exception).

  As there is no way to force process function to stop execution after
  its process has exited, a process can be _alive_ or _exiting_:

  - a process is alive until it exits for any reason,
  - a process becomes exiting after it exited until it's initial
    function returns.

  There can be cases when exiting process tries to communicate with
  other processes. In such cases exception with the reason `:noproc`
  is thrown.

  The following happens when a process exits:

  - its mailbox becomes closed so that no future messages can be received,
  - all linked/monitoring processes receive exit/down signal,
  - it can not be reached using its pid,
  - it is no longer registered.

  ### Signals (control messages)

  Signals are used internally to manage processes. Exiting, monitoring,
  linking and some other operations require sending signals (not messages)
  to involved processes."
  (:require [clojure.core.async :as async :refer [<!! <! >! put! go go-loop]]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.core.match :refer [match]]
            [clojure.data.int-map :as imap]
            [clojure.spec.alpha :as spec]
            [otplike.util :as u]))

(when (and (= 1 (:major *clojure-version*))
           (< (:minor *clojure-version*) 9))
  (require '[clojure.future :refer :all]))

(declare pid->str pid? self whereis ref? ! ex->reason exit async?)

(deftype Pid [^Long id]
  Object
  (toString [self]
    (pid->str self))
  (hashCode [_self]
    (.hashCode id))
  (equals [^Pid self other]
    (and (identical? Pid (type other))
         (= id (.id ^Pid other)))))
(alter-meta! #'->Pid assoc :private true)

(defn- pid?* [pid]
  (instance? Pid pid))

(deftype Async [chan value map-fns])
(alter-meta! #'->Async assoc :no-doc true)

(deftype TRef [id]
  Object
  (toString [_]
    (format "TRef<%d>" id))
  (hashCode [_self]
    (.hashCode id))
  (equals [_ other]
    (and (identical? TRef (type other))
         (= id (.id ^TRef other)))))
(alter-meta! #'->TRef assoc :private true)

(defprotocol IProcFn
  (call [_ pid args]))

(deftype TProcFn [f name]
  IProcFn
  (call [this pid args]
    (apply f this pid args))
  Object
  (toString [_]
    (format "TProcFn<%s>" name)))
(alter-meta! #'->TProcFn assoc :no-doc true)

;; ====================================================================
;; Specs

(spec/def ::pid pid?*)

(spec/def ::async #(instance? Async %))

;; ====================================================================
;; Internal

(def ^:private *next-pid
  (atom 0))

(def ^:private *refids
  (atom 0))

(def ^:private *trace-handlers
  (atom {}))

(def ^:private *processes
  (atom (imap/int-map)))

(def ^:private *registered
  (atom {}))

(def ^:private *registered-reverse
  (atom (imap/int-map)))

(def ^:private *control-timeout 100)

(def ^:no-doc ^:dynamic *self* nil)

(def ^:dynamic ^:no-doc *message-context* (atom {}))

(defn- ->nil [x])

(defrecord TraceMessage [pid reg-name kind extra])
(alter-meta! #'->TraceMessage assoc :private true)
(alter-meta! #'map->TraceMessage assoc :private true)

(defn ^:no-doc send-trace-event [^Pid pid kind extra]
  (if-let [handlers (seq (vals @*trace-handlers))]
    (let [reg-name (if pid (@*registered-reverse (.id pid)))]
      (doseq [handler handlers]
        (try
          (handler (TraceMessage. pid reg-name kind extra))
          (catch Throwable _))))))

(defmethod print-method Pid [o w]
  (print-simple (pid->str o) w))

(deftype TProcess
  [pid
   initial-call
   start-ns
   message-chan
   message-q
   control-chan
   control-q
   exit-reason
   status
   monitored-by
   monitors
   linked
   flags])

(defn- new-process [initial-call flags]
  {:pre [(map? flags)]
   :post [(instance? TProcess %)]}
  (let [id (swap! *next-pid inc)
        pid (Pid. id)
        start-ns (System/nanoTime)
        message-chan (async/chan (async/sliding-buffer 1))
        message-q (atom (u/queue))
        control-chan (async/chan (async/sliding-buffer 1))
        control-q (atom (u/queue))
        exit-reason (atom nil)
        status (atom :running)
        monitored-by (atom {})
        monitors (atom {})
        linked (atom #{})
        flags (atom (merge {:trap-exit false} flags))]
    (TProcess.
     pid
     initial-call
     start-ns
     message-chan
     message-q
     control-chan
     control-q
     exit-reason
     status
     monitored-by
     monitors
     linked
     flags)))

(defn ^:no-doc self-process
  []
  {:post [(instance? TProcess %)]}
  (or (@*processes (.id ^Pid *self*))
      (exit :noproc)))

(defn- make-ref []
  (TRef. (swap! *refids inc)))

(defn- find-process [id]
  {:pre [(some? id)]
   :post [(or (nil? %) (instance? TProcess %))]}
  (if (pid? id)
    (@*processes (.id ^Pid id))
    (when-let [^Pid pid (whereis id)]
      (@*processes (.id pid)))))

(defn- !control [^Pid pid message]
  {:pre [(pid? pid)
         (vector? message) (keyword? (first message))]
   :post [(or (true? %) (false? %))]}
  (if-let [^TProcess process (@*processes (.id pid))]
    (do
      (swap! (.control-q process) conj message)
      (async/put! (.control-chan process) :go))
    false))

(defn- monitor-message [mref object reason]
  {:pre [(ref? mref)]}
  [:DOWN mref :process object reason])

(defn- dispatch-control [^TProcess process message]
  {:pre [(instance? TProcess process)]
   :post []}
  (let [trap-exit (:trap-exit @(.flags process))
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
        (if trap-exit
          (let [[old] (swap-vals! (.linked process) disj xpid)]
            (if (contains? old xpid)
              (! pid [:EXIT xpid reason]))
            ::continue)
          (case reason
            :normal
            (do
              (swap-vals! (.linked process) disj xpid)
              ::continue)
            (let [[old new]
                  (swap-vals! (.linked process) #(if (contains? % xpid) nil %))]
              (if (nil? new)
                (do
                  (doseq [p (disj old xpid)]
                    (!control p [:linked-exit pid reason]))
                  reason)
                ::continue)))))

      (identical? k :monitored-exit)
      (let [mref (message 1)
            reason (message 2)
            my-mrefs (.monitors process)]
        (locking my-mrefs
          (let [[old] (swap-vals! my-mrefs dissoc mref)]
            (when-let [[_ obj] (get old mref)]
              (swap!
               (.message-q process) conj [{} (monitor-message mref obj reason)])
              (async/put! (.message-chan process) :go))))
        ::continue)

      :else
      (throw (Exception. "Unexpected control message")))))

(defn- sync-unregister [^TProcess process reason]
  (let [^Pid pid (.pid process)
        control-chan (.control-chan process)
        message-chan (.message-chan process)
        exit-reason (.exit-reason process)]
    (swap! exit-reason #(if (nil? %) reason %))
    (send-trace-event pid :exiting {:reason reason})
    (when-let [register (@*registered-reverse (.id pid))]
      (swap! *registered dissoc register)
      (swap! *registered-reverse dissoc (.id pid)))
    (async/close! control-chan)
    (async/close! message-chan)
    (let [[linked] (reset-vals! (.linked process) nil)
          [mrefs] (reset-vals! (.monitored-by process) nil)]
      (reset! (.monitors process) nil)
      (doseq [p linked]
        (!control p [:linked-exit pid reason]))
      (doseq [[mref p] mrefs]
        (!control p [:monitored-exit mref reason])))))

(defn ^:no-doc !exit [^TProcess process reason]
  (swap! (.exit-reason process) #(if (nil? %) reason %))
  (async/put! (.control-chan process) :go))

(defn- start-process [^TProcess process ^TProcFn proc-fn args register link?]
  (let [^Pid pid (.pid process)
        ^Pid self-pid *self*]
    (send-trace-event self-pid :spawn {:fn (.name proc-fn) :args args})
    (when link?
      (let [^TProcess other-process (or (@*processes (.id self-pid))
                                        (exit :noproc))
            other-pid (.pid other-process)]
        (swap! (.linked process) conj other-pid)
        (let [[old] (swap-vals! (.linked other-process) #(if % (conj % pid)))]
          (when (nil? old)
            (reset! (.linked process) nil)
            (exit :noproc)))))
    (swap! *processes assoc (.id pid) process)
    ;; FIXME bindings from folded binding blocks are stacked, so no values
    ;; bound between bottom and top folded binding blocks are garbage
    ;; collected; see "ring" benchmark example
    (binding [*self* (.pid process)
              *message-context* (atom @*message-context*)]
      (try
        (when (some? register)
          (when (@*registered register)
            (exit :already-registered))
          (swap! *registered assoc register pid)
          (swap! *registered-reverse assoc (.id pid) register))
        (call proc-fn pid args)
        (catch Throwable t
          (!exit process (ex->reason t))
          (swap! *processes dissoc (.id pid)))))))

(defn- spawn* [^TProcFn proc-fn args {:keys [flags link register] :as options}]
  {:post [(pid? %)]}
  (u/check-args [(sequential? args)
                 (instance? TProcFn proc-fn)
                 (map? options) ;FIXME check for unknown options
                 (or (nil? link) (boolean? link))
                 (or (nil? flags) (map? flags)) ;FIXME check for unknown flags
                 (not (pid? register))])
  (let [flags (or flags {})
        ^TProcess process (new-process [(.name proc-fn) args] flags)
        control-chan (.control-chan process)
        control-q (.control-q process)
        exit-reason (.exit-reason process)]
    (start-process process proc-fn args register link)
    (go-loop []
      (let [result
            (loop []
              (if-let [reason @exit-reason]
                reason
                (if-let [m (peek @control-q)]
                  (do
                    (swap! control-q pop)
                    (dispatch-control process m))
                  (do
                    (<! control-chan)
                    (recur)))))]
        (if (identical? result ::continue)
          (recur)
          (sync-unregister process result))))
    (.pid process)))

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

(defn ^:no-doc change-status [^TProcess process status]
  (let [^:once swap-fn
        #(case %
           :running (case status :waiting :waiting)
           :waiting (case status :running :running))]
    (swap! (.status process) swap-fn)))

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
               (do
                 (change-status process# :waiting)
                 (let [[res# ch#] (async/alts! [message-chan# timeout-chan#])]
                   (change-status process# :running)
                   (if res#
                     (recur new-mq#)
                     (if (identical? ch# message-chan#)
                       (exit :noproc)
                       [:timeout nil new-mq#]))))))]
       (swap! message-q# #(into new-mq# %))
       (if (identical? msg# :timeout)
         (do ~@or-body)
         (let [[context# ~msg-sym] msg#]
           (send-trace-event (.pid process#) :receive {:message ~msg-sym})
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
               (do
                 (change-status process# :waiting)
                 (let [res# (<! message-chan#)]
                   (change-status process# :running)
                   (if (some? res#)
                     (recur new-mq#)
                     (exit :noproc))))))]
       (swap! message-q# #(into new-mq# %))
       (let [[context# ~msg-sym] msg#]
         (send-trace-event (.pid process#) :receive {:message ~msg-sym})
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
           (send-trace-event (.pid process#) :receive {:message ~msg-sym})
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
         (send-trace-event (.pid process#) :receive {:message msg#})
         (update-message-context! context#)
         (match msg# ~@match-clauses))
       (do ~@or-body))))

(defmacro ^:no-doc receive-message-infinitely [park? match-clauses]
  (let [msg-sym (gensym "msg")
        context-sym (gensym "context")
        mchan-sym (gensym "message-chan")
        take (if park? `async/<! `async/<!!)]
    `(let [^TProcess process# (self-process)
           ~mchan-sym (message-chan* process#)
           mq# (message-q* process#)
           res# (loop []
                  (if-let [res# (peek @mq#)]
                    (do
                      (swap! mq# pop)
                      res#)
                    (do
                      (change-status process# :waiting)
                      (let [res# (~take ~mchan-sym)]
                        (change-status process# :running)
                        (if (nil? res#)
                          :noproc
                          (recur))))))]
       (if (identical? res# :noproc)
         (exit :noproc)
         (let [[~context-sym ~msg-sym] res#]
           (send-trace-event (.pid process#) :receive {:message ~msg-sym})
           (update-message-context! ~context-sym)
           (match ~msg-sym ~@match-clauses))))))

(defmacro ^:no-doc receive-message [park? timeout match-clauses timeout-body]
  (let [msg-sym (gensym "msg")
        context-sym (gensym "context")
        timeout-sym (gensym "timeout")
        mchan-sym (gensym "message-chan")
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
                    (do
                      (change-status process# :waiting)
                      (let [[m# ch#] (~alts [~mchan-sym ~timeout-sym])]
                        (change-status process# :running)
                        (match [m# ch#]
                          [nil ~mchan-sym] :noproc
                          [nil ~timeout-sym] :timeout
                          :else (recur))))))]
       (cond
         (identical? res# :timeout)
         (do ~@timeout-body)

         (identical? res# :noproc)
         (exit :noproc)

         :else
         (let [[~context-sym ~msg-sym] res#]
           (send-trace-event (.pid process#) :receive {:message ~msg-sym})
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
          (if (int? timeout)
            `(receive-message
              ~park?
              ~timeout
              ~match-clauses
              ~timeout-body)
            `(let [timeout# ~timeout]
               (case timeout#
                 0 (take-message-or ~match-clauses ~timeout-body)
                 :infinity (receive-message-infinitely ~park? ~match-clauses)
                 (receive-message
                  ~park?
                  timeout#
                  ~match-clauses
                  ~timeout-body)))))))))

(defn ^:no-doc !finish [^Pid self-pid reason]
  (let [process (@*processes (.id self-pid))]
    (send-trace-event self-pid :exit {:reason reason})
    (!exit process reason)
    (swap! *processes dissoc (.id self-pid))))

(defmacro ^:no-doc proc-fn*
  [proc-fn-name fname args & body]
  (assert (vector? args)
          (format "Parameter declaration %s should be a vector" args))
  (assert (not (some #{'&} args))
          (format "Variadic arguments are not supported" args))
  (let [proc-fn-name (or proc-fn-name
                         (gensym (if fname (str (name fname) "_") "proc-fn_")))
        arg-names (vec (repeatedly (count args) #(gensym "argname")))
        this-proc-fn-sym (gensym "this-proc-fn-")
        self-pid-sym (gensym "self-pid-")
        fn-arg-names (into [this-proc-fn-sym self-pid-sym] arg-names)
        ns-fname (symbol (str *ns* "/" proc-fn-name))]
    `(->TProcFn
      (fn ~@(if fname [fname]) ~fn-arg-names
        (send-trace-event
         ~self-pid-sym :spawned {:fn '~ns-fname :args ~arg-names})
        (go
          (try
            ~(if fname
               `(let [~fname ~this-proc-fn-sym]
                  (loop ~(vec (interleave args arg-names))
                    ~@body))
               `(loop ~(vec (interleave args arg-names))
                 ~@body))
            (!finish ~self-pid-sym :normal)
            (catch Throwable t#
              (!finish ~self-pid-sym (ex->reason t#)))))) 
      '~ns-fname)))

(defmacro ^:no-doc await* [park? x]
  (let [take (if park? `<! `<!!)
        k-sym (gensym "k")
        res-sym (gensym "res")]
    `(let [a# ~x]
       (when-not (async? a#)
         (throw (IllegalArgumentException. "argument must be 'async' value")))
       (let [res# (if-let [ch# (.chan a#)]
                    (let [[~k-sym ~res-sym] (~take ch#)]
                      (case ~k-sym
                        :ok
                        ~res-sym

                        :EXIT
                        (exit ~res-sym)))
                    (.value a#))]
         (reduce #(%2 %1) res# (.map-fns a#))))))

;; ====================================================================
;; API

(defn ref?
  "Returns `true` if `x` is a reference, `false` otherwise."
  [x]
  (instance? TRef x))

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
  (format "Pid<%d>" (.id pid)))

(defn self
  "Returns the process identifier of the calling process.
  Throws when called not in process context."
  []
  {:post [(pid? %)]}
  (if-let [^Pid self-pid *self*]
    (if-let [^TProcess process (@*processes (.id self-pid))]
      (if (nil? @(.exit-reason process))
        self-pid
        (exit :noproc))
      (exit :noproc))
    (exit :noproc)))

(defn whereis
  "Returns the process identifier with the registered name `reg-name`,
  or `nil` if the name is not registered."
  [reg-name]
  {:post [(or (nil? %) (pid? %))]}
  (@*registered reg-name))

(defn !
  "Sends a `message` to `dest`. `dest` can be a process identifier, or a
  registered name.

  Returns `true` if `message` was sent (process was alive), false
  otherwise.

  Throws if any of arguments is `nil`."
  [dest message]
  {:post [(or (true? %) (false? %))]}
  (u/check-args [(some? dest)])
  (send-trace-event *self* :send {:destination dest :message message})
  (let [wrapped-message [(if (bound? #'*message-context*)
                           @*message-context* {}) message]]
    (if-let [^TProcess process (find-process dest)]
      (do
        (swap! (.message-q process) conj wrapped-message)
        (async/put! (.message-chan process) :go))
      false)))

(defn exit
  "**When called with one argument (reason)**

  Throws special exception (which can be caught). When the exception
  leaves process' initial function, it causes the process to exit with
  the specified reason.

  **When called with two arguments (pid and reason)**

  Sends an exit signal with the reason `reason` to the process
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

  Notice that process can exit with other reason before exit signal is
  processed.

  Returns `true` if exit signal was sent (process was alive), `false`
  otherwise.

  Throws when called not in process context, if `pid` is not a pid, or
  reason is `nil`."
  ([reason]
   (throw (ex-info "exit" {::exit-reason reason})))
  ([^Pid pid reason]
   {:post [(or (true? %) (false? %))]}
   (u/check-args [(pid? pid)
                  (some? reason)])
   (let [self-pid (self)]
     (case reason
       :kill (if-let [^TProcess process (@*processes (.id pid))]
               (do
                 (!exit process :killed)
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
      :trap-exit
      (let [value (boolean value)
            [old] (swap-vals!
                   (.flags process) #(if % (assoc % :trap-exit value)))]
        (if (nil? old)
          (exit :noproc))
        (-> old :trap-exit boolean)))
    (exit :noproc)))

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
  [^Pid pid]
  {:post [(true? %)]}
  (u/check-args [(pid? pid)])
  (let [^TProcess my-process (self-process)
        my-pid (.pid my-process)]
    (if (= my-pid pid)
      true
      (if-let [^TProcess other-process (@*processes (.id pid))]
        (let [[old] (swap-vals! (.linked my-process) #(if % (conj % pid)))]
          (if (nil? old)
            (exit :noproc))
          (let [[old] (swap-vals!
                       (.linked other-process) #(if % (conj % my-pid)))]
            (when (nil? old)
              (swap! (.linked my-process) disj pid)
              (!control my-pid [:exit pid :noproc]))))
        (!control my-pid [:exit pid :noproc])))
    true))

(defn unlink
  "Removes the link, if there is one, between the calling process and
  the process referred to by `pid`.

  Returns `true`.

  Does not fail if there is no link to `pid`, if `pid` is self pid, or
  if `pid` does not exist.

  Once `unlink` has returned, it is guaranteed that the link between
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
  [^Pid pid]
  {:post [(true? %)]}
  (u/check-args [(pid? pid)])
  (let [^TProcess my-process (self-process)
        my-pid (.pid my-process)]
    (if (not= pid my-pid)
      (if-let [^TProcess other-process (@*processes (.id pid))]
        (let [[old] (swap-vals! (.linked my-process) disj pid)]
          (if (nil? old)
            (exit :noproc))
          (swap! (.linked other-process) disj my-pid))))
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
  {:post [(ref? %)]}
  (let [^Pid other-pid (resolve-pid pid-or-name)
        ^TProcess my-process (self-process)
        ^Pid my-pid (.pid my-process)
        mref (make-ref)]
    (if other-pid
      (when (not= my-pid other-pid)
        (if-let [^TProcess other-process (@*processes (.id other-pid))]
          (do
            (swap! (.monitors my-process) assoc mref [other-pid pid-or-name])
            (let [[old] (swap-vals!
                         (.monitored-by other-process)
                         #(if % (assoc % mref my-pid)))]
              (if (nil? old)
                (! my-pid (monitor-message mref pid-or-name :noproc)))))
          (! my-pid (monitor-message mref pid-or-name :noproc))))
      (! my-pid (monitor-message mref pid-or-name :noproc)))
    mref))

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

  Throws when called not in process context, `mref` is not a ref."
  ([mref]
   (demonitor mref {}))
  ([mref {flush? :flush}]
   {:post [(= true %)]}
   (u/check-args [(ref? mref)])
   (let [^TProcess my-process (self-process)
         my-refs (.monitors my-process)]
     (let [[old] (locking my-refs (swap-vals! my-refs dissoc mref))]
       (if-let [[^Pid other-pid] (get old mref)]
         (if-let [^TProcess other-process (@*processes (.id other-pid))]
           (swap! (.monitored-by other-process) dissoc mref)))))
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
  [name-or-args & args-body]
  (if (symbol? name-or-args)
    `(proc-fn* nil ~name-or-args ~(first args-body) ~@(rest args-body))
    `(proc-fn* nil nil ~name-or-args ~@args-body)))

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
    `(def ~fname (proc-fn* ~fname ~fname ~args ~@body))))

(defmacro proc-defn-
  "The same as proc-defn, but defines a private var."
  [fname args & body]
  `(proc-defn ~(vary-meta fname assoc :private true) ~args ~@body))

(defmacro async
  "Executes body asynchronously. Like go-block but propagates
  exceptions.

  The returned value is to be passed to `await!`."
  [& body]
  `(->Async (go (ex-catch [:ok (do ~@body)])) nil []))

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

(defmacro await?!
  "If `x` is returned by `async`, returns the value of the corresponding
  async operation (parks if needed). If `x` is a regular value, returns
  `x`."
  [x]
  `(let [res# ~x]
     (cond
       (async? res#) (await! res#)
       :else res#)))

(defn map-async
  "Creates a copy of `async-val` adding `f` to the list of its
  transformation functions."
  [f ^Async async-val]
  {:pre [(fn? f) (async? async-val)]
   :post (async? %)}
  (Async.
   (.chan async-val)
   (.value async-val)
   (conj (.map-fns async-val) f)))

(defmacro with-async
  "Wraps `body` into a function with binding-form as its single
  argument.

  Returns `(map-async body-fn async-expr-result)`.

  Works as a recursion point for `body`."
  [[binding-form async-expr :as bindings] & body]
  (assert (and (vector? bindings)
               (= 2 (count bindings)))
          (str "binding must be a vector of two elements: a symbol and"
               " an expression returning async value."))
  `(map-async
    (fn [~binding-form] ~@body)
    ~async-expr))

(defn async-value
  "Wraps `value` into async value."
  [value]
  (Async. nil value []))

(defn alive?
  "Returns `true` if the process exists and is alive, that is,
  is not exiting and has not exited. Otherwise returns `false`.

  When called without arguments, returns information about the calling
  process."
  ([]
   (alive? *self*))
  ([^Pid pid]
   (if-let [^TProcess process (@*processes (.id pid))]
     (nil? @(.exit-reason process))
     false)))

(defn processes
  "Returns a sequence of process identifiers corresponding to all
  the processes currently existing.

  Notice that an exiting process exists, but is not alive.
  That is, `(alive? pid)` returns `false` for an exiting process,
  but its process identifier is part of the result returned from
  `(processes)`."
  []
  (map #(.pid ^TProcess %) (vals @*processes)))

(defn process-info [^Pid pid]
  (u/check-args [(pid? pid)])
  (if-let [^TProcess process (@*processes (.id pid))]
    (let [mq @(.message-q process)]
      {:links @(.linked process)
       :monitors (->> @(.monitors process) vals)
       :monitored-by (->> @(.monitored-by process) vals)
       :registered-name (@*registered-reverse (.id pid))
       :status (if (nil? @(.exit-reason process))
                 @(.status process)
                 :exiting)
       :life-time-ms (quot (- (System/nanoTime) (.start-ns process)) 1000000)
       :initial-call (.initial-call process)
       :message-queue-len (count mq)
       :messages (map second mq)
       :flags @(.flags process)})))

(defn trace [pred handler]
  (let [t-ref (make-ref)
        handler #(if (pred %) (handler %))]
    (swap! *trace-handlers assoc t-ref handler)
    t-ref))

(defn untrace [t-ref]
  (swap! *trace-handlers dissoc t-ref))
