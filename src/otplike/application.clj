(ns otplike.application
  (:require
   [clojure.spec.alpha :as spec]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.core.match :refer [match]]
   [clojure.core.async :as async]
   [otplike.util :as u]
   [otplike.process :as process]
   [otplike.proc-util :as proc-util]
   [otplike.gen-server :as gs]
   [otplike.spec-util :as spec-util]))

(when (and (= 1 (:major *clojure-version*))
        (< (:minor *clojure-version*) 9))
  (require '[clojure.future :refer :all]))

(defn debug [pattern & args]
  (let [str (apply format pattern args)]
    (println "DEBUG:" str)))


(spec/def ::ok #{:ok})
(spec/def ::error #{:error})

(spec/def ::name symbol?)
(spec/def ::resource string?)
(spec/def ::applications (spec/coll-of symbol?))
(spec/def ::namespaces (spec/coll-of symbol?))

(spec/def ::app-descr
  (spec/keys
    :req [::name ::resource ::applications ::namespaces]))

(spec/def ::path (spec/coll-of symbol?))

(spec/fdef load-application
  :args (spec/or
         :v1 (spec/cat :name symbol?)
         :v2 (spec/cat :name symbol? :path ::path))
  :ret (spec/or
         :ok (spec/tuple ::ok ::app-descr)
         :error (spec/tuple ::error (spec/tuple keyword? ::path))))
(defn- load-application
  ([name]
   (load-application name []))
  ([name path]
   (let [resource (format "%s.app.edn" name)]
     (if-let [stream (io/resource resource)]
       (try
         (let [{:keys [namespaces applications]} (-> stream slurp read-string)]
           [:ok
            {::name name
             ::resource resource
             ::applications (map symbol applications)
             ::namespaces (map symbol namespaces)}])
         (catch Throwable t
           [:error [:bad-file path]]))
       [:error [:no-file path]]))))
(spec-util/instrument 'load-application)

#_(load-application 'kernel)

(spec/fdef load-applications
  :args (spec/or
          :v1 (spec/cat :name symbol?)
          :v2 (spec/cat
                :name symbol
                :apps (spec/coll-of ::app-descr)
                :loaded any?
                :path ::path))
  :ret (spec/or
         :ok (spec/tuple ::ok (spec/coll-of ::app-descr))
         :error (spec/tuple ::error (spec/tuple keyword? ::path))))
(defn- load-applications
  ([name]
   (try
     [:ok (load-applications name '()  (atom #{}) [name])]
     (catch clojure.lang.ExceptionInfo ex
       [:error (:reason (ex-data ex))])))
  ([name applications loaded? path]
   (if-not (@loaded? name)
     (match (load-application name path)
       [:ok application]
       (do 
         (swap! loaded? conj name)
         (let [deps
               (mapcat
                 (fn [name]
                   (load-applications name applications loaded? (conj path name)))
                 (:applications application))]
           (concat deps [application] applications)))
       [:error reason]
       (throw (ex-info "" {:reason reason})))
     applications)))
(spec-util/instrument 'load-applications)

#_(load-applications 'kernel1)

(process/proc-defn- application-master-p [{:keys [name namespaces start-fn stop-fn] :as application} controller-pid]
  (process/flag :trap-exit true)
  (when-let
      [[sup-pid state]
       (try
         (doseq [ns namespaces]
           (require ns :reload))

         (if-let [start-fn (some-> start-fn resolve var-get)] 
           (match (process/await?! (apply start-fn [{:name name}]))
             [:ok (sup-pid :guard process/pid?) state]
             [sup-pid state]

             [:ok (sup-pid :guard process/pid?)]
             [sup-pid nil]

             bad-return
             (process/exit [:bad-return bad-return]))
           (process/exit :no-start-fn))
         (catch clojure.lang.ExceptionInfo ex
           (throw ex))
         (catch Throwable ex
           (process/exit [:exception (u/stack-trace ex)])))]

    (process/! controller-pid [:started (process/self)])

    (let [call-stop-and-exit
          #(do
             (when-let [stop-fn (some-> stop-fn resolve var-get)]
               (try
                 (apply stop-fn [state])
                 (catch Throwable _ex)))
             (process/exit %))]
      (process/selective-receive!
        [:EXIT sup-pid reason]
        (do
          (debug "app supervisor terminated with reason: %s" reason)
          (call-stop-and-exit reason))

        [:EXIT controller-pid reason]
        (do
          (process/exit sup-pid reason)

          (process/receive!
            [:EXIT sup-pid reason]
            (call-stop-and-exit reason)))))))

(defn init []
  (process/flag :trap-exit true)
  [:ok {::started '()}])

(defn- start-many [applications permanent?]
  (process/async
    (loop [applications applications new-started '()]
      (if (empty? applications)
        [:ok new-started]
        (let [[{:keys [name] :as application} & remaining] applications]
          (debug "starting application: %s" name)
          
          (match (let [app-pid (process/spawn-link application-master-p [application (process/self)])]
                   (process/selective-receive!
                     [:started app-pid]
                     (do
                       (debug "application master started for %s pid=%s" name app-pid)
                       [:ok {:application application :pid app-pid :permanent? permanent?}])
                     
                     [:EXIT app-pid reason]
                     (do
                       (debug "application master exit for %s reason=%s, pid=%s" name reason app-pid)
                       [:error reason])))
            
            [:ok application']
            (recur remaining (conj new-started application'))
            
            [:error reason]
            [:error new-started reason]))))))
 
(defn handle-call [message _reply-to {:keys [started] :as state}]
  (process/async
    (match message
      [::which]
      [:reply
       (apply list
         (map
           (fn [{:keys [application]}]
             [(get application :name) (get application :description "") (get application :version "0.0.0")]) started)) state]

      [::start name permanent?]
      (let [started-names (->> started (map (comp :name :application)) (apply hash-set))]
        (if-not (contains? started-names name)
          (match (load-application name)
            [:ok application]
            (let [not-started
                  (set/difference
                    (->> application :applications (apply hash-set))
                    started-names)]
              (debug "starting application %s" name)
              (if-not (empty? not-started)
                [:reply [:error [:not-started not-started]] state]
                (let [app-pid (process/spawn-link application-master-p [application (process/self)])]
                  (process/selective-receive!
                    [:started app-pid]
                    (do
                      (debug "application master started for %s pid=%s" name app-pid)
                      [:reply :ok
                       (update state :started conj
                         {:application application :pid app-pid :permanent? permanent?})])

                    [:EXIT app-pid reason]
                    (do
                      (debug "application master exit for %s reason=%s, pid=%s" name reason app-pid)
                      [:reply [:error name reason] state])))))

            [:error error]
            [:reply [:error error] state])
          [:reply [:error [:already-started name]] state]))

      [::start-all name permanent?]
      (match (load-applications name)
        [:ok applications]
        (let [applications
              (let [started? (->> started (map (comp :name :application)) (apply hash-set))]
                (filter
                  (fn [{:keys [name]}]
                    (not (started? name))) applications))]
          
          (debug "start many: %s" (apply list applications))

          (match (process/await! (start-many applications permanent?))
            [:ok new-started]
            (do
              (debug "new-started: %s" new-started)
              [:reply :ok (assoc state :started (concat new-started started))])

            [:error new-started reason]
            (do
              (debug "new-started: %s" new-started)
              [:reply [:error reason] (assoc state :started (concat new-started started))])))

        [:error errors]
        [:reply [:error errors] state])

      [::stop name]
      (if-let [app-pid (->> started (filter (comp #(= % name) :name :application)) first :pid)]
        (do
          (debug "requesting application master to stop pid=%s, reason=%s" app-pid :normal)
          (process/exit app-pid :normal)
          (process/selective-receive!
            [:EXIT app-pid reason]
            (do
              (debug "application master exit pid=%s, reason=%s" app-pid reason)
              [:reply :ok
               (assoc state :started (filter (comp #(not= % name) :name :application) started))])))
        [:reply [:error [:not-started name]] state]))))

(defn handle-info [message {:keys [started] :as state}]
  (process/async
   (debug "info: %s" message)
   (match message
     [:EXIT pid _]
     (if-let [{:keys [permanent?] :as application} (->> started (filter (comp #(= % pid) :pid)) first)]
       (let [started (filter (comp #(not= % pid) :pid) started)]
         (if-not permanent?
           [:noreply (assoc state :started started)]
           (do
             (doseq [{:keys [pid]} started]
               (debug "requesting application master to stop pid=%s, reason=%s" pid :normal)
               (process/exit pid :normal)
               (process/selective-receive!
                 [:EXIT pid reason]
                 (debug "application master exit pid=%s, reason=%s" pid reason)))
             [:stop :shutdown (assoc state :started '())])))
       [:noreply state]))))

(defn terminate [reason state]
  (debug "terminate: %s" reason)
  [:noreply state])

(defn start-link []
  (gs/start-link-ns ::application-controller [] {}))

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
    (gs/call! ::application-controller [::start 'kernel true]))

#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::stop 'dep1]))

#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::stop 'dep2]))

#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::stop 'dep3]))

#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::which]))

#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::state]))

#_(process/resolve-pid ::application-controller)


#_(boot)
#_(un-boot)
