# relm.query

`com.lambdaseq/relm.query` provides TanStack Query-style declarative server-state management for Relm applications. Built on top of Relm's Elm architecture and `com.lambdaseq.relm.http`, it offers vector-based query keys, automatic REST URL/parameter inference, context-based caching, automatic stale detection, configurable retries with exponential backoff, optimistic mutations, and hierarchical cache invalidation.

## Table of Contents

- [Installation](#installation)
- [Overview & Architecture](#overview--architecture)
- [Vector Query Keys & Request Inference](#vector-query-keys--request-inference)
  - [Vector Key Structure](#vector-key-structure)
  - [Automatic URL & Query Parameter Inference](#automatic-url--query-parameter-inference)
  - [Reitit Router Integration](#reitit-router-integration)
- [Queries (`::query/update` & `::query/fetch`)](#queries-queryupdate--queryfetch)
  - [Dispatching Queries](#dispatching-queries)
  - [Caching & Stale Times](#caching--stale-times)
  - [Exponential Backoff Retries](#exponential-backoff-retries)
  - [Query Options Reference](#query-options-reference)
- [Mutations (`::query/mutate`)](#mutations-querymutate)
  - [Dispatching Mutations](#dispatching-mutations)
  - [Optimistic Updates & Rollback](#optimistic-updates--rollback)
  - [Mutation Options Reference](#mutation-options-reference)
- [Hierarchical Cache Invalidation (`::query/invalidate`)](#hierarchical-cache-invalidation-queryinvalidate)
- [View Query Helpers](#view-query-helpers)
- [Pure Context Cache Reducers](#pure-context-cache-reducers)
- [Complete Working Example](#complete-working-example)

---

## Installation

Add `com.lambdaseq/relm.query` and `com.lambdaseq/relm.core` to your `deps.edn`:

```clojure
{:deps {com.lambdaseq/relm.core  {:git/url "https://github.com/lambdaseq/relm"
                                 :sha     "..."
                                 :deps/root "core"}
        com.lambdaseq/relm.query {:git/url "https://github.com/lambdaseq/relm"
                                 :sha     "..."
                                 :deps/root "query"}}}
```

---

## Overview & Architecture

`relm.query` organizes remote server data inside Relm's reactive global `context` under `:queries` and `:mutations`. Components read data and loading states synchronously using pure view helpers, while update handlers dispatch declarative HTTP side effects.

```
       +-------------------------------------------------------+
       |                  Replicant Hiccup View                |
       |  (query/data, query/loading?, query/mutation-loading?) |
       +-------------------------------------------------------+
               |                                       ^
               | [::query/update key opts]             | Context
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
       |  - ::http/fetch (GET / POST / PUT / DELETE)           |
       |  - :dispatch-later (Exponential backoff retry)        |
       +-------------------------------------------------------+
                                   |
                  +----------------+----------------+
                  |                                 |
                  v                                 v
         ::fetch-success                     ::fetch-failure
      - Store data in context              - Retry if attempt < max
      - Mark status :success               - Else set error status
      - Run :invalidate prefixes
```

### Key Highlights

- **Vector Query Keys**: Express resources hierarchically as Clojure vectors (e.g. `[:users 1 :posts {:limit 10}]`).
- **Zero-Boilerplate URL Inference**: Automatically transforms vector keys into REST endpoints and query parameter strings without requiring duplicate route strings.
- **Pure Elm Lifecycle**: No hidden background stores or stateful class instances; query caches and mutation lifecycles live directly in Relm's immutable `context`.
- **Hierarchical Invalidation**: Invalidate entire resource subtrees with prefix matching (e.g. invalidating `[:users]` invalidates `[:users 1]` and `[:users 2]`).
- **Optimistic UI Updates**: Instantly update the UI before network requests complete, with automatic snapshot rollback on failure.
- **Smart Retries**: Built-in exponential backoff retry scheduling for resilient data fetching.

---

## Vector Query Keys & Request Inference

### Vector Key Structure

Query keys in `relm.query` are normalized vectors containing keywords, strings, integers, and optional parameter maps:

```clojure
[:todos]                                ;; Top-level collection
[:todos 42]                             ;; Specific entity by ID
[:users 1 :posts]                       ;; Nested resource
[:todos {:status "active" :limit 10}]   ;; Resource with query parameters
```

### Automatic URL & Query Parameter Inference

When `:url` is not explicitly provided in options, `relm.query` infers the URL path and query parameters from the vector key using `key->path-and-params`:

1. Leading keyword, string, or numeric elements are joined with `/` into a URL path.
2. If the last element is a map, it is extracted and converted into HTTP query parameters (`:params`).

| Query Key | Inferred Path | Inferred Params | Inferred Method |
| :--- | :--- | :--- | :--- |
| `[:todos]` | `"/todos"` | `{}` | `:get` |
| `[:users 42 :posts]` | `"/users/42/posts"` | `{}` | `:get` |
| `[:todos {:status "active"}]` | `"/todos"` | `{:status "active"}` | `:get` |

Explicit options always override or merge with inferred values:

```clojure
[::query/update [:posts]
 {:url "https://api.example.com/v1/posts"  ;; Overrides inferred "/posts"
  :params {:sort "desc"}}]                 ;; Injected query parameters
```

### Reitit Router Integration

If a Reitit router is present in `context` (e.g. at `(:router context)` via `com.lambdaseq.relm.reitit`), `relm.query` matches the first keyword against registered route names:

```clojure
;; Given Reitit route ["/users/:id/profile" {:name :user-profile}]
;; With key [:user-profile {:id 42 :tab "activity"}]
;; Inferred path -> "/users/42/profile"
;; Inferred query params -> {:tab "activity"}
```

---

## Queries (`::query/update` & `::query/fetch`)

### Dispatching Queries

Trigger query fetching declaratively inside event handlers or view clicks:

```clojure
[:button {:on {:click [::query/update [:todos {:status "active"}]]}}
 "Load Active Todos"]
```

`::query/fetch` is provided as an exact alias for `::query/update`.

### Caching & Stale Times

`relm.query` implements a cache-first strategy:

1. When a query is requested, `relm.query` checks if fresh data is already present in `context`.
2. If data exists and the elapsed time since `:updated-at` is less than `:stale-time`, the cached data is retained without emitting a network request.
3. If data is missing or stale, `relm.query` marks the query as `:is-fetching? true` and dispatches `::http/fetch`.
4. Pass `:force? true` to bypass fresh cache checks and trigger a guaranteed network refetch.

```clojure
;; Cache for 60 seconds (60000 ms)
[::query/update [:users] {:stale-time 60000}]

;; Force background refetch regardless of staleness
[::query/update [:users] {:force? true}]
```

### Exponential Backoff Retries

Failed queries automatically retry up to 3 times (configurable via `:retry`) using exponential backoff:

$$\text{delay} = \min(1000 \times 2^{\text{attempt}}, 30000)\text{ ms}$$

- **Attempt 0**: 1,000 ms (1s)
- **Attempt 1**: 2,000 ms (2s)
- **Attempt 2**: 4,000 ms (4s)
- **Max Delay**: 30,000 ms (30s)

To disable retries, pass `{:retry false}` or `{:retry 0}`.

### Query Options Reference

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `:base-url` | `string` | `nil` | Base URL prepended to inferred REST path (e.g. `"https://api.example.com"`). |
| `:url` | `string` | Inferred | Target URL. Overrides inferred path / base URL. |
| `:params` | `map` | Inferred | HTTP query parameters map. Merged with key params. |
| `:headers` | `map` | `{}` | HTTP request headers map. |
| `:method` | `keyword` | `:get` | HTTP method (`:get`, `:post`, etc.). |
| `:stale-time` | `number` | `0` | Milliseconds data remains fresh before refetch is required. |
| `:force?` | `boolean` | `false` | When true, skips cache check and forces immediate fetch. |
| `:retry` | `number \| boolean` | `3` | Max retry attempts, or `false` to disable. |
| `:on-success` | `vector` | `nil` | Message vector dispatched on success `[::msg ...]`. |
| `:on-error` | `vector` | `nil` | Message vector dispatched on final error `[::msg ...]`. |

---

## Mutations (`::query/mutate`)

### Dispatching Mutations

Mutations execute write operations (`:post`, `:put`, `:patch`, `:delete`) and track state in `context[:mutations <mutation-key>]`. 

Mutations can infer their REST URL directly from vector keys and automatically invalidate matching query caches on success without requiring explicit `:url` or `:invalidate` parameters:

```clojure
;; Inferred URL: "/todos" (POST), automatically invalidates queries matching [:todos]
[::query/mutate [:todos]
 {:data {:title "New Task"}}]

;; Inferred URL: "/todos/42" (DELETE), automatically invalidates [:todos] / [:todos 42]
[::query/mutate [:todos 42]
 {:method :delete}]
```

Explicit `:url` and `:invalidate` options can still be provided to override or customize behavior:

```clojure
(defmethod relm/update ::delete-todo
  [state context [_ todo-id] _event]
  [state
   context
   [[::query/mutate [:todos todo-id]
     {:method :delete}]]])
```

### Optimistic Updates & Rollback

Provide `:on-mutate` with a message vector (such as `[::query/set-query-data ...]` with an updater function) to optimistically update the cache before the network request finishes. The context prior to the mutation is automatically captured as the rollback snapshot. If the mutation fails, `relm.query` automatically restores the rollback context:

```clojure
[::query/mutate [:todos]
 {:data      {:title "New Item" :completed false}
  :on-mutate [::query/set-query-data [:todos]
              (fn [todos] (conj (or todos []) {:title "New Item" :completed false}))]}]
```

You can also provide `:on-error` and `:on-settled` message vectors:

```clojure
[::query/mutate [:todos]
 {:data       new-todo
  :on-mutate  [::query/set-query-data [:todos] (fn [old] (conj (or old []) new-todo))]
  :on-error   [::query/set-query-data [:todos] previous-todos]
  :on-settled [::query/invalidate [:todos]]}]
```

### Mutation Options Reference

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `:data` / `:body` | `any` | `nil` | Request payload sent to the server (`:variables` supported for backward compatibility). |
| `:base-url` | `string` | `nil` | Base URL prepended to inferred REST path. |
| `:url` | `string` | Inferred | Target URL for the mutation request. Overrides inferred path / base URL. |
| `:method` | `keyword` | `:post` | HTTP method (`:post`, `:put`, `:patch`, `:delete`). |
| `:invalidate` | `vector \| boolean` | `[mutation-key]` | Query key prefixes to invalidate on success. Pass `false` or `nil` to disable automatic invalidation. |
| `:on-mutate` | `vector` | `nil` | Message vector executed optimistically before request `[::msg ...]`. |
| `:rollback-context` | `map` | `context` | Context state to restore if mutation fails. Defaults to context before `:on-mutate`. |
| `:on-success` | `vector` | `nil` | Message vector dispatched on success `[::msg ...]`. |
| `:on-error` | `vector` | `nil` | Message vector dispatched on error `[::msg ...]`. |
| `:on-settled` | `vector` | `nil` | Message vector dispatched on settled completion `[::msg ...]`. |

---

## Hierarchical Cache Invalidation (`::query/invalidate`)

`relm.query` supports prefix-based hierarchical invalidation. When a key prefix is invalidated, all cached queries matching the prefix are marked stale, and cached queries are automatically refetched in the background:

```clojure
;; Invalidates [:users], [:users 1], [:users 1 :posts], and [:users {:role "admin"}]
[::query/invalidate [:users]]

;; Invalidate without automatic background refetching
[::query/invalidate [:users] {:refetch-active? false}]

;; Invalidate with custom predicate
[::query/invalidate nil {:predicate (fn [key query] (str/starts-with? (str (first key)) ":admin"))}]
```

---

## View Query Helpers

All view helpers are pure functions that read from Relm's `context` map:

```clojure
(ns my-app.views
  (:require [com.lambdaseq.relm.query :as query]))
```

| Function | Signature | Description |
| :--- | :--- | :--- |
| `query/data` | `(data context key [default-val])` | Returns cached response data for `key`, or `default-val` (defaults to `nil`). |
| `query/loading?` | `(loading? context key)` | Returns `true` if query is performing its initial data fetch (no cached data yet). |
| `query/fetching?` | `(fetching? context key)` | Returns `true` if query request is currently in flight (including background refetches). |
| `query/error` | `(error context key)` | Returns error payload map for `key`, or `nil`. |
| `query/status` | `(status context key)` | Returns status keyword: `:idle`, `:loading`, `:success`, or `:error`. |
| `query/stale?` | `(stale? context key [custom-stale-time])` | Returns `true` if query has exceeded stale-time or was marked stale. |
| `query/get-query` | `(get-query context key)` | Returns raw query entry map (`:data`, `:status`, `:updated-at`, `:fetch-count`, etc.). |
| `query/mutation` | `(mutation context mutation-key)` | Returns mutation state map from `context`. |
| `query/mutation-loading?` | `(mutation-loading? context mutation-key)` | Returns `true` if mutation for `mutation-key` is in-flight. |
| `query/mutation-error` | `(mutation-error context mutation-key)` | Returns error payload for `mutation-key`, or `nil`. |
| `query/mutation-data` | `(mutation-data context mutation-key)` | Returns response payload data for `mutation-key`. |

---

## Pure Context Cache Reducers

Use these pure functional reducers if you need to inspect or transform context state directly:

```clojure
;; Store query data manually
(query/set-query-data context [:todos] [{:id 1 :title "Buy Milk"}])

;; Mark query loading
(query/set-query-loading context [:todos])

;; Set query error
(query/set-query-error context [:todos] {:status 500 :message "Server Error"})

;; Invalidate keys matching prefix
(query/invalidate-query-keys context [:todos])

;; Set mutation state
(query/set-mutation-state context :create-todo {:status :loading :is-loading? true})
```

---

## Complete Working Example

Below is a complete, runnable component demonstrating cache-first data fetching, background refetching, optimistic mutation creation, and automatic query invalidation:

```clojure
(ns my-app.todos
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.query :as query]))

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
       [[:dispatch [::query/mutate [:todos]
                    {:data      new-item
                     :on-mutate [::query/set-query-data todos-key
                                 (fn [items] (into [new-item] (or items [])))]}]]]])))

(defn view [{:keys [new-title]} context]
  (let [todos             (query/data context todos-key [])
        loading?          (query/loading? context todos-key)
        fetching?         (query/fetching? context todos-key)
        mutation-loading? (query/mutation-loading? context [:todos])]
    [:div.todos-container
     [:h2 "Todo Manager"]

     ;; Action Bar
     [:div.controls
      [:button {:on {:click [::query/update todos-key {:stale-time 15000}]}}
       (if fetching? "Fetching..." "Fetch Todos (Cache-First)")]
      [:button {:on {:click [::query/update todos-key {:force? true}]}}
       "Force Refetch"]
      [:button {:on {:click [::query/invalidate [:todos]]}}
       "Invalidate Cache"]]

     ;; Create Form
     [:div.create-form
      [:input {:type "text"
               :placeholder "Enter todo title..."
               :value new-title
               :on {:input [::set-title :event.target/value]}}]
      [:button {:disabled (or mutation-loading? (clojure.string/blank? new-title))
                :on {:click [::add-todo]}}
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
