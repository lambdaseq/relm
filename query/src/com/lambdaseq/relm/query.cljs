(ns com.lambdaseq.relm.query
  "TanStack Query-style declarative server-state management for Relm applications.

  Provides:
  - Vector query key normalization and hierarchical matching
  - Automatic URL and query parameter inference from vector keys (with optional Reitit integration)
  - Declarative Relm `update` handlers for queries (`::update`, `::fetch`), mutations (`::mutate`), and invalidation (`::invalidate`)
  - Pure context cache state reducers and Hiccup view query helpers
  - Automatic stale detection, configurable retries with exponential backoff, and optimistic mutations"
  (:require [clojure.string :as string]
            [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.http :as http]
            [reitit.core :as r]))

;; -----------------------------------------------------------------------------
;; Utilities & Timestamps
;; -----------------------------------------------------------------------------

(defn now-ms
  "Returns the current timestamp in milliseconds."
  []
  (if (exists? js/Date.now)
    (js/Date.now)
    (.getTime (js/Date.))))

(defn calculate-retry-delay
  "Calculates exponential backoff delay in milliseconds for the given attempt number.
  Formula: `(min (* 1000 (Math/pow 2 attempt)) 30000)`."
  [attempt]
  (min (* 1000 (Math/pow 2 (or attempt 0))) 30000))

;; -----------------------------------------------------------------------------
;; Key Normalization & Matching
;; -----------------------------------------------------------------------------

(defn normalize-key
  "Normalizes a query key into a standard vector representation.
  If `key` is already a vector, returns `key`. Otherwise, wraps `key` in `[key]`."
  [key]
  (cond
    (vector? key) key
    (nil? key)    []
    :else         [key]))

(defn key-match?
  "Returns true if two query keys match identically after normalization."
  [k1 k2]
  (= (normalize-key k1) (normalize-key k2)))

(defn prefix-match?
  "Returns true if `prefix` is a prefix of `key` after normalization.
  Supports hierarchical key invalidation (e.g. `[:users]` matches `[:users]`, `[:users 1]`, and `[:users {:role \"admin\"}]`)."
  [prefix key]
  (let [norm-prefix (normalize-key prefix)
        norm-key    (normalize-key key)
        prefix-cnt  (count norm-prefix)]
    (and (<= prefix-cnt (count norm-key))
         (= norm-prefix (subvec norm-key 0 prefix-cnt)))))

;; -----------------------------------------------------------------------------
;; Vector Key-to-URL and Request Inference
;; -----------------------------------------------------------------------------

(defn- segment->str
  "Converts a single key path segment to a URL string segment."
  [segment]
  (cond
    (keyword? segment) (name segment)
    (nil? segment)     ""
    :else              (str segment)))

(defn key->path-and-params
  "Deconstructs a normalized query key vector into a REST path string and a query parameters map.
  Leading keyword/string/number elements form `/`-separated path segments.
  If the last element is a map, it is treated as query parameters.

  Examples:
    `[:todos]` -> `[\"/todos\" {}]`
    `[:users 42 :posts]` -> `[\"/users/42/posts\" {}]`
    `[:todos {:status \"completed\" :limit 10}]` -> `[\"/todos\" {:status \"completed\" :limit 10}]`"
  [key]
  (let [norm-key (normalize-key key)
        has-params? (and (seq norm-key) (map? (peek norm-key)))
        path-segments (if has-params? (pop norm-key) norm-key)
        params (if has-params? (peek norm-key) {})]
    (if (empty? path-segments)
      ["/" params]
      (let [raw-path (string/join "/" (map segment->str path-segments))
            path (if (string/starts-with? raw-path "/")
                   raw-path
                   (str "/" raw-path))]
        [path params]))))

