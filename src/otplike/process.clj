(ns otplike.process
  (:require [clojure.core.async :as async :refer [<!! <! >! put! go go-loop]]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.core.match :refer [match]]
            [otplike.trace :as trace]))

(def ^:private *pids
  (atom 0))

(def ^:private *processes
  (atom {}))

(def ^:private *registered
  (atom {}))

(def ^:private *control-timout 100)

(def ^:private ^:dynamic *self* nil)

(declare pid->str)

(defrecord Pid [id name]
  Object
  (toString [self]
    (pid->str self))

  ap/WritePort
  (put! [this val handler]
    (when-let [{:keys [inbox]} (@*processes this)]
      (trace/trace this [:inbound val])
      (ap/put! inbox val handler))))

(defn pid? [pid]
  (instance? Pid pid))

(defn pid->str [^Pid {:keys [id name] :as pid}]
  {:pre [(pid? pid)]
   :post [(string? %)]}
  (str "<" (if name (str name "@" id) id) ">"))

(defmethod print-method Pid [o w]
  (print-simple (pid->str o) w))

(defrecord ProcessRecord [pid inbox control monitors exit outbox linked flags])

(defn- new-process [pid inbox control monitors exit outbox linked flags]
  {:pre [(pid? pid)
         (satisfies? ap/ReadPort inbox) (satisfies? ap/WritePort inbox)
         (satisfies? ap/ReadPort control) (satisfies? ap/WritePort control)
         (set? @monitors) (every? pid? @monitors)
         (satisfies? ap/ReadPort outbox)
         (set? @linked) (every? pid? @linked)
         (map? @flags)]
   :post [(instance? ProcessRecord %)]}
  (->ProcessRecord pid inbox control monitors exit outbox linked flags))

(defn self []
  {:post [(pid? %)]}
  (or *self* (throw (Exception. "not in process"))))

(defn whereis [id]
  {:pre [(some? id)]
   :post [(or (nil? %) (pid? %))]}
  (@*registered id))

(defn- find-process [id]
  {:post [(or (nil? %) (instance? ProcessRecord %))]}
  (if (pid? id)
    (@*processes id)
    (when-let [pid (whereis id)]
      (@*processes pid))))

(defn ! [pid message]
  {:pre [(some? pid)
         (some? message)]
   :post [(or (true? %) (false? %))]}
  (if-let [{:keys [inbox]} (find-process pid)]
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

(defn exit [pid reason]
  {:pre [(pid? pid)
         (some? reason)]
   :post [(or (true? %) (false? %))]}
  (!control pid [:exit nil reason]))

(defn flag [flag value]
  {:pre [(keyword? flag)]
   :post []}
  (if-let [^ProcessRecord {:keys [flags]} (find-process (self))]
    (dosync
      (let [old-value (flag @flags)]
        (match flag
          :trap-exit (do
                       (swap! flags assoc flag (boolean value))
                       (boolean old-value)))))
    (throw (Exception. "stopped"))))

(defn- monitor* [func pid1 pid2]
  (if-let [{:keys [monitors] :as process} (find-process pid2)]
    (do
      (swap! monitors func pid1)
      :ok)))

(def monitor
  (partial monitor* conj))

(def demonitor
  (partial monitor* disj))

(defn registered []
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
          noproc #(do (cfn :noproc process p1pid) nil)]
      (if (!control p1pid [:two-phase-p1 p1result-chan p2pid cfn])
        (let [timeout (async/timeout *control-timout)]
          (match (async/alts! [p1result-chan timeout])
            [_ p1result-chan]
            (do
              (cfn :phase-two process p1pid)
              nil)
            [nil timeout]
            (noproc)))
        (noproc)))))

(defn- link-fn [phase {:keys [linked pid]} other-pid]
  (case phase
    :phase-one (do
                 (trace/trace pid [:link-phase-one other-pid])
                 (swap! linked conj other-pid))
    :phase-two (do
                 (trace/trace pid [:link-phase-two other-pid])
                 (swap! linked conj other-pid))
    :noproc (do
               (trace/trace pid [:link-timeout other-pid])
               (exit pid :noproc)))) ; TODO crash :noproc vs. exit :noproc

(defn link [pid]
  {:pre [(pid? pid)]
   :post [(true? %)]}
  (if-let [complete (two-phase-start (self) pid link-fn)]
    true
    (throw (Exception. "stopped"))))

(defn- unlink-fn [phase {:keys [linked pid]} other-pid]
  (let [p2unlink #(do (trace/trace pid [% other-pid])
                      (swap! linked disj other-pid))]
    (case phase
      :phase-one (p2unlink :unlink-phase-one)
      :phase-two (p2unlink :unlink-phase-two)
      :noproc (p2unlink :unlink-phase-two))))

(defn unlink [pid]
  {:pre [(pid? pid)]
   :post [(true? %)]}
  (if-let [complete (two-phase-start (self) pid unlink-fn)]
    (do (<!! complete) true)
    (throw (Exception. "stopped"))))

