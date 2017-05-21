(ns otplike.example.e7-bike-rent
  (:require [clojure.core.match :refer [match]]
            [otplike.process :as process :refer [!]]))

;; Try it:
; > lein repl
; => (require '[otplike.example.e7-bike-rent :as e7])
; => (require '[otplike.proc-util :as proc-util])
; => (e7/start [1 2 3])
; => (proc-util/execute-proc!!
;      (let [[_ bike] (e7/rent)]
;        (println (e7/return bike))
;        (println (e7/rent))
;        (println (e7/rent))
;        (println (e7/rent))
;        (println (e7/rent))
;        (println (e7/stop)))

(declare bike-rent reply call)

; API

(defn start [bikes]
  (process/spawn-opt bike-rent [bikes] {:register :bike-rent}))

(defn stop []
  (call :stop))

(defn rent []
  (call :rent))

(defn return [bike]
  (call [:return [(process/self) bike]]))

; Loop

(process/proc-defn bike-rent [bikes]
  (printf "server starting with bikes %s%n" bikes)
  (process/flag :trap-exit true)
  (println "server trapping exits")
  (loop [rented #{} available bikes]
    (printf "waiting for rent request, rented %s, available %s%n"
            rented available)
    (process/receive!
      [:request pid :rent]
      (do
        (printf "rent request from %s, rented %s, available %s%n"
                pid rented available)
        (match available
          ([] :seq)
          (do
            (reply pid [:error :no-bikes])
            (recur rented available))
          ([bike & bikes-left] :seq)
          (do
            (process/link pid)
            (printf "bike %s rented%n" bike)
            (reply pid [:ok bike])
            (printf "rent response sent, bikes left %s%n" bikes-left)
            (recur (conj rented [pid bike]) bikes-left))))
      [:request pid [:return record]]
      (do
        (printf "return request from %s, record %s, rented %s, available %s%n"
                pid record rented available)
        (match (get rented record)
          [pid bike]
          (do
            (process/unlink pid)
            (reply pid :ok)
            (recur (disj rented record) (conj available bike)))))
      [:EXIT pid reason]
      (do
        (printf "client exit, pid %s, reason %s, rented %s, available %s%n"
                pid reason rented available)
        (let [{rented-by-pid true other false} (group-by
                                                 #(= (first %) pid) rented)]
          (printf "rented by pid %s, other %s%n" rented-by-pid other)
          (recur (set other) (concat available (map second rented-by-pid)))))
      [:request pid :stop]
      (do
        (printf "stop request form %s%n" pid)
        (reply pid :ok))
      msg
      (printf "unexpected %s%n" msg)))
  (println "exiting"))

; Internal

(defn- call [msg]
  (if (! :bike-rent [:request (process/self) msg])
    (process/receive!!
      [:reply r] r
      (after 1000
        (process/exit :timeout)))
    (process/exit :noproc)))

(defn- reply [pid msg]
  (! pid [:reply msg]))
