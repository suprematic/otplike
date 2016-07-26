(ns otplike.example.e4-timeout
  (:require [otplike.process :as process :refer [!]]))

(process/defn-proc run [inbox]
  (process/receive!
    msg :ok
    (after 1000
      :timeout)))

; TODO timeout 0 to flush inbox?
