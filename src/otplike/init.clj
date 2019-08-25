(ns otplike.init
  (:require
   [clojure.core.match :refer [match]]
   [clojure.core.async :as async]
   [otplike.process :as process]
   [otplike.proc-util :as proc-util]
   [otplike.gen-server :as gs]
   [otplike.application :as application])
  (:gen-class))

(defn debug [pattern & args]
  (let [str (apply format pattern args)]
    (println "DEBUG:" str)))

(process/proc-defn- init-p [terminate-ch]
  (process/flag :trap-exit true)
  (try
    (match (process/await! (application/start-link))
      [:ok controller-pid]
      (do
        (process/await! (application/start 'kernel true))
        
        (debug "application controller started, pid=%s" controller-pid)
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

(defonce terminate
  (atom nil))

(defn- init []
  (let [ch (async/promise-chan)]
    (process/spawn-opt init-p [ch] {:register ::init})
    ch))

(defn halt
  ([]
   (halt 0))
  ([rc]
   (if-let [pid (process/resolve-pid ::init)]
     (process/! pid [::halt rc])
     :noproc)))

#_(init)
#_(halt)

(defn -main [& args]
  (match (async/<!! (init))
    [::error reason]
    (do
      (println "otplike terminated unexpectedly, with reason: %s" reason)
      (System/exit -1))
    [::halt _ rc]
    (System/exit rc)))
