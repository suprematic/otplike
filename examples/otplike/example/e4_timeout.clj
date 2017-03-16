(ns otplike.example.e4-timeout
  (:require [otplike.process :as process :refer [!]]
            [otplike.proc-util :as proc-util]))

(proc-util/defn-proc run []
  (process/receive!
    msg :ok
    (after 1000
      :timeout)))

; TODO timeout 0 to flush inbox?
