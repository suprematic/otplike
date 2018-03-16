(ns otplike.spec-util
  (:require [clojure.spec.alpha :as spec]))

; FIXME: workaround to ensure spec is reloaded after the namespace
; it is defined in has been reloaded.
(defn foreign-spec
  "When used to wrap a reference to a spec from another namespace,
  prevents the spec value from being captured, e.g. by spec/fdef
  or spec/spec."
  [x]
  (spec/spec #(spec/valid? x %)
             :gen #(spec/gen x)))

(defn instrument [f]
  (if-let [sym (resolve f)]
    (let [{:keys [args ret]} (spec/get-spec sym)]
      (when (nil? args)
        (throw (ex-info (str "no spec for arguments of " f) {:fn sym})))
      (when (nil? ret)
        (throw (ex-info (str "no spec for return of " f) {:fn sym})))
      (alter-var-root
        sym
        (fn [ofn]
          (fn [& params]
            (if-not (spec/valid? args params)
              (let [explain (spec/explain-data args params)]
                (throw (ex-info
                         (str "Call to " sym " did not conform to spec:\n"
                              (with-out-str (spec/explain-out explain)))
                         explain)))
              (let [return (apply ofn params)]
                (if-not (spec/valid? ret return)
                  (let [explain (spec/explain-data ret return)]
                    (throw (ex-info
                             (str "Value returned from " sym
                                  " did not conform to spec:\n"
                                  (with-out-str (spec/explain-out explain)))
                             explain)))
                  return)))))))
    (throw (ex-info (str "Undefined function: " f) {}))))
