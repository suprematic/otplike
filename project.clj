(def core-async-version "0.2.385")

(defproject otplike/otplike "0.2.0-alpha-SNAPSHOT"
  :description "Erlang/OTP like processes and behaviours on top of core.async"
  :license {:name "Eclipse Public License - v1.0"
            :url  "https://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async ~core-async-version]
                 [org.clojure/core.match "0.3.0-alpha4"]]

  :source-paths  ["src"]

  :profiles {:parallel-test
             {:dependencies [[org.clojure/core.async ~core-async-version]]}

             :repl
             {:source-paths  ["src" "examples"]}}

  :codox {:source-paths ["src"]}

  :test-selectors {:parallel :parallel
                   :serial :serial}

  :plugins [[lein-ancient "0.6.10"]])
