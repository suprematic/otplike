(ns otplike.init
  (:require
   [clojure.core.match :refer [match]]
   [clojure.core.async :as async]
   [clojure.pprint :refer [pprint]]
   [otplike.process :as process]
   [otplike.proc-util :as proc-util]
   [otplike.gen-server :as gs]
   [otplike.application :as application])
  (:gen-class))

(defn debug [pattern & args]
  (let [str (apply format pattern args)]
    (println "DEBUG:" str)))

(process/proc-defn- init-p [terminate-ch {:keys [applications environment]}]
  (process/flag :trap-exit true)
  (try
    (match (process/await! (application/start-link environment))
      [:ok controller-pid]
      (let [applications (concat ['kernel] applications)]
        (debug "application controller started, pid=%s" controller-pid)
        (debug "autostarting: applications=%s" (apply list applications))

        (loop [[application & rest] applications]
          (when application
            (match (process/await! (application/start-all application true))
              [:error reason]
              (do
                (debug "autostart failed: application=%s, reason=%s" application reason)
                (process/! (process/self) [::halt 1]))

              :ok
              (recur rest))))

        (process/receive!
         [:EXIT controller-pid reason]
         (do
           (debug "application controller terminated: %s" reason)
           (async/put! terminate-ch [::error reason]))
         [::halt rc]
         (do
           (debug "halt request received, rc=%d" rc)
           (process/exit controller-pid :shutdown)
           (process/receive!
            [:EXIT controller-pid reason]
            (do
              (debug "application controller terminated upon request: %s" reason)
              (async/put! terminate-ch [::halt reason rc]))))))
      [:error reason]
      (async/put! terminate-ch [::error reason]))
    (catch Throwable t
      (async/put! terminate-ch [::error (process/ex->reason t)]))))

(defn init [args]
  (let [ch (async/promise-chan)]
    (process/spawn-opt init-p [ch args] {:register ::init})
    (match (async/<!! ch)
      [::error reason]
      (do
        (debug "otplike terminated: \n%s" (pprint reason))
        (System/exit -1))
      [::halt _ rc]
      (System/exit rc))))

(defn halt
  ([]
   (halt 0))
  ([rc]
   (if-let [pid (process/resolve-pid ::init)]
     (process/! pid [::halt rc])
     :noproc)))