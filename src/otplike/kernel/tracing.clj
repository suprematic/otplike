(ns otplike.kernel.tracing)

(def ^:private ^:dynamic *context* nil)

(defn- default-resolver []
  *context*)

(def ^:private context-resolver
  (atom default-resolver))

(defn resolve-context []
  (@context-resolver))

(defn set-context-resolver! [f]
  (reset! context-resolver f))

(defn reset-context-resolver! []
  (reset! context-resolver default-resolver))

(defn with-context [context f]
  (binding [*context* context]
    (f)))

(defn context
  ([] *context*))

#_(context)






