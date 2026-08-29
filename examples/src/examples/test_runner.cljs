(ns examples.test-runner
  (:require [cljs.test :as test :refer-macros [run-tests]]
            [com.lambdaseq.relm.query-test]
            [com.lambdaseq.relm.form-test]
            [com.lambdaseq.relm.reitit-test]
            [com.lambdaseq.relm.core-test]))

(defn main []
  (test/run-tests
    'com.lambdaseq.relm.query-test
    'com.lambdaseq.relm.form-test
    'com.lambdaseq.relm.reitit-test
    'com.lambdaseq.relm.core-test))
