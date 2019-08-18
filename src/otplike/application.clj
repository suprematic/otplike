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

(defn start [name]
  (gs/call ::applocation-controller [::start name]))

(defn stop [name]
  (gs/call ::applocation-controller [::stop name]))

(def normalize symbol)

(defn- load-application [name path]
  (let [name (normalize name)
        resource (format "%s.app.edn" name)]
    (if-let [stream (io/resource resource)]
      (try
        (let [resource
              (-> stream slurp read-string
                (assoc :name name :resource resource)
                (update :applications #(->> % (map normalize) (apply hash-set)))
                (update :namespace
                  #(if (coll? %)
                     (map normalize %)
                     [(normalize %)])))]
          [:ok resource])
        
        (catch Throwable t
          (.printStackTrace t)
          [:error {:reason :bad-app-file :name name :resource resource :path path}]))
      [:error {:reason :no-file :name name :resource resource :path path}])))

(defn- load-apps-resources
  ([name]
   (load-apps-resources name [{} {}] [name]))
  ([name [loaded errors] path]
   (if-not (loaded name)
     (match (load-application name path)
       [:ok application]
       (reduce
         (fn [[loaded errors] name]
           (load-apps-resources name [loaded errors] (conj path name)))
         [(assoc loaded name application) errors]
         (:applications application))
       [:error error]
       [loaded (assoc errors name error)])
     [loaded errors])))

(process/proc-defn- application-master-p [{:keys [name namespace start-fn stop-fn] :as application} controller-pid]
  (process/flag :trap-exit true)
  (when-let
      [[sup-pid state]
       (try
         (doseq [ns namespace]
           (require ns :reload))

         (if-let [start-fn (some-> start-fn resolve var-get)] 
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

(defn filter-rev-comp [preds coll]
  (let [pred (apply comp (reverse preds))]
    (filter pred coll)))

(defn handle-call [message _reply-to {:keys [started] :as state}]
  (process/async
    (match message
      [::which]
      [:reply
       (into '()
         (map
           (fn [{:keys [application]}]
             [(get application :name) (get application :description "") (get application :version "0.0.0")]) started)) state]

      [::start name]
      (let [name (normalize name)
            started-names (->> started (map (comp :name :application)) (apply hash-set))]
        (if-not (contains? started-names name)
          (match (load-application name [])
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
                         {:application application :pid app-pid})])

                    [:EXIT app-pid reason]
                    (do
                      (debug "application master exit for %s reason=%s, pid=%s" name reason app-pid)
                      [:reply [:error reason] state])))))

            [:error error]
            [:reply [:error error] state])
          [:reply [:error [:already-started name]] state]))

      [::stop name]
      (let [name (normalize name)]
        (if-let [app-pid (->> started (filter (comp #(= % name) :name :application)) first :pid)]
          (do
            (debug "requesting application master to stop pid=%s, reason=%s" app-pid :normal)
            (process/exit app-pid :normal)
            
            (process/selective-receive!
              [:EXIT app-pid reason]
              (do
                (debug "application master exit pid=%s, reason=%s" app-pid reason)
                [:ok reason]
                [:reply :ok
                 (assoc state :started (filter (comp #(not= % name) :name :application) started))])))
          [:reply [:error [:not-started name]] state])))))

(defn handle-info [message {:keys [started] :as state}]
  (match message
    [:EXIT pid _]
    (if-let [application (->> started (filter (comp #(= % pid) :pid)) first)]
      [:noreply
       (assoc state :started (filter (comp #(not= % pid) :pid) started))]
      [:noreply state])))

#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::start 'dep1]))

#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::start 'kernel]))

#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::stop 'kernel]))

#_(proc-util/execute-proc!!
    (gs/call! ::application-controller [::which]))

#_(boot)
#_(un-boot)

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

