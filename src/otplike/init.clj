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
        (log/debug
          {:in :init-p
           :log :trace
           :what :controller-start
           :details
           {:applications applications
            :controller-pid controller-pid}})
        (loop [[application & rest] applications]
          (when application
            (match (process/await! (application/start-all application true))
              [:error reason]
              (do
                (log/error
                  {:in :init-p
                   :log :event
                   :what :application-start
                   :result :error
                   :details
                   {:application application
                    :reason reason}})
                (process/! (process/self) [::halt 1]))

              :ok
              (recur rest))))

        (process/receive!
          [:EXIT controller-pid reason]
          (do
            (log/notice
              {:in :init-p
               :log :event
               :what :controller-exit
               :details
               {:controller-pid controller-pid
                :reason reason}})
            (async/put! terminate-ch [::error reason]))
          [::halt rc]
          (do
            (log/debug
              {:in :init-p
               :log :request
               :what :controller-halt
               :details
               {:controller-pid controller-pid
                :rc rc}})
            (process/exit controller-pid :shutdown)
            (process/receive!
              [:EXIT controller-pid reason]
              (do
                (log/debug
                  {:in :init-p
                   :log :event
                   :what :controller-exit
                   :details
                   {:controller-pid controller-pid
                    :reason reason}})
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
          (log/notice
            {:in :init
             :log :event
             :what :terminated
             :details
             {:reason reason}})
          (System/exit -1))
        [::halt _ rc]
        (System/exit rc)))))

(defn -main [& args]
  (init
    (->> args
      (map
        (fn [fname]
          (if-let [file (io/file fname)]
            (-> file slurp read-string)
            (throw
              (ex-info (format "file not found: %s" fname) {:file-name fname})))))
      (reduce u/deep-merge nil))))

(defn halt
  ([]
    (halt 0))
  ([rc]
    (if-let [pid (process/resolve-pid ::init)]
      (process/! pid [::halt rc])
      :noproc)))