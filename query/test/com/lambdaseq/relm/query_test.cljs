(ns com.lambdaseq.relm.query-test
  "Comprehensive unit test suite for the Relm query module:
  - Vector key normalization and matching
  - Vector-to-URL and Reitit router inference
  - Pure context cache state reducers and view queries
  - Query fetch, caching, stale evaluation, and exponential backoff retries
  - Mutations, optimistic updates, rollback, and hierarchical invalidation"
  (:require [cljs.test :refer-macros [deftest is testing]]
            [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.http :as http]
            [com.lambdaseq.relm.query :as query]
            [reitit.core :as r]))

;; -----------------------------------------------------------------------------
;; Test Fixtures & Routes
;; -----------------------------------------------------------------------------

(def test-routes
  [["/users" {:name :users}]
   ["/users/:id" {:name :user}]
   ["/users/:id/posts" {:name :user-posts}]
   ["/todos" {:name :todos}]])

(def test-router
  (r/router test-routes))

;; -----------------------------------------------------------------------------
;; 1. Vector Key Normalization & Matching Tests
;; -----------------------------------------------------------------------------

(deftest key-normalization-test
  (testing "normalize-key wraps non-vector keys into vectors"
    (is (= [:todos] (query/normalize-key :todos)))
    (is (= ["todos"] (query/normalize-key "todos")))
    (is (= [123] (query/normalize-key 123)))
    (is (= [] (query/normalize-key nil)))
    (is (= [:users 1 :posts] (query/normalize-key [:users 1 :posts]))))

  (testing "key-match? checks normalized equality"
    (is (true? (query/key-match? :todos [:todos])))
    (is (true? (query/key-match? [:users 1] [:users 1])))
    (is (false? (query/key-match? [:users 1] [:users 2])))
    (is (true? (query/key-match? [:todos {:status "open"}] [:todos {:status "open"}]))))

  (testing "prefix-match? supports hierarchical matching"
    (is (true? (query/prefix-match? [:users] [:users])))
    (is (true? (query/prefix-match? [:users] [:users 1])))
    (is (true? (query/prefix-match? [:users] [:users 1 :posts])))
    (is (true? (query/prefix-match? [:users] [:users {:role "admin"}])))
    (is (true? (query/prefix-match? [:users 1] [:users 1 :posts])))
    (is (false? (query/prefix-match? [:users 1] [:users 2 :posts])))
    (is (false? (query/prefix-match? [:posts] [:users 1 :posts])))
    (is (false? (query/prefix-match? [:users 1 :posts :details] [:users 1])))))

;; -----------------------------------------------------------------------------
;; 2. URL and Request Inference Tests
;; -----------------------------------------------------------------------------

(deftest url-inference-test
  (testing "key->path-and-params converts vector segments into REST URL path"
    (is (= ["/todos" {}]
           (query/key->path-and-params [:todos])))
    (is (= ["/users/42/posts" {}]
           (query/key->path-and-params [:users 42 :posts])))
    (is (= ["/api/v1/items" {}]
           (query/key->path-and-params ["api" "v1" :items])))
    (is (= ["/todos" {:status "completed" :limit 10}]
           (query/key->path-and-params [:todos {:status "completed" :limit 10}]))))

  (testing "infer-request-from-key with standard vector key"
    (let [req (query/infer-request-from-key {} [:users 42 :posts {:page 2}] {:headers {"X-Custom" "1"}})]
      (is (= "/users/42/posts" (:url req)))
      (is (= {:page 2} (:params req)))
      (is (= :get (:method req)))
      (is (= :json (:request-content-type req)))
      (is (= {"X-Custom" "1"} (:headers req)))))

  (testing "infer-request-from-key with Reitit router matching"
    (let [ctx {:router test-router}
          req (query/infer-request-from-key ctx [:user {:id 99 :tab "info"}] {})]
      (is (= "/users/99" (:url req)))
      (is (= {:tab "info"} (:params req)))))

  (testing "infer-request-from-key respects explicit overrides"
    (let [req (query/infer-request-from-key {} [:todos] {:url "/custom/api"
                                                         :method :post
                                                         :params {:override true}
                                                         :body {:title "New"}})]
      (is (= "/custom/api" (:url req)))
      (is (= :post (:method req)))
      (is (= {:override true} (:params req)))
      (is (= {:title "New"} (:body req))))))

;; -----------------------------------------------------------------------------
;; 3. Context Cache Reducers and View Queries Tests
;; -----------------------------------------------------------------------------

