(ns otplike.example.e1-hello-world
  (:require [otplike.process :as process :refer [!]]))

(process/proc-defn hello-world []
  (process/receive!
    msg (println msg)))

(defn run []
  (let [pid (process/spawn hello-world)]
    (! pid "hello, world")))

#_(run)
