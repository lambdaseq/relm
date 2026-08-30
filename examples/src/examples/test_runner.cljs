(ns examples.test-runner
  (:require [cljs.test :as test]
            [relm.core-test]
            [relm.form-test]
            [relm.query-test]
            [relm.reitit-test]))

(defn main []
  (test/run-tests
   'relm.query-test
   'relm.form-test
   'relm.reitit-test
   'relm.core-test))
