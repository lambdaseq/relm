(ns com.lambdaseq.relm.reitit-test
  "Unit tests for Reitit routing integration with Relm."
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.navigation :as nav]
            [com.lambdaseq.relm.reitit :as relm.reitit]
            [reitit.core :as r]))

;; -----------------------------------------------------------------------------
;; Test Fixtures & Routes
;; -----------------------------------------------------------------------------

(def test-routes
  [["/" {:name :home
         :path "/"
         :view :home-view}]
   ["/users" {:name :users
              :path "/users"
              :view :users-view}]
   ["/user/:id" {:name :user
                 :path "/user/:id"
                 :view :user-view}]])

(def test-router
  (r/router test-routes))

;; -----------------------------------------------------------------------------
;; Route Matching & Helper Tests
;; -----------------------------------------------------------------------------

(deftest match-helpers-test
  (testing "match-by-path resolves correct match record by URL path"
    (let [m (relm.reitit/match-by-path test-router "/users")]
      (is (= :users (get-in m [:data :name])))
      (is (= "/users" (:path m)))))

  (testing "match-by-name resolves correct match record by route keyword and parameters"
    (let [m (relm.reitit/match-by-name test-router :user {:id "42"})]
      (is (= :user (get-in m [:data :name])))
      (is (= {:id "42"} (:path-params m)))
      (is (= "/user/42" (:path m)))))

  (testing "match-target with fallback default-path returns fallback match for unmatched route"
    (let [m (relm.reitit/match-target test-router "/unknown" nil nil "/users")]
      (is (= :users (get-in m [:data :name])))))

  (testing "path-for generates path with path params and serialized query params"
    (is (= "/users" (relm.reitit/path-for test-router :users)))
    (is (= "/user/100" (relm.reitit/path-for test-router :user {:id 100})))
    (is (= "/user/100?tab=details" (relm.reitit/path-for test-router :user {:id 100} {:tab "details"})))))

;; -----------------------------------------------------------------------------
;; Context Route Synchronization Tests
;; -----------------------------------------------------------------------------

(deftest context-helpers-test
  (testing "set-route-context and current-* helpers correctly extract route metadata"
    (let [match (relm.reitit/match-by-name test-router :user {:id "1"})
          ctx (relm.reitit/set-route-context {:theme :dark} match)]
      (is (= :user (relm.reitit/current-route ctx)))
      (is (= match (relm.reitit/current-match ctx)))
      (is (= :user-view (relm.reitit/current-view ctx)))
      (is (= :dark (:theme ctx))))))

;; -----------------------------------------------------------------------------
;; Relm Update Message Handler Tests
;; -----------------------------------------------------------------------------

(deftest update-events-test
  (testing "::navigate-to by path updates context route and emits push-state effect"
    (let [ctx {:router test-router}
          [new-state new-ctx effects] (relm/update {:my-state 1} ctx [::relm.reitit/navigate-to "/users"] nil)]
      (is (= {:my-state 1} new-state))
      (is (= :users (relm.reitit/current-route new-ctx)))
      (is (= [[::nav/push-state nil "/users"]] effects))))

  (testing "::navigate-to by route name and params updates context and emits parameterized push-state"
    (let [ctx {:router test-router}
          [new-state new-ctx effects] (relm/update {:my-state 1} ctx [::relm.reitit/navigate-to :user {:id "99"} {:query "abc"}] nil)]
      (is (= {:my-state 1} new-state))
      (is (= :user (relm.reitit/current-route new-ctx)))
      (is (= {:id "99"} (get-in new-ctx [:route :path-params])))
      (is (= [[::nav/push-state nil "/user/99?query=abc"]] effects))))

  (testing "::replace-to by route name updates context and emits replace-state effect"
    (let [ctx {:router test-router}
          [new-state new-ctx effects] (relm/update nil ctx [::relm.reitit/replace-to :user {:id "5"}] nil)]
      (is (= :user (relm.reitit/current-route new-ctx)))
      (is (= [[::nav/replace-state nil "/user/5"]] effects))))

  (testing "::route-changed updates context without emitting side effects"
    (let [ctx {:router test-router}
          [new-state new-ctx effects] (relm/update nil ctx [::relm.reitit/route-changed "/users"] nil)]
      (is (= :users (relm.reitit/current-route new-ctx)))
      (is (nil? effects)))))

;; -----------------------------------------------------------------------------
;; Router Initialization Tests
;; -----------------------------------------------------------------------------

(deftest start-router-test
  (testing "start! initializes router and registers current route in global application state"
    (let [match (relm.reitit/start! test-router {:default-path "/users"})]
      (is (some? match))
      (is (= test-router (:router (:context @relm/!app-state))))
      (is (some? (:route (:context @relm/!app-state)))))))
