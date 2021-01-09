(ns otplike.init
  (:require
    [clojure.core.match :refer [match]]
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [otplike.process :as process]
    [otplike.logger :as log]
    [otplike.util :as u]
    [otplike.proc-util :as proc-util]
    [otplike.gen-server :as gs]
    [otplike.application :as application])
  (:gen-class))

(process/proc-defn- init-p [terminate-ch {:keys [applications environment]}]
  (process/flag :trap-exit true)
  (try
    (match (process/await! (application/start-link environment))
      [:ok controller-pid]
      (let [applications (concat ['kernel] applications)]
        (log/debug "application controller started" :pid controller-pid)
        (log/debug "autostarting applications" :applications applications)

        (loop [[application & rest] applications]
          (when application
            (match (process/await! (application/start-all application true))
              [:error reason]
              (do
                (log/warn "application autostart failed" :application application :reason reason)
                (process/! (process/self) [::halt 1]))

              :ok
              (recur rest))))

        (process/receive!
          [:EXIT controller-pid reason]
          (do
            (log/notice "application controller terminated" :reason reason)
            (async/put! terminate-ch [::error reason]))
          [::halt rc]
          (do
            (log/debug "halt request received" :rc rc)
            (process/exit controller-pid :shutdown)
            (process/receive!
              [:EXIT controller-pid reason]
              (do
                (log/debug "application controller terminated on request" :reason reason)
                (async/put! terminate-ch [::halt reason rc]))))))
      [:error reason]
      (async/put! terminate-ch [::error reason]))
    (catch Throwable t
      (async/put! terminate-ch [::error (process/ex->reason t)]))))

(defn init [args]
  (let [args (some-> "system.edn" io/resource slurp read-string (u/deep-merge args))]
    (let [ch (async/promise-chan)]
      (process/spawn-opt init-p [ch args] {:register ::init})
      (match (async/<!! ch)
        [::error reason]
        (do
          (log/notice "otplike unexpectedly terminated" :reason reason)
          (System/exit -1))
        [::halt _ rc]
        (System/exit rc)))))

(defn halt
  ([]
    (halt 0))
  ([rc]
    (if-let [pid (process/resolve-pid ::init)]
      (process/! pid [::halt rc])
      :noproc)))