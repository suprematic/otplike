(ns otplike.example.e7-bike-rent
  (:require [otplike.process :as process :refer [!]]
            [clojure.core.match :refer [match]]))

;; Try it:
; > lein repl
; => (require '[otplike.example.e7-bike-rent :as e7])
; => (require '[otplike.process :as process])
; => (e7/start [1 2 3])
; => (process/defn-proc p []
;      (let [[_ bike] (e7/rent)]
;        (e7/return bike)
;        (e7/rent)
;        (e7/rent)
;        (e7/rent)
;        (e7/rent)
;        (e7/stop)))
; => (p)

(declare bike-rent reply call)

; API

(defn start [bikes]
  (process/spawn bike-rent [bikes] {:register :bike-rent}))

(defn stop []
  (call :stop))

(defn rent []
  (call :rent))

(defn return [bike]
  (call [:return [(process/self) bike]]))

; Loop

(process/proc-defn bike-rent [bikes]
  (println "server starting with bikes" bikes)
  (process/flag :trap-exit true)
  (println "server trapping exits")
  (loop [rented #{} available bikes]
    (println (str "waiting for rent request, rented " rented
                  ", available " available))
    (process/receive!
      [:request pid :rent]
      (do
        (println (str "rent request from " pid ", rented " rented
                      ", available " available))
        (match available
          ([] :seq)
          (do
            (reply pid [:error :no-bikes])
            (recur rented available))
          ([bike & bikes-left] :seq)
          (do
            (process/link pid)
            (println (str "bike " bike " rented"))
            (reply pid [:ok bike])
            (println (str "rent response sent, bikes left " bikes-left))
            (recur (conj rented [pid bike]) bikes-left))))
      [:request pid [:return record]]
      (do
        (println (str "return request from " pid ", record " record
                      ", rented " rented ", available " available))
        (match (get rented record)
          [pid bike]
          (do
            (process/unlink pid)
            (reply pid :ok)
            (recur (disj rented record) (conj available bike)))))
      [:EXIT pid reason]
      (do
        (println (str "client exit, pid " pid ", reason " reason
                      ", rented " rented ", available " available))
        (let [{rented-by-pid true other false} (group-by
                                                 #(= (first %) pid) rented)]
          (println (str "rented by pid " rented-by-pid ", other " other))
          (recur (set other) (concat available (map second rented-by-pid)))))
      [:request pid :stop]
      (do
        (println (str "stop request form " pid))
        (reply pid :ok))
      msg
      (println (str "unexpected " msg))))
  (println "exiting"))

; Internal

(defn- call [msg]
  (! :bike-rent [:request (process/self) msg])
  (process/receive!!
    [:reply r] r
    (after 1000
      (throw (Exception. "timeout 1000")))))

(defn- reply [pid msg]
  (! pid [:reply msg]))
