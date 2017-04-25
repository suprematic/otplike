(ns otplike.supervisor-test
  (:require [clojure.test :refer [is deftest]]
            [clojure.core.match :refer [match]]
            [clojure.pprint :as pprint]
            [clojure.core.async :as async :refer [<!! <! >! >!!]]
            [clojure.math.combinatorics :as combinatorics]
            [otplike.process :as process :refer [!]]
            [otplike.trace :as trace]
            [otplike.test-util :refer :all]
            [otplike.proc-util :as proc-util]
            [otplike.supervisor :as sup]
            [otplike.gen-server :as gs])
  (:import [otplike.gen_server IGenServer]))

;(otplike.trace/console-trace [otplike.trace/filter-crashed])

;; ====================================================================
;; (start-link [sup-fn args])

; TODO try different supervisor flags
;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:no-children
  (let [done (async/chan)
        init-fn (fn []
                  (async/close! done)
                  [:ok [{} []]])]
    (match (sup/start-link init-fn)
      [:ok pid]
      (do
        (is (process/pid? pid)
            "start-link must return supervisor's pid")
        (is (await-completion done 50)
            "supervisor must call init-fn to get its spec")))))

; TODO try different child spec
; TODO try different supervisor flags
;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:single-child
  (let [sup-init-done (async/chan)
        server-init-done (async/chan)
        sup-flags {}
        server {:init (fn [_]
                        (async/close! server-init-done)
                        [:ok :state])}
        children-spec [{:id 1 :start [#(gs/start-link server) []]}]
        sup-spec [sup-flags children-spec]
        init-fn (fn []
                  (async/close! sup-init-done)
                  [:ok sup-spec])]
    (match (sup/start-link init-fn)
      [:ok pid]
      (do
        (is (process/pid? pid)
            "start-link must return supervisor's pid")
        (is (await-completion sup-init-done 50)
            "supervisor must call init-fn to get its spec")
        (is (await-completion server-init-done 50)
            "supervisor must call child's start-fn to start it")))))

; TODO try different child spec
; TODO try different supervisor flags
;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:multiple-children
  (let [sup-init-done (async/chan)
        s1-init-done (async/chan)
        s2-init-done (async/chan)
        s3-init-done (async/chan)
        sup-flags {}
        make-child
        (fn [await-chan done-chan id]
          {:id id
           :start
           [#(gs/start-link
               {:init (fn [_]
                        (is (await-completion await-chan 50)
                            "supervisor must start children in spec's order")
                        (async/close! done-chan)
                        [:ok :state])})
            []]})
        children-spec (map make-child
                           [sup-init-done s1-init-done s2-init-done]
                           [s1-init-done s2-init-done s3-init-done]
                           (range))
        sup-spec [sup-flags children-spec]
        init-fn (fn []
                  (async/close! sup-init-done)
                  [:ok sup-spec])]
    (match (sup/start-link init-fn)
      [:ok pid]
      (do
        (is (process/pid? pid)
            "start-link must return supervisor's pid")
        (is (await-completion sup-init-done 50)
            "supervisor must call init-fn to get its spec")
        (is (await-completion s3-init-done 50)
            "supervisor must call child's start-fn to start it")))))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:duplicate-child-id
  (let [sup-init-done (async/chan)
        sup-flags {}
        make-child (fn [id]
                     {:id id
                      :start [#(gs/start-link
                                 {:init (fn [_]
                                          (is false "child must not be started")
                                          [:ok :state])})
                              []]})
        children-spec (map make-child [:id1 :id2 :id2])
        sup-spec [sup-flags children-spec]
        init-fn (fn []
                  (async/close! sup-init-done)
                  [:ok sup-spec])]
    (match (process/ex-catch (sup/start-link init-fn))
      [:error [:bad-child-specs [:duplicate-child-id :id2]]]
      (is (await-completion sup-init-done 50)
          "supervisor must call init-fn to get its spec"))))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:bad-return:not-allowed-return
  (let [sup-init-done (async/chan)
        init-fn (fn []
                  (async/close! sup-init-done)
                  {:ok []})]
    (match (sup/start-link init-fn)
           [:error [:bad-return _]]
           (do
             (is (thrown? Exception (process/self))
                 "process must exit after suprevisor/init error")
             (is (await-completion sup-init-done 50)
                 "supervisor must call init-fn to get its spec")))))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:bad-return:no-child-id
  (let [sup-init-done (async/chan)
        init-fn (fn []
                  (async/close! sup-init-done)
                  [:ok [{} [{:start
                             [#(gs/start-link
                                 {:init (fn [_]
                                          (is false "child must not be started")
                                          [:ok :state])})
                              []]}]]])]
    (process/flag :trap-exit true)
    (match (sup/start-link init-fn)
           [:error [:bad-child-specs _]]
           (do
             (is (match (<! (await-message 50))
                        [:exit [_ [:bad-child-specs _]]] :ok)
                 "process must exit after suprevisor/init error")
             (is (await-completion sup-init-done 50)
                 "supervisor must call init-fn to get its spec")))))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:bad-return:bad-child-start-fn
  (let [sup-init-done (async/chan)
        init-fn (fn []
                  (async/close! sup-init-done)
                  [:ok [{} [{:id :child1
                             :start ["not a function" []]}]]])]
    (process/flag :trap-exit true)
    (match (sup/start-link init-fn)
           [:error [:bad-child-specs _]]
           (do
             (is (match (<! (await-message 50))
                        [:exit [_ [:bad-child-specs _]]] :ok)
                 "process must exit after suprevisor/init error")
             (is (await-completion sup-init-done 50)
                 "supervisor must call init-fn to get its spec")))))

