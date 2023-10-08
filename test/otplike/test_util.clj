(ns otplike.test-util
  (:require [clojure.core.async :as async]
            [clojure.test :as clojure-test]
            [clojure.core.match :refer [match]]
            [otplike.proc-util :as proc-util]
            [otplike.process :as process]))

(defmacro clojure-minor-version []
  (let [[_ minor] (re-find #"^1\.(\d+)" (clojure-version))]
    (Integer/parseInt minor)))

(defmacro if-clojure-version [minor-versions & body]
  (if ((set minor-versions) (clojure-minor-version))
    `(do ~@body)))

(defn uuid-keyword
  "Makes random keyword with a name being UUID string."
  []
  (keyword (str (java.util.UUID/randomUUID))))

(defn await-message
  "Tries to receive message. Returns:
    - :timeout if no message appears during timeout-ms
    - [:exit [pid reason]] if :EXIT message received
    - [:down [ref object reason]] if :DOWN message received
    - [:message message] on any other message
    - [:noproc reason] if inbox becomes closed before message
      appears"
  [timeout-ms]
  (async/go
    (try
      (process/receive!
       [:EXIT pid reason] [:exit [pid reason]]
       [:DOWN ref :process object reason] [:down [ref object reason]]
       msg [:message msg]
       (after timeout-ms
              :timeout))
      (catch Exception e
        [:noproc (process/ex->reason e)]))))

(defmacro await-completion* [park? chan timeout-ms]
  `(let [timeout# (async/timeout ~timeout-ms)]
     (match (~(if park? `async/alts! `async/alts!!) [~chan timeout#])
       [nil ~chan] :closed
       [value# ~chan] [:ok value#]
       [nil timeout#] (throw (Exception. (str "timeout " ~timeout-ms))))))

(defmacro await-completion!
  "Returns:
    - [:ok val] if chan gets any value during timeout-ms,
    - :closed if chan becomes closed during timeout-ms.
  Otherwise throws."
  [chan timeout-ms]
  `(await-completion* true ~chan ~timeout-ms))

(defn await-completion!!
  "The same as await-completion! but blocks."
  [chan timeout-ms]
  (await-completion* false chan timeout-ms))

(defmacro def-proc-test [name & body]
  `(clojure-test/deftest ~name
     (proc-util/execute-proc!! ~@body)))

(defmacro matches? [what to]
  `(match ~what ~to true _# false))

(defn sym-bound? [sym]
  (if-let [v (resolve sym)]
    (bound? v)))
