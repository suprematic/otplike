(ns otplike.example.e2-echo
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
  (let [pid (process/spawn server)]
    (! pid [(process/self) :hello])
    (process/receive!
      [pid msg] (println "client: receive" msg))
    (! pid :stop)))
