# relm.query

[![Clojars Project](https://img.shields.io/clojars/v/io.github.conjurernix/relm.query.svg)](https://clojars.org/io.github.conjurernix/relm.query)

`io.github.conjurernix/relm.query` provides TanStack Query-style declarative server-state management for Relm
applications. Built on top of Relm's Elm architecture and `relm.http`, it offers vector-based query keys, explicit
request configuration, context-based caching, automatic stale detection, configurable retries with exponential backoff,
optimistic mutations, and flexible single/hierarchical/predicate cache invalidation with optional URL helpers.

## Table of Contents

- [Installation](#installation)
- [Overview & Architecture](#overview--architecture)
- [Vector Query Keys & Request Helpers](#vector-query-keys--request-helpers)
    - [Vector Key Structure](#vector-key-structure)
    - [Optional URL & Parameter Helpers](#optional-url--parameter-helpers)
    - [Reitit Router Helpers](#reitit-router-helpers)
- [Queries (`::query/fetch`)](#queries-queryfetch)
    - [Dispatching Queries](#dispatching-queries)
    - [Caching & Stale Times](#caching--stale-times)
    - [Exponential Backoff Retries](#exponential-backoff-retries)
    - [Query Options Reference](#query-options-reference)
- [Mutations (`::query/mutate`)](#mutations-querymutate)
    - [Dispatching Mutations](#dispatching-mutations)
    - [Optimistic Updates & Rollback](#optimistic-updates--rollback)
    - [Mutation Options Reference](#mutation-options-reference)
- [Cache Invalidation (`::query/invalidate`)](#cache-invalidation-queryinvalidate)
    - [Single / Exact Key Invalidation](#single--exact-key-invalidation)
    - [Hierarchical Prefix Invalidation](#hierarchical-prefix-invalidation)
    - [Predicate & All-Queries Invalidation](#predicate--all-queries-invalidation)
- [View Query Helpers](#view-query-helpers)
- [Pure Context Cache Reducers](#pure-context-cache-reducers)
- [Complete Working Example](#complete-working-example)

---

## Installation

Add `io.github.conjurernix/relm.query` and `io.github.conjurernix/relm.core` to your `deps.edn`:

```clojure
{:deps {io.github.conjurernix/relm.core  {:mvn/version "0.1.0"}
        io.github.conjurernix/relm.query {:mvn/version "0.1.0"}}}
```

For Leiningen / `project.clj`:

```clojure
[io.github.conjurernix/relm.core "0.1.0"]
[io.github.conjurernix/relm.query "0.1.0"]
```

---

## Overview & Architecture

`relm.query` organizes remote server data inside Relm's reactive global `context` under `:queries` and `:mutations`.
Components read data and loading states synchronously using pure view helpers, while update handlers dispatch
declarative HTTP side effects.

```
       +-------------------------------------------------------+
       |                  Replicant Hiccup View                |
       |  (query/data, query/loading?, query/mutation-loading?) |
       +-------------------------------------------------------+
               |                                       ^
               | [::query/fetch key opts]             | Context
               | [::query/mutate id opts]              | Subscriptions
               v                                       |
       +-------------------------------------------------------+
       |                      relm/update                      |
       |  - Cache hit? -> Immediate context return             |
       |  - Cache miss/stale? -> Set loading & emit fetch FX   |
       |  - Optimistic mutation? -> Transform context & fetch  |
       +-------------------------------------------------------+
                                   |
                                   v
       +-------------------------------------------------------+
       |               HTTP Side Effects (relm/fx)             |
       |  - ::http/fetch! (GET / POST / PUT / DELETE)          |
       |  - ::relm/dispatch-later! (Exponential retry)         |
       +-------------------------------------------------------+
                                   |
                  +----------------+----------------+
                  |                                 |
                  v                                 v
         ::fetch-success                     ::fetch-failure
      - Store data in context              - Retry if attempt < max
      - Mark status :success               - Else set error status
      - Run :invalidate targets
```

### Key Highlights

- **Vector Query Keys**: Express cache resources as arbitrary Clojure vectors or keywords (e.g. `[:users 1 :posts]`).
- **No Implied Inference**: Query keys are purely cache identifiers—URLs and invalidations are explicit or built via
  optional helpers.
- **Pure Elm Lifecycle**: No hidden background stores or stateful class instances; query caches and mutation lifecycles
  live directly in Relm's immutable `context`.
- **Flexible Invalidation**: Invalidate exact single keys (`query/invalidate-exact`), hierarchical subtrees (`query/invalidate-hierarchical`),
  predicates (`query/invalidate-predicate`), or all queries (`query/invalidate-all`).
- **Optimistic UI Updates**: Instantly update the UI before network requests complete, with automatic snapshot rollback
  on failure.
- **Smart Retries**: Built-in exponential backoff retry scheduling for resilient data fetching.

---

## Vector Query Keys & Request Helpers

### Vector Key Structure

Query keys in `relm.query` identify cache entries and are normalized into vectors:

```clojure
[:todos]                                ;; Top-level collection key
[:todos 42]                             ;; Specific entity key
[:users 1 :posts]                       ;; Nested resource key
[:todos {:status "active" :limit 10}]   ;; Resource key with parameter metadata
```

### Optional URL & Parameter Helpers

If you want to derive URLs or options from vector keys, `relm.query` provides several optional helpers:

- `(query/key->url key [base-url])`: Returns the URL path string (e.g. `(query/key->url [:users 42 :posts])` ->
  `"/users/42/posts"`).
- `(query/key->params key)`: Extracts the trailing map from a vector key as query parameters.
- `(query/key->path-and-params key)`: Deconstructs the key into `[path params]`.
- `(query/key->opts key [extra-opts])`: Converts a key into a complete options map with `:url` and `:params`.
- `(query/infer-request-from-key context key opts)`: Builds an HTTP request map from key and options.

```clojure
;; Explicit URL in query options
[::query/fetch [:posts]
 {:url    "/api/v1/posts"
  :params {:sort "desc"}}]

;; Or optionally using key->opts helper
[::query/fetch [:posts {:sort "desc"}]
 (query/key->opts [:posts {:sort "desc"}] {:base-url "https://api.example.com"})]
```

### Reitit Router Helpers

If a Reitit router is present in `context` (e.g. at `(:router context)` via `relm.reitit`), you can resolve route names
to URL paths:

```clojure
;; Given Reitit route ["/users/:id/profile" {:name :user-profile}]
(query/reitit-url context :user-profile {:id 42})
;; => "/users/42/profile"
```

---

## Queries (`::query/fetch`)

### Dispatching Queries

Trigger query fetching declaratively inside event handlers or view clicks:

```clojure
[:button {:on {:click [::query/fetch [:todos {:status "active"}]
                       {:url    "/todos"
                        :params {:status "active"}}]}}
 "Load Active Todos"]
```

You can pass a URL string directly as options:
`[::query/fetch :todos "/todos"]`.

### Caching & Stale Times

`relm.query` implements a cache-first strategy:

1. When a query is requested, `relm.query` checks if fresh data is already present in `context`.
2. If data exists and the elapsed time since `:updated-at` is less than `:stale-time`, the cached data is retained
   without emitting a network request.
3. If data is missing or stale, `relm.query` marks the query as `:is-fetching? true` and dispatches `::http/fetch!`.
4. Pass `:force? true` to bypass fresh cache checks and trigger a guaranteed network refetch.

```clojure
;; Cache for 60 seconds (60000 ms)
[::query/fetch [:users] {:url "/users" :stale-time 60000}]

;; Force background refetch regardless of staleness
[::query/fetch [:users] {:url "/users" :force? true}]
```

### Exponential Backoff Retries

Failed queries automatically retry up to 3 times (configurable via `:retry`) using exponential backoff:

$$\text{delay} = \min (1000 \times 2^{\text{attempt}}, 30000)\text{ ms}$$

- **Attempt 0**: 1,000 ms (1s)
- **Attempt 1**: 2,000 ms (2s)
- **Attempt 2**: 4,000 ms (4s)
- **Max Delay**: 30,000 ms (30s)

To disable retries, pass `{:retry false}` or `{:retry 0}`.

### Query Options Reference

| Option        | Type                | Default | Description                                                               |
|:--------------|:--------------------|:--------|:--------------------------------------------------------------------------|
| `:url`        | `string`            | `nil`   | Target URL. Required for HTTP fetches (or string passed as `opts`).       |
| `:base-url`   | `string`            | `nil`   | Base URL prepended to relative `:url` (e.g. `"https://api.example.com"`). |
| `:params`     | `map`               | `nil`   | HTTP query parameters map.                                                |
| `:headers`    | `map`               | `{}`    | HTTP request headers map.                                                 |
| `:method`     | `keyword`           | `:get`  | HTTP method (`:get`, `:post`, etc.).                                      |
| `:stale-time` | `number`            | `0`     | Milliseconds data remains fresh before refetch is required.               |
| `:force?`     | `boolean`           | `false` | When true, skips cache check and forces immediate fetch.                  |
| `:retry`      | `number \| boolean` | `3`     | Max retry attempts, or `false` to disable.                                |
| `:on-success` | `vector`            | `nil`   | Message vector dispatched on success `[::msg ...]`.                       |
| `:on-error`   | `vector`            | `nil`   | Message vector dispatched on final error `[::msg ...]`.                   |

---

## Mutations (`::query/mutate`)

### Dispatching Mutations

Mutations execute write operations (`:post`, `:put`, `:patch`, `:delete`) and track state in
`context[:mutations <mutation-key>]`.

Specify `:url` (or derive via `(query/key->url key)`) and pass invalidation message vectors via `:on-settled`:

```clojure
;; POST request, invalidates [:todos] and its children hierarchically on settled
[::query/mutate :create-todo
 {:url        (query/key->url [:todos])
  :data       {:title "New Task"}
  :on-settled (query/invalidate-hierarchical [:todos])}]

;; DELETE request, invalidates only the exact [:todos 42] cache
[::query/mutate :delete-todo
 {:url        "/todos/42"
  :method     :delete
  :on-settled (query/invalidate-exact [:todos 42])}]
```

### Optimistic Updates & Rollback

Provide `:on-mutate` with a message vector (such as `[::query/set-query-data ...]` with an updater function) to
optimistically update the cache before the network request finishes. The context prior to the mutation is automatically
captured as the rollback snapshot. If the mutation fails, `relm.query` automatically restores the rollback context:

```clojure
[::query/mutate :create-todo
 {:url        (query/key->url [:todos])
  :data       {:title "New Item" :completed false}
  :on-mutate  [::query/set-query-data [:todos]
               (fn [todos] (conj (or todos []) {:title "New Item" :completed false}))]
  :on-settled (query/invalidate-hierarchical [:todos])}]
```

You can also provide `:on-error` and `:on-settled` message vectors:

```clojure
[::query/mutate :create-todo
 {:url        "/todos"
  :data       new-todo
  :on-mutate  [::query/set-query-data [:todos] (fn [old] (conj (or old []) new-todo))]
  :on-error   [::query/set-query-data [:todos] previous-todos]
  :on-settled (query/invalidate-hierarchical [:todos])}]
```

### Mutation Options Reference

| Option              | Type      | Default   | Description                                                                                                            |
|:--------------------|:----------|:----------|:-----------------------------------------------------------------------------------------------------------------------|
| `:url`              | `string`  | `nil`     | Target URL for the mutation request (explicit or via `(key->url key)`).                                                |
| `:data` / `:body`   | `any`     | `nil`     | Request payload sent to the server (`:variables` supported for backward compatibility).                                |
| `:base-url`         | `string`  | `nil`     | Base URL prepended to relative `:url`.                                                                                 |
| `:method`           | `keyword` | `:post`   | HTTP method (`:post`, `:put`, `:patch`, `:delete`).                                                                    |
| `:on-mutate`        | `vector`  | `nil`     | Message vector executed optimistically before request `[::msg ...]`.                                                   |
| `:rollback-context` | `map`     | `context` | Context state to restore if mutation fails. Defaults to context before `:on-mutate`.                                   |
| `:on-success`       | `vector`  | `nil`     | Message vector or collection of messages dispatched on success `[::msg ...]`.                                          |
| `:on-error`         | `vector`  | `nil`     | Message vector or collection of messages dispatched on error `[::msg ...]`.                                            |
| `:on-settled`       | `vector`  | `nil`     | Message vector or collection of messages dispatched on settled completion (e.g. `(invalidate-hierarchical [:todos])`). |

---

## Cache Invalidation (`::query/invalidate`)

`relm.query` provides explicit helpers and update handlers for single key, hierarchical prefix, predicate, and full
cache invalidation:

### Single / Exact Key Invalidation

Invalidates only the exact matching query key without affecting child or sibling keys:

```clojure
;; Using message handlers or helper
[::query/invalidate-exact [:users 1]]
[::query/invalidate [:users 1] {:exact? true}]
[::relm/dispatch! (query/invalidate-exact [:users 1])]

;; In mutation on-settled
{:on-settled (query/invalidate-exact [:users 1])}

;; Using pure reducer on context
(query/invalidate-exact context [:users 1])
```

### Hierarchical Prefix Invalidation

Invalidates all cached queries sharing the key prefix:

```clojure
;; Invalidates [:users], [:users 1], [:users 1 :posts], and [:users {:role "admin"}]
[::query/invalidate-hierarchical [:users]]
[::query/invalidate [:users] {:hierarchical? true}]
[::relm/dispatch! (query/invalidate-hierarchical [:users])]

;; In mutation on-settled
{:on-settled (query/invalidate-hierarchical [:users])}

;; Without automatic background refetching
[::query/invalidate-hierarchical [:users] {:refetch-active? false}]
(query/invalidate-hierarchical [:users] {:refetch-active? false})

;; Using pure reducer on context
(query/invalidate-hierarchical context [:users])
```

### Predicate & All-Queries Invalidation

```clojure
;; Invalidate by predicate
[::query/invalidate-predicate (fn [key query] (str/starts-with? (str (first key)) ":admin"))]
[::relm/dispatch! (query/invalidate-predicate (fn [key query] ...))]

;; Invalidate all cached queries
[::query/invalidate-all]
[::relm/dispatch! (query/invalidate-all)]

;; Using pure reducers
(query/invalidate-all context)
(query/invalidate-predicate context (fn [key query] ...))
```

---

## View Query Helpers

All view helpers are pure functions that read from Relm's `context` map:

```clojure
(ns my-app.views
  (:require [relm.query :as query]))
```

| Function                  | Signature                                  | Description                                                                              |
|:--------------------------|:-------------------------------------------|:-----------------------------------------------------------------------------------------|
| `query/data`              | `(data context key [default-val])`         | Returns cached response data for `key`, or `default-val` (defaults to `nil`).            |
| `query/loading?`          | `(loading? context key)`                   | Returns `true` if query is performing its initial data fetch (no cached data yet).       |
| `query/fetching?`         | `(fetching? context key)`                  | Returns `true` if query request is currently in flight (including background refetches). |
| `query/error`             | `(error context key)`                      | Returns error payload map for `key`, or `nil`.                                           |
| `query/status`            | `(status context key)`                     | Returns status keyword: `:idle`, `:loading`, `:success`, or `:error`.                    |
| `query/stale?`            | `(stale? context key [custom-stale-time])` | Returns `true` if query has exceeded stale-time or was marked stale.                     |
| `query/get-query`         | `(get-query context key)`                  | Returns raw query entry map (`:data`, `:status`, `:updated-at`, `:fetch-count`, etc.).   |
| `query/mutation`          | `(mutation context mutation-key)`          | Returns mutation state map from `context`.                                               |
| `query/mutation-loading?` | `(mutation-loading? context mutation-key)` | Returns `true` if mutation for `mutation-key` is in-flight.                              |
| `query/mutation-error`    | `(mutation-error context mutation-key)`    | Returns error payload for `mutation-key`, or `nil`.                                      |
| `query/mutation-data`     | `(mutation-data context mutation-key)`     | Returns response payload data for `mutation-key`.                                        |

---

## Pure Context Cache Reducers

Use these pure functional reducers to inspect or transform context state directly:

```clojure
;; Store query data manually
(query/set-query-data context [:todos] [{:id 1 :title "Buy Milk"}])

;; Mark query loading
(query/set-query-loading context [:todos])

;; Set query error
(query/set-query-error context [:todos] {:status 500 :message "Server Error"})

;; Invalidate single exact key
(query/invalidate-exact context [:todos 1])

;; Invalidate keys matching prefix hierarchically
(query/invalidate-hierarchical context [:todos])

;; Invalidate all queries
(query/invalidate-all context)

;; Set mutation state
(query/set-mutation-state context :create-todo {:status :loading :is-loading? true})
```

---

## Complete Working Example

Below is a complete, runnable component demonstrating cache-first data fetching, background refetching, optimistic
mutation creation, and explicit query invalidation:

```clojure
(ns my-app.todos
  (:require [relm.core :as relm]
            [relm.query :as query]))

(def todos-key [:todos {:limit 10}])

(defn init [_context _args]
  {:new-title ""})

(defmethod relm/update ::set-title
  [state context [_ val] _event]
  [(assoc state :new-title val) context])

(defmethod relm/update ::add-todo
  [{:keys [new-title] :as state} context _message _event]
  (if (clojure.string/blank? new-title)
    [state context]
    (let [new-item {:id (rand-int 10000) :title new-title :completed false}]
      [(assoc state :new-title "")
       context
       [[::relm/dispatch! [::query/mutate :create-todo
                           {:url        "/todos"
                            :data       new-item
                            :invalidate (query/invalidate-hierarchical [:todos])
                            :on-mutate  [::query/set-query-data todos-key
                                         (fn [items] (into [new-item] (or items [])))]}]]]])))

(defn view [{:keys [new-title]} context]
  (let [todos (query/data context todos-key [])
        loading? (query/loading? context todos-key)
        fetching? (query/fetching? context todos-key)
        mutation-loading? (query/mutation-loading? context :create-todo)]
    [:div.todos-container
     [:h2 "Todo Manager"]

     ;; Action Bar
     [:div.controls
      [:button {:on {:click [::query/fetch todos-key
                             (query/key->opts todos-key {:stale-time 15000})]}}
       (if fetching? "Fetching..." "Fetch Todos (Cache-First)")]
      [:button {:on {:click [::query/fetch todos-key
                             (query/key->opts todos-key {:force? true})]}}
       "Force Refetch"]
      [:button {:on {:click [::query/invalidate-hierarchical [:todos]]}}
       "Invalidate Cache"]]

     ;; Create Form
     [:div.create-form
      [:input {:type        "text"
               :placeholder "Enter todo title..."
               :value       new-title
               :on          {:input [::set-title :event.target/value]}}]
      [:button {:disabled (or mutation-loading? (clojure.string/blank? new-title))
                :on       {:click [::add-todo]}}
       (if mutation-loading? "Adding..." "Add Todo")]]

     ;; Content View
     (cond
       loading?
       [:div.loading "Loading todos..."]

       (empty? todos)
       [:div.empty "No todos found. Click fetch or add one above!"]

       :else
       [:ul.todo-list
        (for [{:keys [id title completed]} todos]
          [:li {:key id}
           [:span {:style (when completed {:text-decoration "line-through"})}
            title]])])]))

(def TodosApp
  (relm/component {:init init :view view}))
```
