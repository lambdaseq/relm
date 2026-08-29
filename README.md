# relm

A simple, purely functional abstraction layer on top of [Replicant](https://github.com/cjohansen/replicant) inspired by the Elm architecture for Clojure and ClojureScript applications.

## Status

This library is a Work In Progress (WIP) and the API may change.

## Overview

`relm` provides a clean, predictable, and functional approach to building user interfaces in Clojure/ClojureScript using the Elm Architecture pattern (Model-View-Update + Effects). It manages component lifecycles, isolated local states, global shared context, form management, and side effects while leveraging Replicant for lightweight, fast DOM rendering.

### Key Features

- **Component-based Architecture**: Independent components created via `(relm/component ...)`.
- **Hybrid State Management**: Isolated local component state combined with a reactive global application context.
- **Pure Functional Views**: Pure view functions `(fn [state context] hiccup)` that decouple rendering from state mutations.
- **Declarative Updates & Effects**: Predictable message handlers (`relm/update`) and asynchronous side-effect handlers (`relm/fx`).
- **Form State Management**: Declarative validation, dirty/touch tracking, field registration, and semantic HTML attribute generation.
- **Modular Ecosystem**: Core runtime, forms, HTTP, navigation, query caching, and Reitit routing.

---

## Installation

Add the required modules to your `deps.edn`:

```clojure
{:deps {com.lambdaseq/relm.core   {:git/url "https://github.com/lambdaseq/relm"
                                   :sha     "..."
                                   :deps/root "core"}
        com.lambdaseq/relm.form   {:git/url "https://github.com/lambdaseq/relm"
                                   :sha     "..."
                                   :deps/root "form"}
        com.lambdaseq/relm.query  {:git/url "https://github.com/lambdaseq/relm"
                                   :sha     "..."
                                   :deps/root "query"}
        com.lambdaseq/relm.reitit {:git/url "https://github.com/lambdaseq/relm"
                                   :sha     "..."
                                   :deps/root "reitit"}}}
```

---

## Module Catalog

| Module | Namespace | Description | Documentation |
| :--- | :--- | :--- | :--- |
| **Core** | `com.lambdaseq.relm.core` | Elm runtime, component lifecycle, state, `update`, and `fx`. | [Core Documentation](core/README.md) |
| **Form** | `com.lambdaseq.relm.form` | Declarative form state, `form/register`, validators, and submission. | [Form Documentation](form/README.md) |
| **HTTP** | `com.lambdaseq.relm.http` | Fetch API side effects (`::fetch!`, `::abort!`) and JSON decoders. | [HTTP Documentation](core/README.md#http-client-comlambdaseqrelmhttp) |
| **Navigation** | `com.lambdaseq.relm.navigation` | Browser History API effects (`::push-state!`, `::back!`, etc.). | [Navigation Documentation](core/README.md#browser-navigation-comlambdaseqrelmnavigation) |
| **Query** | `com.lambdaseq.relm.query` | TanStack Query-style caching, retries, optimistic mutations, and key inference. | [Query Documentation](query/README.md) |
| **Reitit** | `com.lambdaseq.relm.reitit` | Client-side routing with Reitit, context sync, and navigation messages. | [Reitit Documentation](reitit/README.md) |

---

## Architecture Overview

Relm follows the Elm Architecture:

```
                  +--------------------------------+
                  |            DOM Event           |
                  +--------------------------------+
                                  |
                                  v
                  +--------------------------------+
                  |        relm/dispatch           |
                  +--------------------------------+
                                  |
                                  v
                  +--------------------------------+
                  |          relm/update           |
                  |  [state context msg event]     |
                  +--------------------------------+
                     /            |             \
                    /             |              \
                   v              v               v
            +-----------+  +-------------+  +-------------+
            | new-state |  | new-context |  |   effects   |
            +-----------+  +-------------+  +-------------+
                  |               |                |
                  +-------+-------+                v
                          |                 +-------------+
                          v                 |   relm/fx   |
                  +----------------+        +-------------+
                  |  Re-render DOM |               |
                  |  (Replicant)   |               v
                  +----------------+        (Async/Follow-up Msg)
```

1. **Model**: Isolated local `state` per component instance + shared global `context`.
2. **View**: Pure function `(fn [state context] hiccup)` returning Replicant data structures.
3. **Update**: Pure multimethod `(update state context message event)` returning `[new-state new-context effects]`.
4. **Effects**: Multimethod `(fx event effect)` executing side effects linearly.

---

## Module Previews

### 1. Core Component (`com.lambdaseq.relm.core`)

Define isolated components with pure initialization, rendering, and update handlers:

```clojure
(defn init [_context {:keys [start] :or {start 0}}]
  {:count start})

(defn view [{:keys [count]} _context]
  [:div
   [:span "Count: " count]
   [:button {:on {:click [::increment]}} "+1"]])

(def Counter (relm/component {:init init :view view}))

(defmethod relm/update ::increment [state context _ _]
  [(update state :count inc) context])
```

[Read full Core documentation ->](core/README.md)

---

### 2. Form State Management (`com.lambdaseq.relm.form`)

Colocate validation rules and initial values directly on fields with `form/register`:

```clojure
(defn init [_context _]
  {:form (form/create {:validate-on #{:change :blur :submit}})})

(defn view [{:keys [form]} _context]
  [:form {:on {:submit (form/on-submit form {:on-submit [::save-user]})}}
   [:input (form/register form :email {:type "email" :required "Email required" :email true})]
   (when-let [err (form/error form :email true)]
     [:span {:class "error"} err])
   [:button {:type "submit" :disabled (form/submitting? form)} "Save"]])
```

[Read full Form documentation ->](form/README.md)

---

### 3. HTTP Requests (`com.lambdaseq.relm.http`)

Declarative HTTP Fetch requests with automatic JSON parsing and cancellation:

```clojure
(defmethod relm/update ::fetch-user [state context [_ id] _]
  [state context [[::relm.http/fetch!
                   {:url        (str "https://api.example.com/users/" id)
                    :on-success [::user-loaded]
                    :on-failure [::user-failed]}]]])

(defmethod relm/update ::user-loaded [state context [_ {:keys [body]}]]
  [(assoc state :user body) context])
```

[Read full HTTP documentation ->](core/README.md#http-client-comlambdaseqrelmhttp)

---

### 4. Browser Navigation (`com.lambdaseq.relm.navigation`)

Browser History API side effects:

```clojure
(defmethod relm/update ::go-profile [state context [_ id] _]
  [state context [[::nav/push-state! nil (str "/users/" id)]]])

(defmethod relm/update ::go-back [state context _ _]
  [state context [[::nav/back!]]])
```

[Read full Navigation documentation ->](core/README.md#browser-navigation-comlambdaseqrelmnavigation)

---

### 5. Routing with Reitit (`com.lambdaseq.relm.reitit`)

Declarative client-side routing with automatic context synchronization:

```clojure
(def routes
  [["/" {:name :home :view (fn [] [:h1 "Home"])}]
   ["/users" {:name :users :view (fn [] [:h1 "Users"])}]])

(defn root-view [_state context]
  (let [view-fn (relm.reitit/current-view context)]
    [:div
     [:button {:on {:click [::relm.reitit/navigate-to "/"]}} "Home"]
     [:button {:on {:click [::relm.reitit/navigate-to "/users"]}} "Users"]
     (when view-fn (view-fn))]))
```

[Read full Reitit documentation ->](reitit/README.md)

---

### 6. Query & Cache Management (`com.lambdaseq.relm.query`)

TanStack Query-style caching, automatic URL inference, retries, and optimistic mutations:

```clojure
(ns my-app.posts
  (:require [com.lambdaseq.relm.query :as query]))

(def posts-key [:posts {:limit 10}])

(defn view [_state context]
  (let [posts     (query/data context posts-key [])
        loading?  (query/loading? context posts-key)
        fetching? (query/fetching? context posts-key)]
    [:div
     [:button {:on {:click [::query/update posts-key {:stale-time 10000}]}}
      (if fetching? "Fetching..." "Load Posts")]
     (when loading? [:p "Loading..."])
     [:ul (for [{:keys [id title]} posts]
            [:li {:key id} title])]]))
```

[Read full Query documentation ->](query/README.md)

---

## Comparison

### Comparison with re-frame

| Dimension | `relm` | `re-frame` |
| :--- | :--- | :--- |
| **View Paradigm** | **Pure functions** `(fn [state context] hiccup)` without reactive atoms | Reagent components dereferencing reactive `Reaction`s/`RAtom`s |
| **DOM Renderer** | [Replicant](https://github.com/cjohansen/replicant) (data-driven VDOM) | React via Reagent |
| **State Model** | **Hybrid**: Isolated local component state + shared global context | **Single global store**: `app-db` holds all app state |
| **Event System** | Pure `relm/update` multimethods returning `[new-state new-context effects]` | Interceptor chain (`reg-event-db` / `reg-event-fx`) |
| **Effect System** | Ordered vector of effect vectors `[[::fx ...]]` | Effect description maps `{:fx [...]}` |
| **Dependencies** | Minimal (Replicant only, zero React dependency) | React, Reagent, and re-frame |

### Comparison with Elm

| Dimension | `relm` | Elm |
| :--- | :--- | :--- |
| **Architecture** | Model-View-Update + Side Effects (`[state context effects]`) | Model-View-Update + Commands (`(Model, Cmd Msg)`) |
| **Component Model** | **First-class hierarchical components** with isolated local state | **Monolithic model**: Single root `Model` tree |
| **View Paradigm** | Pure functions returning Clojure data structures (Hiccup) | Pure functions returning typed `Html Msg` AST |
| **Message System** | **Open multimethods** (`relm/update`) on namespaced keywords | **Closed algebraic data types** (`type Msg = ...`) |
| **Side Effects** | Extensible open multimethods (`relm/fx`) | Opaque runtime `Cmd` / `Sub` with JS Ports |

---

## Running Tests and Examples

### Running Tests

```bash
# Run Core tests
cd core && clj -M:test -e "(require '[clojure.test :as t] '[com.lambdaseq.relm.core-test]) (t/run-tests 'com.lambdaseq.relm.core-test)"

# Run Form tests
cd form && clj -M:test -e "(require '[clojure.test :as t] '[com.lambdaseq.relm.form-test]) (t/run-tests 'com.lambdaseq.relm.form-test)"

# Run Reitit tests
cd reitit && clj -M:test -e "(require '[clojure.test :as t] '[com.lambdaseq.relm.reitit-test]) (t/run-tests 'com.lambdaseq.relm.reitit-test)"

# Run Query and all module tests via Shadow-CLJS runner
cd examples && npx shadow-cljs compile test && node out/test.js
```

### Running Examples

```bash
cd examples
npm install
npx shadow-cljs watch examples
```

Then open `http://localhost:8080` in your browser.

---

## License

Copyright © 2023 LambdaSeq

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
