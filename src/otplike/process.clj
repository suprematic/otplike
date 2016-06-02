(ns otplike.process
  (:require
    [clojure.test :refer [deftest run-tests is]]
    [clojure.core.async :as async :refer [<! >! put! go go-loop]]
    [clojure.core.async.impl.protocols :as ap]
    [clojure.core.match :refer [match]]
    [otplike.trace :as trace]))

(def *pids
  (atom 0))

(def *processes
  (atom {}))

(def *registered
  (atom {}))

(defn pid->str [{:keys [id name]}]
  (str "<" (if name (str (str name) "@" id) id) ">"))

(defrecord Pid [id name]
  Object
  (toString [self]
    (pid->str self))

  ap/WritePort
  (put! [this val handler]
    (when-let [{:keys [inbox]} (@*processes this)]
      (trace/trace this [:inbound val])
      (ap/put! inbox val handler))))

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

(defn link [pid1 pid2]
  (let [proc1 (@*processes pid1) proc2 (@*processes pid2)]
    (when (and proc1 proc2)
      (swap! (:linked proc1) conj pid2)
      (swap! (:linked proc2) conj pid1))))

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

(defn- dispatch-control [{:keys [flags pid linked]} message]
  (trace/trace pid [:control message])

  (let [trap-exit (:trap-exit @flags)]
    (match message
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
      (let [trap-exit (:trap-exit @flags)]
        (if (or (not trap-exit) (= reason :normal) (= reason :kill))
          reason
          (do
            (async/put! pid [:exit reason]) nil)))

      [:link xpid confirm]
      (do
        (swap! linked conj xpid)
        (async/close! confirm) nil))))

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

(def ^:dynamic *self* nil)

(defn spawn
  "Returns the pid of newly created process."
  [proc-func params {:keys [link-to inbox-size flags name register] :as options}]
  (assert (or (fn? proc-func) (symbol? proc-func)))
  (assert (sequential? params))
  (assert (map? options))

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
          (when register
            (when (@*registered register)
              (throw (Exception. (str "already registered: " register))))
            (swap! *registered assoc register pid))

          (swap! *processes assoc pid process))

        (trace/trace pid [:start (str proc-func) params options])

        (binding [*self* pid] ; workaround for ASYNC-170. once fixed, binding should move to (start-process...)
          (go
            (when link-to
              (doseq [link-to (apply hash-set (flatten [link-to]))] ; ??? probably optimize by sending link requests concurently
                (let [reply (async/chan)
                      timeout (async/timeout 100)]
                  (!control link-to [:link pid reply])
                  (match (async/alts!! [reply timeout])
                     [nil reply]
                     (swap! linked conj link-to)

                     [nil timeout]
                     (exit pid :noproc)))))

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


;******* tests
(deftest spawn-terminate-normal []
  (let [result (trace/trace-collector [:p1])]
    (spawn
      (fn [inbox p1 p2 & other]
        (is (instance? Pid *self*) "self must be instance of Pid")
        (is (satisfies? ap/ReadPort inbox) "inbox must be a ReadPort")
        (is (and (= p1 :p1) (= p2 :p2) "formal parameters must match actuals"))
        (is (= (count other) 0) "no extra parameters")
        :normal) [:p1 :p2] {:name :p1})

    (let [trace (result 1000)]
      (is
        (match trace
          [[_ [:start _ _ _]]
           [_ [:return :normal]]
           [_ [:terminate :normal]]] true)))))

(deftest spawn-terminate-nil []
  (let [result (trace/trace-collector [:p1])]
    (spawn
      (fn [_inbox]) [] {:name :p1})

    (let [trace (result 1000)]
      (is
        (match trace
          [[_ [:start _ _ _]]
           [_ [:return :nil]]
           [_ [:terminate :nil]]] true)))))


(deftest link-to-normal []
  (let [result (trace/trace-collector [:p1 :p2])]
    (let [p1 (spawn (fn [_inbox] (go (async/<! (async/timeout 500)) :blah) ) [] {:name :p1})
          p2 (spawn (fn [inbox]  (go (<! inbox))) [] {:name :p2 :link-to p1})]
      (let [trace (result 1000)]
        (is (trace/terminated? trace p1 :blah))
        (is (trace/terminated? trace p2 :blah))))))


(deftest link-to-terminated []
  (let [result (trace/trace-collector [:p1 :p2])]
    (let [p1 (spawn (fn [_inbox]) [] {:name :p1})
          _  (async/<!! (async/timeout 500))
          p2 (spawn (fn [inbox]  (go (<! inbox))) [] {:name :p2 :link-to p1})]
      (let [trace (result 1000)]
        (is (trace/terminated? trace p1 :nil))
        (is (trace/terminated? trace p2 :noproc))))))



(def ^:dynamic *x* 1)

(defn bind-test []
  (println "initial:" *x*)

  (binding [*x* 2]
    (println "before go:" *x*)

    (go
      (println "outer go:" *x*)
      (go
        (println "inner go:" *x*)))))



; TODO
; 1. link/monitor should send notification if process died
; 2. Process Dictionary


; go-proc wraps go-loop into try-catch