(def-proc-test ^:parallel start-link:bad-return:bad-supervisor-flags
  (let [make-child (fn [id]
                     {:id id
                      :start [#(gs/start-link
                                 {:init (fn [_]
                                          (is false "child must not be started")
                                          [:ok :state])})
                              []]})]
    (otplike.proc-util/execute-proc!
      (let [sup-init-done (async/chan)
            sup-flags {:strategy "one-for-one"}
            children-spec (map make-child [:id1])
            sup-spec [sup-flags children-spec]
            init-fn (fn []
                      (async/close! sup-init-done)
                      [:ok sup-spec])]
        (match (is (sup/start-link init-fn))
               [:error [:bad-supervisor-flags _]]
               (is (await-completion sup-init-done 50)
                   "supervisor must call init-fn to get its spec"))))
    (otplike.proc-util/execute-proc!
      (let [sup-init-done (async/chan)
            sup-flags {:intensity "2"}
            children-spec (map make-child [:id1])
            sup-spec [sup-flags children-spec]
            init-fn (fn []
                      (async/close! sup-init-done)
                      [:ok sup-spec])]
        (match (is (sup/start-link init-fn))
               [:error [:bad-supervisor-flags _]]
               (is (await-completion sup-init-done 50)
                   "supervisor must call init-fn to get its spec"))))
    (otplike.proc-util/execute-proc!
      (let [sup-init-done (async/chan)
            sup-flags {:period :1}
            children-spec (map make-child [:id1])
            sup-spec [sup-flags children-spec]
            init-fn (fn []
                      (async/close! sup-init-done)
                      [:ok sup-spec])]
        (match (is (sup/start-link init-fn))
               [:error [:bad-supervisor-flags _]]
               (is (await-completion sup-init-done 50)
                   "supervisor must call init-fn to get its spec"))))
    (otplike.proc-util/execute-proc!
      (let [sup-init-done (async/chan)
            sup-flags {:strategy "one-for-one"
                       :intensity "2"
                       :period :1}
            children-spec (map make-child [:id1])
            sup-spec [sup-flags children-spec]
            init-fn (fn []
                      (async/close! sup-init-done)
                      [:ok sup-spec])]
        (match (is (sup/start-link init-fn))
               [:error [:bad-supervisor-flags _]]
               (is (await-completion sup-init-done 50)
                   "supervisor must call init-fn to get its spec"))))))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:invalid-arguments
  (is (thrown? Exception (sup/start-link "not-fn"))
      "start-link must throw on invalid arguments")
  (is (thrown? Exception (sup/start-link (fn [_] [:ok [{} []]]) :arg1))
      "start-link must throw on invalid arguments"))