; TODO return new process and exit code
(defn- dispatch-control [{:keys [flags pid linked] :as process} message]
  {:pre [(instance? ProcessRecord process)]
   :post []}
  (trace/trace pid [:control message])
  (go
    (let [trap-exit (:trap-exit @flags)]
      (match message
        [:exit xpid :kill] :killed
        [:exit xpid :normal] (when trap-exit
                               (! pid [:EXIT xpid :normal])
                               nil)
        [:exit xpid reason] (if trap-exit
                              (do
                                (! pid [:EXIT xpid reason])
                                nil)
                              reason)
        [:two-phase
         complete other cfn] (let [p1result (two-phase process other pid cfn)]
                               (<! p1result)
                               (async/close! complete)
                               nil)
        [:two-phase-p1
         result other-pid cfn] (do
                                 (async/put! result
                                             (cfn :phase-one process other-pid))
                                 nil)))))

; TODO get rid of this fn moving its code to calling fn
(defn- dispatch
  [{:keys [pid control return] :as process} {message 0 port 1 :as mp}]
  {:pre [(instance? ProcessRecord process)
         (satisfies? ap/ReadPort port)
         (vector? mp) (= 2 (count mp))]}
  (go
    (condp = port
      return (do
               (trace/trace pid [:return (or message :nil)])
               (if (some? message) message :nil))
      control (<! (dispatch-control process message)))))

(defprotocol IClose
  (close! [_]))

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
            (trace/trace pid [:deliver value])
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
(defn- start-process [proc-func inbox params]
  {:pre [(fn? proc-func)
         (satisfies? ap/ReadPort inbox)
         (sequential? params)]
   :post [(satisfies? ap/ReadPort %)]}
  (match (apply proc-func inbox params)
    (chan :guard #(satisfies? ap/ReadPort %)) chan))

(defn- resolve-proc-func [form]
  {:pre [(or (fn? form) (symbol? form))]
   :post [(fn? %)]}
  (cond
    (fn? form) form
    (symbol? form) (some-> form resolve var-get)))

(defn spawn
  "Returns the pid of newly created process."
  [proc-func params {:keys [link-to inbox-size flags name register] :as options}]
  {:pre [(or (fn? proc-func) (symbol? proc-func))
         (sequential? params)
         (map? options) ;FIXME check for unknown options
         (or (nil? link-to) (pid? link-to) (every? pid? link-to))
         (or (nil? inbox-size)
             (and (integer? inbox-size) (not (neg? inbox-size))))
         (or (nil? flags) (map? flags))] ;FIXME check for unknown flags
   :post [(pid? %)]}
  (let [proc-func (resolve-proc-func proc-func)
        id        (swap! *pids inc)
        inbox     (async/chan (or inbox-size 1024))
        pid       (Pid. id (or name (str "proc" id)))
        control   (async/chan 128)
        linked    (atom #{})
        monitors  (atom #{})
        flags     (atom (or flags {}))]
    (locking *processes
      (let [outbox  (outbox pid inbox)
            process (new-process
                      pid inbox control monitors exit outbox linked flags)]
        (dosync
          (when (some? register)
            (when (@*registered register)
              (throw (Exception. (str "already registered: " register))))
            (swap! *registered assoc register pid))
          (swap! *processes assoc pid process))
        (trace/trace pid [:start (str proc-func) params options])
        (binding [*self* pid] ; workaround for ASYNC-170. once fixed, binding should move to (start-process...)
          (let [return (start-process proc-func outbox params)]
            (go
              (when link-to
                (doseq [link-to (apply hash-set (flatten [link-to]))] ; ??? probably optimize by sending link requests concurently
                  (<! (two-phase process link-to pid link-fn)))) ; wait for protocol to complete
              (let [process (assoc process :return return)]
                (loop []
                  (let [vp (async/alts! [control return])]
                    (if-let [reason (<! (dispatch process vp))]
                      (do
                        (trace/trace pid [:terminate reason])
                        (close! outbox)
                        (dosync
                          (swap! *processes dissoc pid)
                          (when register
                            (swap! *registered dissoc register))
                          (doseq [p @linked]
                            (when-let [p (@*processes p)]
                              (swap! (:linked p) disj process))))
                        (doseq [p @linked]
                          (!control p [:exit pid reason]))
                        (doseq [p @monitors]
                          (! p [:down pid reason])))
                      (recur))))))))))
    pid))

(defn spawn-link [proc-func params opts]
  {:pre [(or (nil? opts) (map? opts))]
   :post [(pid? %)]}
  (let [opts (update-in opts [:link-to] conj (self))]
    (spawn proc-func params opts)))

; TODO move to util namespace
(defn pipe [from to]
  (go-loop []
    (let [message (<! from)]
      (! to [from message])
      (when message
        (recur)))))
