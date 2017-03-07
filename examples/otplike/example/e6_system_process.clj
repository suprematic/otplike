(ns otplike.example.e6-system-process
  (:require [clojure.core.match :refer [match]]
            [otplike.process :as process :refer [!]]
            [otplike.util :as util]))

; TODO tell about (process/flags [flag value])
; TODO check if process is still alive using special fn
(util/defn-proc run-exit-abnormal []
  (let [pfn (process/proc-fn []
              (loop []
                (process/receive!
                  [:EXIT (self) :abnormal]
                  (do
                    (println "receive" sig)
                    (recur))
                  :stop
                  (println "stopped"))))
        pid (process/spawn pfn [] {:register :p, :flags {:trap-exit true}})]
    (match (process/whereis :p) pid :ok)
    (process/exit pid :abnormal)
    (match (process/whereis :p) pid :ok)
    (! :p :stop)))

(util/defn-proc run-exit-normal []
  (let [pfn (process/proc-fn []
              (process/receive!
                sig (println "receive" sig)))
        pid (process/spawn pfn [] {:register :p})]
    (match (process/whereis :p) pid :ok)
    (process/link pid)
    (process/exit pid :normal)
    (match (process/whereis :p) pid :ok)))

(util/defn-proc run-exit-kill []
  (process/flag :trap-exit true)
  (let [pfn (process/proc-fn []
              (process/receive!
                _ (assert false "must never reach this place")))
        pid (process/spawn pfn [] {:register :p, :flags {:trap-exit true}})]
    (match (process/whereis :p) pid :ok)
    (process/link pid)
    (process/exit pid :kill)
    (match (process/whereis :p) nil :ok)
    (process/receive!
      [:EXIT pid :killed] :ok)))
