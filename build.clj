(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib-modules
  {:core   {:lib 'com.lambdaseq/relm.core   :dir "core"}
   :form   {:lib 'com.lambdaseq/relm.form   :dir "form"}
   :query  {:lib 'com.lambdaseq/relm.query  :dir "query"}
   :reitit {:lib 'com.lambdaseq/relm.reitit :dir "reitit"}})

(def default-all-modules
  ["core" "form" "query" "reitit" "examples"])

(def internal-libs
  (into #{} (map :lib (vals lib-modules))))

(defn compute-version
  "Derives version from options or git tags.
   All modules share the same version and update in lockstep."
  [opts]
  (or (:version opts)
      (try
        (let [tag (b/git-process {:git-args ["describe" "--tags" "--always"]})]
          (when (and tag (seq (str/trim tag)))
            (let [v (str/replace (str/trim tag) #"^v" "")]
              (if (re-find #"^\d+\.\d+" v)
                v
                (str "0.1.0-" v)))))
        (catch Exception _ nil))
      "0.1.0-SNAPSHOT"))

(def version (compute-version {}))

(defn- resolve-lib-modules
  [opts]
  (let [mods (or (:modules opts) (:submodules opts) (keys lib-modules))]
    (mapv (fn [m]
            (let [k (if (keyword? m) m (keyword (str m)))]
              (or (get lib-modules k)
                  (throw (ex-info (str "Unknown library module: " m)
                                  {:available (keys lib-modules)})))))
          (if (coll? mods) mods [mods]))))

(defn- module-basis
  [dir ver]
  (let [raw-basis (b/create-basis {:dir dir :project "deps.edn"})]
    (reduce (fn [basis lib-name]
              (if (contains? (:libs basis) lib-name)
                (assoc-in basis [:libs lib-name] {:mvn/version ver})
                basis))
            raw-basis
            internal-libs)))

(defn- module-src-dirs
  [dir]
  (let [existing (filter #(-> (io/file dir %) .exists) ["src" "resources"])]
    (mapv #(str dir "/" %) (if (seq existing) existing ["src"]))))

(defn- jar-opts-for-module
  [{:keys [lib dir]} ver opts]
  (let [class-dir (str "target/" dir "/classes")
        jar-file  (format "target/%s-%s.jar" (name lib) ver)
        basis     (module-basis dir ver)
        src-dirs  (module-src-dirs dir)]
    (merge opts
           {:lib       lib
            :version   ver
            :jar-file  jar-file
            :scm       (merge {:url "https://github.com/lambdaseq/relm"
                               :tag (str "v" ver)}
                              (:scm opts))
            :basis     basis
            :class-dir class-dir
            :target    (str "target/" dir)
            :src-dirs  src-dirs})))

(defn clean
  "Deletes the target directory and build artifacts."
  [opts]
  (println "Cleaning target directory...")
  (b/delete {:path "target"})
  opts)

(defn test
  "Runs the test suite across modules."
  [opts]
  (println "\n=== Running ClojureScript test suite ===")
  (let [compile-res (b/process {:command-args ["clojure" "-M" "-m" "shadow.cljs.devtools.cli" "compile" "test"]
                                :dir "examples"})]
    (when-not (zero? (:exit compile-res))
      (throw (ex-info "Shadow-cljs test compilation failed" compile-res))))
  (let [run-res (b/process {:command-args ["node" "out/test.js"]
                            :dir "examples"})]
    (when-not (zero? (:exit run-res))
      (throw (ex-info "Tests failed" run-res))))
  opts)

(defn jar
  "Builds JARs for all library modules in lockstep version.
   Options:
     :modules - vector/list of modules to build (default: all library modules [:core :form :query :reitit])
     :version - optional version string override (default derived from git tags)"
  [opts]
  (let [ver (compute-version opts)
        targets (resolve-lib-modules opts)]
    (println (str "\nBuilding JARs for version: " ver))
    (doseq [mod targets]
      (let [{:keys [lib dir]} mod
            m-opts (jar-opts-for-module mod ver opts)
            {:keys [class-dir jar-file src-dirs]} m-opts]
        (println (str "\n--- Module: " lib " (" dir ") ---"))
        (println "Writing pom.xml...")
        (b/write-pom m-opts)
        (println "Copying sources...")
        (b/copy-dir {:src-dirs   src-dirs
                     :target-dir class-dir})
        (println (str "Building JAR: " jar-file "..."))
        (b/jar m-opts)))
    (println "\nJAR build complete.")
    opts))

(defn install
  "Installs module JARs to the local Maven repository.
   Options:
     :modules - vector/list of modules to install (default: all library modules)
     :version - optional version string override"
  [opts]
  (let [ver (compute-version opts)
        targets (resolve-lib-modules opts)]
    (println (str "\nInstalling JARs locally for version: " ver))
    (doseq [mod targets]
      (let [{:keys [lib dir]} mod
            m-opts (jar-opts-for-module mod ver opts)]
        (println (str "Installing " lib " (" (:jar-file m-opts) ")..."))
        (b/install m-opts)))
    (println "\nLocal installation complete.")
    opts))

(defn deploy
  "Deploys module JARs to Clojars.
   Options:
     :modules - vector/list of modules to deploy (default: all library modules)
     :version - optional version string override"
  [opts]
  (let [ver (compute-version opts)
        targets (resolve-lib-modules opts)]
    (println (str "\nDeploying JARs to Clojars for version: " ver))
    (doseq [mod targets]
      (let [{:keys [lib dir]} mod
            {:keys [jar-file class-dir] :as m-opts} (jar-opts-for-module mod ver opts)]
        (println (str "Deploying " lib " (" jar-file ")..."))
        (dd/deploy (merge {:installer :remote
                           :artifact  (b/resolve-path jar-file)
                           :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}
                          (dissoc opts :modules :submodules :version)))))
    (println "\nDeployment to Clojars complete.")
    opts))

(defn ci
  "Runs the CI pipeline: tests, clean, and builds JARs for all modules."
  [opts]
  (test opts)
  (clean opts)
  (jar opts)
  opts)

;; ----------------------------------------------------------------------------
;; Linting, Formatting, and Vulnerability Scanning
;; ----------------------------------------------------------------------------

(defn- resolve-tool-modules [opts]
  (or (:modules opts) (:submodules opts) default-all-modules))

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
  "Runs clj-kondo in submodules.
   Options:
     :modules - vector of submodules to run against (default: [\"core\" \"form\" \"query\" \"reitit\" \"examples\"])
     :args    - custom vector of CLI arguments to pass to clj-kondo"
  [opts]
  (let [modules (resolve-tool-modules opts)]
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
  "Runs cljfmt in submodules.
   Options:
     :modules - vector of submodules to run against (default: [\"core\" \"form\" \"query\" \"reitit\" \"examples\"])
     :mode    - \"check\" (default) or \"fix\"
     :fix     - boolean flag, if true runs \"fix\" instead of \"check\"
     :args    - custom vector of CLI arguments to pass to cljfmt"
  [opts]
  (let [modules (resolve-tool-modules opts)
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
  "Runs clj-watson vulnerability scan in submodules.
   Options:
     :modules - vector of submodules to run against (default: [\"core\" \"form\" \"query\" \"reitit\" \"examples\"])
     :args    - custom vector of CLI arguments to pass to clj-watson (default: [\"scan\" \"-p\" \"deps.edn\"])"
  [opts]
  (let [modules (resolve-tool-modules opts)]
    (run-in-submodules
     {:modules modules
      :tool-name "clj-watson"
      :command-args-fn
      (fn [_mod]
        (into ["clojure" "-M:clj-watson"]
              (or (:args opts) ["scan" "-p" "deps.edn"])))})))

(def clj-watson watson)

(defn all
  "Runs kondo, fmt, and watson in submodules."
  [opts]
  {:kondo  (kondo opts)
   :fmt    (fmt opts)
   :watson (watson opts)})

(def check all)
(def lint all)
