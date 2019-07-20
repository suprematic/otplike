(ns otplike.supervisor
  "Provides a supervisor, a process that supervises other processes
  called child processes.

  A child process can either be another supervisor or a worker
  process. Worker processes are normally implemented using `gen_server`
  behavior. Supervisors are used to build a hierarchical process
  structure called a supervision tree, a nice way to structure a
  fault-tolerant application. For more information, see
  [Supervisor Behaviour][1] in OTP Design Principles.

  A supervisor expects the definition of which child processes to
  supervise to be specified in the return value of a supervisor's
  function.

  Unless otherwise stated, all functions of this namespaces fail if the
  specified supervisor does not exist or if bad arguments are specified.

  ### Supervision Principles

  The supervisor is responsible for starting, stopping, and monitoring
  its child processes. The basic idea of a supervisor is that it must
  keep its child processes alive by restarting them when necessary.

  The children of a supervisor are defined as a list of child
  specifications. When the supervisor is started, the child processes
  are started in order from left to right according to this list. When
  the supervisor terminates, it first terminates its child processes in
  reversed start order, from right to left.

  The supervisor properties are defined by the supervisor flags. The
  spec definition for the supervisor flags is as follows:

  ```
  (spec/def ::intensity nat-int?)
  (spec/def ::period pos-int?)
  (spec/def ::strategy
    #{:one-for-all
      :one-for-one
      :rest-for-one})

  (spec/def ::sup-flags
  (spec/keys
    :opt-un [::strategy
             ::intensity
             ::period]))
  ```

  A supervisor can have one of the following restart strategies
  specified with the strategy key in the above map:

  `:one-for-one` - If one child process terminates and is to be
  restarted, only that child process is affected. This is the default
  restart strategy.

  `:one-for-all` - If one child process terminates and is to be
  restarted, all other child processes are terminated and then all child
  processes are restarted.

  `:rest-for-one` - If one child process terminates and is to be
  restarted, the 'rest' of the child processes (that is, the child
  processes after the terminated child process in the start order) are
  terminated. Then the terminated child process and all child processes
  after it are restarted.

  To prevent a supervisor from getting into an infinite loop of child
  process terminations and restarts, a maximum restart intensity is
  defined using two integer values specified with keys `:intensity` and
  `:period` in the above map. Assuming the values `max-r` for
  `:intensity` and `max-t` for `:period`, then, if more than `max-r`
  restarts occur within `max-t` seconds, the supervisor terminates all
  child processes and then itself. The termination reason for the
  supervisor itself in that case will be `:shutdown`. intensity defaults
  to `1` and period defaults to `5`.

  The spec definition of a child specification is as follows:

  ```
  (spec/def ::timeout (spec/or :ms nat-int? :inf #{:infinity}))
  (spec/def ::args (spec/coll-of any?))

  (spec/def ::id any?)
  (spec/def ::start (spec/tuple fn? ::args))
  (spec/def ::restart #{:permanent :transient :temporary})
  (spec/def ::shutdown
  (spec/or :brutal-kill #{:brutal-kill}
           :timeout ::timeout))
  (spec/def ::type #{:worker :supervisor})

  (spec/def ::child-spec
  (spec/keys
    :req-un [::id
             ::start]
    :opt-un [::restart
             ::shutdown
             ::type]))
  ```

  `:id` is used to identify the child specification internally by the
  supervisor.

  `:start` defines the function call used to start the child process.
  It must be a function-arguments tuple `[f args]` used as
  `(apply f args)`.

  The start function must create a child process and link to it, and
  must return `[:ok child-pid]` or `[:ok child-pid info]`, where `info`
  is any value that is ignored by the supervisor. The function is
  allowed to return async value wrapping the actual return.

  If something goes wrong, the function can also return an error tuple
  `[:error error]`.

  Notice that the `gen-server/start-link` functions fulfill the above
  requirements.

  `:restart` defines when a terminated child process must be
  restarted. A `:permanent` child process is always restarted. A
  `:temporary` child process is never restarted (even when the
  supervisor's restart strategy is `:rest-for-one` or `one-for-all` and
  a sibling's death causes the temporary process to be terminated). A
  `:transient` child process is restarted only if it terminates
  abnormally, that is, with another exit reason than `:normal`,
  `:shutdown`, or `[:shutdown reason]`. The `:restart` key is optional
  and defaults to `:permanent`.

  `:shutdown` defines how a child process must be
  terminated. `:brutal-kill` means that the child process is
  unconditionally terminated using `(process/exit child-pid :kill)`. An
  integer time-out value means that the supervisor tells the child
  process to terminate by calling `(process/exit child-pid :shutdown)`
  and then wait for an exit signal with reason `:shutdown` back from the
  child process. If no exit signal is received within the specified
  number of milliseconds, the child process is unconditionally
  terminated using `(process/exit child-id :kill)`.

  If the child process is another supervisor, the shutdown time is to be
  set to `:infinity` to give the subtree ample time to shut down. It is
  also allowed to set it to `:infinity`, if the child process is a
  worker.

  > **Warning!**
  > Be careful when setting the shutdown time to
  > `:infinity` when the child process is a worker. Because, in this
  > situation, the termination of the supervision tree depends on the
  > child process, it must be implemented in a safe way and its cleanup
  > procedure must always return.

  Notice that all child processes implemented using the standard
  behaviors (`gen-server`) automatically adhere to the shutdown
  protocol.

  The `:shutdown` key is optional. If it is not specified, it defaults
  to `5000` if the child is of type `:worker` and it defaults to
  `:infinity` if the child is of type `:supervisor`.

  `:type` specifies if the child process is a supervisor or a worker.
  The `:type` key is optional and defaults to `:worker`.

  [1]: https://erldocs.com/current/doc/design_principles/sup_princ.html"
  (:require [clojure.spec.alpha :as spec]
            [clojure.core.match :refer [match]]
            [clojure.pprint :as pprint]
            [otplike.spec-util :as spec-util]
            [otplike.process :as process :refer [!]]
            [otplike.gen-server :as gen-server]
            [otplike.util :as util]))

(when (and (= 1 (:major *clojure-version*))
           (< (:minor *clojure-version*) 9))
  (require '[clojure.future :refer :all]))

(declare check-child-specs)

;; ====================================================================
;; Specs

(spec/def ::sup-ref (spec/or :pid ::process/pid :reg-name any?))
(spec/def ::timeout (spec/or :ms nat-int? :inf #{:infinity}))

(spec/def ::id any?)
(spec/def ::start (spec/tuple fn? (spec/coll-of any?)))
(spec/def ::restart #{:permanent :transient :temporary})
(spec/def ::shutdown (spec/or :brutal-kill #{:brutal-kill}
                              :timeout ::timeout))
(spec/def ::type #{:worker :supervisor})

(spec/def ::child-spec (spec/keys :req-un [::id
                                           ::start]
                                  :opt-un [::restart
                                           ::shutdown
                                           ::type]))

(spec/def ::child-specs (spec/coll-of ::child-spec))

(spec/def ::intensity nat-int?)
(spec/def ::period pos-int?)
(spec/def ::strategy #{:one-for-all
                       :one-for-one
                       :rest-for-one})

(spec/def ::sup-flags (spec/keys :req [::strategy
                                       ::intensity
                                       ::period]))

(spec/def ::sup-spec (spec/keys :opt-un [::strategy
                                         ::intensity
                                         ::period]))

(spec/def ::complete-sup-flags (spec/keys :req-un [::strategy
                                                   ::intensity
                                                   ::period]))

(spec/def ::pid (spec/or :pid ::process/pid
                         :restarting #{:restarting}
                         :stopped nil?))

(spec/def ::child (spec/keys :req [::id
                                   ::start
                                   ::restart
                                   ::shutdown
                                   ::type
                                   ::pid]))

(spec/def ::started-child (spec/and #(spec/valid? ::child %)
                                    #(-> % ::pid process/pid?)))

(spec/def ::stopped-child (spec/and #(spec/valid? ::child %)
                                    #(-> % ::pid process/pid? not)))

(spec/def ::started-children (spec/coll-of ::started-child :distinct true))

(spec/def ::children (spec/and (spec/coll-of ::child)))

(spec/def ::ok #{:ok})
(spec/def ::error #{:error})

(spec/def ::reason any?)

(spec/def :spec/path vector?)
(spec/def :spec/pred any?)
(spec/def :spec/val any?)
(spec/def :spec/in any?)
(spec/def :spec/message string?)
(spec/def ::spec-problem (spec/keys :req-un [:spec/path :spec/pred :spec/val]
                                    :opt-un [:spec/in]))
(spec/def ::spec/problems (spec/coll-of ::spec-problem))
(spec/def ::spec-problems (spec/keys :req [::spec/problems]))

(spec/def ::restarts (spec/coll-of int?))

(spec/def ::state (spec/keys ::req [::intensity
                                    ::period
                                    ::strategy
                                    ::restarts
                                    ::children]))

;; ====================================================================
;; Internal

(defn- report-error [message]
  ; TODO
  )

(spec/fdef spec-problem
  :args (spec/cat :problem ::spec-problem)
  :ret (spec/merge ::spec-problem
                   (spec/keys :req-un [:spec/message])))
(defn- spec-problem [{:keys [pred val] :as problem}]
  (assoc problem :message
         (str "value "
              (pprint/write val :stream nil :length 3 :level 1 :pretty false)
              " does not satisfy predicate " (pr-str pred))))
(spec-util/instrument `spec-problem)

(spec/fdef check-spec
  :args (spec/cat :spec some? :x any? :reason any?)
  :ret any?)
(defn- check-spec [spec x reason]
  (if-let [{problems ::spec/problems} (spec/explain-data spec x)]
    [:error [reason [:problems (map spec-problem problems)]]]
    :ok))
(spec-util/instrument `check-spec)

(spec/fdef spec->child
  :args (spec/cat :spec ::child-spec)
  :ret ::stopped-child)
(defn- spec->child [{:keys [id start restart shutdown type]}]
  {::id id
   ::start start
   ::restart (or restart :permanent)
   ::shutdown (or shutdown (if (= :supervisor type) :infinity 5000))
   ::type (or type :worker)
   ::pid nil})
(spec-util/instrument `spec->child)

(spec/fdef shutdown
  :args (spec/cat :pid process/pid?
                  :reason any?
                  :timeout ::timeout)
  :ret ::process/async)
(defn- shutdown [pid reason timeout]
  (process/async
    (try
      (process/exit pid reason)
      (if (= :kill reason)
        (process/selective-receive!
          [:EXIT pid :killed] [:ok reason]
          [:EXIT pid other-reason] [:error other-reason])
        (process/selective-receive!
          [:EXIT pid reason] [:ok reason]
          [:EXIT pid other-reason] [:error other-reason]
          (after timeout
            (process/exit pid :kill)
            (process/selective-receive!
              [:EXIT pid other-reason] [:error other-reason]))))
      (catch Throwable t
        (println t)
        [:panic (process/ex->reason t)]))))
(spec-util/instrument `shutdown)

(spec/fdef do-terminate-child
  :args (spec/cat :child ::started-child
                  :reason ::reason
                  :timeout ::timeout)
  :ret ::process/async)
(defn- do-terminate-child
  [{pid ::pid restart ::restart :as child} reason timeout]
  ;;(printf "sup terminating child, id=%s, reason=%s, timeout=%s%n" (::id child) reason timeout)
  (process/with-async
    [res (shutdown pid reason timeout)]
    (match res
      [:ok reason] :ok
      [:error other-reason]
      (when (or (not= other-reason :normal) (= restart :permanent))
        (report-error [:shutdown-error other-reason child])))
    ;;(printf "sup child shut down, id=%s%n" (::id child))
    (assoc child ::pid nil)))
(spec-util/instrument `do-terminate-child)

(spec/fdef terminate-child*
  :args (spec/cat :child ::child)
  :ret ::process/async)
(defn- terminate-child* [{how ::shutdown pid ::pid :as child}]
  (if (process/pid? pid)
    (match how
      :brutal-kill (do-terminate-child child :kill :infinity)
      timeout (do-terminate-child child :shutdown timeout))
    (process/async-value (assoc child ::pid nil))))
(spec-util/instrument `terminate-child*)

(spec/fdef terminate-children
  :args (spec/cat :children ::children)
  :ret ::process/async)
(defn- terminate-children [children]
  ;;(printf "sup terminate all children%n")
  (process/async
    (loop [children children
           new-children '()]
      (if (empty? children)
        new-children
        (let [child (first children)
              children (rest children)
              new-child (process/await! (terminate-child* child))]
          (if (= :temporary (::restart child))
            (recur children new-children)
            (recur children (cons new-child new-children))))))))
(spec-util/instrument `terminate-children)

(spec/fdef dispatch-child-start
  :args (spec/cat :child ::stopped-child)
  :ret (spec/or
        :success
        (spec/or :child (spec/tuple ::ok ::started-child)
                 :child+info (spec/tuple ::ok ::started-child any?))
        :failure
        (spec/tuple ::error ::reason)))
(defn- dispatch-child-start [child res]
  (match res
    [:ok (pid :guard process/pid?)]
    [:ok (assoc child ::pid pid)]

    [:ok (pid :guard process/pid?) info]
    [:ok (assoc child ::pid pid) info]

    [:error reason]
    [:error reason]

    [:ok other]
    [:error [:bad-return other]]))

(spec/fdef start-child*
  :args (spec/cat :child ::stopped-child)
  :ret ::process/async)
(defn- start-child* [{[f args] ::start :as child}]
  ;;(printf "sup starting child, id=%s%n" (::id child))
  (match (process/ex-catch [:ok (apply f args)])
    [:ok (async :guard process/async?)]
    (process/map-async #(dispatch-child-start child %) async)

    [:ok res]
    (process/async-value (dispatch-child-start child res))

    [:EXIT reason]
    (process/async-value [:error reason])))
(spec-util/instrument `start-child*)

(spec/fdef start-children
  :args (spec/cat :children ::children)
  :ret ::process/async)
(defn- start-children [children]
  (process/async
    (loop [to-start children
           started []]
      (if-let [child (first to-start)]
        (match (process/await! (start-child* child))
          [:ok started-child]
          (do
            ;;(printf "sup child started, id=%s%n" (::id started-child))
            (recur (rest to-start) (cons started-child started)))
          [:error reason]
          (let [stopped-child (assoc child ::pid nil)
                new-children (concat (reverse (rest to-start))
                                     (cons stopped-child started))]
            (report-error [:start-error reason child])
            ;;(printf "sup start-child error, reason: %s%n" (pprint/write reason :length 3 :level 3 :stream nil))
            [:error
             [:failed-to-start-child (::id child) reason] child new-children]))
        [:ok started]))))
(spec-util/instrument `start-children)

(spec/fdef child-by-pid
  :args (spec/cat :children ::children :pid ::process/pid)
  :ret (spec/nilable ::child))
(defn- child-by-pid [children pid]
  (some #(if (= pid (::pid %)) %) children))
(spec-util/instrument `child-by-pid)

(spec/fdef child-by-id
  :args (spec/cat :children ::children :id ::id)
  :ret (spec/nilable ::child))
(defn- child-by-id [children id]
  (some #(if (= id (::id %)) %) children))
(spec-util/instrument `child-by-id)

(spec/fdef delete-child-by-id
  :args (spec/cat :children ::children :id ::id)
  :ret ::children)
(defn- delete-child-by-id [children id]
  (filter #(not= id (::id %)) children))
(spec-util/instrument `delete-child-by-id)

(spec/fdef delete-child-by-pid
  :args (spec/cat :children ::children :pid ::process/pid)
  :ret ::children)
(defn- delete-child-by-pid [children pid]
  (filter #(= pid (::pid %)) children))
(spec-util/instrument `delete-child-by-pid)

(spec/fdef replace-child
  :args (spec/cat :children ::children :child ::child)
  :ret ::children)
(defn- replace-child [children {id ::id :as child}]
  (match (split-with #(not= id (::id %)) children)
    [before ([_ & after] :seq)] (concat before [child] after)))
(spec-util/instrument `replace-child)

(spec/fdef split-children-after
  :args (spec/cat :id ::id :children ::children)
  :ret (spec/cat :after ::children
                 :before ::children))
(defn- split-children-after [id children]
  (match (split-with #(not= id (::id %)) children)
    [before ([child & after] :seq)] [(concat before [child]) after]))
(spec-util/instrument `split-children-after)

(defn- monotonic-time-ms []
  (quot (System/nanoTime) 1000000))

(spec/fdef in-period?
  :args (spec/cat :restart-time int? :now int? :period ::period)
  :ret boolean?)
(defn- in-period? [restart-time now period]
  (>= period (- now restart-time)))
(spec-util/instrument `in-period?)

(spec/fdef add-restart
  :args (spec/cat :state ::state)
  :ret (spec/or :continue (spec/tuple #{:continue} ::state)
                :shutdown (spec/tuple #{:shutdown} ::state)))
(defn- add-restart
  [{intensity ::intensity period ::period restarts ::restarts :as state}]
  ;;(printf "adding restart, intensity=%s, restarts=%s%n" intensity (pprint/write restarts :stream nil))
  (let [time-ms (monotonic-time-ms)
        restarts (take-while #(in-period? % time-ms period)
                             (cons time-ms restarts))
        state (assoc state ::restarts restarts)]
    ;;(printf "new restarts=%s%n" (pprint/write restarts :stream nil))
    (if (> (count restarts) intensity)
      (do
        ;;(println "too many restarts, shutting sup down")
        [:shutdown state])
      (do
        ;;(println "restart is allowed")
        [:continue state]))))
(spec-util/instrument `add-restart)

(spec/fdef restart-child--one-for-one
  :args (spec/cat :child ::child :state ::state)
  :ret ::process/async)
(defn- restart-child--one-for-one [{id ::id :as child} state]
  ;;(println "strategy is 'one-for-one', restarting only this child")
  (process/with-async
    [res (start-child* child)]
    (match res
      [:ok new-child]
      (update state ::children replace-child new-child)

      [:error reason]
      (do
        (gen-server/cast (process/self) [:restart id])
        state))))
(spec-util/instrument `restart-child--one-for-one)

(spec/fdef restart-child--one-for-all
  :args (spec/cat :child ::child :state ::state)
  :ret ::process/async)
(defn- restart-child--one-for-all
  [{id ::id :as child} {children ::children :as state}]
  ;;(println "strategy is 'one-for-all', restarting all children")
  (process/async
    (let [terminated-children (process/await! (terminate-children children))]
      (match (process/await! (start-children terminated-children))
        [:ok started-children]
        (assoc state ::children started-children)

        [:error reason ({::id failed-to-start-id} :as child1) new-children]
        (let [new-children
              (replace-child new-children (assoc child1 ::pid :restarting))]
          (report-error [:start-error reason child1])
          (gen-server/cast (process/self) [:restart failed-to-start-id])
          (assoc state ::children new-children))))))
(spec-util/instrument `restart-child--one-for-all)

(spec/fdef restart-child--rest-for-one
  :args (spec/cat :child ::child :state ::state)
  :ret ::process/async)
(defn- restart-child--rest-for-one
  [{id ::id :as child} {children ::children :as state}]
  ;;(println "strategy is 'rest-for-one', restarting rest children")
  (process/async
    (let [[after before] (split-children-after id children)
          terminated-children (process/await! (terminate-children after))]
      (match (process/await! (start-children terminated-children))
        [:ok started-children]
        (assoc state ::children (concat started-children before))

        [:error reason ({::id failed-to-start-id} :as child1) new-children]
        (let [new-children
              (replace-child new-children (assoc child1 ::pid :restarting))]
          (report-error [:start-error reason child1])
          (gen-server/cast (process/self) [:restart failed-to-start-id])
          (assoc state ::children (concat new-children before)))))))
(spec-util/instrument `restart-child--rest-for-one)

(spec/fdef restart-child*
  :args (spec/cat :child ::child :state ::state)
  :ret ::process/async)
(defn- restart-child* [{id ::id :as child} {strategy ::strategy :as state}]
  ;;(printf "restart child: id=%s, strategy=%s%n" id strategy)
  (let [child (assoc child ::pid :restarting)
        state (update state ::children replace-child child)]
    (match (add-restart state)
      [:continue new-state]
      (match strategy
        :one-for-one
        (process/with-async [res (restart-child--one-for-one child new-state)]
          [:ok res])

        :one-for-all
        (process/with-async [res (restart-child--one-for-all child new-state)]
          [:ok res])

        :rest-for-one
        (process/with-async [res (restart-child--rest-for-one child new-state)]
          [:ok res]))

      [:shutdown new-state]
      (do
        ;;(printf "too many restarts shutting down%n")
        (report-error
         [:shutdown :reached-max-restart-intensity
          (select-keys state [::intensity ::period ::strategy]) child])
        (process/async-value
         [:shutdown
          (update new-state ::children delete-child-by-id (::id child))])))))
(spec-util/instrument `restart-child*)

(spec/fdef handle-child-exit
  :args (spec/cat :child ::child
                  :reason ::reason
                  :state ::state)
  :ret ::process/async)
(defn- handle-child-exit [{restart ::restart id ::id :as child} reason state]
  (match [restart reason]
    [:permanent _]
    (do
      ;;(println "permanent child, restarting")
      (report-error [:child-terminated reason child])
      (restart-child* child state))

    [_ (:or :normal :shutdown)]
    (do
      ;;(println "not persistent child exited normally, deleting")
      (process/async-value
       [:ok (update state ::children delete-child-by-id (::id child))]))

    [:transient _]
    (do
      ;;(println "transient child exited abnormally, restarting")
      (report-error [:child-terminated reason child])
      (restart-child* child state))

    [:temporary _]
    (do
      ;;(println "temporary child, deleting")
      (report-error [:child-terminated reason child])
      (process/async-value
       [:ok (update state ::children delete-child-by-id (::id child))]))))
(spec-util/instrument `handle-child-exit)

(spec/fdef handle-exit
  :args (spec/cat :pid ::process/pid
                  :reason ::reason
                  :state ::state)
  :ret ::process/async)
(defn- handle-exit [pid reason {children ::children :as state}]
  (match (child-by-pid children pid)
    nil (do ;(println "child not found" )
          (process/async-value [:ok state]))
    child (do ;(printf "child found id=%s%n" (::id child))
            (handle-child-exit child reason state))))
(spec-util/instrument `handle-exit)

(spec/fdef sup-flags
  :args (spec/cat :spec ::sup-spec)
  :ret ::sup-flags)
(defn- sup-flags [{:keys [strategy intensity period]}]
  {::strategy (or strategy :one-for-one)
   ::intensity (or intensity 1)
   ::period (or period 5000)})
(spec-util/instrument `sup-flags)

(spec/fdef handle-start-child
  :args (spec/cat :spec any? :state ::state)
  :ret ::process/async)
(defn- handle-start-child [child-spec {children ::children :as state}]
  (match (check-spec ::child-spec child-spec :bad-child-spec)
    :ok
    (match (child-by-id children (:id child-spec))
      nil
      (process/with-async [res (start-child* (spec->child child-spec))]
        (match res
          [:ok child] [[:ok (::pid child)]
                       (update state ::children #(cons child %))]
          [:ok child info] [[:ok (::pid child) info]
                            (update state ::children #(cons child %))]
          [:error reason] [[:error reason] state]))

      {::pid (pid :guard process/pid?)}
      (process/async-value [[:error [:already-started pid]] state])

      _
      (process/async-value [[:error :already-present] state]))

    error
    (process/async-value [error state])))
(spec-util/instrument `handle-start-child)

(spec/fdef handle-restart-child
  :args (spec/cat :id ::id :state ::state)
  :ret ::process/async)
(defn- handle-restart-child [id {children ::children :as state}]
  (match (child-by-id children id)
    nil
    (process/async-value [[:error :not-found] state])

    ({::pid nil} :as child)
    (process/with-async [res (start-child* child)]
      (match res
        [:ok started-child]
        [[:ok (::pid started-child)]
         (update state ::children replace-child started-child)]

        [:ok started-child info]
        [[:ok (::pid started-child) info]
         (update state ::children replace-child started-child)]

        [:error reason]
        [[:error reason] state]))

    {::pid :restarting}
    (process/async-value [[:error :restarting] state])

    {::pid (_ :guard process/pid?)}
    (process/async-value [[:error :running] state])))
(spec-util/instrument `handle-restart-child)

(spec/fdef handle-terminate-child
  :args (spec/cat :id ::id :state ::state)
  :ret ::process/async)
(defn- handle-terminate-child [id {children ::children :as state}]
  (match (child-by-id children id)
    nil
    (process/async-value [[:error :not-found] state])

    child
    (process/with-async [res (terminate-child* child)]
      (match res
        {::restart :temporary}
        [:ok (update state ::children delete-child-by-id id)]

        terminated-child
        [:ok (update state ::children replace-child terminated-child)]))))
(spec-util/instrument `handle-terminate-child)

(spec/fdef handle-delete-child
  :args (spec/cat :id ::id :state ::state)
  :ret (spec/tuple
        (spec/or :success ::ok
                 :failure (spec/tuple
                           ::error
                           #{:restarting :running :not-found}))
        ::state))
(defn- handle-delete-child [id {children ::children :as state}]
  (match (child-by-id children id)
    nil
    [[:error :not-found] state]

    {::pid nil}
    [:ok (update state ::children delete-child-by-id id)]

    {::pid :restarting}
    [[:error :restarting] state]

    {::pid (_ :guard process/pid?)}
    [[:error :running] state]))
(spec-util/instrument `handle-delete-child)

(spec/fdef start-link*
  :args (spec/cat :sup-fn fn?
                  :args (spec/nilable (spec/coll-of any?))
                  :spawn-opts map?)
  :ret ::process/async)
(defn- start-link* [sup-fn args spawn-opts]
  (gen-server/start-link-ns
   [sup-fn args] {:spawn-opt (merge spawn-opts {:flags {:trap-exit true}
                                                :name "supervisor"})}))
(spec-util/instrument `start-link*)

;; ====================================================================
;; gen-server callbacks

(spec/fdef init
  :args (spec/cat :fn fn? :args (spec/nilable (spec/coll-of any?)))
  :ret ::process/async)
(defn- init [sup-fn args]
  ;;(printf "sup init: %s%n" args)
  (process/async
    (match (process/await?! (apply sup-fn args))
      [:ok [sup-spec child-specs]]
      (do
        ;;(printf "sup init sup-spec: %s%n" (pprint/write sup-spec :level 3 :stream nil))
        ;;(printf "sup init child-specs: %s%n" (pprint/write child-specs :level 3 :stream nil))
        (match (check-spec ::sup-spec sup-spec :bad-supervisor-flags)
          :ok :ok
          [:error reason] (process/exit reason))
        (match (check-child-specs child-specs)
          :ok :ok
          [:error reason] (process/exit reason))
        (let [sup-flags (sup-flags sup-spec)
              children (map spec->child child-specs)]
          ;;(printf "sup init sup-flags: %s%n" (pprint/write sup-flags :level 3 :stream nil))
          ;;(printf "sup init children: %s%n" (pprint/write children :level 3 :stream nil))
          (match (process/await! (start-children children))
            [:ok started-children]
            [:ok (merge sup-flags
                        {::children started-children
                         ::restarts []})]
            [:error reason _child new-children]
            (do
              (process/await! (terminate-children new-children))
              [:stop [:shutdown reason]]))))
      value
      [:stop [:bad-return value]])))
(spec-util/instrument `init)

(spec/fdef handle-call
  :args (spec/cat :request any?
                  :from ::gen-server/from
                  :state ::state)
  :ret ::process/async)
(defn- handle-call [request from {children ::children :as state}]
  ;;(printf "sup call: %s%n" request)
  (match request
    [:start-child child-spec]
    (process/with-async [[res new-state] (handle-start-child child-spec state)]
      [:reply res new-state])

    [:restart-child id]
    (process/with-async [[res new-state] (handle-restart-child id state)]
      [:reply res new-state])

    [:terminate-child id]
    (process/with-async [[res new-state] (handle-terminate-child id state)]
      [:reply res new-state])

    [:delete-child id]
    (let [[res new-state] (handle-delete-child id state)]
      (process/async-value [:reply res new-state]))))
(spec-util/instrument `handle-call)

(spec/fdef handle-cast
  :args (spec/cat :request (spec/tuple #{:restart} ::id)
                  :state ::state)
  :ret ::process/async)
(defn- handle-cast [request {children ::children :as state}]
  ;;(printf "sup cast: %s, children %s%n" request (pr-str children))
  ;;(printf "child by id: %s%n" (child-by-id children (second request)))
  (match request
    [:restart id]
    (match (child-by-id children id)
      ({::pid (pid :guard #{:restarting})} :as child) ;FIXME: replace guard with value
      (process/with-async [res (restart-child* child state)]
        (match res
          [:ok new-state] [:noreply new-state]
          [:shutdown new-state] [:stop :shutdown new-state]))

      _
      (process/async-value [:noreply state]))))
(spec-util/instrument `handle-cast)

(spec/fdef handle-info
  :args (spec/cat :request any?
                  :state ::state)
  :ret ::process/async)
(defn- handle-info [request state]
  ;;(printf "sup info: %s%n" request)
  (match request
    [:EXIT pid reason]
    (process/with-async [res (handle-exit pid reason state)]
      (match res
        [:ok new-state] [:noreply new-state]
        [:shutdown new-state] [:stop :shutdown new-state]))
    
    message
    (do ;;(printf "sup %s -unexpected message: %s%n" (process/self) message)
      (process/async-value [:noreply state]))))
(spec-util/instrument `handle-info)

(spec/fdef terminate
  :args (spec/cat :reason ::reason :state ::state)
  :ret any?)
(defn- terminate [reason {children ::children}]
  ;;(printf "sup %s terminate, reason=%s, children: %s%n" (process/self) reason (pprint/write children :level 3 :stream nil))
  (terminate-children children))
(spec-util/instrument `terminate)

;; ====================================================================
;; API

(spec/fdef start-link!
  :args (spec/or
         :noname (spec/cat :sup-fn fn?
                           :args (spec/? (spec/nilable (spec/coll-of any?))))
         :register (spec/cat :sup-name any?
                             :sup-fn fn?
                             :args (spec/nilable (spec/coll-of any?))))
  :ret (spec/or :success (spec/tuple ::ok ::process/pid)
                :failure (spec/tuple ::error ::reason)))
(defmacro start-link!
  "Creates a supervisor process as part of a supervision tree.
  For example, the function ensures that the supervisor is linked to
  the calling process (its supervisor).

  The created supervisor process calls `sup-fn` to find out about
  restart strategy, maximum restart intensity, and child processes.
  To ensure a synchronized startup procedure, `start-link!` does not
  return until `sup-fn` has returned and all child processes have been
  started. `sup-fn` is allowed to return async value wrapping the actual
  return.

  If `sup-name` is provided, the supervisor is registered locally as
  `sup-name`.

  `args` is a vector of the arguments to `sup-fn`.

  If the supervisor and its child processes are successfully created
  (that is, if all child process start functions return
  `[ok, child-pid]`), the function returns `[ok, pid]`, where `pid` is
  the pid of the supervisor.

  If there already exists a process with the specified `sup-name`,
  the function retunrs `[:error reason]`.

  If `sup-fn` fails or returns an incorrect value, this function returns
  `[:error ([:bad-return-value 'init ret] :as reason)]` , where `ret`
  is the returned value, and the supervisor terminates with the same
  reason.

  If any child process start function fails or returns an error tuple
  or an erroneous value, the supervisor first terminates all already
  started child processes with reason `:shutdown` and then terminate
  itself and returns `[:error, [:shutdown reason]]`."
  ([sup-fn]
   `(start-link! ~sup-fn []))
  ([sup-fn args]
   `(process/await! (start-link ~sup-fn ~args)))
  ([sup-name sup-fn args]
   `(process/await! (start-link ~sup-name ~sup-fn ~args))))

(spec/fdef start-link
  :args (spec/or
         :noname (spec/cat :sup-fn fn?
                           :args (spec/? (spec/nilable (spec/coll-of any?))))
         :register (spec/cat :sup-name any?
                             :sup-fn fn?
                             :args (spec/nilable (spec/coll-of any?))))
  :ret ::process/async)
(defn start-link
  "The same as `start-link!` but returns async value."
  ([sup-fn]
   (start-link sup-fn []))
  ([sup-fn args]
   (start-link* sup-fn args {}))
  ([sup-name sup-fn args]
   (start-link* sup-fn args {:register sup-name})))

(spec/fdef check-child-specs
  :args (spec/cat :child-specs any?)
  :ret (spec/or :ok ::ok
                :error (spec/tuple ::error ::reason)))
(defn check-child-specs
  "Takes a list of child specification and returns `:ok` if all of
  them are syntactically correct, otherwise `[error, reason]`."
  [spec]
  (match (check-spec ::child-specs spec :bad-child-specs)
    :ok (if-let [[id _] (->> spec
                          (map :id)
                          (frequencies)
                          (some #(if (> (val %) 1) %)))]
          [:error [:bad-child-specs [:duplicate-child-id id]]]
          :ok)
    error error))
(spec-util/instrument `check-child-specs)

(spec/fdef start-child!
  :args (spec/cat :sup ::sup-ref :child-spec any?)
  :ret (spec/or :ok (spec/or :pid (spec/tuple ::ok ::process/pid)
                             :pid+info (spec/tuple ::ok ::process/pid any?))
                :error (spec/tuple ::error ::reason)))
(defmacro start-child!
  "Dynamically adds a child specification to supervisor `sup`, which
  starts the corresponding child process.

  `sup` can be a pid or a registered name.

  `child-spec` must be a valid child specification. The child process is
  started by using the start function as defined in the child
  specification.

  If there already exists a child specification with the specified
  identifier, `child-spec` is discarded, and the function returns
  `[:error :already-present]` or `[:error [:already-started child]]`,
  depending on if the corresponding child process is running or not.

  If the child process start function returns `[:ok child-pid]`,
  the child specification and pid are added to the supervisor and
  the function returns the same value.

  If the child process start function returns an error tuple or an
  erroneous value, or if it fails, the child specification is discarded,
  and the function returns `[:error error]`, where `error` is a form
  containing information about the error and child specification."
  [sup child-spec]
  `(gen-server/call! ~sup [:start-child ~child-spec]))

(spec/fdef start-child
  :args (spec/cat :sup ::sup-ref :id ::child-spec)
  :ret ::process/async)
(defn start-child
  "The same as `start-child!` but returns async value."
  [sup child-spec]
  (gen-server/call sup [:start-child child-spec]))

(spec/fdef restart-child!
  :args (spec/cat :sup ::sup-ref :id ::id)
  :ret (spec/or :ok (spec/or :pid (spec/tuple ::ok ::process/pid)
                             :pid+info (spec/tuple ::ok ::process/pid any?))
                :error (spec/tuple ::error ::reason)))
(defmacro restart-child!
  "Tells supervisor to restart a child process corresponding to
  the child specification identified by `id`. The child specification
  must exist, and the corresponding child process must not be running.

  Notice that for temporary children, the child specification is
  automatically deleted when the child terminates; thus, it is not
  possible to restart such children.

  If the child specification identified by `id` does not exist, the
  function returns `[:error :not-found]`. If the child specification
  exists but the corresponding process is already running, the function
  returns `[:error :running]`.

  If the child process start function returns `[:ok pid]`,
  the `pid` is added to the supervisor and the function returns
  the same value.

  If the child process start function returns an error tuple or an
  erroneous value, or if it fails, the function returns
  `[:error error]`, where `error` is a form containing information
  about the error."
  [sup id]
  `(gen-server/call! ~sup [:restart-child ~id]))

(spec/fdef restart-child
  :args (spec/cat :sup ::sup-ref :id ::id)
  :ret ::process/async)
(defn restart-child
  "The same as `restart-child!` but returns async value."
  [sup id]
  (gen-server/call sup [:restart-child id]))

(spec/fdef terminate-child!
  :args (spec/cat :sup ::sup-ref :id ::id)
  :ret (spec/or :ok ::ok
                :error (spec/tuple ::error #{:not-found})))
(defmacro terminate-child!
  "Tells supervisor to terminate the specified child.

  `id` must be the child specification identifier. The process,
  if any, is terminated and, unless it is a temporary child, the child
  specification is kept by the supervisor. The child process can later
  be restarted by the supervisor. The child process can also be
  restarted explicitly by calling `restart-child`.
  Use `delete-child` to remove the child specification.

  If the child is temporary, the child specification is deleted as soon
  as the process terminates. This means that `delete-child` has no
  meaning and `restart-child` cannot be used for these children.

  If successful, the function returns `:ok`. If there is no child
  specification with the specified `id`, the function returns
  `[:error :not-found]`."
  [sup id]
  `(gen-server/call! ~sup [:terminate-child ~id]))

(spec/fdef terminate-child
  :args (spec/cat :sup ::sup-ref :id ::id)
  :ret ::process/async)
(defn terminate-child
  "The same as `terminate-child!` but returns async value."
  [sup id]
  (gen-server/call sup [:terminate-child id]))

(spec/fdef delete-child!
  :args (spec/cat :sup ::sup-ref :id ::id)
  :ret (spec/or :ok ::ok
                :error (spec/tuple ::error
                                   #{:running :restarting :not-found})))
(defmacro delete-child!
  "Tells supervisor to delete the child specification identified by
  `id`. The corresponding child process must not be running. Use
  `terminate-child` to terminate it.

  If successful, the function returns `:ok`. If the child specification
  identified by `id` exists but the corresponding child process is
  running or is about to be restarted, the function returns
  `[:error :running]` or `[:error :restarting]`, respectively.
  If the child specification identified by `id` does not exist,
  the function returns `[:error :not-found]`."
  [sup id]
  `(gen-server/call! ~sup [:delete-child ~id]))

(spec/fdef delete-child
  :args (spec/cat :sup ::sup-ref :id ::id)
  :ret ::process/async)
(defn delete-child
  "The same as `delete-child!` but returns async value."
  [sup id]
  (gen-server/call sup [:delete-child id]))
