(ns otplike.example.e2-echo
  (:require [otplike.process :as process :refer [!]]))

(process/defproc server [inbox]
  (println "server: waiting for messages...")
  (loop []
    (process/receive!
      [from msg] (do
                   (println "server: got" msg)
                   (! from [(process/self) msg])
                   (recur))
      :stop (println "server: stopped"))))

(process/defn-proc run [inbox]
  (let [pid (process/spawn server [] {})]
    (! pid [(process/self) :hello])
    (process/receive!
      [pid msg] (println "client: got" msg))
    (! pid :stop)))
