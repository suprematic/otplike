(ns otplike.application
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.core.match :refer [match]]
   [clojure.core.async :as async]
   [otplike.util :as u]
   [otplike.process :as process]
   [otplike.proc-util :as proc-util]
   [otplike.gen-server :as gs])
  (:gen-class))

(defn debug [pattern & args]
  (let [str (apply format pattern args)]
    (println "DEBUG:" str)))

(def normalize symbol)

(defn- load-app-resource [name path]
  (let [name (normalize name)
        resource (format "%s.app.edn" name)]
    (if-let [stream (io/resource resource)]
      (try
        (let [{:keys [namespace] :as resource}
              (-> stream slurp read-string
                (assoc :name name :resource resource)
                (update :applications #(->> % (map normalize) (apply hash-set)))
                (update :namespace normalize))]
          (try
            (require namespace :reload) 
            [:ok resource]
            (catch Throwable ex
              [:error {:reason :exception :name name :resource resource :stacktrace (u/stack-trace ex) :path path}])))
        
        (catch Throwable t
          [:error {:reason :bad-app-file :name name :resource resource :path path}]))
      [:error {:reason :no-file :name name :resource resource :path path}])))


(defn- load-apps-resources
  ([name]
   (load-apps-resources name [{} {}] [name]))
  ([name [loaded errors] path]
   (if-not (loaded name)
     (match (load-app-resource name path)
       [:ok application]
       (reduce
         (fn [[loaded errors] name]
           (load-apps-resources name [loaded errors] (conj path name)))
         [(assoc loaded name application) errors]
         (:applications application))
       [:error error]
       [loaded (assoc errors name error)])
     [loaded errors])))


#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::start 'kernel] 10000))

#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::stop 'kernel]))

(process/proc-defn- application-master-p [{:keys [name namespace] :as application} controller-pid]
  (process/flag :trap-exit true)

  (when-let
      [[sup-pid state]
       (try
         (require namespace)

         (if-let [start-fn (u/ns-function namespace 'start)] 
           (match (process/await?! (apply start-fn []))
             [:ok (sup-pid :guard process/pid?) state]
             [sup-pid state]

             [:ok (sup-pid :guard process/pid?)]
             [sup-pid nil]

             bad-return
             (process/exit [:error [:bad-return bad-return]]))

           (process/exit [:error :no-start-fn]))
         (catch clojure.lang.ExceptionInfo ex
           (throw ex))
         (catch Throwable ex
           (process/exit [:error [:exception (u/stack-trace ex)]])))]

    (process/! controller-pid [:started (process/self)])

    (loop []
      (process/selective-receive!
        [:EXIT sup-pid reason]
        (do
          (debug "*** app supervisor terminated with reason: %s" reason)
          (when-let [stop-fn (u/ns-function namespace 'stop)]
            (try
              (apply stop-fn [state])
              (catch Throwable _ex)))
          (process/exit reason))

        [:stop reason]
        (do
          (process/exit sup-pid reason)
          (recur))))))


(defn- start-application [{:keys [name applications] :as application} started]
  (process/async
    (let [missing (set/difference applications (->> started keys (apply hash-set)))]
      (if (empty? missing)
        (do
          (debug "starting application %s" name)
          (let [app-pid (process/spawn-link application-master-p [application (process/self)])]
            (process/selective-receive!
              [:started app-pid]
              (do
                (debug "application master started for %s pid=%s" name app-pid)
                [:started app-pid])

              [:EXIT app-pid reason]
              (do
                (debug "application master exit for %s reason=%s, pid=%s" name reason app-pid)
                [:error reason]))))
        [:error [:not-started missing]]))))

(defn- stop-application [app-pid]
  (process/async
    (debug "requesting application master to stop pid=%s, reason=%s" app-pid :normal)
    (process/! app-pid [:stop :normal])

    (process/selective-receive!
      [:EXIT app-pid reason]
      (do
        (debug "application master terminated pid=%s, reason=%s" app-pid reason)
        [:ok reason]))))

(defn init []
  (process/flag :trap-exit true)
  [:ok {::pid->name {} ::name->pid {}}])

(defn handle-call [message _reply-to {name->pid ::name->pid :as state}]
  (process/async
    (match message
      [::state]
      [:reply state state]

      [::start name]
      (let [name (normalize name)]
        (if-not (contains? name->pid name)
          (match (load-app-resource name [])
            [:ok resource]
            (match (process/await! (start-application resource name->pid))
              [:started app-pid]
              [:reply :ok
               (-> state
                 (update ::name->pid assoc name app-pid)
                 (update ::pid->name assoc app-pid name))]

              [:error reason]
              [:reply [:error reason] state])

            [:error error]
            [:reply [:error error] state])

          [:reply [:error [:already-started name]] state]))

      [::stop name]
      (let [name (normalize name)]
        (if-let [app-pid (name->pid name)]
          (let [result (process/await! (stop-application app-pid))]
            [:reply result
             (-> state
               (update ::name->pid dissoc name)
               (update ::pid->name dissoc app-pid))])
          [:reply [:error [:not-started name]] state])))))


#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::start 'dep1]))

#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::start 'kernel]))

#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::stop 'kernel]))



#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::start-all 'kernel1]))


#_(boot)
#_(un-boot)


(defn handle-info [message state]
  (debug "*** info message: %s" message)
  [:noreply state])

#_(load-app-resources 'dep1)


(defn start [name]
  (gs/call ::applocation-controller [::start name]))

(defn start-all [name]
  (gs/call ::applocation-controller [::start-all name]))

(defn stop [name]
  (gs/call ::applocation-controller [::stop name]))


(process/proc-defn- init-p [terminate-ch]
  (process/flag :trap-exit true)
  (try
    (match (gs/start-link-ns! ::application-controller [] {})
      [:ok controller-pid]
      (do
        (process/receive!
          [:EXIT controller-pid reason] 
          (do
            (debug "application controller terminated unexpectedly: %s" reason)
            (async/put! terminate-ch [:error reason]))

          [::shutdown]
          (do
            (debug "shutdown request received") 
            (process/exit controller-pid :shutdown)
            (process/receive!
              [:EXIT controller-pid reason]
              (do
                (debug "application controller terminated upon request: %s" reason)
                (async/put! terminate-ch [:exit reason]))))))

      [:error reason]
      (async/put! terminate-ch [:error reason]))

    (catch Throwable t
      (async/put! terminate-ch [:error (process/ex->reason t)]))))

(defn- boot []
  (let [terminate (async/promise-chan)]
    (try
      (process/spawn-opt init-p [terminate] {:register ::init})
      (catch Throwable t
        (async/put! terminate [:error (process/ex->reason t)])))
    terminate))

(defn- un-boot []
  (if-let [pid (process/resolve-pid ::init)]
    (process/! pid [::shutdown])
    [:error :noproc]))


(defn -main [& args]
  (if-let [error (async/<!! (boot))]
    (do
      (println "otplike terminated: " error)
      (System/exit -1))
    (System/exit 0)))


#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::state]))

#_(process/resolve-pid ::application-controller)


#_(boot)
#_(un-boot)

