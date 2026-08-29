# relm.core

`com.lambdaseq/relm.core` is the foundational module of Relm, providing an Elm-architecture (Model-View-Update + Effects) runtime on top of [Replicant](https://github.com/cjohansen/replicant) for Clojure and ClojureScript.

## Table of Contents

- [Installation](#installation)
- [Module Overview](#module-overview)
- [Core Architecture (`com.lambdaseq.relm.core`)](#core-architecture-comlambdaseqrelmcore)
  - [Component Lifecycle](#component-lifecycle)
  - [State & Context Model](#state--context-model)
  - [Message Handlers (`relm/update`)](#message-handlers-relmupdate)
  - [Side-Effect Handlers (`relm/fx`)](#side-effect-handlers-relmfx)
  - [Nested Components](#nested-components)
- [HTTP Client (`com.lambdaseq.relm.http`)](#http-client-comlambdaseqrelmhttp)
  - [Fetch Effect (`::fetch`)](#fetch-effect-fetch)
  - [Abort Effect (`::abort`)](#abort-effect-abort)
- [Browser Navigation (`com.lambdaseq.relm.navigation`)](#browser-navigation-comlambdaseqrelmnavigation)

---

## Installation

Add the dependency to your `deps.edn`:

```clojure
{:deps {com.lambdaseq/relm.core {:git/url "https://github.com/lambdaseq/relm"
                                 :sha     "..."
                                 :deps/root "core"}}}
```

---

## Module Overview

The `core` module includes three functional namespaces:

| Namespace | Role |
| :--- | :--- |
| `com.lambdaseq.relm.core` | Component runtime, lifecycle management, Replicant dispatcher, `relm/update`, and `relm/fx`. |
| `com.lambdaseq.relm.http` | Fetch API integration, asynchronous HTTP requests, cancellation, and response decoders. |
| `com.lambdaseq.relm.navigation` | Browser History API side effects (`pushState`, `replaceState`, `back`, `forward`, `reload`). |

---

## Core Architecture (`com.lambdaseq.relm.core`)

Relm structures applications using the Elm Architecture:

```
DOM Event -> relm/dispatch -> relm/update -> [new-state new-context effects]
                                                     |             |
                                            Replicant Render    relm/fx
```

### Component Lifecycle

Components are defined with `relm/component` by passing an `:init` function and a `:view` function:

```clojure
(ns my-app.counter
  (:require [com.lambdaseq.relm.core :as relm]
            [replicant.dom :as r]))

(defn init
  "Initializes local component state. Receives global context and mount arguments."
  [_context {:keys [initial-count] :or {initial-count 0}}]
  {:count initial-count})

(defn view
  "Pure function of (state, context) returning Replicant Hiccup."
  [{:keys [count]} _context]
  [:div
   [:h2 "Counter: " count]
   [:button {:on {:click [::increment]}} "+1"]
   [:button {:on {:click [::decrement]}} "-1"]])

(def Counter
  (relm/component
    {:init init
     :view view}))

;; Mount into the DOM
(r/set-dispatch! relm/dispatch)
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
  [state context [[::log-message text]]])

;; 2. Effect handler executes the side effect
(defmethod relm/fx ::log-message
  [_event [_ text]]
  (js/console.log "Notification:" text))
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

## HTTP Client (`com.lambdaseq.relm.http`)

The `http` module provides declarative, asynchronous Fetch API requests with automatic JSON decoding and request cancellation.

### Fetch Effect (`::fetch`)

Dispatch `::relm.http/fetch` in your update handler's effects vector:

```clojure
(ns my-app.posts
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.http :as relm.http]))

(defmethod relm/update ::load-posts
  [state context _ _]
  [(assoc state :loading? true)
   context
   [[::relm.http/fetch
     {:url         "https://api.example.com/posts"
      :method      :get
      :mode        :cors
      :headers     {"Accept" "application/json"}
      :on-success  [::posts-loaded]
      :on-failure  [::posts-failed]
      :request-id  :posts-request}]]])

(defmethod relm/update ::posts-loaded
  [state context [_ {:keys [status body headers]}] _]
  [(assoc state :loading? false :posts body) context])

(defmethod relm/update ::posts-failed
  [state context [_ {:keys [problem problem-message status]}] _]
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

### Abort Effect (`::abort`)

Cancel an active in-flight request using its `:request-id`:

```clojure
(defmethod relm/update ::cancel-posts
  [state context _ _]
  [(assoc state :loading? false)
   context
   [[::relm.http/abort {:request-id :posts-request}]]])
```

---

## Browser Navigation (`com.lambdaseq.relm.navigation`)

`com.lambdaseq.relm.navigation` provides effect handlers for browser history manipulation:

```clojure
(ns my-app.nav
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.navigation :as nav]))

;; Push a new history entry
(defmethod relm/update ::go-to-profile
  [state context [_ user-id] _]
  [state context [[::nav/push-state nil (str "/users/" user-id)]]])

;; Replace current history entry
(defmethod relm/update ::replace-url
  [state context [_ path] _]
  [state context [[::nav/replace-state nil path]]])

;; History back / forward / reload
(defmethod relm/update ::back [state context _ _] [state context [[::nav/back]]])
(defmethod relm/update ::forward [state context _ _] [state context [[::nav/forward]]])
(defmethod relm/update ::reload [state context _ _] [state context [[::nav/reload]]])
```
