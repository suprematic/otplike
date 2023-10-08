(ns build
  (:require
   [clojure.tools.build.api :as b]))

(def lib 'otplike/otplike)
(def version "0.7.0")
(def class-dir "target/classes")

(defn jar [_]
  (b/delete {:path class-dir})

  (b/write-pom
   {:lib lib
    :basis
    (b/create-basis {:project "deps.edn"})
    :class-dir class-dir
    :version version
    :src-dirs ["src"]})

  (b/copy-dir
   {:src-dirs ["src" "resources"]
    :target-dir class-dir
    :ignores [#"^dep.*\.app\.edn$"]})

  (b/jar
   {:class-dir class-dir
    :jar-file
    (format "target/%s-%s.jar" (name lib) version)}))

#_(defn deploy [_]
    (cb/deploy props))