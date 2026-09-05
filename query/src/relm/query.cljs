(ns relm.query
  "TanStack Query-style declarative server-state management for Relm applications.

  Provides:
  - Vector query key normalization and matching (exact, prefix/hierarchical)
  - Optional helpers to extract URLs and query parameters from vector keys or Reitit routes
  - Explicit and helper-driven cache invalidation (exact single key, hierarchical prefix, predicate, all)
  - Declarative Relm `update` handlers for queries (`::update`, `::fetch`), mutations (`::mutate`), and invalidation (`::invalidate`)
  - Pure context cache state reducers and Hiccup view query helpers
  - Automatic stale detection, configurable retries with exponential backoff, and optimistic mutations"
  (:require [clojure.string :as string]
            [relm.core :as relm]
            [relm.http :as http]
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
    (nil? key) []
    :else [key]))

(defn key-match?
  "Returns true if two query keys match identically after normalization."
  [k1 k2]
  (= (normalize-key k1) (normalize-key k2)))

(defn exact-match?
  "Returns true if two query keys match identically after normalization.
  Alias for `key-match?`."
  [k1 k2]
  (key-match? k1 k2))

(defn prefix-match?
  "Returns true if `prefix` is a prefix of `key` after normalization.
  Supports hierarchical key matching (e.g. `[:users]` matches `[:users]`, `[:users 1]`, and `[:users {:role \"admin\"}]`)."
  [prefix key]
  (let [norm-prefix (normalize-key prefix)
        norm-key (normalize-key key)
        prefix-cnt (count norm-prefix)]
    (and (<= prefix-cnt (count norm-key))
         (= norm-prefix (subvec norm-key 0 prefix-cnt)))))

(defn hierarchical-match?
  "Returns true if `prefix` is a prefix of `key` after normalization.
  Alias for `prefix-match?`."
  [prefix key]
  (prefix-match? prefix key))

