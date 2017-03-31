(ns otplike.supervisor
  (:require [clojure.future :refer :all]
            [clojure.spec :as spec]
            [clojure.core.match :refer [match]]
            [clojure.core.async :as async]
            [clojure.pprint :as pprint]
            [otplike.spec-util :as spec-util]
            [otplike.trace :as trace]
            [otplike.process :as process]
            [otplike.gen-server :as gen-server]))

;; ====================================================================
;; Specs

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

(spec/def ::children-spec (spec/coll-of ::child-spec))

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

(spec/def ::child (spec/keys :req [::id
                                   ::start
                                   ::restart
                                   ::shutdown
                                   ::type]
                             :opt [::process/pid]))

(spec/def ::started-child (spec/merge ::child (spec/keys :req [::process/pid])))

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
  (trace/send-trace [(process/self) nil] [::log-error message]))

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
    (process/exit [reason [:problems (map spec-problem problems)]])))
(spec-util/instrument `check-spec)

(spec/fdef spec->child
           :args (spec/cat :spec ::child-spec)
           :ret ::child)
(defn- spec->child [{:keys [id start restart shutdown type]}]
  {::id id
   ::start start
   ::restart (or restart :permanent)
   ::shutdown (or shutdown (if (= :supervisor type) :infinity 5000))
   ::type (or type :worker)})
(spec-util/instrument `spec->child)

(process/proc-defn await-termination-proc [pid reason timeout done]
  (process/receive!
    [:EXIT pid reason] (async/put! done [:ok reason])
    [:EXIT pid other-reason] (async/put! done [:error other-reason])
    (after timeout
      (process/exit pid :kill)
      (process/receive!
        [:EXIT pid other-reason] (async/put! done [:error other-reason])))))

(spec/fdef shutdown
           :args (spec/cat :pid process/pid?
                           :reason any?
                           :timeout ::timeout)
           :ret (spec/or :success (spec/tuple ::ok ::reason)
                         :failure (spec/tuple ::error ::reason)))
(defn- shutdown [pid reason timeout]
  ;(printf "sup shutting down child %s%n" pid)
  (let [done (async/chan)]
    (process/spawn await-termination-proc
                   [pid reason timeout done]
                   {:link-to pid
                    :flags {:trap-exit true}})
    (process/unlink pid)
    (process/exit pid reason)
    (async/<!! done)))
(spec-util/instrument `shutdown)

(spec/fdef do-terminate-child
           :args (spec/cat :child ::started-child
                           :reason ::reason
                           :timeout ::timeout)
           :ret ::child)
(defn- do-terminate-child
  [{pid ::process/pid restart ::restart :as child} reason timeout]
  ;(printf "sup terminating child, id=%s, reason=%s, timeout=%s%n" (::id child) reason timeout)
  (match (shutdown pid reason timeout)
         [:ok reason] :ok
         [:error other-reason]
         (when (or (not= other-reason :normal) (= restart :permanent))
           (report-error [:shutdown-error other-reason child])))
  ;(printf "sup child shut down, id=%s%n" (::id child))
  (dissoc child ::process/pid))
(spec-util/instrument `do-terminate-child)

(spec/fdef terminate-child
           :args (spec/cat :child ::child)
           :ret ::child)
(defn- terminate-child [{how ::shutdown pid ::process/pid :as child}]
  ;(printf "sup terminating child, id=%s%n" (::id child))
  (if pid
    (match how
      :brutal-kill (do-terminate-child child :kill :infinity)
      timeout (do-terminate-child child :shutdown timeout))
    child))
(spec-util/instrument `terminate-child)

(spec/fdef terminate-children
           :args (spec/cat :children ::children)
           :ret ::children)
(defn- terminate-children [children]
  ;(printf "sup terminate all children%n")
  (->> children
       (reduce #(cons (terminate-child %2) %1) '())
       (filter #(not= :temporary (::restart %)))))
(spec-util/instrument `terminate-children)

