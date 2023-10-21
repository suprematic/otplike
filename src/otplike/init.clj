(ns otplike.init
  (:require
   [clojure.core.match :refer [match]]
   [clojure.core.async :as async]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [otplike.process :as process]
   [otplike.logger :as log]
   [otplike.util :as u]
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

(def ^:private root
  {:environment
   {}})

(defn init [args]
  (let
   [system (some-> "system.edn" io/resource slurp read-string)
    merged (u/deep-merge root system args)
    ch (async/promise-chan)]

    #_(klog/set-config! (get-in merged [:environment 'kernel :logger]))

    (log/debug
     {:in :init
      :log :environment
      :what :args
      :details merged})

    (process/spawn-opt init-p [ch merged] {:register ::init})
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
      (System/exit rc))))

(defn- read-file [s]
  (let
   [[fname sections] (str/split s #":")
    file
    (if-let [file (io/file fname)]
      (-> file slurp read-string)
      (throw
       (ex-info (format "file not found: %s" fname) {:file-name fname})))]

    (cond
      (and (vector? file) (empty? sections))
      (->>
       file
       (map second)
       (apply u/deep-merge))

      (vector? file)
      (let
       [sections (str/split sections #",")
        file (u/name-keys file)]
        (->>
         sections
         (map
          (fn [section]
            (get file section)))
         (filter some?)
         (apply u/deep-merge)))

      (map? file)
      file

      :else
      (do
        (println file)
        (throw
         (ex-info (format "file %s does not contain vector or map" fname) {:file-name fname :file file}))))))

(defn -main [& args]
  (init
   (->>
    args
    (map read-file)
    (reduce u/deep-merge {}))))

(defn halt
  ([]
   (halt 0))
  ([rc]
   (if-let [pid (process/resolve-pid ::init)]
     (process/! pid [::halt rc])
     :noproc)))