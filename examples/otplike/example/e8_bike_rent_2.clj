(ns otplike.example.e8-bike-rent-2
  (:require [otplike.process :as process :refer [!]]
            [clojure.core.match :refer [match]]))

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

(process/defproc bike-rent [bikes]
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
  (let [mref (process/monitor (process/whereis :bike-rent))]
    (! :bike-rent [:request (process/self) msg])
    (process/receive!!
      [:DOWN mref _ _] (throw (Exception. "down"))
      [:reply r] r
      (after 1000
             (throw (Exception. "timeout 1000"))))))

; FIXME replace (call [msg]) above, when process/request! will be ready
#_(defn- call [msg]
  (process/request! :bike-rent [:request (process/self) msg] 1000))

(defn- reply [pid msg]
  (! pid [:reply msg]))
