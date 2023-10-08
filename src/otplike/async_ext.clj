(ns ^:no-doc otplike.async-ext
  (:require [clojure.core.async.impl.protocols :as ap]
            [clojure.core.async.impl.dispatch :as dispatch]))

(when (and (= 1 (:major *clojure-version*))
           (< (:minor *clojure-version*) 9))
  (require '[clojure.future :refer :all]))

;; ====================================================================
;; Internal

(defn- box-deref [val]
  (reify clojure.lang.IDeref
    (deref [_] val)))

(def true-deref
  (box-deref true))

(def false-deref
  (box-deref false))

(def nil-deref
  (box-deref nil))

(defn- update-state-put [state]
  (case state
    :closed :closed
    (nil :set) :set
    ;; if handler - remove it
    nil))

(defn- update-state-take [state ^clojure.core.async.impl.protocols.Handler h]
  (case state
    :closed :closed
    :set nil
    (if (ap/blockable? h) h)))

(deftype NotifyChannel [state]
  ap/WritePort
  (put! [_this value handler]
    (when (nil? value)
      (throw (IllegalArgumentException. "Can't put nil on channel")))
    (let [[old] (swap-vals! state update-state-put)]
      (case old
        :closed false-deref
        (nil :set) true-deref
        ;; if handler
        (let [^clojure.core.async.impl.protocols.Handler h old
              ^java.util.concurrent.locks.Lock hlock h]
          (.lock hlock)
          (let [hfn (and (ap/active? h) (ap/commit h))]
            (.unlock hlock)
            (if hfn
              (dispatch/run (fn run-dispatch [] (hfn :set)))))
          true-deref))))

  ap/ReadPort
  (take! [_this handler]
    (let [[old] (swap-vals! state update-state-take handler)]
      (case old
        :closed
        (do
          (ap/commit ^clojure.core.async.impl.protocols.Handler handler)
           nil-deref)
        :set
        (do
          (ap/commit ^clojure.core.async.impl.protocols.Handler handler)
          true-deref)
        nil nil
        ;; if handler
        (do
          (ap/commit ^clojure.core.async.impl.protocols.Handler old)
          nil))))

  ap/Channel
  (closed? [_this]
    (= @state :closed))
  (close! [_this]
    (let [[old] (reset-vals! state :closed)]
      (case old
        (:closed :set nil) nil
        ;; if handler
        (let [^clojure.core.async.impl.protocols.Handler h old
              ^java.util.concurrent.locks.Lock hlock h]
          (.lock hlock)
          (let [hfn (and (ap/active? h) (ap/commit h))]
            (.unlock hlock)
            (if hfn
              (dispatch/run (fn run-dispatch [] (hfn nil))))))))
    nil))

;; ====================================================================
;; API

(defn notify-chan []
  (NotifyChannel. (atom nil)))
