(ns otplike.example.e5-exit
  (:require [otplike.process :as process :refer [!]]
            [clojure.core.match :refer [match]]))

; FIXME process/exit with self pid and reason other than normal must throw
(process/defn-proc run-exit-self [inbox]
  (process/exit (process/self) :abnormal)
  (assert false "must never reach this place"))

(process/defn-proc run-exit-self-normal [inbox]
  (process/exit (process/self) :normal)
  (println "feels good"))

; Tell that any attempt to use process fns throws after process got exit signal
; as it happens with process/receive! in example.
(process/defn-proc run-exit-other [inbox]
  (let [pfn (process/proc-fn [inbox]
              (process/receive!
                _ (assert false "must never reach this place")))
        pid (process/spawn pfn [] {:register :p})]
    (match (process/whereis :p) pid :ok)
    (process/exit pid :abnormal)
    (match (process/whereis :p) nil :ok)))

(process/defn-proc run-exit-other-normal [inbox]
  (let [pfn (process/proc-fn [inbox]
              (process/receive!
                :how-are-you? (println "I'm alive")))
        pid (process/spawn pfn [] {:register :p})]
    (match (process/whereis :p) pid :ok)
    (process/exit pid :normal)
    (! pid :how-are-you?)
    (match (process/whereis :p) pid :ok)))