(spec/fdef start-child
           :args (spec/cat :child ::child)
           :ret (spec/or :success (spec/tuple ::ok ::started-child)
                         :failure (spec/tuple ::error ::reason)))
(defn- start-child [{[f args] ::start :as child}]
  ;(printf "sup starting child, id=%s%n" (::id child))
  (match (process/ex-catch [:ok (apply f args)])
    [:ok [:ok (pid :guard process/pid?)]]
    [:ok (assoc child ::process/pid pid)]

    [:ok [:error reason]]
    [:error reason]

    [:ok other]
    [:error [:bad-return other]]

    [:EXIT reason]
    [:error reason]))
(spec-util/instrument `start-child)

(spec/fdef start-children
  :args (spec/cat :children ::children)
  :ret (spec/or :success (spec/tuple ::ok ::started-children)
                :failure (spec/tuple
                           ::error
                           (spec/tuple #{:failed-to-start-child} ::id ::reason)
                           ::child
                           ::children)))
(defn- start-children [children]
  (loop [to-start children
         started []]
    (if-let [child (first to-start)]
      (match (start-child child)
        [:ok started-child]
        (do
          ;(printf "sup child started, id=%s%n" (::id started-child))
          (recur (rest to-start) (cons started-child started)))
        [:error reason]
        (let [stopped-child (dissoc child ::process/pid)
              new-children (concat (reverse to-start)
                                   (cons stopped-child started))]
          (report-error [:start-error reason child])
          ;(printf "sup start-child error, reason: %s%n" (pprint/write reason :length 3 :level 3 :stream nil))
          [:error
           [:failed-to-start-child (::id child) reason] child new-children]))
      [:ok started])))
(spec-util/instrument `start-children)

(spec/fdef child-by-pid
           :args (spec/cat :children ::children :pid ::process/pid)
           :ret (spec/nilable ::child))
