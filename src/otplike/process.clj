(ns otplike.process
  (:require
    [clojure.core.async :as async :refer [<!! <! >! put! go go-loop]]
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

(defn self []
  *self*)

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

(defn pid->str [^Pid {:keys [id name]}]
  (str "<" (if name (str (str name) "@" id) id) ">"))

(defmethod print-method Pid [o w]
  (print-simple
    (pid->str o) w))

(defn pid? [pid]
  (instance? Pid pid))

(defn- !control [pid message]
  (when-let [{:keys [control]} (@*processes pid)]
    (async/put! control message)))

(defn whereis [id]
  (@*registered id))

(defn- find-process [id]
  (if (instance? Pid id)
    (@*processes id)
    (when-let [pid (whereis id)]
      (@*processes pid))))

(defn ! [pid message]
  (when-let [{:keys [inbox]} (find-process pid)]
    (async/put! inbox message)))

(defn exit [pid reason]
  (when-let [{:keys [control]} (find-process pid)]
    (async/put! control [:exit reason])) :ok)

(defn flag [pid flag value]
  (when-let [{:keys [flags]} (find-process pid)]
    (swap! flags assoc flag value)) :ok)



(defn- monitor* [func pid1 pid2]
  (if-let [{:keys [monitors] :as process} (find-process pid2)]
    (do
      (swap! monitors func pid1) :ok)))

(def monitor
  (partial monitor* conj))

(def demonitor
  (partial monitor* disj))

(defn registered []
  (keys @*registered))

(defrecord ProcessRecord [pid inbox control monitors exit outbox linked flags])

(defn- two-phase [process p1pid p2pid cfn]
  (go
    (let [p1result-chan (async/chan) timeout (async/timeout *control-timout)]
      (!control p1pid [:two-phase-p1 p1result-chan p2pid cfn])
      (match (async/alts! [p1result-chan timeout])
        [_ p1result-chan]
        (do
          (cfn :phase-two process p1pid) nil)

        [nil timeout]
        (do
          (cfn :timeout process p1pid) nil)))))

(defn- link-fn [phase {:keys [linked pid]} other-pid]
  (case phase
    :phase-one
    (do
      (trace/trace pid [:link-phase-one other-pid])
      (swap! linked conj other-pid))

    :phase-two
    (do
      (trace/trace pid [:link-phase-two other-pid])
      (swap! linked conj other-pid))

    :timeout
    (do
      (trace/trace pid [:link-timeout other-pid])
      (exit pid :noproc)))) ; TODO crash :noproc vs. exit :noproc

(defn link [pid]
  (if *self*
    (!control *self* [:two-phase pid link-fn])
    (throw (Exception. "link can only be called in process context"))))

(defn- dispatch-control [{:keys [flags pid linked] :as process} message]
  (trace/trace pid [:control message])

  (let [trap-exit (:trap-exit @flags)]
    (match message
      ; check if xpid is really linked to pid
      [:linked-exit xpid :kill] ; if neighbour is killed, we terminate unconditionally
        :kill

      [:linked-exit xpid :normal] ; if neighbour terminates normally we do nothing unless we have :trap-exit
      (when trap-exit
        (! pid [:down xpid :normal]) nil)

      [:linked-exit xpid reason] ; if neighbour terminates with non-normal reason, we terminate as well, unless we have :trap-exit
      (if trap-exit
        (do
          (! pid [:down xpid reason]) nil) reason)

      [:exit reason]
      (if (or (not trap-exit) (= reason :normal) (= reason :kill))
        reason
        (do
          (async/put! pid [:exit reason]) nil))

      [:two-phase other cfn]
        (let [p1result (two-phase process other pid cfn)]
          (<!! p1result))

      [:two-phase-p1 result other-pid cfn]
      (do
        (async/put! result
          (cfn :phase-one process other-pid)) nil))))

(defn- dispatch [{:keys [pid control return] :as process} [message port]]
  (condp = port
    return
    (do
      (trace/trace pid [:return (or message :nil)])
      (or message :nil))

    control
    (dispatch-control process message)))

(defprotocol IClose
  (close! [_]))

(defn- outbox [pid inbox]
  (let [outbox (async/chan 1) stop (async/chan)]
    (go-loop []
      (let [[value _] (async/alts! [stop inbox] :priority true)]
        (if (not (nil? value))
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
  (let [return (apply proc-func inbox params)]
    (if (satisfies? ap/ReadPort return)
      return
      (let [wrap (async/chan)]
          (if-not (nil? return)
            (async/put! wrap return)
            (async/close! wrap))
          wrap))))

(defn- resolve-proc-func [form]
  (cond
    (fn? form)
    form

    (symbol? form)
    (some-> form
      resolve var-get)))

(defn spawn
  "Returns the pid of newly created process."
  [proc-func params {:keys [link-to inbox-size flags name register] :as options}]
  {:pre [(or (fn? proc-func) (symbol? proc-func))
         (sequential? params)
         (map? options)
         (or (nil? link-to) (pid? link-to) (every? pid? link-to))
         (or (nil? inbox-size) (not (neg? inbox-size)))
         (or (nil? flags) (map? flags))]
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
            process (->ProcessRecord pid inbox control monitors exit outbox linked flags)]

        (dosync
          (when (some? register)
            (when (@*registered register)
              (throw (Exception. (str "already registered: " register))))
            (swap! *registered assoc register pid))

          (swap! *processes assoc pid process))

        (trace/trace pid [:start (str proc-func) params options])

        (binding [*self* pid] ; workaround for ASYNC-170. once fixed, binding should move to (start-process...)
          (go
            (when link-to
              (doseq [link-to (apply hash-set (flatten [link-to]))] ; ??? probably optimize by sending link requests concurently
                (<!! (two-phase process link-to pid link-fn)))) ; wait for protocol to complete))

            (let [return (start-process proc-func outbox params)
                  process (assoc process :return return)]
              (loop []
                (let [vp (async/alts! [control return])]
                  (if-let [reason (dispatch process vp)]
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
                        (!control p [:linked-exit pid reason]))

                      (doseq [p @monitors]
                        (! p [:down pid reason])))
                    (recur))))))))) pid))

(defn spawn-link [proc-func params opts]
  (if *self*
    (let [opts (update-in opts [:link-to] conj *self*)]
      (spawn proc-func params opts))
    (throw (Exception. "spawn-link can only be called in process context"))))

(defn pipe [from to]
  (go-loop []
    (let [message (<! from)]
      (! to [from message])
      (when message
        (recur)))))
