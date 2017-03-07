(ns otplike.example.e4-timeout
  (:require [otplike.process :as process :refer [!]]
            [otplike.util :as util]))

(util/defn-proc run []
  (process/receive!
    msg :ok
    (after 1000
      :timeout)))

; TODO timeout 0 to flush inbox?