(defn- extract-path-param-keys
  "Extracts path parameter keywords from a route path template (e.g. `\"/users/:id\"` -> `#{:id}`)."
  [template]
  (when (string? template)
    (->> (re-seq #":([a-zA-Z0-9_-]+)" template)
         (map (fn [[_ k]] (keyword k)))
         set)))

(defn- join-base-url
  "Prepends `base-url` to a `path`, trimming duplicate boundary slashes."
  [base-url path]
  (if (string/blank? base-url)
    path
    (let [base (string/replace base-url #"/+$" "")
          rel-path (if (string/starts-with? (or path "") "/") path (str "/" path))]
      (str base rel-path))))

(defn infer-request-from-key
  "Infers a complete HTTP request map from a query key and options map.

  Resolution strategy:
  1. If `context` contains a Reitit router (`:router`), and the first element of `key`
     matches a registered route name, Reitit path resolution is used.
  2. Otherwise, vector elements are joined into a `/`-separated REST URL path, and any trailing map is used as query parameters.
  3. If `:base-url` is specified in `opts` (or in `context`), it is prepended to the inferred path unless an explicit `:url` is provided.
  4. Explicit options in `opts` (`:url`, `:params`, `:headers`, `:method`, `:data`, `:body`, etc.) override or merge with inferred values."
  [context key opts]
  (let [norm-key (normalize-key key)
        first-elem (first norm-key)
        trailing-map (when (and (seq norm-key) (map? (peek norm-key))) (peek norm-key))
        router (:router context)
        raw-match (when (and router (keyword? first-elem))
                    (r/match-by-name router first-elem))
        [inferred-path inferred-params]
        (if raw-match
          (let [template (or (:template raw-match)
                             (get-in raw-match [:data :path])
                             (:path raw-match))
                path-keys (or (extract-path-param-keys template) #{})
                path-params (select-keys (or trailing-map {}) path-keys)
                query-params (apply dissoc (or trailing-map {}) path-keys)
                final-match (if (seq path-params)
                              (r/match-by-name router first-elem path-params)
                              raw-match)]
            [(:path final-match) query-params])
          (key->path-and-params norm-key))

        base-url      (or (:base-url opts) (get-in context [:query :base-url]) (:base-url context))
        inferred-url  (if (and base-url inferred-path)
                        (join-base-url base-url inferred-path)
                        inferred-path)
        merged-url    (or (:url opts) inferred-url)
        merged-params (merge (or inferred-params {}) (:params opts))
        merged-method (or (:method opts) :get)
        body-payload  (or (:data opts) (:body opts) (:variables opts))]
    (cond-> (assoc opts
              :url merged-url
              :method merged-method)
      (seq merged-params) (assoc :params merged-params)
      (nil? (:request-content-type opts)) (assoc :request-content-type :json)
      (some? body-payload) (assoc :body body-payload))))

;; -----------------------------------------------------------------------------
;; Context Cache State Reducers (Pure Functional)
;; -----------------------------------------------------------------------------

(defn get-query
  "Retrieves the query cache entry map for `key` from `context`."
  [context key]
  (get-in context [:queries (normalize-key key)]))

(defn set-query-loading
  "Pure reducer: marks the query for `key` as loading / fetching in `context`."
  [context key & [opts]]
  (let [norm-key (normalize-key key)
        existing (get-in context [:queries norm-key])
        has-data? (some? (:data existing))]
    (update-in context [:queries norm-key]
               (fn [q]
                 (merge (or q {:data nil
                               :fetch-count 0
                               :retry-count 0})
                        {:status       (if has-data? (or (:status q) :success) :loading)
                         :is-loading?  (not has-data?)
                         :is-fetching? true
                         :stale?       false
                         :options      (merge (:options q) opts)})))))

(defn set-query-data
  "Pure reducer: stores fetched `data` for `key` in `context` and marks query status `:success`.
  `data` can be a value or an updater function `(fn [prev-data] ...)`."
  [context key data & [opts]]
  (let [norm-key (normalize-key key)]
    (update-in context [:queries norm-key]
               (fn [q]
                 (let [prev-data (:data q)
                       new-data  (if (fn? data)
                                   (data prev-data)
                                   data)]
                   (merge (or q {})
                          {:data         new-data
                           :status       :success
                           :is-loading?  false
                           :is-fetching? false
                           :stale?       false
                           :error        nil
                           :updated-at   (now-ms)
                           :fetch-count  (inc (or (:fetch-count q) 0))
                           :retry-count  0
                           :options      (merge (:options q) opts)}))))))

(defn set-query-error
  "Pure reducer: records `error` for `key` in `context` and marks query status `:error`."
  [context key error & [opts]]
  (let [norm-key (normalize-key key)]
    (update-in context [:queries norm-key]
               (fn [q]
                 (merge (or q {})
                        {:status       :error
                         :is-loading?  false
                         :is-fetching? false
                         :error        error
                         :options      (merge (:options q) opts)})))))

(defn invalidate-query-keys
  "Pure reducer: marks all queries matching `key-prefix` (or matching a custom predicate) as stale.

  Options:
  - `:predicate` Optional `(fn [key query])` predicate function for custom filtering."
  [context key-prefix & [opts]]
  (let [pred (or (:predicate opts)
                 (fn [k _q] (prefix-match? key-prefix k)))]
    (update context :queries
            (fn [queries]
              (reduce-kv
                (fn [acc k q]
                  (if (pred k q)
                    (assoc acc k (assoc q :stale? true))
                    (assoc acc k q)))
                {}
                (or queries {}))))))

(defn get-mutation
  "Retrieves the mutation entry map for `mutation-key` from `context`."
  [context mutation-key]
  (or (get-in context [:mutations mutation-key])
      (get-in context [:mutations (normalize-key mutation-key)])
      (when (and (vector? mutation-key) (seq mutation-key))
        (get-in context [:mutations (first mutation-key)]))))

(defn set-mutation-state
  "Pure reducer: updates the state map for `mutation-key` under `:mutations` in `context`."
  [context mutation-key state-map]
  (assoc-in context [:mutations mutation-key] state-map))

;; -----------------------------------------------------------------------------
;; View Queries (Context Inspectors)
;; -----------------------------------------------------------------------------

(defn data
  "Returns cached data for `key` in `context`, or `default-val` (defaults to nil)."
  ([context key]
   (data context key nil))
  ([context key default-val]
   (let [q (get-query context key)]
     (if (some? (:data q))
       (:data q)
       default-val))))

(defn loading?
  "Returns true if the query for `key` is currently performing its initial data load."
  [context key]
  (let [q (get-query context key)]
    (boolean (or (:is-loading? q)
                 (and (= :loading (:status q))
                      (nil? (:data q)))))))

(defn fetching?
  "Returns true if the query for `key` is in-flight (initial fetch or background refetch)."
  [context key]
  (let [q (get-query context key)]
    (boolean (:is-fetching? q))))

(defn error
  "Returns the error payload for `key` in `context`, or nil if no error."
  [context key]
  (:error (get-query context key)))

(defn status
  "Returns the status keyword (`:idle`, `:loading`, `:success`, `:error`) for `key` in `context`."
  [context key]
  (or (:status (get-query context key)) :idle))

(defn stale?
  "Returns true if the query for `key` is stale or has exceeded its `stale-time` (ms).
  If `stale-time` is omitted, checks `:stale-time` in query options (defaults to 0)."
  ([context key]
   (stale? context key nil))
  ([context key custom-stale-time]
   (let [q (get-query context key)]
     (if-not q
       true
       (if (:stale? q)
         true
         (let [updated-at (:updated-at q)
               stale-time (or custom-stale-time (get-in q [:options :stale-time]) 0)]
           (if-not updated-at
             true
             (> (- (now-ms) updated-at) stale-time))))))))

(defn mutation
  "Returns the mutation map for `mutation-key` from `context`."
  [context mutation-key]
  (get-mutation context mutation-key))

(defn mutation-loading?
  "Returns true if the mutation for `mutation-key` is currently in-flight."
  [context mutation-key]
  (let [m (get-mutation context mutation-key)]
    (boolean (or (:is-loading? m)
                 (= :loading (:status m))))))

(defn mutation-error
  "Returns the error payload for `mutation-key` from `context`."
  [context mutation-key]
  (:error (get-mutation context mutation-key)))

(defn mutation-data
  "Returns the response data payload for `mutation-key` from `context`."
  [context mutation-key]
  (:data (get-mutation context mutation-key)))

;; -----------------------------------------------------------------------------
;; Relm Update Message Handlers
;; -----------------------------------------------------------------------------

;; Query fetching handler: [::update key opts?] or [::update key]
(defmethod relm/update ::update
  [state context [_ key opts] _event]
  (let [norm-key   (normalize-key key)
        opts       (or opts {})
        force?     (:force? opts)
        stale-time (:stale-time opts)
        q          (get-query context norm-key)
        is-fresh?  (and (not force?)
                        (= :success (:status q))
                        (some? (:data q))
                        (not (stale? context norm-key stale-time)))]
    (if is-fresh?
      ;; Return cache hit immediately without issuing HTTP fetch
      [state context]
      ;; Mark loading and emit HTTP fetch effect
      (let [new-context (set-query-loading context norm-key opts)
            http-req    (infer-request-from-key
                          new-context norm-key
                          (assoc opts
                            :on-success [::fetch-success norm-key opts]
                            :on-failure [::fetch-failure norm-key 0 opts]))]
        [state new-context [[::http/fetch http-req]]]))))

;; Alias `::fetch` to `::update`
(defmethod relm/update ::fetch
  [state context message event]
  (let [[_ key opts] message]
    (relm/update state context [::update key opts] event)))

;; Query fetch success handler: [::fetch-success norm-key opts response]
(defmethod relm/update ::fetch-success
  [state context [_ norm-key opts response] _event]
  (let [data (or (:body response) response)
        new-context (set-query-data context norm-key data opts)
        on-success (:on-success opts)]
    [state new-context (if (seq on-success) [[:dispatch on-success]] [])]))

;; Query fetch failure handler: [::fetch-failure norm-key attempt opts response]
(defmethod relm/update ::fetch-failure
  [state context [_ norm-key attempt opts response] _event]
  (let [max-retry (if (false? (:retry opts)) 0 (or (:retry opts) 3))
        attempt   (or attempt 0)]
    (if (< attempt max-retry)
      ;; TanStack-style exponential backoff retry
      (let [delay-ms (calculate-retry-delay attempt)
            retry-msg [::retry norm-key (inc attempt) opts]
            ;; Update retry count in context
            new-context (update-in context [:queries norm-key] assoc :retry-count (inc attempt))]
        [state new-context [[:dispatch-later {:ms delay-ms :dispatch retry-msg}]]])
      ;; Exceeded max retries: record final error state
      (let [new-context (set-query-error context norm-key response opts)
            on-error    (:on-error opts)]
        [state new-context (if (seq on-error) [[:dispatch on-error]] [])]))))

;; Query retry handler: [::retry norm-key attempt opts]
(defmethod relm/update ::retry
  [state context [_ norm-key attempt opts] _event]
  (let [http-req (infer-request-from-key
                   context norm-key
                   (assoc opts
                     :on-success [::fetch-success norm-key opts]
                     :on-failure [::fetch-failure norm-key attempt opts]))]
    [state context [[::http/fetch http-req]]]))

;; Manual cache data setter: [::set-query-data key data opts?]
(defmethod relm/update ::set-query-data
  [state context [_ key data opts] _event]
  [state (set-query-data context key data opts)])

;; Query invalidation handler: [::invalidate key-prefix opts?]
(defmethod relm/update ::invalidate
  [state context [_ key-prefix opts] _event]
  (let [opts (or opts {})
        new-context (invalidate-query-keys context key-prefix opts)
        refetch-active? (get opts :refetch-active? true)]
    (if-not refetch-active?
      [state new-context]
      ;; Refetch all queries that were invalidated and are currently cached
      (let [norm-prefix (normalize-key key-prefix)
            queries-to-refetch
            (filter (fn [[k _q]] (prefix-match? norm-prefix k))
                    (:queries new-context))
            inv-opts (dissoc opts :refetch-active? :predicate)
            refetched-context
            (reduce (fn [ctx [k q]]
                      (set-query-loading ctx k (or (:options q) {})))
                    new-context
                    queries-to-refetch)
            refetch-effects
            (mapv (fn [[k q]]
                    (let [q-opts (or (:options q) {})
                          merged-opts (merge q-opts inv-opts {:force? true})]
                      [::http/fetch (infer-request-from-key
                                      refetched-context k
                                      (assoc merged-opts
                                        :on-success [::fetch-success k merged-opts]
                                        :on-failure [::fetch-failure k 0 merged-opts]))]))
                  queries-to-refetch)]
        [state refetched-context refetch-effects]))))

;; Mutation handler: [::mutate mutation-key opts]
(defmethod relm/update ::mutate
  [state context [_ mutation-key opts] _event]
  (let [opts              (or opts {})
        payload           (or (:data opts) (:body opts) (:variables opts))
        on-mutate         (:on-mutate opts)
        ;; Default rollback context is context before optimistic updates
        rollback-context  (or (:rollback-context opts) context)
        ;; Update mutation state to loading
        new-context       (set-mutation-state
                            context
                            mutation-key
                            {:status       :loading
                             :is-loading?  true
                             :body         payload
                             :data         payload
                             :variables    payload
                             :options      opts
                             :updated-at   (now-ms)})
        method            (or (:method opts) :post)
        http-req          (infer-request-from-key
                            new-context mutation-key
                            (assoc opts
                              :method method
                              :body   payload
                              :on-success [::mutate-success mutation-key rollback-context opts]
                              :on-failure [::mutate-failure mutation-key rollback-context opts]))
        all-effects       (cond-> []
                            (seq on-mutate) (conj [:dispatch on-mutate])
                            true            (conj [::http/fetch http-req]))]
    [state new-context all-effects]))

;; Mutation success handler: [::mutate-success mutation-key rollback-context opts response]
(defmethod relm/update ::mutate-success
  [state context message _event]
  (let [[_ mutation-key rollback-context opts response]
        (if (= 5 (count message))
          message
          (let [[_ m-key m-opts resp] message]
            [_ m-key nil m-opts resp]))
        data            (or (:body response) response)
        new-context     (set-mutation-state
                          context
                          mutation-key
                          {:status      :success
                           :is-loading? false
                           :data        data
                           :options     opts
                           :updated-at  (now-ms)})
        invalidate-keys (if (contains? opts :invalidate)
                          (or (:invalidate opts) [])
                          [mutation-key])
        ;; Invalidate query keys and prepare refetches
        [invalidated-context refetch-effects]
        (reduce
          (fn [[ctx effects] key-prefix]
            (let [norm-prefix (normalize-key key-prefix)
                  ctx'        (invalidate-query-keys ctx norm-prefix)
                  matched     (filter (fn [[k _q]] (prefix-match? norm-prefix k))
                                      (:queries ctx'))
                  ctx''       (reduce (fn [c [k q]]
                                        (set-query-loading c k (or (:options q) {})))
                                      ctx'
                                      matched)
                  new-fxs     (mapv (fn [[k q]]
                                      (let [q-opts (or (:options q) {})
                                            merged-opts (merge q-opts {:force? true})]
                                        [::http/fetch
                                         (infer-request-from-key
                                           ctx'' k
                                           (assoc merged-opts
                                             :on-success [::fetch-success k merged-opts]
                                             :on-failure [::fetch-failure k 0 merged-opts]))]))
                                    matched)]
              [ctx' (into effects new-fxs)]))
          [new-context []]
          invalidate-keys)
        on-success      (:on-success opts)
        on-settled      (:on-settled opts)
        all-effects     (cond-> (vec refetch-effects)
                          (seq on-success) (conj [:dispatch on-success])
                          (seq on-settled) (conj [:dispatch on-settled]))]
    [state invalidated-context all-effects]))

;; Mutation failure handler: [::mutate-failure mutation-key rollback-context opts response]
(defmethod relm/update ::mutate-failure
  [state context message _event]
  (let [[_ mutation-key rollback-context opts response]
        (if (= 5 (count message))
          message
          (let [[_ m-key m-rollback m-opts resp] message]
            [_ m-key m-rollback m-opts resp]))
        base-context  (or rollback-context context)
        new-context   (set-mutation-state
                        base-context
                        mutation-key
                        {:status      :error
                         :is-loading? false
                         :error       response
                         :options     opts
                         :updated-at  (now-ms)})
        on-error      (:on-error opts)
        on-settled    (:on-settled opts)
        all-effects   (cond-> []
                        (seq on-error)   (conj [:dispatch on-error])
                        (seq on-settled) (conj [:dispatch on-settled]))]
    [state new-context all-effects]))