(defn target->predicate
  "Given an invalidation target (which can be an invalidation message, descriptor map, key vector, keyword, or predicate)
  and optional opts, returns a predicate `(fn [k q])` that tests whether a query `[k q]` should be invalidated."
  [target & [opts]]
  (cond
    ;; Collection / Vector of targets or messages
    (and (vector? target) (seq target) (vector? (first target)))
    (let [preds (mapv #(target->predicate % opts) target)]
      (fn [k q] (boolean (some (fn [pred] (pred k q)) preds))))

    ;; Invalidation message vectors: [::invalidate-hierarchical prefix opts?], [::invalidate-exact key opts?], etc.
    (and (vector? target) (keyword? (first target)))
    (let [kw (first target)
          kw-name (name kw)]
      (cond
        (or (= kw ::invalidate-hierarchical) (= kw ::invalidate-prefix) (= kw-name "invalidate-hierarchical") (= kw-name "invalidate-prefix"))
        (let [prefix (normalize-key (nth target 1 nil))]
          (fn [k _q] (prefix-match? prefix k)))

        (or (= kw ::invalidate-exact) (= kw ::invalidate-single) (= kw-name "invalidate-exact") (= kw-name "invalidate-single"))
        (let [exact-k (normalize-key (nth target 1 nil))]
          (fn [k _q] (key-match? exact-k k)))

        (or (= kw ::invalidate-all) (= kw-name "invalidate-all"))
        (constantly true)

        (or (= kw ::invalidate-predicate) (= kw-name "invalidate-predicate"))
        (let [pred (nth target 1 nil)]
          (fn [k q] (if (fn? pred) (pred k q) false)))

        (or (= kw ::invalidate) (= kw-name "invalidate"))
        (let [inner-target (nth target 1 nil)
              inner-opts (merge (or opts {}) (nth target 2 nil))]
          (target->predicate inner-target inner-opts))

        ;; Default vector (query key vector)
        :else
        (cond
          (:predicate opts) (:predicate opts)
          (:all? opts) (constantly true)
          (or (:hierarchical? opts) (:prefix? opts))
          (let [norm-prefix (normalize-key target)]
            (fn [k _q] (prefix-match? norm-prefix k)))
          :else
          (let [norm-k (normalize-key target)]
            (fn [k _q] (key-match? norm-k k))))))

    ;; Descriptor map
    (and (map? target) (:type target))
    (case (:type target)
      :exact (let [norm-k (normalize-key (:key target))]
               (fn [k _q] (key-match? norm-k k)))
      :hierarchical (let [norm-prefix (normalize-key (or (:prefix target) (:key target)))]
                      (fn [k _q] (prefix-match? norm-prefix k)))
      :predicate (let [pred (:predicate target)]
                   (fn [k q] (if (fn? pred) (pred k q) false)))
      :all (constantly true)
      (constantly false))

    ;; Map with specific keys
    (and (map? target) (contains? target :exact))
    (let [norm-k (normalize-key (:exact target))]
      (fn [k _q] (key-match? norm-k k)))

    (and (map? target) (or (contains? target :hierarchical) (contains? target :prefix)))
    (let [norm-prefix (normalize-key (or (:hierarchical target) (:prefix target)))]
      (fn [k _q] (prefix-match? norm-prefix k)))

    (and (map? target) (contains? target :predicate))
    (let [pred (:predicate target)]
      (fn [k q] (if (fn? pred) (pred k q) false)))

    (and (map? target) (contains? target :all))
    (constantly true)

    (= :all target)
    (constantly true)

    (nil? target)
    (cond
      (:predicate opts) (:predicate opts)
      (:all? opts) (constantly true)
      :else (constantly false))

    (fn? target)
    (fn [k q] (target k q))

    :else
    (cond
      (:predicate opts)
      (:predicate opts)

      (:all? opts)
      (constantly true)

      (or (:hierarchical? opts) (:prefix? opts))
      (let [norm-prefix (normalize-key target)]
        (fn [k _q] (prefix-match? norm-prefix k)))

      :else
      (let [norm-k (normalize-key target)]
        (fn [k _q] (key-match? norm-k k))))))

(defn invalidate-query-keys
  "Pure reducer: marks queries matching `target` as stale in `context`.
  `target` can be an invalidation message, descriptor, a key vector/keyword, or nil with opts.

  Options:
  - `:exact?` Boolean for exact key matching.
  - `:hierarchical?` / `:prefix?` Boolean for hierarchical prefix matching.
  - `:predicate` Optional `(fn [key query])` predicate function.
  - `:all?` Boolean to invalidate all queries."
  [context target & [opts]]
  (let [pred (target->predicate target opts)]
    (update context :queries
            (fn [queries]
              (reduce-kv
                (fn [acc k q]
                  (if (pred k q)
                    (assoc acc k (assoc q :stale? true))
                    (assoc acc k q)))
                {}
                (or queries {}))))))

;; -----------------------------------------------------------------------------
;; Invalidation Message Helpers
;; -----------------------------------------------------------------------------

(defn invalidate-exact
  "Creates a vector of invalidation message(s) for exact query key matching,
  or when passed a context map as the first argument, marks matching cached queries as stale in context.
  Can be passed directly to `:on-settled`, `:on-success`, or dispatched via `::relm/dispatch!`.

  Examples (Message generation):
    `(invalidate-exact [:todos 1])`
    => `[[::query/invalidate-exact [:todos 1]]]`

    `(invalidate-exact [:todos 1] {:refetch-active? false})`
    => `[[::query/invalidate-exact [:todos 1] {:refetch-active? false}]]`

    `(invalidate-exact [:todos 1] [:users 2])`
    => `[[::query/invalidate-exact [:todos 1]] [::query/invalidate-exact [:users 2]]]`

  Examples (Context reducer):
    `(invalidate-exact context [:todos 1])`
    => updated context"
  [& args]
  (if (and (seq args)
           (map? (first args))
           (not (vector? (first args)))
           (or (contains? (first args) :queries)
               (contains? (first args) :components)
               (and (> (count args) 1) (or (vector? (second args)) (keyword? (second args)) (string? (second args))))))
    (let [[context key & [opts]] args]
      (invalidate-query-keys context key (assoc (or opts {}) :exact? true)))
    (let [last-arg (last args)
          has-opts? (and (> (count args) 1) (map? last-arg) (not (vector? last-arg)))
          opts (when has-opts? last-arg)
          raw-keys (if has-opts? (butlast args) args)
          keys (if (and (= 1 (count raw-keys))
                        (vector? (first raw-keys))
                        (seq (first raw-keys))
                        (vector? (first (first raw-keys))))
                 (first raw-keys)
                 raw-keys)]
      (mapv (fn [k]
              (if (seq opts)
                [::invalidate-exact (normalize-key k) opts]
                [::invalidate-exact (normalize-key k)]))
            keys))))

(defn invalidate-single
  "Creates a vector of invalidation message(s) for a single/exact query key,
  or when passed a context map as the first argument, marks matching cached queries as stale in context.
  Alias for `invalidate-exact`.

  Examples:
    `(invalidate-single [:todos 1])`
    => `[[::query/invalidate-single [:todos 1]]]`"
  [& args]
  (if (and (seq args)
           (map? (first args))
           (not (vector? (first args)))
           (or (contains? (first args) :queries)
               (contains? (first args) :components)
               (and (> (count args) 1) (or (vector? (second args)) (keyword? (second args)) (string? (second args))))))
    (let [[context key & [opts]] args]
      (invalidate-exact context key opts))
    (let [last-arg (last args)
          has-opts? (and (> (count args) 1) (map? last-arg) (not (vector? last-arg)))
          opts (when has-opts? last-arg)
          raw-keys (if has-opts? (butlast args) args)
          keys (if (and (= 1 (count raw-keys))
                        (vector? (first raw-keys))
                        (seq (first raw-keys))
                        (vector? (first (first raw-keys))))
                 (first raw-keys)
                 raw-keys)]
      (mapv (fn [k]
              (if (seq opts)
                [::invalidate-single (normalize-key k) opts]
                [::invalidate-single (normalize-key k)]))
            keys))))

(defn invalidate-hierarchical
  "Creates a vector of invalidation message(s) for hierarchical prefix matching,
  or when passed a context map as the first argument, marks matching cached queries as stale in context.
  Can be passed directly to `:on-settled`, `:on-success`, or dispatched via `::relm/dispatch!`.

  Examples (Message generation):
    `(invalidate-hierarchical [:posts])`
    => `[[::query/invalidate-hierarchical [:posts]]]`

    `(invalidate-hierarchical [:posts] [:users])`
    => `[[::query/invalidate-hierarchical [:posts]] [::query/invalidate-hierarchical [:users]]]`

    `(invalidate-hierarchical [[:posts] [:users]])`
    => `[[::query/invalidate-hierarchical [:posts]] [::query/invalidate-hierarchical [:users]]]`

    `(invalidate-hierarchical [:posts] {:refetch-active? false})`
    => `[[::query/invalidate-hierarchical [:posts] {:refetch-active? false}]]`

  Examples (Context reducer):
    `(invalidate-hierarchical context [:posts])`
    => updated context"
  [& args]
  (if (and (seq args)
           (map? (first args))
           (not (vector? (first args)))
           (or (contains? (first args) :queries)
               (contains? (first args) :components)
               (and (> (count args) 1) (or (vector? (second args)) (keyword? (second args)) (string? (second args))))))
    (let [[context prefix & [opts]] args]
      (invalidate-query-keys context prefix (assoc (or opts {}) :hierarchical? true)))
    (let [last-arg (last args)
          has-opts? (and (> (count args) 1) (map? last-arg) (not (vector? last-arg)))
          opts (when has-opts? last-arg)
          raw-prefixes (if has-opts? (butlast args) args)
          prefixes (if (and (= 1 (count raw-prefixes))
                            (vector? (first raw-prefixes))
                            (seq (first raw-prefixes))
                            (vector? (first (first raw-prefixes))))
                     (first raw-prefixes)
                     raw-prefixes)]
      (mapv (fn [p]
              (if (seq opts)
                [::invalidate-hierarchical (normalize-key p) opts]
                [::invalidate-hierarchical (normalize-key p)]))
            prefixes))))

(defn invalidate-prefix
  "Creates a vector of invalidation message(s) for hierarchical prefix matching,
  or when passed a context map as the first argument, marks matching cached queries as stale in context.
  Alias for `invalidate-hierarchical`."
  [& args]
  (if (and (seq args)
           (map? (first args))
           (not (vector? (first args)))
           (or (contains? (first args) :queries)
               (contains? (first args) :components)
               (and (> (count args) 1) (or (vector? (second args)) (keyword? (second args)) (string? (second args))))))
    (let [[context prefix & [opts]] args]
      (invalidate-hierarchical context prefix opts))
    (let [last-arg (last args)
          has-opts? (and (> (count args) 1) (map? last-arg) (not (vector? last-arg)))
          opts (when has-opts? last-arg)
          raw-prefixes (if has-opts? (butlast args) args)
          prefixes (if (and (= 1 (count raw-prefixes))
                            (vector? (first raw-prefixes))
                            (seq (first raw-prefixes))
                            (vector? (first (first raw-prefixes))))
                     (first raw-prefixes)
                     raw-prefixes)]
      (mapv (fn [p]
              (if (seq opts)
                [::invalidate-prefix (normalize-key p) opts]
                [::invalidate-prefix (normalize-key p)]))
            prefixes))))

(defn invalidate-predicate
  "Creates a vector of invalidation message(s) with a custom predicate function,
  or when passed a context map as the first argument, marks matching cached queries as stale in context.
  `pred-fn` is `(fn [key query])` returning true if the query should be invalidated.

  Examples (Message generation):
    `(invalidate-predicate (fn [k _] (= (first k) :todos)))`
    => `[[::query/invalidate-predicate pred-fn]]`

  Examples (Context reducer):
    `(invalidate-predicate context (fn [k _] (= (first k) :todos)))`
    => updated context"
  ([pred-or-ctx]
   (if (and (map? pred-or-ctx) (or (contains? pred-or-ctx :queries) (contains? pred-or-ctx :components)))
     (invalidate-query-keys pred-or-ctx nil {:predicate (constantly true)})
     [[::invalidate-predicate pred-or-ctx]]))
  ([arg1 arg2]
   (if (and (map? arg1) (or (contains? arg1 :queries) (contains? arg1 :components)))
     (invalidate-query-keys arg1 nil {:predicate arg2})
     (if (seq arg2)
       [[::invalidate-predicate arg1 arg2]]
       [[::invalidate-predicate arg1]])))
  ([context pred-fn opts]
   (invalidate-query-keys context nil (assoc (or opts {}) :predicate pred-fn))))

(defn invalidate-all
  "Creates a vector of invalidation message(s) matching all cached queries,
  or when passed a context map as the first argument, marks all cached queries as stale in context.

  Examples (Message generation):
    `(invalidate-all)`
    => `[[::query/invalidate-all]]`

    `(invalidate-all {:refetch-active? false})`
    => `[[::query/invalidate-all {:refetch-active? false}]]`

  Examples (Context reducer):
    `(invalidate-all context)`
    => updated context"
  ([]
   [[::invalidate-all]])
  ([arg]
   (if (and (map? arg) (or (contains? arg :queries) (contains? arg :components)))
     (invalidate-query-keys arg :all {:all? true})
     (if (and (map? arg) (seq arg))
       [[::invalidate-all arg]]
       [[::invalidate-all]])))
  ([context opts]
   (invalidate-query-keys context :all (assoc (or opts {}) :all? true))))

(defn invalidate-all-keys
  "Creates a vector of invalidation message(s) matching all cached queries.
  Alias for `invalidate-all`."
  ([]
   [[::invalidate-all-keys]])
  ([arg]
   (if (and (map? arg) (or (contains? arg :queries) (contains? arg :components)))
     (invalidate-query-keys arg :all {:all? true})
     (if (and (map? arg) (seq arg))
       [[::invalidate-all-keys arg]]
       [[::invalidate-all-keys]])))
  ([context opts]
   (invalidate-query-keys context :all (assoc (or opts {}) :all? true))))

;; Aliases for invalidation message helpers
(def exact invalidate-exact)
(def single invalidate-single)
(def hierarchical invalidate-hierarchical)
(def prefix invalidate-prefix)
(def predicate invalidate-predicate)
(def all invalidate-all)
(def all-keys invalidate-all-keys)

;; -----------------------------------------------------------------------------
;; Vector Key-to-URL and Request Helpers (Optional)
;; -----------------------------------------------------------------------------

(defn segment->str
  "Converts a single key path segment to a URL string segment."
  [segment]
  (cond
    (keyword? segment) (name segment)
    (nil? segment) ""
    :else (str segment)))

(defn join-base-url
  "Prepends `base-url` to a `path`, trimming duplicate boundary slashes."
  [base-url path]
  (if (string/blank? base-url)
    path
    (let [base (string/replace base-url #"/+$" "")
          rel-path (if (string/starts-with? (or path "") "/") path (str "/" path))]
      (str base rel-path))))

(defn key->path-and-params
  "Deconstructs a query key vector into a REST path string and a query parameters map.
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

(defn key->url
  "Helper: converts a vector key into a REST URL path string (ignoring any trailing params map).
  If `base-url` is provided, it is prepended.

  Examples:
    `(key->url [:users 42 :posts])` -> `\"/users/42/posts\"`
    `(key->url \"https://api.com\" [:users 42])` -> `\"https://api.com/users/42\"`"
  ([key]
   (first (key->path-and-params key)))
  ([base-url key]
   (let [path (first (key->path-and-params key))]
     (join-base-url base-url path))))

(defn key->path
  "Helper: returns the `/`-separated path string from a vector key.
  Alias for `(key->url key)`."
  [key]
  (key->url key))

(defn key->params
  "Helper: extracts query parameters map from the trailing map element of a key vector, or returns `{}`.

  Examples:
    `(key->params [:todos {:status \"active\"}])` -> `{:status \"active\"}`
    `(key->params [:todos 1])` -> `{}`"
  [key]
  (second (key->path-and-params key)))

(defn key->opts
  "Helper: converts a key vector into a query/mutation options map with `:url` and `:params`.
  Merges with optional `extra-opts`.

  Examples:
    `(key->opts [:users 42 :posts {:page 2}])`
    => `{:url \"/users/42/posts\", :params {:page 2}}`

    `(key->opts [:users 42] {:base-url \"https://api.com\", :stale-time 5000})`
    => `{:url \"https://api.com/users/42\", :stale-time 5000}`"
  ([key]
   (key->opts key nil))
  ([key extra-opts]
   (let [[path params] (key->path-and-params key)
         base-url (:base-url extra-opts)
         url (if (and base-url (not (re-find #"^https?://" path)))
               (join-base-url base-url path)
               path)]
     (cond-> (assoc (or extra-opts {}) :url (or (:url extra-opts) url))
             (seq params) (assoc :params (merge params (:params extra-opts)))))))

(defn- extract-path-param-keys
  "Extracts path parameter keywords from a route path template (e.g. `\"/users/:id\"` -> `#{:id}`)."
  [template]
  (when (string? template)
    (->> (re-seq #":([a-zA-Z0-9_-]+)" template)
         (map (fn [[_ k]] (keyword k)))
         set)))

(defn reitit-url
  "Helper: resolves a URL path string using a Reitit router from `context` (or router instance) and route name."
  ([context route-name]
   (reitit-url context route-name nil))
  ([context route-name params]
   (let [router (if (and (map? context) (contains? context :router))
                  (:router context)
                  context)]
     (when router
       (if (seq params)
         (when-let [m (r/match-by-name router route-name params)]
           (:path m))
         (when-let [m (r/match-by-name router route-name)]
           (:path m)))))))

(defn infer-request-from-key
  "Helper: infers an HTTP request map from a query key and options map (using Reitit router if available or vector path segments).
  Can be used when automated key-to-URL inference is desired.

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

        base-url (or (:base-url opts) (get-in context [:query :base-url]) (:base-url context))
        inferred-url (if (and base-url inferred-path (not (re-find #"^https?://" inferred-path)))
                       (join-base-url base-url inferred-path)
                       inferred-path)
        merged-url (or (:url opts) inferred-url)
        merged-params (merge (or inferred-params {}) (:params opts))
        merged-method (or (:method opts) :get)
        body-payload (or (:data opts) (:body opts) (:variables opts))]
    (cond-> (assoc opts
              :url merged-url
              :method merged-method)
            (seq merged-params) (assoc :params merged-params)
            (nil? (:request-content-type opts)) (assoc :request-content-type :json)
            (some? body-payload) (assoc :body body-payload))))

(defn build-request
  "Constructs an HTTP request map from `opts` without inferring URL from query key.
  Prepends `:base-url` (from `opts` or `context`) if `:url` is a relative path.
  Applies default `:method :get` and `:request-content-type :json`."
  [context opts]
  (let [opts (if (string? opts) {:url opts} (or opts {}))
        url (:url opts)
        base-url (or (:base-url opts) (get-in context [:query :base-url]) (:base-url context))
        full-url (if (and base-url url (not (re-find #"^https?://" url)))
                   (join-base-url base-url url)
                   url)
        params (:params opts)
        method (or (:method opts) :get)
        body (or (:data opts) (:body opts) (:variables opts))]
    (cond-> (assoc opts
              :url full-url
              :method method)
            (some? params) (assoc :params params)
            (nil? (:request-content-type opts)) (assoc :request-content-type :json)
            (some? body) (assoc :body body))))

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
                 (merge (or q {:data        nil
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
                       new-data (if (fn? data)
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

;; Query fetching handler: [::fetch key opts?]
;;
;; Message Signature:
;;   `[::fetch query-key opts?]`
;;
;; Parameters:
;;   - `query-key`: Vector, keyword, or value identifying the query cache entry (e.g. `[:posts 1]`).
;;   - `opts`: (Optional) Map of query options or URL string:
;;       - `:url`        Target URL (explicit or via `(key->url key)` / `(key->opts key)`).
;;       - `:base-url`   Root URL prepended to relative `:url`.
;;       - `:params`     Explicit query parameters map.
;;       - `:headers`    HTTP request headers map.
;;       - `:stale-time` Duration (ms) before cached data is considered stale (default: 0).
;;       - `:force?`     When true, bypasses fresh cache and forces a network fetch.
;;       - `:retry`      Max retry attempts on failure (default: 3, false to disable).
;;       - `:on-success` Message vector dispatched upon successful fetch (e.g. `[::on-posts-loaded]`).
;;       - `:on-failure` Message vector dispatched upon final fetch failure.
;;
;; Behavior & Lifecycle:
;;   1. Normalizes `query-key` and checks context cache (`[:queries norm-key]`).
;;   2. If cache is fresh (`:status :success`, data present, not stale, not `:force?`),
;;      returns `[state context]` immediately with zero HTTP effects.
;;   3. Otherwise, marks query as loading in context via `set-query-loading` and emits
;;      `[::http/fetch! http-req]` with success/failure callback handlers.

(defmethod relm/update ::update
  [state context [_ key opts] _event]
  (let [norm-key (normalize-key key)
        opts (if (string? opts) {:url opts} (or opts {}))
        force? (:force? opts)
        stale-time (:stale-time opts)
        q (get-query context norm-key)
        is-fresh? (and (not force?)
                       (= :success (:status q))
                       (some? (:data q))
                       (not (stale? context norm-key stale-time)))]
    (if is-fresh?
      ;; Return cache hit immediately without issuing HTTP fetch
      [state context]
      ;; Mark loading and emit HTTP fetch effect
      (let [new-context (set-query-loading context norm-key opts)
            http-req (build-request
                       new-context
                       (assoc opts
                         :on-success [::fetch-success norm-key opts]
                         :on-failure [::fetch-failure norm-key 0 opts]))]
        (if (:url http-req)
          [state new-context [[::http/fetch! http-req]]]
          [state new-context []])))))

;; Alias `::fetch` to `::update`
;;
;; Message Signature:
;;   `[::fetch query-key opts?]`
;;
;; Delegates directly to `::update` for semantic readability in fetching queries.
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
    [state new-context (if (seq on-success) [[::relm/dispatch! on-success]] [])]))

;; Query fetch failure handler: [::fetch-failure norm-key attempt opts response]
(defmethod relm/update ::fetch-failure
  [state context [_ norm-key attempt opts response] _event]
  (let [max-retry (if (false? (:retry opts)) 0 (or (:retry opts) 3))
        attempt (or attempt 0)]
    (if (< attempt max-retry)
      ;; TanStack-style exponential backoff retry
      (let [delay-ms (calculate-retry-delay attempt)
            retry-msg [::retry norm-key (inc attempt) opts]
            ;; Update retry count in context
            new-context (update-in context [:queries norm-key] assoc :retry-count (inc attempt))]
        [state new-context [[::relm/dispatch-later! {:ms delay-ms :dispatch! retry-msg}]]])
      ;; Exceeded max retries: record final error state
      (let [new-context (set-query-error context norm-key response opts)
            on-error (:on-error opts)]
        [state new-context (if (seq on-error) [[::relm/dispatch! on-error]] [])]))))

;; Query retry handler: [::retry norm-key attempt opts]
(defmethod relm/update ::retry
  [state context [_ norm-key attempt opts] _event]
  (let [opts (if (string? opts) {:url opts} (or opts {}))
        http-req (build-request
                   context
                   (assoc opts
                     :on-success [::fetch-success norm-key opts]
                     :on-failure [::fetch-failure norm-key attempt opts]))]
    (if (:url http-req)
      [state context [[::http/fetch! http-req]]]
      [state context []])))

;; Manual cache data setter: [::set-query-data key data opts?]
(defmethod relm/update ::set-query-data
  [state context [_ key data opts] _event]
  (let [opts (if (string? opts) {:url opts} (or opts {}))]
    [state (set-query-data context key data opts)]))

(defn- normalize-invalidate-targets
  "Normalizes `:invalidate` option or argument into a vector of invalidation targets."
  [invalidate-opt]
  (cond
    (nil? invalidate-opt) []
    (false? invalidate-opt) []
    (vector? invalidate-opt)
    (if (and (seq invalidate-opt)
             (or (vector? (first invalidate-opt))
                 (and (map? (first invalidate-opt))
                      (or (:type (first invalidate-opt))
                          (contains? (first invalidate-opt) :exact)
                          (contains? (first invalidate-opt) :hierarchical)
                          (contains? (first invalidate-opt) :prefix)
                          (contains? (first invalidate-opt) :predicate)
                          (contains? (first invalidate-opt) :all)))))
      invalidate-opt
      [invalidate-opt])
    :else [invalidate-opt]))

(defn- invalidate-and-refetch-targets
  "Invalidates queries matching `targets` in `context`, sets them to fetching/stale,
  and generates refetch HTTP effects using the queries' saved options (merged with any base-url override)."
  [context targets & [opts]]
  (let [target-list (normalize-invalidate-targets targets)
        refetch-active? (get opts :refetch-active? true)]
    (reduce
      (fn [[ctx effects] target]
        (let [pred (target->predicate target opts)
              ctx' (invalidate-query-keys ctx target opts)
              matched (filter (fn [[k q]] (pred k q))
                              (:queries ctx'))]
          (if-not refetch-active?
            [ctx' effects]
            (let [ctx'' (reduce (fn [c [k q]]
                                  (update-in c [:queries k]
                                             (fn [entry]
                                               (assoc (or entry {})
                                                 :is-fetching? true
                                                 :stale? true
                                                 :options (merge (:options entry) (:options q))))))
                                ctx'
                                matched)
                  base-url (or (:base-url opts) (get-in ctx [:query :base-url]) (:base-url ctx))
                  new-fxs (keep (fn [[k q]]
                                  (let [q-opts (or (:options q) {})
                                        url (or (:url q-opts) (:url opts))
                                        merged-opts (cond-> (merge q-opts (dissoc (or opts {}) :refetch-active? :predicate :exact? :hierarchical? :prefix? :all?) {:force? true})
                                                            url (assoc :url url)
                                                            (and base-url (nil? (:base-url q-opts))) (assoc :base-url base-url))]
                                    (when (:url merged-opts)
                                      [::http/fetch!
                                       (build-request
                                         ctx''
                                         (assoc merged-opts
                                           :on-success [::fetch-success k merged-opts]
                                           :on-failure [::fetch-failure k 0 merged-opts]))])))
                                matched)]
              [ctx'' (into effects new-fxs)]))))
      [context []]
      target-list)))

;; Query invalidation handler: [::invalidate target opts?]
;;
;; Message Signature:
;;   `[::invalidate target opts?]`
;;
;; Parameters:
;;   - `target`: Invalidation descriptor (`(exact [:todos])`, `(hierarchical [:todos])`, `(all)`, `(predicate pred)`),
;;               a vector key, or `:all`.
;;   - `opts`: (Optional) Invalidation options map:
;;       - `:exact?`          Exact single key invalidation.
;;       - `:hierarchical?`   Hierarchical prefix invalidation.
;;       - `:refetch-active?` Boolean indicating whether currently cached queries matching
;;                            the target should be refetched immediately (default: true).
;;       - `:predicate`       Custom `(fn [key query])` predicate for selective invalidation.
(defmethod relm/update ::invalidate
  [state context [_ target opts] _event]
  (let [opts (or opts {})
        [new-context refetch-effects] (invalidate-and-refetch-targets context target opts)]
    [state new-context refetch-effects]))

(defmethod relm/update ::invalidate-exact
  [state context [_ key opts] _event]
  (let [opts (assoc (or opts {}) :exact? true)
        [new-context refetch-effects] (invalidate-and-refetch-targets context key opts)]
    [state new-context refetch-effects]))

(defmethod relm/update ::invalidate-single
  [state context message event]
  (let [[_ key opts] message]
    (relm/update state context [::invalidate-exact key opts] event)))

(defmethod relm/update ::invalidate-hierarchical
  [state context [_ prefix opts] _event]
  (let [opts (assoc (or opts {}) :hierarchical? true)
        [new-context refetch-effects] (invalidate-and-refetch-targets context prefix opts)]
    [state new-context refetch-effects]))

(defmethod relm/update ::invalidate-prefix
  [state context message event]
  (let [[_ prefix opts] message]
    (relm/update state context [::invalidate-hierarchical prefix opts] event)))

(defmethod relm/update ::invalidate-all
  [state context [_ opts] _event]
  (let [opts (assoc (or opts {}) :all? true)
        [new-context refetch-effects] (invalidate-and-refetch-targets context :all opts)]
    [state new-context refetch-effects]))

(defmethod relm/update ::invalidate-all-keys
  [state context message event]
  (let [[_ opts] message]
    (relm/update state context [::invalidate-all opts] event)))

(defmethod relm/update ::invalidate-predicate
  [state context [_ pred opts] _event]
  (let [opts (assoc (or opts {}) :predicate pred)
        [new-context refetch-effects] (invalidate-and-refetch-targets context nil opts)]
    [state new-context refetch-effects]))

;; Mutation handler: [::mutate mutation-key opts]
;;
;; Message Signature:
;;   `[::mutate mutation-key opts]`
;;
;; Parameters:
;;   - `mutation-key`: Vector or keyword identifying the mutation (e.g. `:create-post` or `[:todos]`).
;;   - `opts`: Mutation configuration map:
;;       - `:url`              Target URL (explicit or via `(key->url key)`).
;;       - `:data` / `:body`   Payload to send with the HTTP request.
;;       - `:method`           HTTP method (default: `:post`).
;;       - `:base-url`         Base URL prepended to relative `:url`.
;;       - `:on-mutate`        Message vector dispatched immediately before network request for optimistic updates.
;;       - `:on-success`       Message vector dispatched upon successful mutation completion.
;;       - `:on-error`         Message vector dispatched upon mutation failure.
;;       - `:on-settled`       Message vector or vector of messages dispatched upon mutation settling (e.g. `(hierarchical [:todos])`, `(single [:stats])`, `(all)`).
;;       - `:rollback-context` Optional context snapshot for rollback on error (defaults to pre-mutation context).
(defmethod relm/update ::mutate
  [state context [_ mutation-key opts] _event]
  (let [opts (if (string? opts) {:url opts} (or opts {}))
        payload (or (:data opts) (:body opts) (:variables opts))
        on-mutate (:on-mutate opts)
        new-context (set-mutation-state
                      context
                      mutation-key
                      {:status      :loading
                       :is-loading? true
                       :body        payload
                       :data        payload
                       :variables   payload
                       :options     opts
                       :updated-at  (now-ms)})
        method (or (:method opts) :post)
        http-req (build-request
                   new-context
                   (assoc opts
                     :method method
                     :body payload
                     :on-success [::mutate-success mutation-key opts]
                     :on-failure [::mutate-failure mutation-key opts]))
        all-effects (cond-> []
                            (seq on-mutate) (conj [::relm/dispatch! on-mutate])
                            (:url http-req) (conj [::http/fetch! http-req]))]
    [state new-context all-effects]))

;; Mutation success handler: [::mutate-success mutation-key opts response]
(defmethod relm/update ::mutate-success
  [state context message _event]
  (let [[_ mutation-key opts response]
        (if (= 5 (count message))
          (let [[_ m-key _rollback m-opts resp] message]
            [_ m-key m-opts resp])
          message)
        opts (or opts {})
        data (or (:body response) response)
        new-context (set-mutation-state
                      context
                      mutation-key
                      {:status      :success
                       :is-loading? false
                       :data        data
                       :options     opts
                       :updated-at  (now-ms)})
        on-success (:on-success opts)
        on-settled (:on-settled opts)
        resolved-on-success (if (fn? on-success) (on-success data) on-success)
        resolved-on-settled (if (fn? on-settled) (on-settled data) on-settled)
        all-effects (cond-> []
                            (seq resolved-on-success) (conj [::relm/dispatch! resolved-on-success])
                            (seq resolved-on-settled) (conj [::relm/dispatch! resolved-on-settled]))]
    [state new-context all-effects]))

;; Mutation failure handler: [::mutate-failure mutation-key opts response]
(defmethod relm/update ::mutate-failure
  [state context message _event]
  (let [[_ mutation-key {:keys [rollback-context] :as opts} response]
        (if (= 5 (count message))
          (let [[_ m-key m-rollback m-opts resp] message]
            [_ m-key (assoc (or m-opts {}) :rollback-context m-rollback) resp])
          message)
        opts (or opts {})
        base-context (or rollback-context context)
        new-context (set-mutation-state
                      base-context
                      mutation-key
                      {:status      :error
                       :is-loading? false
                       :error       response
                       :options     opts
                       :updated-at  (now-ms)})
        on-error (:on-error opts)
        on-settled (:on-settled opts)
        resolved-on-error (if (fn? on-error) (on-error response) on-error)
        resolved-on-settled (if (fn? on-settled) (on-settled response) on-settled)
        all-effects (cond-> []
                            (seq resolved-on-error) (conj [::relm/dispatch! resolved-on-error])
                            (seq resolved-on-settled) (conj [::relm/dispatch! resolved-on-settled]))]
    [state new-context all-effects]))
