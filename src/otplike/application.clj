(ns otplike.application
  (:require
   [clojure.spec.alpha :as spec]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.core.match :refer [match]]
   [otplike.util :as u]
   [otplike.process :as process]
   [otplike.logger :as log]
   [otplike.proc-util :as proc-util]
   [otplike.gen-server :as gs]
   [otplike.spec-util :as spec-util]))

(when
 (and
  (= 1 (:major *clojure-version*))
  (< (:minor *clojure-version*) 9))
  (require '[clojure.future :refer :all]))

(spec/def ::ok #{:ok})
(spec/def ::error #{:error})

(spec/def ::name symbol?)
(spec/def ::resource string?)
(spec/def ::applications (spec/coll-of symbol?))
(spec/def ::namespaces (spec/coll-of symbol?))

(spec/def ::app-descr
  (spec/keys
   :req-un [::name ::resource ::applications ::namespaces]))

(spec/def ::path (spec/coll-of symbol?))

(spec/fdef load-appfile
  :args
  (spec/or
   :v1 (spec/cat ::name symbol?)
   :v2 (spec/cat ::name symbol? ::path ::path))
  :ret
  (spec/or
   :ok (spec/tuple ::ok ::app-descr)
   :error (spec/tuple ::error any?)))
(defn- load-appfile
  ([application]
   (load-appfile application [application]))
  ([application path]
   (let [resource (format "%s.app.edn" application)]
     (if-let [stream (io/resource resource)]
       (try
         (let [{:keys [namespaces applications] :as application-meta} (-> stream slurp read-string)]
           [:ok
            (merge
             (select-keys application-meta [:environment :start-fn :stop-fn])
             {:name application
              :resource resource
              :applications (map symbol applications)
              :namespaces (map symbol namespaces)})])
         (catch Throwable _
           [:error [:badfile resource path]]))
       [:error [:nofile resource path]]))))
(spec-util/instrument 'load-appfile)

#_(load-appfile 'kernel)

(defn- load-appfile-all* [name applications loaded? path]
  (if-not (@loaded? name)
    (match (load-appfile name path)
      [:ok application]
      (do
        (swap! loaded? conj name)
        (let
         [deps
          (mapcat
           (fn [name]
             (load-appfile-all* name applications loaded? (conj path name)))
           (:applications application))]
          (concat deps [application] applications)))
      [:error reason]
      (throw (ex-info "" {:exception reason})))
    applications))

(spec/fdef load-appfile-all
  :args
  (spec/cat :name symbol?)
  :ret
  (spec/or
   :ok (spec/tuple ::ok (spec/coll-of ::app-descr))
   :error (spec/tuple ::error (spec/tuple keyword? any?))))
(defn- load-appfile-all [name]
  (try
    [:ok (load-appfile-all* name '() (atom #{}) [name])]
    (catch clojure.lang.ExceptionInfo ex
      [:error [:exception (get (ex-data ex) :exception)]])))
(spec-util/instrument 'load-appfile-all)

#_(load-applications 'kernel)

(defn- dummy-start [_start-args]
  [:ok
   (process/spawn-link
    (process/proc-fn []
      (process/receive!
       _
       nil)))])

(process/proc-defn- application-master-p [{:keys [name environment namespaces start-fn stop-fn]} controller-pid]
  (process/flag :trap-exit true)

  (when-let
   [[sup-pid state]
    (try
      (doseq [ns namespaces]
        (require ns :reload))

      (let [start-fn (or (some-> start-fn resolve var-get) dummy-start)]
        (match (process/await?! (start-fn environment))
          [:ok (sup-pid :guard process/pid?) state]
          [sup-pid state]

          [:ok (sup-pid :guard process/pid?)]
          [sup-pid nil]

          bad-return
          (process/exit [:bad-return bad-return])))
      (catch clojure.lang.ExceptionInfo ex
        (throw ex))
      (catch Throwable ex
        (process/exit [:exception (u/stack-trace ex)])))]

    (process/! controller-pid [:started (process/self)])

    (log/debug "application supervisor start" :application name :pid sup-pid)

    (let
     [call-stop-and-exit
      #(do
         (when-let [stop-fn (some-> stop-fn resolve var-get)]
           (try
             (apply stop-fn [state])
             (catch Throwable _ex)))
         (process/exit %))]
      (process/selective-receive!
       [:EXIT sup-pid reason]
       (do
         (log/debug "application supervisor exit" :application name :reason reason :pid sup-pid)
         (call-stop-and-exit reason))

       [:EXIT controller-pid reason]
       (do
         (process/exit sup-pid reason)

         (process/receive!
          [:EXIT sup-pid reason]
          (call-stop-and-exit reason)))))))

(defn init [environment]
  (process/flag :trap-exit true)
  [:ok {::started '() :environment environment}])

(defn- start-many [applications permanent?]
  (process/async
   (loop [applications applications new-started '()]
     (if (empty? applications)
       [:ok new-started]
       (let [[{:keys [name] :as application} & remaining] applications]
         (match
          (let [app-pid (process/spawn-link application-master-p [application (process/self)])]
            (process/selective-receive!
             [:started app-pid]
             (do
               (log/debug "application master start" :application name :pid app-pid)
               [:ok {:application application :pid app-pid :permanent? permanent?}])

             [:EXIT app-pid reason]
             [:error reason]))

           [:ok application']
           (recur remaining (conj new-started application'))

           [:error reason]
           [:error new-started reason]))))))

(defn- merge-environment [environment {:keys [name] :as application}]
  (let [to-merge (get environment name)]
    (update application :environment u/deep-merge to-merge)))

(defn handle-call [message _reply-to {:keys [started environment] :as state}]
  (process/async
   (match message
     [::which]
     [:reply
      (apply
       list
       (map
        (fn [{:keys [application]}]
          [(get application :name) (get application :description "")]) started)) state]

     [::start name permanent?]
     (let [started-names (->> started (map (comp :name :application)) (apply hash-set))]
       (if-not (contains? started-names name)
         (match (load-appfile name)
           [:ok application]
           (let
            [application (merge-environment environment application)
             not-started
             (set/difference
              (->> application :applications (apply hash-set))
              started-names)]

             (if-not (empty? not-started)
               [:reply [:error [:not-started not-started]] state]
               (let [app-pid (process/spawn-link application-master-p [application (process/self)])]
                 (process/selective-receive!
                  [:started app-pid]
                  [:reply :ok
                   (update
                    state :started conj {:application application :pid app-pid :permanent? permanent?})]
                  [:EXIT app-pid reason]
                  [:reply [:error name reason] state]))))

           [:error error]
           [:reply [:error error] state])
         [:reply [:error [:already-started name]] state]))

     [::start-all name permanent?]
     (match (load-appfile-all name)
       [:ok applications]
       (let
        [applications
         (map
          (partial merge-environment environment) applications)

         applications
         (let [started? (->> started (map (comp :name :application)) (apply hash-set))]
           (filter
            (fn [{:keys [name]}]
              (not (started? name))) applications))]

         (match (process/await! (start-many applications permanent?))
           [:ok new-started]
           [:reply :ok (assoc state :started (concat new-started started))]

           [:error new-started reason]
           [:reply [:error reason] (assoc state :started (concat new-started started))]))

       [:error errors]
       [:reply [:error errors] state])

     [::stop name]
     (if-let [app-pid (->> started (filter (comp #(= % name) :name :application)) first :pid)]
       (do
         (log/debug "requesting application master to stop" :application name :pid app-pid)
         (process/exit app-pid :normal)
         (process/selective-receive!
          [:EXIT app-pid reason]
          (do
            (log/debug "application master exit" :application name :pid app-pid :reason reason)
            [:reply :ok
             (assoc state :started (filter (comp #(not= % name) :name :application) started))])))
       [:reply [:error [:not-started name]] state]))))

(defn handle-info [message {:keys [started] :as state}]
  (process/async
   (match message
     [:EXIT pid _]
     (if-let [{:keys [permanent? name]} (->> started (filter (comp #(= % pid) :pid)) first)]
       (do
         (log/debug "application master exit" :application name :pid pid :permanent? permanent?)
         (let [started (filter (comp #(not= % pid) :pid) started)]
           (if-not permanent?
             [:noreply (assoc state :started started)]
             (do
               (doseq [{:keys [pid name]} started]
                 (log/debug "requesting application master exit" :application name :pid pid)
                 (process/exit pid :normal)
                 (process/selective-receive!
                  [:EXIT pid reason]
                  (log/debug "application master exit" :pid pid :reason reason)))
               [:stop :shutdown (assoc state :started '())]))))
       [:noreply state]))))

(defn terminate [_reason state]
  [:noreply state])

(defn start-link [environment]
  (gs/start-link-ns ::application-controller [environment] {}))

(defn start
  ([name]
   (start name true))
  ([name permanent?]
   (gs/call ::application-controller [::start name permanent?])))

(defn start-all
  ([name]
   (start-all name true))
  ([name permanent?]
   (gs/call ::application-controller [::start-all name permanent?])))

(defn stop [name]
  (gs/call ::application-controller [::stop name]))

(defn which []
  (gs/call ::application-controller [::which]))

#_(which)

#_(load-applications 'dep1)

#_(proc-util/execute-proc!!
   (gs/call! ::application-controller [::start 'dep1 true]))

#_(proc-util/execute-proc!!
   (gs/call! ::application-controller [::start 'dep2 true]))

#_(proc-util/execute-proc!!
   (gs/call! ::application-controller [::start-all 'dep1 true]))

#_(proc-util/execute-proc!!
   (gs/call! ::application-controller [::start-all 'dep2 true]))

#_(proc-util/execute-proc!!
   (gs/call! ::application-controller [::start 'nrepl true]))

#_(proc-util/execute-proc!!
   (gs/call! ::application-controller [::stop 'dep1]))

#_(proc-util/execute-proc!!
   (gs/call! ::application-controller [::stop 'dep2]))

#_(proc-util/execute-proc!!
   (gs/call! ::application-controller [::stop 'otplike.nrepl]))

#_(proc-util/execute-proc!!
   (gs/call! ::application-controller [::which]))

#_(proc-util/execute-proc!!
   (gs/call! ::application-controller [::state]))

#_(process/resolve-pid ::application-controller)

#_(boot)
#_(un-boot)

