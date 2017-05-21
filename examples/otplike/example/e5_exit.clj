(ns otplike.example.e5-exit
  (:require [clojure.core.match :refer [match]]
            [otplike.process :as process :refer [!]]
            [otplike.proc-util :as proc-util]))

; abnormal
(proc-util/execute-proc!!
  (process/flag :trap-exit true)
  (let [parent (process/self)
        pfn (process/proc-fn []
              (process/receive!
                _ (! parent :alive)
                (after 1000
                  (! parent :alive))))
        pid (process/spawn-link pfn)]
    (process/exit pid :abnormal)
    (process/receive!
      [:EXIT pid :abnormal] (println "exited")
      :alive (println "alive"))))

; normal
(proc-util/execute-proc!!
  (process/flag :trap-exit true)
  (let [parent (process/self)
        pfn (process/proc-fn []
              (process/receive!
                _ :ok
                (after 1000
                  (! parent :alive))))
        pid (process/spawn-link pfn)]
    (process/exit pid :normal))
  (process/receive!
    [:EXIT pid :normal] (println "exited")
    :alive (println "alive")))

; abnormal trap-exit
(proc-util/execute-proc!!
  (process/flag :trap-exit true)
  (let [parent (process/self)
        pfn (process/proc-fn []
              (process/receive!
                [:EXIT pid :abnormal] (! parent :alive)))
        pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
    (process/exit pid :abnormal)
    (process/receive!
      :alive (println "alive"))))

; normal trap-exit
(proc-util/execute-proc!!
  (process/flag :trap-exit true)
  (let [parent (process/self)
        pfn (process/proc-fn []
              (process/receive!
                [:EXIT pid :normal] (! parent :alive)))
        pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
    (process/exit pid :normal)
    (process/receive!
      :alive (println "alive"))))

; kill trap-exit
(proc-util/execute-proc!!
  (process/flag :trap-exit true)
  (let [parent (process/self)
        pfn (process/proc-fn []
              (process/receive!
                [:EXIT pid :kill] (! parent :alive)
                _ (println "unexpected")))
        pid (process/spawn-opt pfn {:link true :flags {:trap-exit true}})]
    (process/exit pid :kill)
    (process/receive!
      :alive (println "alive")
      [:EXIT pid :killed] (println "killed"))))
