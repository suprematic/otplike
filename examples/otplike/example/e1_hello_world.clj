(ns otplike.example.e1-hello-world
  (:require [otplike.process :as process :refer [!]]))

(process/defproc hello-world [inbox]
  (process/receive!
    msg (println msg)))

(defn run []
  (let [pid (process/spawn hello-world [] {})]
    (! pid "hello, world")))