(defn- child-by-pid [children pid]
  (some #(if (= pid (::process/pid %)) %) children))
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
  (filter #(= pid (::process/pid %)) children))
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
  ;(printf "adding restart, intensity=%s, restarts=%s%n" intensity (pprint/write restarts :stream nil))
  (let [time-ms (monotonic-time-ms)
        restarts (take-while #(in-period? % time-ms period)
                             (cons time-ms restarts))
        state (assoc state ::restarts restarts)]
    ;(printf "new restarts=%s%n" (pprint/write restarts :stream nil))
    (if (> (count restarts) intensity)
      (do
        ;(println "too many restarts, shutting sup down")
        [:shutdown state])
      (do
        ;(println "continuing to restart child")
        [:continue state]))))
(spec-util/instrument `add-restart)

(spec/fdef restart-child:one-for-one
           :args (spec/cat :child ::child :state ::state)
           :ret ::state)
(defn- restart-child:one-for-one [{id ::id :as child} state]
  ;(println "strategy is 'one-for-one', restarting only this child")
  (match (start-child child)
    [:ok new-child]
    (update state ::children replace-child new-child)

    [:error reason]
    (do
      (gen-server/cast (process/self) [:restart id])
      state)))
(spec-util/instrument `restart-child:one-for-one)

(spec/fdef restart-child:one-for-all
           :args (spec/cat :child ::child :state ::state)
           :ret ::state)
(defn- restart-child:one-for-all
  [{id ::id :as child} {children ::children :as state}]
  ;(println "strategy is 'one-for-all', restarting all children")
  (let [terminated-children (terminate-children children)]
    (match (start-children terminated-children)
      [:ok started-children]
      (assoc state ::children started-children)

      [:error _reason {::id failed-to-start-id} new-children]
      (do
        (gen-server/cast (process/self) [:restart failed-to-start-id])
        (assoc state ::children new-children)))))
(spec-util/instrument `restart-child:one-for-all)

(spec/fdef restart-child:rest-for-one
           :args (spec/cat :child ::child :state ::state)
           :ret ::state)
(defn- restart-child:rest-for-one
  [{id ::id :as child} {children ::children :as state}]
  ;(println "strategy is 'rest-for-one', restarting rest children")
  (let [[after before] (split-children-after id children)
        terminated-children (terminate-children after)]
    (match (start-children terminated-children)
      [:ok started-children]
      (assoc state ::children (concat started-children before))

      [:error reason {::id failed-to-start-id} new-children]
      (do
        (report-error [:start-error reason child])
        (gen-server/cast (process/self) [:restart failed-to-start-id])
        (assoc state ::children (concat new-children before))))))
(spec-util/instrument `restart-child:rest-for-one)

(spec/fdef restart-child
           :args (spec/cat :child ::child :state ::state)
           :ret (spec/or :success (spec/tuple ::ok ::state)
                         :shutdown (spec/tuple #{:shutdown} ::state)))
(defn- restart-child [{id ::id :as child} {strategy ::strategy :as state}]
  ;(printf "strategy %s%n" strategy)
  (let [child (dissoc child ::process/pid)
        state (update state ::children replace-child child)]
    (match (add-restart state)
      [:continue new-state]
      (match strategy
             :one-for-one [:ok (restart-child:one-for-one child new-state)]
             :one-for-all [:ok (restart-child:one-for-all child new-state)]
             :rest-for-one [:ok (restart-child:rest-for-one child new-state)])
      [:shutdown new-state]
      (do
        (report-error [:shutdown :reached-max-restart-intensity
                (select-keys state [::intensity ::period ::strategy]) child])
        [:shutdown
         (update new-state ::children delete-child-by-id (::id child))]))))
(spec-util/instrument `restart-child)

(spec/fdef handle-child-exit
           :args (spec/cat :child ::child
                           :reason ::reason
                           :state ::state)
           :ret (spec/or :success (spec/tuple ::ok ::state)
                         :shutdown (spec/tuple #{:shutdown} ::state)))
(defn- handle-child-exit [{restart ::restart id ::id :as child} reason state]
  (match [restart reason]
    [:permanent _]
    (do
      ;(println "permanent child, restarting")
      (report-error [:child-terminated reason child])
      (restart-child child state))

    [_ (:or :normal :shutdown)]
    (do
      ;(println "not persistent child exited normally, deleting")
      [:ok (update state ::children delete-child-by-id (::id child))])

    [:transient _]
    (do
      ;(println "transient child exited abnormally, restarting")
      (report-error [:child-terminated reason child])
      (restart-child child state))

    [:temporary _]
    (do
      ;(println "temporary child, deleting")
      (report-error [:child-terminated reason child])
      [:ok (update state ::children delete-child-by-id (::id child))])))
(spec-util/instrument `handle-child-exit)

(spec/fdef handle-exit
           :args (spec/cat :pid ::process/pid
                           :reason ::reason
                           :state ::state)
           :ret (spec/or :success (spec/tuple ::ok ::state)
                         :shutdown (spec/tuple #{:shutdown} ::state)))
(defn- handle-exit [pid reason {children ::children :as state}]
  (match (child-by-pid children pid)
    nil (do ;(println "child not found" )
            [:ok state])
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

(spec/fdef check-children-spec
           :args (spec/cat :children-spec any?)
           :ret any?)
(defn- check-children-spec [spec]
  (check-spec ::children-spec spec :bad-children-spec)
  (if-let [[id _] (->> spec
                       (map :id)
                       (frequencies)
                       (some #(if (> (val %) 1) %)))]
    (process/exit [:bad-children-spec [:duplicate-child-id id]])))
(spec-util/instrument `check-children-spec)

;; ====================================================================
;; gen-server callbacks

(spec/fdef init
  :args (spec/cat :fargs (spec/tuple fn? (spec/nilable (spec/coll-of any?))))
  :ret (spec/or :success (spec/tuple ::ok ::state)
                :failure (spec/tuple #{:stop} ::reason)))
(defn init [[sup-fn args]]
  ;(printf "sup init: %s%n" args)
  (match (apply sup-fn args)
    [:ok [sup-spec children-spec]]
    (do
      ;(printf "sup init sup-spec: %s%n" (pprint/write sup-spec :level 3 :stream nil))
      ;(printf "sup init children-spec: %s%n" (pprint/write children-spec :level 3 :stream nil))
      (check-spec ::sup-spec sup-spec :bad-supervisor-flags)
      (check-children-spec children-spec)
      (let [sup-flags (sup-flags sup-spec)
            children (map spec->child children-spec)]
        ;(printf "sup init sup-flags: %s%n" (pprint/write sup-flags :level 3 :stream nil))
        ;(printf "sup init children: %s%n" (pprint/write children :level 3 :stream nil))
        (match (start-children children)
          [:ok started-children]
          [:ok (merge sup-flags
                      {::children started-children
                       ::restarts []})]
          [:error reason _child new-children]
          (do
            (terminate-children new-children)
            [:stop [:shutdown reason]]))))
    value
    [:stop [:bad-return value]]))
(spec-util/instrument `init)

(spec/fdef handle-call
  :args (spec/cat :request any?
                  :state ::state)
  :ret (spec/or :reply (spec/tuple #{:reply} any? ::state)
                :noreply (spec/tuple #{:noreply} ::state)
                :stop-reply (spec/tuple #{:stop} ::reason any? ::state)
                :stop (spec/tuple #{:stop} ::reason ::state)))
(defn handle-call [request from state]
  ;(printf "sup call: %s%n" request)
  (process/exit :not-implemented))
(spec-util/instrument `handle-call)

(spec/fdef handle-cast
  :args (spec/cat :request (spec/tuple #{:restart} ::id)
                  :state ::state)
  :ret (spec/or :noreply (spec/tuple #{:noreply} ::state)
                :stop (spec/tuple #{:stop} ::reason ::state)))
(defn handle-cast [request {children ::children :as state}]
  ;(printf "sup cast: %s%n" request)
  (match request
    [:restart id]
    (match (child-by-id children)
      ({::process/pid pid} :as child)
      (match (restart-child child state)
        [:ok new-state] [:ok new-state]
        [:shutdown new-state] [:stop :shutdown new-state])

      _ [:noreply state])))
(spec-util/instrument `handle-cast)

(spec/fdef handle-info
  :args (spec/cat :request any?
                  :state ::state)
  :ret (spec/or :noreply (spec/tuple #{:noreply} ::state)
                :stop (spec/tuple #{:stop} ::reason ::state)))
(defn handle-info [request state]
  ;(printf "sup info: %s%n" request)
  (match request
    [:EXIT pid reason]
    (match (handle-exit pid reason state)
      [:ok new-state] [:noreply new-state]
      [:shutdown new-state] [:stop :sutdown new-state])
    message
    (do ;(printf "sup %s -unexpected message: %s%n" (process/self) message)
        [:noreply state])))
(spec-util/instrument `handle-info)

(spec/fdef terminate
  :args (spec/cat :reason ::reason :state ::state)
  :ret any?)
(defn terminate [reason {children ::children}]
  ;(printf "sup %s terminate, reason=%s, children: %s%n" (process/self) reason (pprint/write children :level 3 :stream nil))
  (terminate-children children))
(spec-util/instrument `terminate)

;; ====================================================================
;; API

(spec/fdef start-link
  :args (spec/cat :sup-fn fn?
                  :args (spec/nilable (spec/coll-of any?)))
  :ret (spec/or :success (spec/tuple ::ok ::process/pid)
                :failure (spec/tuple ::error ::reason)))
(defn start-link
  "Supervisor always links to calling process.
  Thus it can not be started from nonprocess context."
  [sup-fn args]
  ;(printf "parent %s%n" (process/self))
  (gen-server/start-ns [sup-fn args] {:link-to (process/self)
                                      :flags {:trap-exit true}}))
(spec-util/instrument `start-link)
