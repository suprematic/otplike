(ns otplike.example.e5-exit
  (:require [otplike.process :as process :refer [!]]
            [clojure.core.match :refer [match]]))

(process/defn-proc run-exit-self []
  (process/exit (process/self) :abnormal)
  (if true ;TODO check if process exited
    (println "exited")))

(process/defn-proc run-exit-self-normal []
  (process/exit (process/self) :normal)
  (if true ; TODO check if process exited
    (println "feels good")))

; Tell that any attempt to use process fns throws after process got exit signal
; as it happens with process/receive! in example.
(process/defn-proc run-exit-other []
  (let [pfn (process/proc-fn []
              (process/receive!
                _ (assert false "must never reach this place")))
        pid (process/spawn pfn [] {:register :p})]
    (match (process/whereis :p) pid :ok)
    (process/exit pid :abnormal)
    (match (process/whereis :p) nil :ok)))

(process/defn-proc run-exit-other-normal []
  (let [pfn (process/proc-fn []
              (process/receive!
                :how-are-you? (println "I'm alive")))
        pid (process/spawn pfn [] {:register :p})]
    (match (process/whereis :p) pid :ok)
    (process/exit pid :normal)
    (! pid :how-are-you?)
    (match (process/whereis :p) pid :ok)))
