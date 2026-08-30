# relm.core

[![Clojars Project](https://img.shields.io/clojars/v/io.github.conjurernix/relm.core.svg)](https://clojars.org/io.github.conjurernix/relm.core)

`io.github.conjurernix/relm.core` is the foundational module of Relm, providing an Elm-architecture (Model-View-Update + Effects) runtime on top of [Replicant](https://github.com/cjohansen/replicant) for Clojure and ClojureScript.

## Table of Contents

- [Installation](#installation)
- [Module Overview](#module-overview)
- [Core Architecture (`relm.core`)](#core-architecture-relmcore)
  - [Component Lifecycle](#component-lifecycle)
  - [State & Context Model](#state--context-model)
  - [Message Handlers (`relm/update`)](#message-handlers-relmupdate)
  - [Side-Effect Handlers (`relm/fx`)](#side-effect-handlers-relmfx)
  - [Nested Components](#nested-components)
- [HTTP Client (`relm.http`)](#http-client-relmhttp)
  - [Fetch Effect (`::fetch!`)](#fetch-effect-fetch)
  - [Abort Effect (`::abort!`)](#abort-effect-abort)
- [Browser Navigation (`relm.navigation`)](#browser-navigation-relmnavigation)

---

## Installation

Add the dependency to your `deps.edn`:

```clojure
{:deps {io.github.conjurernix/relm.core {:mvn/version "0.1.0-alpha5"}}}
```

For Leiningen / `project.clj`:

```clojure
[io.github.conjurernix/relm.core "0.1.0-alpha5"]
```

---

## Module Overview

The `core` module includes three functional namespaces:

| Namespace | Role |
| :--- | :--- |
| `relm.core` | Component runtime, lifecycle management, Replicant dispatcher, `relm/update`, and `relm/fx`. |
| `relm.http` | Fetch API integration, asynchronous HTTP requests, cancellation, and response decoders. |
| `relm.navigation` | Browser History API side effects (`pushState`, `replaceState`, `back`, `forward`, `reload`). |

---

## Core Architecture (`relm.core`)

Relm structures applications using the Elm Architecture:

```
DOM Event -> relm/dispatch -> relm/update -> [new-state new-context effects]
                                                     |             |
                                            Replicant Render    relm/fx
```

### Component Lifecycle

Components are defined with `relm/component` by passing a configuration map with:
- `:init` - Pure function `(fn [context args] initial-state)` returning initial local state.
- `:view` - Pure function `(fn [state context] hiccup)` returning Hiccup structure representing the UI.
- `:on-init` - Optional lifecycle hook function `(fn [state context args event] [state context effects])` executed when component mounts.
- `:on-deinit` - Optional lifecycle hook function `(fn [state context args event] [state context effects])` executed when component unmounts.

```clojure
(ns my-app.counter
  (:require [relm.core :as relm]
            [replicant.dom :as r]))

(defn init
  "Pure function initializing local component state. Receives global context and mount arguments."
  [_context {:keys [initial-count] :or {initial-count 0}}]
  {:count initial-count})

(defn on-init
  "Lifecycle hook with update-style signature and return format called when component initializes."
  [state context {:keys [initial-count]} _event]
  [state context [[::log-mount! (str "Mounted with count: " initial-count)]]])

(defn on-deinit
  "Lifecycle hook called when component is unmounted from the DOM."
  [state context _args _event]
  [state context [[::log-unmount! "Unmounted counter"]]])

(defn view
  "Pure function of (state, context) returning Replicant Hiccup."
  [{:keys [count]} _context]
  [:div
   [:h2 "Counter: " count]
   [:button {:on {:click [::increment]}} "+1"]
   [:button {:on {:click [::decrement]}} "-1"]])

(def Counter
  (relm/component
    {:init      init
     :on-init   on-init
     :on-deinit on-deinit
     :view      view}))

;; Mount into the DOM
(r/set-dispatch! relm/dispatch!)
(relm/render js/document.body Counter {:initial-count 10})
```

### State & Context Model

- **Component Local State (`state`)**: Private to each component instance. Ideal for UI toggles, input values, and component-specific counters.
- **Global Shared Context (`context`)**: Shared across all active components. Ideal for current user data, theme settings, and active router state.

### Message Handlers (`relm/update`)

The `relm/update` multimethod handles actions dispatched by the view. Every handler returns a 3-element vector: `[new-state new-context effects]`. If `effects` is omitted, it defaults to empty.

```clojure
(defmethod relm/update ::increment
  [state context _message _event]
  [(update state :count inc) context])

(defmethod relm/update ::decrement
  [state context _message _event]
  [(update state :count dec) context])
```

### Side-Effect Handlers (`relm/fx`)

Side effects are declared as vectors `[[::effect-type arg1 arg2 ...]]` and executed by the `relm/fx` multimethod:

```clojure
;; 1. Update handler emits an effect
(defmethod relm/update ::notify-user
  [state context [_ text] _event]
  [state context [[::log-message! text]]])

;; 2. Effect handler executes the side effect
(defmethod relm/fx ::log-message!
  [_event [_ text]]
  (js/console.log "Notification:" text))
```

#### Built-in Dispatch Effects

Relm Core provides re-frame style dispatch side-effects out of the box:

- `[::relm/dispatch! [::message ...]]`: Dispatches a follow-up message vector back to the runtime.
- `[::relm/dispatch-n! [[::msg-1] [::msg-2]]]`: Dispatches a batch of message vectors.
- `[::relm/dispatch-later! {:ms 1000 :dispatch! [::message]} ...]`: Dispatches messages after a specified delay in milliseconds.

```clojure
(defmethod relm/update ::save-and-notify
  [state context [_ item] _]
  [state context [[::relm/dispatch! [::save-item item]]
                  [::relm/dispatch-later! {:ms 3000 :dispatch! [::clear-notification]}]]])
```

### Nested Components

Components can be nested arbitrarily. Each instance maintains isolated local state identified by an `:id` or `:key`:

```clojure
(defn dashboard-view
  [{:keys [widget-ids]} _context]
  [:div
   [:h1 "Dashboard"]
   (for [id widget-ids]
     ^{:key id}
     (Counter {:id (str "counter-" id) :initial-count 0}))])
```

---

## HTTP Client (`relm.http`)

The `http` module provides declarative, asynchronous Fetch API requests with automatic JSON decoding and request cancellation.

### Fetch Effect (`::fetch!`)

Dispatch `::relm.http/fetch!` in your update handler's effects vector:

```clojure
(ns my-app.posts
  (:require [relm.core :as relm]
            [relm.http :as relm.http]))

(defmethod relm/update ::load-posts
  [state context _ _]
  [(assoc state :loading? true)
   context
   [[::relm.http/fetch!
     {:url         "https://api.example.com/posts"
      :method      :get
      :mode        :cors
      :headers     {"Accept" "application/json"}
      :on-success  [::posts-loaded]
      :on-failure  [::posts-failed]
      :request-id  :posts-request}]]])

(defmethod relm/update ::posts-loaded
  [state context [_ {:keys [status body headers]}]]
  [(assoc state :loading? false :posts body) context])

(defmethod relm/update ::posts-failed
  [state context [_ {:keys [problem problem-message status]}]]
  [(assoc state :loading? false :error problem-message) context])
```

#### Fetch Configuration Options

- `:url`: Target URL string.
- `:method`: HTTP verb (`:get`, `:post`, `:put`, `:patch`, `:delete`, `:head`).
- `:headers`: Map of header key-value pairs.
- `:body`: Request payload (maps are JSON-encoded automatically).
- `:mode`: CORS mode (`:cors`, `:no-cors`, `:same-origin`).
- `:credentials`: Credentials policy (`:include`, `:same-origin`, `:omit`).
- `:request-id`: Identifier keyword used for cancellation.
- `:on-success`: Message vector dispatched on success `[msg-name response-map]`.
- `:on-failure`: Message vector dispatched on error `[msg-name error-map]`.

### Abort Effect (`::abort!`)

Cancel an active in-flight request using its `:request-id`:

```clojure
(defmethod relm/update ::cancel-posts
  [state context _ _]
  [(assoc state :loading? false)
   context
   [[::relm.http/abort! {:request-id :posts-request}]]])
```

---

## Browser Navigation (`relm.navigation`)

`relm.navigation` provides effect handlers for browser history manipulation:

```clojure
(ns my-app.nav
  (:require [relm.core :as relm]
            [relm.navigation :as nav]))

;; Push a new history entry
(defmethod relm/update ::go-to-profile
  [state context [_ user-id] _]
  [state context [[::nav/push-state! nil (str "/users/" user-id)]]])

;; Replace current history entry
(defmethod relm/update ::replace-url
  [state context [_ path] _]
  [state context [[::nav/replace-state! nil path]]])

;; History back / reload / go
(defmethod relm/update ::back [state context _ _] [state context [[::nav/back!]]])
(defmethod relm/update ::reload [state context _ _] [state context [[::nav/reload!]]])
(defmethod relm/update ::go-by-delta [state context [_ delta] _] [state context [[::nav/go! delta]]])
```
