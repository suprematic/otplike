(ns otplike.example.e2-echo
  (:require [otplike.process :as process :refer [!]]))

(process/defproc server []
  (println "server: waiting for messages...")
  (loop []
    (process/receive!
      [from msg] (do
                   (println "server: got" msg)
                   (! from [(process/self) msg])
                   (recur))
      :stop (println "server: stopped"))))

(process/defn-proc run []
  (let [pid (process/spawn server [] {})]
    (! pid [(process/self) :hello])
    (process/receive!
      [pid msg] (println "client: got" msg))
    (! pid :stop)))
