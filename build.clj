(ns build
  (:require
    [org.corfield.build :as cb]))

(def props
  {:lib 'otplike/otplike
   :version "0.7.0-SNAPSHOT"})

(defn jar [_]
  (cb/jar
    (merge props
      {:src-pom "maven/pom.xml"})))

(defn deploy [_]
  (cb/deploy props))