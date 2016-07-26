(ns otplike.example.e3-echo-registered
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
  (process/spawn server [] {:register :echo})
  (! :echo [(process/self) :hello])
  (process/receive!
    [_pid msg] (println "client: receive" msg))
  (! :echo :stop))
