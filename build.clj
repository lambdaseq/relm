(ns build
  (:require [clojure.java.io :as io]
            [clojure.tools.build.api :as b]))

(def default-modules
  ["core" "form" "query" "reitit" "examples"])

(defn- resolve-modules [opts]
  (or (:modules opts) (:submodules opts) default-modules))

(defn- run-in-submodules
  [{:keys [modules tool-name command-args-fn]}]
  (let [results (reduce (fn [acc mod]
                          (println (str "\n=== Running " tool-name " in " mod " ==="))
                          (let [args (command-args-fn mod)
                                res  (b/process {:command-args args
                                                 :dir mod})]
                            (conj acc (assoc res :module mod))))
                        []
                        modules)
        failures (filter #(not (zero? (:exit %))) results)]
    (when (seq failures)
      (println (str "\n[" tool-name "] Completed with non-zero exit code in: "
                    (mapv :module failures))))
    results))

(defn kondo
  "Runs clj-kondo in all submodules.
   Options:
     :modules - vector of submodules to run against (default: [\"core\" \"form\" \"query\" \"reitit\" \"examples\"])
     :args    - custom vector of CLI arguments to pass to clj-kondo"
  [opts]
  (let [modules (resolve-modules opts)]
    (run-in-submodules
     {:modules modules
      :tool-name "clj-kondo"
      :command-args-fn
      (fn [mod]
        (if-let [args (:args opts)]
          (into ["clojure" "-M:clj-kondo"] args)
          (let [existing-dirs (filter #(-> (io/file mod %) .exists) ["src" "test"])]
            (into ["clojure" "-M:clj-kondo" "--lint"] (if (seq existing-dirs) existing-dirs ["."])))))})))

(def clj-kondo kondo)

(defn fmt
  "Runs cljfmt in all submodules.
   Options:
     :modules - vector of submodules to run against (default: [\"core\" \"form\" \"query\" \"reitit\" \"examples\"])
     :mode    - \"check\" (default) or \"fix\"
     :fix     - boolean flag, if true runs \"fix\" instead of \"check\"
     :args    - custom vector of CLI arguments to pass to cljfmt"
  [opts]
  (let [modules (resolve-modules opts)
        mode (cond
               (:args opts) nil
               (:fix opts)  "fix"
               (:mode opts) (name (:mode opts))
               :else        "check")]
    (run-in-submodules
     {:modules modules
      :tool-name "cljfmt"
      :command-args-fn
      (fn [_mod]
        (if-let [args (:args opts)]
          (into ["clojure" "-M:cljfmt"] args)
          ["clojure" "-M:cljfmt" mode]))})))

(def cljfmt fmt)

(defn watson
  "Runs clj-watson vulnerability scan in all submodules.
   Options:
     :modules - vector of submodules to run against (default: [\"core\" \"form\" \"query\" \"reitit\" \"examples\"])
     :args    - custom vector of CLI arguments to pass to clj-watson (default: [\"scan\" \"-p\" \"deps.edn\"])"
  [opts]
  (let [modules (resolve-modules opts)]
    (run-in-submodules
     {:modules modules
      :tool-name "clj-watson"
      :command-args-fn
      (fn [_mod]
        (into ["clojure" "-M:clj-watson"]
              (or (:args opts) ["scan" "-p" "deps.edn"])))})))

(def clj-watson watson)

(defn all
  "Runs kondo, fmt, and watson in all submodules."
  [opts]
  {:kondo  (kondo opts)
   :fmt    (fmt opts)
   :watson (watson opts)})

(def check all)
(def lint all)
