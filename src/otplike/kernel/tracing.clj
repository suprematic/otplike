(ns otplike.kernel.tracing)

(def ^:private ^:dynamic *context* nil)

(def ^:private context-resolver
  (atom (fn [] *context*)))

(defn resolve-context []
  (@context-resolver))

(defn set-context-resolver! [f]
  (reset! context-resolver f))

(defn with-context [context f]
  (binding [*context* context]
    (f)))

(defn context
  ([] *context*))

#_(context)