(defn test-start-link:child-init-returns-error [init-fn reason]
  (let [sup-init-done (async/chan)
        sup-flags {}
        children-spec [{:id :child-id
                        :start [#(gs/start-link {:init init-fn}) []]}]
        sup-spec [sup-flags children-spec]
        init-fn (fn []
                  (async/close! sup-init-done)
                  [:ok sup-spec])]
    (match (sup/start-link init-fn)
           [:error [:shutdown [:failed-to-start-child :child-id reason]]]
           (is (await-completion sup-init-done 50)
               "supervisor must call init-fn to get its spec"))))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:child-init-returns-error:exit-normal
  (test-start-link:child-init-returns-error
    (fn [_] (process/exit :normal))
    :normal))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:child-init-returns-error:exit-abnormal
  (test-start-link:child-init-returns-error
    (fn [_] (process/exit :abnormal))
    :abnormal))

;(otplike.proc-util/execute-proc
(def-proc-test ^:parallel start-link:child-init-returns-error:stop-with-reason
  (test-start-link:child-init-returns-error
    (fn [_] [:stop :some-reason])
    :some-reason))

(def-proc-test ^:parallel
 start-link:multiple-children:first-child-init-returns-error
;(otplike.proc-util/execute-proc
  (let [child1-done (async/chan)
        sup-init-done (async/chan)
        sup-flags {}
        error-child-init (fn [_]
                           (async/close! child1-done)
                           [:stop :abnormal])
        healthy-child-init (fn [_]
                              (is false "child must not be started")
                              [:ok :state])
        child-spec (fn [id init-fn]
                     {:id id
                      :start [#(gs/start-link {:init init-fn}) []]})
        children-spec [(child-spec :id1 error-child-init)
                       (child-spec :id2 healthy-child-init)
                       (child-spec :id3 healthy-child-init)]
        sup-spec [sup-flags children-spec]
        init-fn (fn []
                  (async/close! sup-init-done)
                  [:ok sup-spec])]
    (match (sup/start-link init-fn)
           [:error [:shutdown [:failed-to-start-child :id1 :abnormal]]]
           (do
             (is (await-completion child1-done 50)
                 "first child must be started")
             (is (await-completion sup-init-done 50)
                 "supervisor must call init-fn to get its spec")))))

(def-proc-test ^:parallel
  start-link:multiple-children:middle-child-init-returns-error
;(otplike.proc-util/execute-proc
  (let [child1-init-done (async/chan)
        child1-terminate-done (async/chan)
        error-child-done (async/chan)
        sup-init-done (async/chan)
        sup-flags {}
        healthy-child1-init (fn [_]
                              (async/close! child1-init-done)
                              [:ok :state])
        child1-terminate
        (fn [reason _]
          (async/close! child1-terminate-done)
          (is (= :shutdown reason)
              "child must be stopped with :shutdown reason"))
        error-child-init (fn [_]
                           (async/close! error-child-done)
                           [:stop :abnormal])
        healthy-child2-init (fn [_]
                              (is false "child must not be started")
                              [:ok :state])
        child-spec (fn [id init-fn terminate-fn]
                     {:id id
                      :start [#(gs/start-link {:init init-fn
                                               :terminate terminate-fn}
                                              []
                                              {:flags {:trap-exit true}})
                              []]})
        children-spec [(child-spec :id1 healthy-child1-init child1-terminate)
                       (child-spec :id2 error-child-init nil)
                       (child-spec :id3 healthy-child2-init nil)]
        sup-spec [sup-flags children-spec]
        init-fn (fn []
                  (async/close! sup-init-done)
                  [:ok sup-spec])]
    (match (sup/start-link init-fn)
           [:error [:shutdown [:failed-to-start-child :id2 :abnormal]]]
           (do
             (is (await-completion child1-init-done 50)
                 "first child must be started")
             (is (await-completion error-child-done 50)
                 "error child must be started")
             (is (await-completion child1-terminate-done 50)
                 "first child must be terminated")
             (is (await-completion sup-init-done 50)
                 "supervisor must call init-fn to get its spec")))))

(def-proc-test ^:parallel
  start-link:multiple-children:last-child-init-returns-error
;(otplike.proc-util/execute-proc!!
  (let [child1-init-done (async/chan)
        child1-terminate-done (async/chan)
        child2-init-done (async/chan)
        child2-terminate-done (async/chan)
        error-child-done (async/chan)
        sup-init-done (async/chan)
        sup-flags {}
        healthy-child1-init (fn [_]
                              (async/close! child1-init-done)
                              [:ok :state])
        child1-terminate
        (fn [reason _]
          (is (await-completion child2-terminate-done 50)
              "second child must be terminated before the first")
          (async/close! child1-terminate-done)
          (is (= :shutdown reason)
              "child must be stopped with :shutdown reason"))
        error-child-init (fn [_]
                           (async/close! error-child-done)
                           [:stop :abnormal])
        healthy-child2-init (fn [_]
                              (async/close! child2-init-done)
                              [:ok :state])
        child2-terminate
        (fn [reason _]
          (async/close! child2-terminate-done)
          (is (= :shutdown reason)
              "child must be stopped with :shutdown reason"))
        child-spec (fn [id init-fn terminate-fn]
                     {:id id
                      :start [#(gs/start-link {:init init-fn
                                               :terminate terminate-fn}
                                              []
                                              {:flags {:trap-exit true}})
                              []]})
        children-spec [(child-spec :id1 healthy-child1-init child1-terminate)
                       (child-spec :id2 healthy-child2-init child2-terminate)
                       (child-spec :id3 error-child-init nil)]
        sup-spec [sup-flags children-spec]
        init-fn (fn []
                  (async/close! sup-init-done)
                  [:ok sup-spec])]
    (match (sup/start-link init-fn)
           [:error [:shutdown [:failed-to-start-child :id3 :abnormal]]]
           (do
             (is (await-completion child1-init-done 50)
                 "first child must be started")
             (is (await-completion child2-init-done 50)
                 "second child must be started")
             (is (await-completion error-child-done 50)
                 "error child must be started")
             (is (await-completion child1-terminate-done 50)
                 "first child must be terminated")
             (is (await-completion sup-init-done 50)
                 "supervisor must call init-fn to get its spec")))))

;; ====================================================================
;; one-for-one

; TODO
; Variables:
;; number of children - 3
;; intensity and period
;; Children exit plan:
;;; child restart-type - 3
;;; child shutdown type - 2
;;; child type - 2
;;; Child exits:
;;;; when - 3
;;;; restart failure - 2
;;;; reason - 2 (normal, abnormal)
;;;; restart timeout - 2
;;;; exit timeout - 2

; Properties to be hold:
;; Independent of all
;;; all children must be started before start-link returns
;;; all children must exit before gen-server exits
;;; supervisor must be stopped if allowed number of restarts is reached
;;; children must be started in the same order as in spec
;;; children must be stopped in order opposite to start order
;; Strategy-dependent:
;;; only exited child must be restarted
;; Depend on child spec
;;; all terporary children must stay exited after first exit
;;; all transient children must stay exited after first normal exit
;;; all permanent children must be restarted until supervisor is alive
;;; child must be killed if its shutdown type is :brutal-kill
;;; child must be killed if shutdown timeout occurs
;;; child must be restarted if restart fails

(defn map-vals [f m]
  (reduce-kv #(assoc %1 %2 (f %3)) {} m))

(defn ms []
  (rem (System/currentTimeMillis) 10000))

(defn log! [log fmt & args]
  (async/put! log [(ms) fmt args]))

(defn await-events [events-chan log timeout-ms {:keys [in-order unordered]}]
  (let [log! (partial log! log)]
    (async/go-loop
      [expect-in-order in-order
       other-expected unordered
       timeout (async/timeout timeout-ms)]
      (if (or (seq expect-in-order) (seq other-expected))
        (match
          (async/alts! [events-chan timeout])
          [nil timeout]
          (log! "!ERROR timeout, expected in order %s, other %s"
                (pr-str expect-in-order) (pr-str other-expected))

          [nil events-chan]
          (log! "!ERROR log closed")

          [event events-chan]
          (if (= (first expect-in-order) event)
            (do
              (log! "expected in order %s" event)
              (recur (rest expect-in-order) other-expected timeout))
            (if (some #{event} other-expected )
              (do
                (log! "other expected %s" event)
                (recur
                  expect-in-order
                  (concat (take-while #(not= % event) other-expected)
                          (rest (drop-while #(not= % event) other-expected)))
                  timeout))
              (log! "!ERROR unexpected event %s, expected in order %s, other %s"
                event (pr-str expect-in-order) (pr-str other-expected)))))))))

(defn report-progress [events-chan msg]
  (if-not (async/put! events-chan (conj msg))
    (printf "!ERROR event after test is finished: %s%n" msg)))

(process/proc-defn child-proc
  [id log {:keys [exit-delay exit-failure] :as problem}]
  (report-progress log [id :process-start])
  (try
    (process/spawn-opt
      (process/proc-fn []
        (process/receive!
          [:EXIT _ reason] (report-progress log [id :exit-watcher reason])
          msg (printf "!!! unexpected message %s%n" msg)))
      {:link true
       :flags {:trap-exit true}
       :name (str "exit-watcher-" id)})
    (catch Exception e
      (report-progress log [id :exit-watcher :killed])))

  (process/receive!
    [:exit reason] (do
                     (report-progress log [id :exit-command reason])
                     (process/exit reason))
    [:EXIT _ reason] (do
                       (report-progress log [id :exit reason])
                       (if exit-delay
                         (async/<! (async/timeout exit-delay)))
                       (if exit-failure
                         (process/exit exit-failure)
                         (process/exit reason)))))

(defn start-child [{id :id} log problems]
  (report-progress log [id :start-fn])
  (let [{:keys [start-failure] :as problem} (first @problems)]
    (swap! problems rest)
    (if start-failure
      [:error start-failure]
      [:ok (process/spawn-opt
             child-proc [id log problem] {:link true
                                          :register id
                                          :flags {:trap-exit true}
                                          :name (str "child-" id)})])))

(defn ->expected-events [in-order unordered]
  {:in-order in-order
   :unordered unordered})

(defn expected-exits [children]
  (let [children (reverse children)]
    (->expected-events
      (->> children
           (filter #(not= :brutal-kill (:shutdown %)))
           (map #(vector (:id %) :exit :shutdown)))
      (map (fn [{:keys [id shutdown type] [current-problem & _] :problems}]
              (case shutdown
                :brutal-kill [id :exit-watcher :killed]
                (if (:exit-delay current-problem)
                  (if (= :supervisor type)
                    [id :exit-watcher :shutdown]
                    [id :exit-watcher :killed])
                  (if-let [reason (:exit-failure current-problem)]
                    [id :exit-watcher reason]
                    [id :exit-watcher :shutdown]))))
           children))))

(defn exit-command-events [{:keys [id reason]}]
  (->expected-events [[id :exit-command reason]]
                     [[id :exit-watcher reason]]))

(defn start-events [children]
  (->expected-events (map #(vector (:id %) :start-fn) children)
                     (map #(vector (:id %) :process-start) children)))

(defn concat-events [& events]
  (apply merge-with concat events))

(defn delete-child [children id]
  (remove #(= id (:id %)) children))

(defn get-child [children id]
  (some #(if (= id (:id %)) %) children))

(defn replace-child [children {id :id :as child}]
  (match (split-with #(not= id (:id %)) children)
    [before ([_ & after] :seq)] (concat before [child] after)))

(defn survive-exit? [{:keys [restart] :as _child} reason]
  (or (= :permanent restart)
      (and (= :transient restart)
           (not= :normal reason))))

(defn process-restart:one-for-one
  [id children restarts {:keys [intensity] :as test}]
  (let [child (update (get-child children id) :problems rest)
        [start-failures other-problems]
        (split-with :start-failure (:problems child))
        ;_ (printf "n: %s, start-failures: %s, other-problems: %s%n" (:n test) (pr-str start-failures) (pr-str other-problems))
        child (assoc child :problems other-problems)
        failures-count (count start-failures)
        extra-restarts-count (min failures-count (- intensity restarts))
        restarts (+ restarts failures-count)
        ;_ (printf "restarts %s%n" restarts)
        expected-restarts (->expected-events
                            (repeat (inc extra-restarts-count) [id :start-fn]) [])
        expected (concat-events expected-restarts
                                (->expected-events [] [[id :process-start]]))]
    (if (> restarts intensity)
      [:exit expected-restarts (delete-child children id)]
      [:ok expected restarts (replace-child children child)])))

(defn process-restart:one-for-all
  [id children restarts {:keys [intensity] :as test}]
  (let [expected (expected-exits (delete-child children id))
        children-left (filter #(survive-exit? % :shutdown) children)
        children-left (map #(update % :problems rest) children-left)]
    (loop [restarts restarts
           children-left children-left
           expected expected]
      (let [[started not-started]
            (split-with #(-> % :problems first :start-failure not) children-left)
            ;_ (printf "restarts: %s, started: %s, not-started: %s%n" restarts (pr-str started) (pr-str not-started))
            failed (first not-started)
            expected (concat-events
                       expected
                       (start-events started)
                       (->expected-events
                         (if-let [{id :id} failed] [[id :start-fn]] []) []))]
        (if (seq not-started)
          (let [restarts (inc restarts)]
            (if (> restarts intensity)
              [:exit expected started]
              (let [expected (concat-events expected (expected-exits started))
                    new-started (map #(update % :problems rest) started)
                    new-children
                    (concat new-started
                            (if failed [(update failed :problems rest)])
                            (rest not-started))]
                (recur restarts new-children expected))))
          [:ok expected restarts children-left])))))

(defn process-restart:rest-for-one
  [id children restarts {:keys [intensity] :as test}]
  (let [[running [child & rest-children]]
        (split-with #(not= (:id %) id) children)
        events (expected-exits rest-children)
        rest-children-left (filter #(survive-exit? % :shutdown) rest-children)
        to-start (cons child rest-children-left)
        to-start (map #(update % :problems rest) to-start)]
    (loop [restarts restarts
           to-start to-start
           running running
           events events]
      (let [[started [failed & not-started]]
            (split-with #(-> % :problems first :start-failure not) to-start)
            events (concat-events
                     events
                     (start-events started)
                     (->expected-events
                       (if-let [{id :id} failed] [[id :start-fn]] []) []))
            running (concat running started)
            failed (if failed (update failed :problems rest))
            to-start (if failed (cons failed not-started))]
        (if failed
          (let [restarts (inc restarts)]
            (if (> restarts intensity)
              [:exit events running]
              (recur restarts to-start running events)))
          [:ok events restarts running])))))

(defn execute-test-commands
  [await-events children {:keys [strategy exits intensity] :as test}]
  (async/go-loop
    [exits exits
     children children
     restarts 0]
    (if (or (empty? exits) (empty? children))
      children
      (let [process-restart (case strategy
                              :one-for-one process-restart:one-for-one
                              :one-for-all process-restart:one-for-all
                              :rest-for-one process-restart:rest-for-one)
            {:keys [id reason] :as exit} (first exits)
            expected (exit-command-events exit)]
        (! id [:exit reason])
        (if-not (survive-exit? (get-child children id) reason)
          (do
            (<! (await-events 5000 expected))
            (recur (rest exits) (delete-child children id) restarts))
          (let [restarts (inc restarts)]
            (if (> restarts intensity)
              (do
                (<! (await-events
                      5000
                      (concat-events
                        expected
                        (expected-exits (delete-child children id)))))
                [])
              (match (process-restart id children restarts test)
                [:exit expected-1 children-left]
                (do
                  (<! (await-events
                        5000
                        (concat-events expected
                                       expected-1
                                       (expected-exits children-left))))
                  [])
                [:ok expected-1 new-restarts children-left]
                (do
                  (<! (await-events
                        5000 (concat-events expected expected-1)))
                  (recur (rest exits) children-left new-restarts))))))))))

(defn run-test-process
  [events-chan log children {:keys [strategy intensity period] :as test}]
  (let [await-events (partial await-events events-chan log)
        sup-flags {:strategy strategy
                   :intensity intensity
                   :period period}
        add-start
        #(assoc % :start [start-child [% events-chan (atom (:problems %))]])
        children-spec (map add-start children)]
    (async/go
      (proc-util/execute-proc!
        (process/flag :trap-exit true)
        (match (sup/start-link (constantly [:ok [sup-flags children-spec]]))
          [:ok pid]
          (do
            (<! (await-events 5000 (start-events children)))
            (let [children-left
                  (<! (execute-test-commands await-events children test))]
              (process/exit pid :normal)
              (<! (await-events 5000 (expected-exits children-left)))))
          res
          (log! log "!ERROR %s" res))))))

(defn run-test [strategy {:keys [children problems] :as test}]
  (async/go
    (let [test (assoc test :strategy strategy)
          log (async/chan)
          events-chan (async/chan)
          problems-map (->> problems
                            (group-by :id)
                            (map-vals #(if (:start-failure (first %)) (cons {} %) %)))
          children (map #(assoc % :problems (problems-map (:id %))) children)]
      ;(printf "problems-map %s%n" problems-map)
      ;(printf "children: %s%n" (pr-str children))
      (try
        (log! log "RUN test%n%s" (clojure.pprint/write test :stream nil))
        (<! (run-test-process events-chan log children test))
        (log! log "DONE run-test")
        log
        (catch Throwable t
          (log! log "!ERROR %s" (pr-str t))
          log)
        (finally
          (async/close! events-chan)
          (async/close! log))))))

(defn gen-tests []
  (let [possible-exits [:normal :abnormal]
        exit-combinations
        (mapcat #(combinatorics/selections possible-exits %) [1])
        possible-problems
        (for [start-failure-reason [nil :abnormal]
              exit-delay (if start-failure-reason
                           [nil]
                           [nil 100])
              exit-failure-reason (if (or start-failure-reason exit-delay)
                                    [nil]
                                    [nil :normal :abnormal])]
          (cond
            start-failure-reason {:start-failure start-failure-reason}
            exit-delay {:exit-delay exit-delay}
            exit-failure-reason {:exit-failure exit-failure-reason}
            :else {}))
        problem-combinations
        (mapcat #(combinatorics/selections possible-problems %) [0 1])
        problem-combinations
        (filter #(or (empty? %) (not (every? empty? %))) problem-combinations)
        possible-children
        (for [restart-type [:permanent :transient :temporary]
              child-type [:worker :supervisor]
              shutdown (if (= child-type :supervisor)
                         [nil]
                         [:brutal-kill 50])]
          (merge {:restart restart-type
                  :type child-type}
                 (if shutdown
                   {:shutdown shutdown})))
        add-id
        (fn [children] (map #(assoc %1 :id %2) children (map inc (range))))
        children-combinations
        (mapcat #(combinatorics/selections possible-children %) [1 2 3])
        children-combinations
        (map add-id children-combinations)
        tests
        (for [intensity [0 1 2]
              period [1000]
              cc children-combinations
              problems problem-combinations
              exits exit-combinations
              problem-ids (combinatorics/selections (map :id cc) (count problems))
              exit-ids (combinatorics/selections (map :id cc) (count exits))]
          {:children cc
           :problems (map #(assoc %1 :id %2) problems problem-ids)
           :exits (map (fn [e id] {:reason e :id id}) exits exit-ids)
           :intensity intensity
           :period period})
        tests
        (map #(assoc %1 :n %2) tests (map inc (range)))
        tests
        (map (fn [t]
               (let [children (:children t)
                     ids (map :id children)
                     uids (->> ids (map #(str % "_")) (map gensym))
                     id-map (->> (map vector ids uids) (into {}))
                     update-id #(update % :id id-map)]
                 (-> t
                     (update :children #(map update-id %))
                     (update :problems #(map update-id %))
                     (update :exits #(map update-id %)))))
             tests)]
    ;(println (count possible-exits))
    ;(clojure.pprint/pprint possible-exits)
    ;(println (count exit-combinations))
    ;(clojure.pprint/pprint exit-combinations)
    ;(println (count possible-problems))
    ;(clojure.pprint/pprint possible-problems)
    ;(println (count problem-combinations))
    ;(clojure.pprint/pprint problem-combinations)
    ;(println (count possible-children))
    ;(println (count children-combinations))
    ;(println (count tests))
    ;(clojure.pprint/pprint (first tests))
    tests))

(defn test-one-for-one []
  (time
    (binding [*out* (clojure.java.io/writer "sup-test.log")]
      (<!!
        (async/go
          (let [tests (gen-tests)]
            (doseq [
                    ;tests (partition 8 8 [] [(nth tests (dec 51482))])
                    tests (partition 8 8 [] tests)
                    ;tests (partition 8 8 [] (take 20000 tests))
                    ]
              (doseq [res (doall (map (partial run-test :rest-for-one) tests))]
              ;(doseq [res (doall (map (partial run-test :one-for-all) tests))]
              ;(doseq [res (doall (map (partial run-test :one-for-one) tests))]
                (println "---")
                (let [log (async/<! res)]
                  (loop [x (async/<! log)]
                    (when-let [[ms fmt args] x]
                      (printf "log: %05d - %s%n" ms (apply format fmt args))
                      (recur (async/<! log)))))
                (flush)))))))))

;; ====================================================================
;; one-for-all

;; ====================================================================
;; rest-for-one

;; ====================================================================
;; exit message

;TODO check defaults

#_(do
    (otplike.proc-util/execute-proc!!
      (let [done0 (doto (async/chan) (async/close!))
            done1 (async/chan)
            done2 (async/chan)
            done3 (async/chan)
            buggy-child-name :3
            sup-flags {:period 200
                       ;:strategy :one-for-one
                       :strategy :one-for-all
                       ;:strategy :rest-for-one
                       }
            child (fn [await-chan done sname restart-type]
                    (let [server {:init (fn [_]
                                          (printf "server %s init %s%n" sname (rem (System/currentTimeMillis) 10000))
                                          (await-completion await-chan 50)
                                          (async/close! done)
                                          [:ok :state])
                                  :handle-call (fn [req _ state] [:stop :normal state])
                                  :terminate (fn [reason _state]
                                               (printf "server %s terminate %s, reason %s%n" sname (rem (System/currentTimeMillis) 10000) reason))}]
                      {:id sname
                       :start [#(gs/start-link sname server [] {}) []]
                       :restart restart-type}))
            children-spec [(child done0 done1 :1 :permanent)
                           (child done1 done2 :2 :permanent)
                           (child done2 done3 :3 :transient)]
            sup-spec [sup-flags children-spec]]
        (match (sup/start-link (fn [] [:ok sup-spec])) [:ok pid] :ok)
        (await-completion done3 100)
        (printf "server started %s%n" (rem (System/currentTimeMillis) 10000))
        (printf "call 1 %s%n" (rem (System/currentTimeMillis) 10000))
        (match (process/ex-catch (gs/call buggy-child-name 1)) [:EXIT _] :ok)
        (printf "call 1 done %s%n" (rem (System/currentTimeMillis) 10000))
        (<! (async/timeout 500))
        (printf "call 2 %s%n" (rem (System/currentTimeMillis) 10000))
        (match (process/ex-catch (gs/call buggy-child-name 1)) [:EXIT _] :ok)
        (printf "call 2 done %s%n" (rem (System/currentTimeMillis) 10000))
        (<! (async/timeout 500))))
    (<!! (async/timeout 1000)))


#_(let [trace-ch (trace/console-trace [trace/filter-crashed])]
    (otplike.proc-util/execute-proc!!
      (let [buggy-child-name :1
            sup-flags {:period 200 ;:strategy :one-for-one
                       :strategy :one-for-all
                       ;:strategy :rest-for-one
                       }
            child (fn [sname restart-type]
                    (let [server {:init (fn [_]
                                          (printf "server %s init %s%n" sname (rem (System/currentTimeMillis) 10000))
                                          [:ok :state])
                                  :handle-call (fn [req _ state] [:stop :normal state])
                                  :terminate (fn [reason _state]
                                               (printf "server %s terminate %s, reason %s%n" sname (rem (System/currentTimeMillis) 10000) reason))}]
                      {:id sname
                       :start [#(gs/start-link sname server [] {:flags {:trap-exit true}}) []]
                       :restart restart-type}))
            child1 (child :1 :permanent)
            child2 (child :2 :permanent)
            child3 (child :3 :transient)
            child4 (child :4 :transient)
            sup-spec [sup-flags []]]
        (match
          (sup/start-link (fn [] [:ok sup-spec]))
          [:ok pid]
          (do
            (printf "server started %s%n" (rem (System/currentTimeMillis) 10000))
            (match (sup/start-child pid child1) [:ok c1pid] :ok)
            (match (sup/start-child pid child2) [:ok c2pid] :ok)
            (match (sup/start-child pid child3) [:ok c3pid] :ok)
            (printf "call 1 %s%n" (rem (System/currentTimeMillis) 10000))
            (match (process/ex-catch (gs/call buggy-child-name 1)) [:EXIT _] :ok)
            (printf "call 1 done %s%n" (rem (System/currentTimeMillis) 10000))
            (<! (async/timeout 500))
            (match (sup/delete-child pid :1)
                   [:error reason] (printf "can't delete child :1 %s%n" reason))
            (match (sup/delete-child pid :4)
                   [:error reason] (printf "can't delete child :4 %s%n" reason))
            (match (sup/terminate-child pid :5)
                   [:error reason] (printf "can't terminate child :5 %s%n" reason))
            (match (sup/terminate-child pid :2)
                   :ok (printf "child :2 terminated%n"))
            (match (sup/terminate-child pid :2)
                   :ok (printf "child :2 terminated%n"))
            (match (sup/restart-child pid :5)
                   [:error reason] (printf "can't restart child :5 %s%n" reason))
            (match (sup/restart-child pid :3)
                   [:error reason] (printf "can't restart child :3 %s%n" reason))
            (match (sup/restart-child pid :2)
                   [:ok _] (printf "child :2 restarted%n"))
            (match (sup/terminate-child pid :2)
                   :ok (printf "child :2 terminated%n"))
            (match (sup/delete-child pid :2)
                   :ok (printf "child :2 deleted%n"))
            (match (sup/restart-child pid :2)
                   [:error reason] (printf "can't restart child :2 %s%n" reason))
            (match (sup/start-child pid child2) [:ok _] :ok)
            (match (sup/start-child pid child4) [:ok _] :ok)
            (printf "call 2 %s%n" (rem (System/currentTimeMillis) 10000))
            (match (process/ex-catch (gs/call buggy-child-name 1)) [:EXIT _] :ok)
            (printf "call 2 done %s%n" (rem (System/currentTimeMillis) 10000))
            (<! (async/timeout 500))))))
    (<!! (async/timeout 1000))
    (async/close! trace-ch))