(deftest cache-reducers-and-views-test
  (testing "set-query-loading and loading? / fetching? helpers"
    (let [ctx (query/set-query-loading {} [:todos])]
      (is (query/loading? ctx [:todos]))
      (is (query/fetching? ctx [:todos]))
      (is (= :loading (query/status ctx [:todos])))
      (is (nil? (query/data ctx [:todos])))))

  (testing "set-query-data stores payload and marks status success"
    (let [ctx (-> {}
                  (query/set-query-loading [:todos])
                  (query/set-query-data [:todos] [{:id 1 :title "Buy milk"}]))]
      (is (false? (query/loading? ctx [:todos])))
      (is (false? (query/fetching? ctx [:todos])))
      (is (= :success (query/status ctx [:todos])))
      (is (= [{:id 1 :title "Buy milk"}] (query/data ctx [:todos])))
      (is (false? (query/stale? ctx [:todos] 60000)))))

  (testing "background refetching keeps existing data while fetching? is true"
    (let [ctx (-> {}
                  (query/set-query-data [:todos] [{:id 1}])
                  (query/set-query-loading [:todos]))]
      (is (false? (query/loading? ctx [:todos])))
      (is (true? (query/fetching? ctx [:todos])))
      (is (= :success (query/status ctx [:todos])))
      (is (= [{:id 1}] (query/data ctx [:todos])))))

  (testing "set-query-error records error map"
    (let [ctx (-> {}
                  (query/set-query-loading [:todos])
                  (query/set-query-error [:todos] {:problem :server :status 500}))]
      (is (false? (query/loading? ctx [:todos])))
      (is (false? (query/fetching? ctx [:todos])))
      (is (= :error (query/status ctx [:todos])))
      (is (= {:problem :server :status 500} (query/error ctx [:todos])))))

  (testing "invalidate-query-keys marks matching queries stale hierarchically"
    (let [ctx (-> {}
                  (query/set-query-data [:users] [{:id 1}])
                  (query/set-query-data [:users 1] {:id 1 :name "Alice"})
                  (query/set-query-data [:users 2] {:id 2 :name "Bob"})
                  (query/set-query-data [:posts] [{:id 101}]))
          invalidated (query/invalidate-query-keys ctx [:users])]
      (is (true? (query/stale? invalidated [:users])))
      (is (true? (query/stale? invalidated [:users 1])))
      (is (true? (query/stale? invalidated [:users 2])))
      (is (false? (query/stale? invalidated [:posts] 60000))))))

;; -----------------------------------------------------------------------------
;; 4. Query Update Lifecycle and Retry Tests
;; -----------------------------------------------------------------------------

(deftest query-lifecycle-test
  (testing "::update on empty cache sets loading and emits http/fetch effect"
    (let [ctx {}
          [new-state new-ctx effects] (relm/update nil ctx [::query/update [:todos]] nil)]
      (is (query/loading? new-ctx [:todos]))
      (is (= 1 (count effects)))
      (let [[effect-type req] (first effects)]
        (is (= ::http/fetch effect-type))
        (is (= "/todos" (:url req)))
        (is (= :get (:method req)))
        (is (= [::query/fetch-success [:todos] {}] (:on-success req))))))

  (testing "::update on fresh cache returns cache hit without HTTP effect"
    (let [ctx (query/set-query-data {} [:todos] [{:id 1}] {:stale-time 60000})
          [_ new-ctx effects] (relm/update nil ctx [::query/update [:todos] {:stale-time 60000}] nil)]
      (is (= ctx new-ctx))
      (is (empty? effects))))

  (testing "::update with :force? true bypasses fresh cache"
    (let [ctx (query/set-query-data {} [:todos] [{:id 1}] {:stale-time 60000})
          [_ new-ctx effects] (relm/update nil ctx [::query/update [:todos] {:force? true}] nil)]
      (is (query/fetching? new-ctx [:todos]))
      (is (= 1 (count effects)))))

  (testing "::fetch-success updates cache data and sets status to success"
    (let [ctx (query/set-query-loading {} [:todos])
          response {:status 200 :ok? true :body [{:id 1 :title "Done"}]}
          [_ new-ctx _] (relm/update nil ctx [::query/fetch-success [:todos] {} response] nil)]
      (is (= :success (query/status new-ctx [:todos])))
      (is (= [{:id 1 :title "Done"}] (query/data new-ctx [:todos])))))

  (testing "::fetch-failure with retry attempts schedules retry-timer"
    (let [ctx (query/set-query-loading {} [:todos] {:retry 3})
          error-resp {:problem :fetch :problem-message "Network failed"}
          [_ new-ctx effects] (relm/update nil ctx [::query/fetch-failure [:todos] 0 {:retry 3} error-resp] nil)]
      (is (= 1 (get-in new-ctx [:queries [:todos] :retry-count])))
      (is (= 1 (count effects)))
      (let [[effect-type timer-payload] (first effects)]
        (is (= ::query/retry-timer effect-type))
        (is (= 1000 (:ms timer-payload)))
        (is (= [::query/retry [:todos] 1 {:retry 3}] (:message timer-payload))))))

  (testing "::fetch-failure when retries are exhausted sets status to error"
    (let [ctx (query/set-query-loading {} [:todos] {:retry 3})
          error-resp {:problem :server :status 500}
          [_ new-ctx effects] (relm/update nil ctx [::query/fetch-failure [:todos] 3 {:retry 3} error-resp] nil)]
      (is (= :error (query/status new-ctx [:todos])))
      (is (= error-resp (query/error new-ctx [:todos])))
      (is (empty? effects))))

  (testing "::fetch alias behaves like ::update"
    (let [ctx {}
          [_ new-ctx effects] (relm/update nil ctx [::query/fetch [:todos]] nil)]
      (is (query/loading? new-ctx [:todos]))
      (is (= 1 (count effects)))))

  (testing "calculate-retry-delay exponential backoff"
    (is (= 1000 (query/calculate-retry-delay 0)))
    (is (= 2000 (query/calculate-retry-delay 1)))
    (is (= 4000 (query/calculate-retry-delay 2)))
    (is (= 8000 (query/calculate-retry-delay 3)))
    (is (= 30000 (query/calculate-retry-delay 10))))

  (testing "::set-query-data updates query state manually"
    (let [ctx {}
          [_ new-ctx _] (relm/update nil ctx [::query/set-query-data [:todos] [{:id 99}]] nil)]
      (is (= [{:id 99}] (query/data new-ctx [:todos])))))

  (testing "::invalidate marks queries stale and optionally refetches"
    (let [ctx (-> {}
                  (query/set-query-data [:users] [{:id 1}])
                  (query/set-query-data [:users 1] {:id 1})
                  (query/set-query-data [:posts] [{:id 100}]))
          [_ ctx-no-refetch effects-none] (relm/update nil ctx [::query/invalidate [:users] {:refetch-active? false}] nil)
          [_ ctx-refetch effects-refetch] (relm/update nil ctx [::query/invalidate [:users] {:refetch-active? true}] nil)]
      (is (true? (query/stale? ctx-no-refetch [:users])))
      (is (true? (query/stale? ctx-no-refetch [:users 1])))
      (is (false? (query/stale? ctx-no-refetch [:posts] 60000)))
      (is (empty? effects-none))
      (is (= 2 (count effects-refetch))))))

