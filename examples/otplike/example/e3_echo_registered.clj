(ns otplike.example.e3-echo-registered
  (:require [otplike.process :as process :refer [!]]
            [otplike.proc-util :as proc-util]))

(process/proc-defn server []
  (println "server: waiting for messages...")
  (process/receive!
    [from msg] (do
                 (println "server: receive" msg)
                 (! from [(process/self) msg])
                 (recur))
    :stop (println "server: stopped")))

(proc-util/defn-proc run []
  (process/spawn-opt server [] {:register :echo})
  (! :echo [(process/self) :hello])
  (process/receive!
    [_pid msg] (println "client: receive" msg))
  (! :echo :stop))
