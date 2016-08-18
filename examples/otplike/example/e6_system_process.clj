(ns otplike.example.e6-system-process
  (:require [otplike.process :as process :refer [!]]
            [clojure.core.match :refer [match]]))

; TODO tell about (process/flags [flag value])
(process/defn-proc run-exit-abnormal []
  (let [pfn (process/proc-fn []
              (process/receive!
                sig (println "receive" sig)))
        pid (process/spawn pfn [] {:register :p, :flags {:trap-exit true}})]
    (match (process/whereis :p) pid :ok)
    (process/link pid)
    (process/exit pid :abnormal)
    (match (process/whereis :p) pid :ok)))

(process/defn-proc run-exit-normal []
  (let [pfn (process/proc-fn []
              (process/receive!
                sig (println "receive" sig)))
        pid (process/spawn pfn [] {:register :p, :flags {:trap-exit true}})]
    (match (process/whereis :p) pid :ok)
    (process/link pid)
    (process/exit pid :normal)
    (match (process/whereis :p) pid :ok)))

(process/defn-proc run-exit-kill []
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
