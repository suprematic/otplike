(ns otplike.registry
  (:refer-clojure :exclude [send])
  (:require
    [clojure.core.async :as async :refer [<! >! put! go go-loop]]
    [clojure.core.match :refer [match]]

    [otplike.process :as process :refer [!]]
    [otplike.timer :as timer]
    [otplike.gen-server :as gs]))

;==========
; Bidirectional map

(defn bidi-make []
  {:l->r {} :r->l {}})

(defn bidi-put [{:keys [l->r r->l]} l r]
  {:l->r (assoc l->r l r) :r->l (assoc r->l r l)})

(defn bidi-dissoc-l [{:keys [l->r r->l]} l]
  (let [r (l->r l)]
    {:l->r (dissoc l->r l) :r->l (dissoc r->l r)}))

(defn bidi-dissoc-r [{:keys [l->r r->l]} r]
  (let [l (r->l r)]
    {:l->r (dissoc l->r l) :r->l (dissoc r->l r)}))

(defn bidi-get-l [{:keys [l->r]} l]
  (l->r l))

(defn bidi-get-r [{:keys [r->l]} r]
  (r->l r))

;============
; Multimap

(defn mult-make []
  {})

(defn multi-set [m k items]
  (assoc m k (set items)))

(defn mult-get [m k]
  (or (m k) #{}))

(defn multi-conj [m k item]
  (if-let [r (m k)]
    (assoc m k (conj r item))
    (assoc m k #{item})))

(defn multi-disj [m k item]
  (if-let [r (m k)]
    (assoc m k (disj r item)) m))

;============
; Registry

(def server :registry)

;--------
; API

(defn reg-name [pid name]
  (gs/call server [:reg-name pid name]))

(defn reg-prop [pid prop]
  (gs/call server [:reg-prop pid prop]))

(defn send [key value]
  (gs/call server [:send key value]))

(defn wait-name [name]
  (let [ch (gs/call server [:wait-name name])
        timeout (async/timeout 5000)]
    (match (async/alts!! [ch timeout])
      [[name pid] ch]
      pid
      [nil timeout]
      :timeout)))

(defn start []
  (gs/start
    (gs/coerce-current-ns)
    []
    {:name server :register server :flags {:trap-exit true}}))

(defn stop []
  (process/exit (process/whereis server) :normal))

;---------
; gen-server callbacks

(defn init [_]
  [:ok {:names (bidi-make) :waiters (mult-make) :props #{}}])

(process/defproc waiter-proc [inbox name ch]
  (match (<! inbox)
    [:available [name pid]]
    (async/put! ch [name pid])))

(defn handle-call [request _sender {:keys [props names waiters] :as state}]
  (match request
    [:reg-prop pid prop]
    [:reply :ok (assoc state :props (conj props [pid prop]))]

    [:send key value]
    (do
      (doseq [[pid prop] props]
        (when (= prop key)
          (! pid value)))

      [:reply :ok state])

    [:reg-name pid name]
    (do
      (process/monitor pid)
      (gs/cast (process/self) [:notify-waiters name pid])
      [:reply :ok (assoc state :names (bidi-put names pid name))])

    [:wait-name name]
    (let [ch (async/chan)
          self (process/self)]
      (if-let [pid (bidi-get-r names name)]
        (do
          (async/put! ch [name pid])
          [:reply ch state])
        (let [waiter (process/spawn waiter-proc [name ch] {:link-to self})]
          [:reply ch (assoc state :waiters (multi-conj waiters name waiter))])))))

(defn handle-cast [request {:keys [waiters] :as state}]
  (match request
    [:notify-waiters name pid]
    (do
      (doseq [w (waiters name)]
        (! w [:available [name pid]]))

      [:noreply (assoc state :waiters (dissoc waiters name))])))

(defn handle-info [message {:keys [names] :as state}]
  (match message
    [:EXIT pid _reason]
    [:noreply (assoc state :names (bidi-dissoc-l names pid))]))

;==============
; tests

(defn p1 [pid inbox]
  (go
    (reg-name pid [:my-name])
    (<! (async/timeout 10000))
    (println "p1 terminate")
    :normal))

(defn p2 [pid inbox]
  (go
    (println "wait success: " (wait-name [:my-name]))
    :normal))
