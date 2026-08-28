# relm

A simple, purely functional abstraction layer on top of [Replicant](https://github.com/cjohansen/replicant) inspired by the Elm architecture for Clojure and ClojureScript applications.

## Status

This library is a Work In Progress (WIP) and the API may change.

## Overview

`relm` provides a clean, predictable, and functional approach to building user interfaces in Clojure/ClojureScript using the Elm Architecture pattern (Model-View-Update + Effects). It manages component lifecycles, isolated local states, global shared context, and side effects while leveraging Replicant for lightweight, fast DOM rendering.

### Key Features

- **Component-based Architecture**: Independent components created via `(relm/component ...)`.
- **Hybrid State Management**: Isolated local component state combined with a reactive global application context.
- **Pure Functional Views**: Pure view functions `(fn [state context] hiccup)` that decouple rendering from state mutations.
- **Declarative Updates & Effects**: Predictable message handlers (`relm/update`) and asynchronous side-effect handlers (`relm/fx`).
- **Modular Ecosystem**:
  - `com.lambdaseq/relm.core`: Core Elm runtime, component lifecycle, rendering, Replicant dispatcher, browser history effects, and HTTP fetch module.
  - `com.lambdaseq/relm.reitit`: Declarative client-side routing integration with [Metosin Reitit](https://github.com/metosin/reitit).

---

## Module Structure

The project is structured into modular libraries:

```
relm/
├── core/                           # Core library (com.lambdaseq/relm.core)
│   └── src/com/lambdaseq/relm/
│       ├── core.cljc               # Component lifecycle, state management, render, dispatch, fx/update
│       ├── http.cljc               # Fetch API side effects (::fetch, ::abort) and body readers
│       └── navigation.cljc         # Browser navigation & History API effects (::push-state, etc.)
│
├── reitit/                         # Routing module (com.lambdaseq/relm.reitit)
│   ├── src/com/lambdaseq/relm/
│   │   └── reitit.cljc             # Reitit router integration, context route synchronization, navigation events
│   └── test/com/lambdaseq/relm/
│       └── reitit_test.cljc        # Comprehensive unit tests for Reitit routing
│
└── examples/                       # Interactive demonstration application
    └── src/examples/
        ├── main.cljs               # App shell, Reitit router setup, and root layout
        ├── counter.cljs            # Simple local state, counter actions, and alert effects
        ├── http.cljs               # Async API requests, JSON decoding, and error handling
        ├── navigation.cljs         # Browser and HTML5 History API effects
        └── nested.cljs             # Multi-level nested components and global theme context
```

---

## Installation

Add the required modules to your `deps.edn`:

```clojure
{:deps {com.lambdaseq/relm.core   {:git/url "https://github.com/lambdaseq/relm"
                                   :sha     "..."
                                   :deps/root "core"}
        com.lambdaseq/relm.reitit {:git/url "https://github.com/lambdaseq/relm"
                                   :sha     "..."
                                   :deps/root "reitit"}}}
```

---

## Core Concepts & Architecture

`relm` follows the Elm Architecture:

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

1. **Model**:
   - **Local State**: State private to each component instance (e.g. counter values, form input, open/close toggles).
   - **Global Context**: Shared state accessible by all components (e.g. current user, theme, active route).
2. **View**: Pure function of `(state, context)` returning Replicant Hiccup data structures.
3. **Update**: Pure multimethod `(update state context message event)` returning `[new-state new-context effects]` where `effects` is always a vector of effect vectors (e.g. `[[::alert "Hi!"]]`).
4. **Effects**: Multimethod `(fx event effect)` executing individual side effects (HTTP requests, browser history changes, timers, alerts).

---

## Usage Guide

### 1. Basic Component

```clojure
(ns examples.counter
  (:require [com.lambdaseq.relm.core :as relm]
            [replicant.dom :as r]))

;; 1. Initialize local component state
(defn init [_context {:keys [init-count] :or {init-count 0}}]
  {:count init-count})

;; 2. Render view as pure function of (state, context)
(defn view [{:keys [count]} _context]
  [:div
   [:h2 "Counter"]
   [:p "Current count: " count]
   [:button {:on {:click [::increment]}} "+1"]
   [:button {:on {:click [::decrement]}} "-1"]
   [:button {:on {:click [::show-alert]}} "Alert"]])

;; 3. Define the component
(def Counter
  (relm/component
    {:init init
     :view view}))

;; 4. Define side effect handler
(defmethod relm/fx ::alert
  [_event [_ message]]
  (js/alert message))

;; 5. Define message update handlers: return [new-state new-context effects]
(defmethod relm/update ::increment
  [state context _message _event]
  [(update state :count inc) context])

(defmethod relm/update ::decrement
  [state context _message _event]
  [(update state :count dec) context])

(defmethod relm/update ::show-alert
  [{:keys [count] :as state} context _message _event]
  [state context [[::alert (str "Current count is " count)]]])

;; 6. Wire Replicant dispatch to Relm
(r/set-dispatch! relm/dispatch)

;; 7. Mount root component into DOM
(relm/render js/document.body Counter {:init-count 0})
```

---

### 2. Nested & Hierarchical Components

Each component instance created with `(relm/component ...)` automatically manages its own isolated state:

```clojure
(def ChildCounter
  (relm/component
    {:init (fn [_ctx {:keys [start]}] {:count (or start 0)})
     :view (fn [{:keys [count]} ctx]
             [:div {:style {:color (if (= (:theme ctx) :dark) "#fff" "#000")}}
              [:span "Count: " count]
              [:button {:on {:click [::increment]}} "+"]])}))

(def ParentDashboard
  (relm/component
    {:init (fn [_ctx _args] {:items [1 2 3]})
     :view (fn [{:keys [items]} ctx]
             [:div
              [:h1 "Dashboard"]
              [:button {:on {:click [::toggle-theme]}} "Toggle Theme"]
              (for [id items]
                ^{:key id}
                (ChildCounter {:id (str "counter-" id) :start (* id 10)}))])}))
```

- Pass an `:id` or `:key` in args to assign a stable component identity.
- Local state is cleaned up automatically when components unmount.
- When `::toggle-theme` updates global `context`, all child components re-render with the new context.

---

### 3. HTTP Requests (`com.lambdaseq.relm.http`)

The `core` module includes a built-in Fetch API effect handler with automated JSON decoding and cancellation:

```clojure
(ns my-app.http-example
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.http :as relm.http]))

(defmethod relm/update ::fetch-posts
  [state context _ _]
  [state context [[::relm.http/fetch
                   {:url        "https://jsonplaceholder.typicode.com/posts"
                    :method     :get
                    :mode       :cors
                    :on-success [::posts-fetched]
                    :on-failure [::posts-failed]}]]])

(defmethod relm/update ::posts-fetched
  [state context [_ {:keys [body]}] _]
  [(assoc state :posts body) context])

(defmethod relm/update ::posts-failed
  [state context [_ {:keys [problem problem-message]}] _]
  [(assoc state :error problem-message) context])
```

#### Aborting Requests

```clojure
;; Abort an in-flight request by request-id
(defmethod relm/update ::cancel
  [state context [_ request-id] _]
  [state context [[::relm.http/abort {:request-id request-id}]]])
```

---

### 4. Browser Navigation & History (`com.lambdaseq.relm.navigation`)

Perform browser navigation actions directly via effect handlers:

```clojure
(ns my-app.nav-example
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.navigation :as nav]))

;; Push state to history
(defmethod relm/update ::go-to-page
  [state context [_ path] _]
  [state context [[::nav/push-state nil path]]])

;; Replace history entry
(defmethod relm/update ::redirect
  [state context [_ path] _]
  [state context [[::nav/replace-state nil path]]])

;; Navigate back or reload
(defmethod relm/update ::go-back [state context _ _] [state context [[::nav/back]]])
(defmethod relm/update ::reload-app [state context _ _] [state context [[::nav/reload]]])
```

---

### 5. Routing with Reitit (`com.lambdaseq.relm.reitit`)

The `reitit` module provides full client-side routing, popstate history synchronization, and context-based route inspection:

```clojure
(ns my-app.main
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.reitit :as relm.reitit]
            [reitit.core :as reitit]
            [replicant.dom :as r]))

;; 1. Define routes table
(def routes
  [["/" {:name :home
         :view (fn [] (HomeView {}))}]
   ["/users" {:name :users
              :view (fn [] (UsersView {}))}]
   ["/user/:id" {:name :user
                 :view (fn [] (UserView {}))}]])

(def router (reitit/router routes))

;; 2. Root view dynamically renders current matched view from context
(defn root-view [_state context]
  (let [current-route (relm.reitit/current-route context)
        view-fn (relm.reitit/current-view context)]
    [:div
     [:nav
      [:button {:on {:click [::relm.reitit/navigate-to "/"]}} "Home"]
      [:button {:on {:click [::relm.reitit/navigate-to "/users"]}} "Users"]
      [:button {:on {:click [::relm.reitit/navigate-to :user {:id 42} {:tab "profile"}]}} "User 42"]]
     (when view-fn
       (view-fn))]))

(def AppRoot
  (relm/component {:view root-view}))

;; 3. Bootstrap app
(r/set-dispatch! relm/dispatch)
(relm.reitit/start! router {:default-path "/"})
(relm/render js/document.body AppRoot)
```

#### Reitit Helpers & Context Accessors

- `(relm.reitit/current-route context)`: Returns the current route keyword (e.g. `:home`, `:user`).
- `(relm.reitit/current-view context)`: Returns the view component/fn attached to the active route.
- `(relm.reitit/current-match context)`: Returns the full Reitit `Match` record.
- `(relm.reitit/path-for router :user {:id 42} {:tab "profile"})`: Reverses route to `"/user/42?tab=profile"`.
- `::relm.reitit/navigate-to`: Navigation message accepting paths or route names with params.
- `::relm.reitit/replace-to`: URL replacement message without adding a new history entry.

---

## Comparison

### Comparison with re-frame

Both `relm` and `re-frame` provide structured, unidirectional data-flow architectures for ClojureScript applications, but they differ fundamentally in how they handle views, state scoping, events, and side effects.

| Dimension | `relm` | `re-frame` |
|---|---|---|
| **View Paradigm** | **Pure functions** `(fn [state context] hiccup)` without reactive atoms or derefs | Reagent components (Form-1/2/3) dereferencing reactive `Reaction`s/`RAtom`s |
| **DOM Renderer** | [Replicant](https://github.com/cjohansen/replicant) (lightweight, pure data-driven VDOM) | React via [Reagent](https://reagent-project.github.io/) |
| **State Model** | **Hybrid**: Isolated local state per component instance + shared global context | **Single global store**: `app-db` holds all app state (or local Reagent atoms) |
| **Event System** | Pure `relm/update` multimethods returning `[new-state new-context effects]` | Interceptor chain with `reg-event-db` / `reg-event-fx` |
| **Effect System** | Ordered vector of effect vectors `[[::fx-type ...]]` processed by `relm/fx` multimethods | Effect map `{:fx-key ...}` processed by `reg-fx` handlers |
| **Reactivity** | Explicit render trigger on state/context change | Reactive signal graph (`reg-sub` subscriptions) |
| **Dependencies** | Minimal (Replicant only for core, zero React dependency) | React, Reagent, and re-frame |

#### 1. Pure Functional Views vs. Reactive Subscriptions

- **relm**: Views are **pure, deterministic functions** of their arguments: `(fn [state context] hiccup)`. A view does not dereference atoms, maintain internal subscriptions, or interact with hidden reactive state graphs. Given the same `state` and `context`, a view always returns the exact same Hiccup data structure. Rendering is powered by Replicant, eliminating React wrapper overhead and lifecycle hooks.
- **re-frame**: Views are Reagent components that subscribe to the global signal graph using `(subscribe [:query-id])`. Reagent components track dereferences (`@sub`) during execution and automatically re-render when reactive signals emit new values. While powerful, this introduces reactive subscriptions directly into the view layer.

#### 2. Event & Message Systems

- **relm (`relm/update`)**:
  - Events are handled by the `relm/update` multimethod dispatched on message type: `(defmethod relm/update ::msg-type [state context message event])`.
  - The handler is a pure function that explicitly receives both the component's private `state` and the global shared `context`.
  - The return signature is explicit: `[new-state new-context effects]`.
  - Component instances are targeted automatically through the event metadata, enabling straightforward component isolation without global path collisions.
- **re-frame (`reg-event-db` / `reg-event-fx`)**:
  - Events are handled via interceptor pipelines registered with `reg-event-db` or `reg-event-fx`.
  - `reg-event-db` receives `(fn [db event-vec])` and returns a new global `app-db`.
  - `reg-event-fx` receives a coeffects map `(fn [{:keys [db]} event-vec])` and returns an effects description map (e.g. `{:db new-db :fx [...]}`).
  - All state transformations operate against the single global `app-db` tree.

#### 3. Side Effects Systems

- **relm (`relm/fx`)**:
  - Side effects are declared as an **ordered vector of effect vectors**: `[[::fx-type arg1 arg2] ...]`.
  - Handlers are defined using the `relm/fx` multimethod: `(defmethod relm/fx ::fx-type [event [_ arg1 arg2]] ...)`.
  - Asynchronous effects dispatch follow-up messages using `(relm/dispatch event [::on-success data])` or via the built-in `[:dispatch [::next-msg]]` effect.
  - Because effects are vectors, their execution order is guaranteed and linear.
- **re-frame (`reg-fx`)**:
  - Side effects are declared as a map of effect handlers: `{:http-xhrio {...} :dispatch [:event]}` or a vector of pairs `{:fx [[:dispatch [:event]] [:http-xhrio {...}]]}`.
  - Effect handlers are registered globally via `(reg-fx :fx-key (fn [val] ...))`.
  - Follow-up events are triggered by dispatching to the global re-frame event queue (`re-frame.core/dispatch`).

### Comparison with Elm

| Feature | `relm` | Elm |
|---|---|---|
| **Language** | Clojure / ClojureScript | Elm |
| **Architecture** | Model-View-Update + Effects | Model-View-Update + Commands/Subscriptions |
| **Component Support** | Hierarchical components with local state + global context | Single root model / nested update pipelines |
| **Syntax** | Clojure Hiccup data structures | Typed Elm HTML expressions |

---

## Running Tests and Examples

### Running Tests

Run Clojure unit tests for the modules:

```bash
cd reitit && clj -M:test -e "(require '[clojure.test :as t] '[com.lambdaseq.relm.reitit-test]) (t/run-tests 'com.lambdaseq.relm.reitit-test)"
```

### Running Examples

Run the interactive examples application with shadow-cljs:

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