;; -----------------------------------------------------------------------------
;; 5. Mutation Lifecycle, Optimistic Updates, and Invalidation Tests
;; -----------------------------------------------------------------------------

(deftest mutation-lifecycle-test
  (testing "::mutate updates mutation state and emits http/fetch"
    (let [ctx {}
          [_ new-ctx effects] (relm/update nil ctx [::query/mutate :create-todo {:url "/todos"
                                                                                 :variables {:title "New Todo"}}] nil)]
      (is (query/mutation-loading? new-ctx :create-todo))
      (is (= 1 (count effects)))
      (let [[effect-type req] (first effects)]
        (is (= ::http/fetch effect-type))
        (is (= "/todos" (:url req)))
        (is (= :post (:method req)))
        (is (= {:title "New Todo"} (:body req))))))

  (testing "::mutate with optimistic updates updates context immediately"
    (let [ctx (query/set-query-data {} [:todos] [{:id 1 :title "Existing"}])
          opt-fn (fn [vars current-ctx]
                   {:rollback-context current-ctx
                    :optimistic-context (update-in current-ctx [:queries [:todos] :data] conj vars)})
          [_ new-ctx _] (relm/update nil ctx [::query/mutate :add-todo {:url "/todos"
                                                                        :variables {:id 2 :title "Optimistic"}
                                                                        :on-mutate opt-fn}] nil)]
      (is (= [{:id 1 :title "Existing"} {:id 2 :title "Optimistic"}]
             (query/data new-ctx [:todos])))))

  (testing "::mutate-failure rolls back optimistic update on error"
    (let [original-ctx (query/set-query-data {} [:todos] [{:id 1 :title "Existing"}])
          error-resp {:problem :server :status 500}
          error-called? (atom false)
          settled-called? (atom false)
          opts {:on-error (fn [err] (reset! error-called? true))
                :on-settled (fn [data err resp] (reset! settled-called? true))}
          [_ rolled-back-ctx _] (relm/update nil {} [::query/mutate-failure :add-todo original-ctx opts error-resp] nil)]
      (is (= [{:id 1 :title "Existing"}] (query/data rolled-back-ctx [:todos])))
      (is (= :error (get-in rolled-back-ctx [:mutations :add-todo :status])))
      (is (= error-resp (query/mutation-error rolled-back-ctx :add-todo)))
      (is (true? @error-called?))
      (is (true? @settled-called?))))

  (testing "::mutate-success invalidates matching queries and triggers refetches"
    (let [ctx (-> {}
                  (query/set-query-data [:todos] [{:id 1}])
                  (query/set-query-data [:todos 1] {:id 1}))
          response {:status 201 :ok? true :body {:id 2 :title "Created"}}
          [_ new-ctx effects] (relm/update nil ctx [::query/mutate-success :add-todo {:invalidate [[:todos]]} response] nil)]
      (is (= :success (get-in new-ctx [:mutations :add-todo :status])))
      (is (= {:id 2 :title "Created"} (query/mutation-data new-ctx :add-todo)))
      (is (true? (query/stale? new-ctx [:todos])))
      (is (true? (query/stale? new-ctx [:todos 1])))
      (is (= 2 (count effects))))))
