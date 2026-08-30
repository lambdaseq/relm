(ns relm.reitit-test
  "Unit tests for Reitit routing integration with Relm."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [relm.core :as relm]
            [relm.navigation :as nav]
            [relm.reitit :as relm.reitit]
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
  (testing "::start initializes router, options, and route in context and emits listen-history effect"
    (let [ctx {:existing-key 123}
          [new-state new-ctx effects] (relm/update nil ctx [::relm.reitit/start test-router {:default-path "/users"}] nil)]
      (is (nil? new-state))
      (is (= test-router (:router new-ctx)))
      (is (= {:default-path "/users" :dispatch-initial? true} (:router-options new-ctx)))
      (is (= "/users" (:default-path new-ctx)))
      (is (some? (:route new-ctx)))
      (is (= 123 (:existing-key new-ctx)))
      (is (= [[::relm.reitit/listen-history! {:router test-router :default-path "/users"}]] effects))))

  (testing "::stop removes router keys from context and emits unlisten-history effect"
    (let [ctx {:router test-router
               :router-options {:default-path "/users"}
               :default-path "/users"
               :route {:data {:name :users}}
               :current-route :users
               :keep-me "preserved"}
          [new-state new-ctx effects] (relm/update nil ctx [::relm.reitit/stop] nil)]
      (is (nil? new-state))
      (is (nil? (:router new-ctx)))
      (is (nil? (:router-options new-ctx)))
      (is (nil? (:default-path new-ctx)))
      (is (nil? (:route new-ctx)))
      (is (nil? (:current-route new-ctx)))
      (is (= "preserved" (:keep-me new-ctx)))
      (is (= [[::relm.reitit/unlisten-history!]] effects))))

  (testing "::set-router updates active router and options in context and emits listen-history effect"
    (let [ctx {:router test-router :router-options {:default-path "/"}}
          new-test-router (r/router [["/new" {:name :new-route}]])
          [new-state new-ctx effects] (relm/update nil ctx [::relm.reitit/set-router new-test-router {:default-path "/new"}] nil)]
      (is (nil? new-state))
      (is (= new-test-router (:router new-ctx)))
      (is (= "/new" (:default-path new-ctx)))
      (is (= [[::relm.reitit/listen-history! {:router new-test-router :default-path "/new"}]] effects))))

  (testing "::navigate-to by path updates context route and emits push-state effect"
    (let [ctx {:router test-router}
          [new-state new-ctx effects] (relm/update {:my-state 1} ctx [::relm.reitit/navigate-to "/users"] nil)]
      (is (= {:my-state 1} new-state))
      (is (= :users (relm.reitit/current-route new-ctx)))
      (is (= [[::nav/push-state! nil "/users"]] effects))))

  (testing "::navigate-to with fallback default-path in context router-options"
    (let [ctx {:router test-router :router-options {:default-path "/users"}}
          [new-state new-ctx effects] (relm/update nil ctx [::relm.reitit/navigate-to :unknown] nil)]
      (is (= :users (relm.reitit/current-route new-ctx)))
      (is (= [[::nav/push-state! nil "/users"]] effects))))

  (testing "::navigate-to by route name and params updates context and emits parameterized push-state"
    (let [ctx {:router test-router}
          [new-state new-ctx effects] (relm/update {:my-state 1} ctx [::relm.reitit/navigate-to :user {:id "99"} {:query "abc"}] nil)]
      (is (= {:my-state 1} new-state))
      (is (= :user (relm.reitit/current-route new-ctx)))
      (is (= {:id "99"} (get-in new-ctx [:route :path-params])))
      (is (= [[::nav/push-state! nil "/user/99?query=abc"]] effects))))

  (testing "::replace-to by route name updates context and emits replace-state effect"
    (let [ctx {:router test-router}
          [new-state new-ctx effects] (relm/update nil ctx [::relm.reitit/replace-to :user {:id "5"}] nil)]
      (is (= :user (relm.reitit/current-route new-ctx)))
      (is (= [[::nav/replace-state! nil "/user/5"]] effects))))

  (testing "::route-changed updates context without emitting side effects"
    (let [ctx {:router test-router}
          [new-state new-ctx effects] (relm/update nil ctx [::relm.reitit/route-changed "/users"] nil)]
      (is (= :users (relm.reitit/current-route new-ctx)))
      (is (nil? effects))))

  (testing "navigation and replace aliases delegate correctly"
    (let [ctx {:router test-router}
          [_ nav-ctx-1 nav-fx-1] (relm/update nil ctx [::relm.reitit/navigate "/users"] nil)
          [_ nav-ctx-2 nav-fx-2] (relm/update nil ctx [::relm.reitit/navigate-to-path "/users"] nil)
          [_ nav-ctx-3 nav-fx-3] (relm/update nil ctx [::relm.reitit/navigate-to-route :users] nil)
          [_ rep-ctx-1 rep-fx-1] (relm/update nil ctx [::relm.reitit/replace "/users"] nil)
          [_ rep-ctx-2 rep-fx-2] (relm/update nil ctx [::relm.reitit/replace-path "/users"] nil)
          [_ rep-ctx-3 rep-fx-3] (relm/update nil ctx [::relm.reitit/replace-route :users] nil)
          [_ set-ctx-1 set-fx-1] (relm/update nil ctx [::relm.reitit/set-route "/users"] nil)
          [_ start-ctx start-fx] (relm/update nil ctx [::relm.reitit/start! test-router] nil)
          [_ stop-ctx stop-fx]   (relm/update nil start-ctx [::relm.reitit/stop!] nil)]
      (is (= :users (relm.reitit/current-route nav-ctx-1)))
      (is (= [[::nav/push-state! nil "/users"]] nav-fx-1))
      (is (= :users (relm.reitit/current-route nav-ctx-2)))
      (is (= [[::nav/push-state! nil "/users"]] nav-fx-2))
      (is (= :users (relm.reitit/current-route nav-ctx-3)))
      (is (= [[::nav/push-state! nil "/users"]] nav-fx-3))
      (is (= :users (relm.reitit/current-route rep-ctx-1)))
      (is (= [[::nav/replace-state! nil "/users"]] rep-fx-1))
      (is (= :users (relm.reitit/current-route rep-ctx-2)))
      (is (= [[::nav/replace-state! nil "/users"]] rep-fx-2))
      (is (= :users (relm.reitit/current-route rep-ctx-3)))
      (is (= [[::nav/replace-state! nil "/users"]] rep-fx-3))
      (is (= :users (relm.reitit/current-route set-ctx-1)))
      (is (nil? set-fx-1))
      (is (= test-router (:router start-ctx)))
      (is (seq start-fx))
      (is (nil? (:router stop-ctx)))
      (is (seq stop-fx)))))

;; -----------------------------------------------------------------------------
;; Router Lifecycle Dispatch Tests
;; -----------------------------------------------------------------------------

(deftest start-and-stop-router-test
  (testing "::start message initializes router and registers current route in global application state"
    (relm/dispatch! nil [::relm.reitit/start test-router {:default-path "/users"}])
    (is (= test-router (:router (:context @relm/!app-state))))
    (is (some? (:route (:context @relm/!app-state)))))

  (testing "router helper extracts router from context and returns nil when absent"
    (is (= test-router (relm.reitit/router {:router test-router})))
    (is (nil? (relm.reitit/router {}))))

  (testing "::stop message cleans up router and route from global application state"
    (relm/dispatch! nil [::relm.reitit/stop])
    (is (nil? (:router (:context @relm/!app-state))))
    (is (nil? (:route (:context @relm/!app-state))))))
