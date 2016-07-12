(defproject suprematic/otplike "0.0.1-SNAPSHOT"
  :description "Erlang/OTP like processes and behaviours based on core.async"
  :license {:name "Eclipse Public License - v 1.0"
            :url  "https://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.385"]
                 [org.clojure/core.match "0.3.0-alpha4"]]

  :plugins [[lein-ancient "0.6.10"]])
