# relm.reitit

`com.lambdaseq/relm.reitit` provides client-side routing integration for Relm applications powered by [Metosin Reitit](https://github.com/metosin/reitit).

## Table of Contents

- [Installation](#installation)
- [Overview](#overview)
- [Quick Start](#quick-start)
- [Context Queries & Helpers](#context-queries--helpers)
- [Navigation Messages](#navigation-messages)
- [HTML5 History & Popstate](#html5-history--popstate)
- [Complete Routing Example](#complete-routing-example)

---

## Installation

Add the dependency to your `deps.edn`:

```clojure
{:deps {com.lambdaseq/relm.core   {:git/url "https://github.com/lambdaseq/relm"
                                   :sha     "..."
                                   :deps/root "core"}
        com.lambdaseq/relm.reitit {:git/url "https://github.com/lambdaseq/relm"
                                   :sha     "..."
                                   :deps/root "reitit"}}}
```

---

## Overview

`com.lambdaseq.relm.reitit` synchronizes Reitit routes with Relm's Elm-architecture runtime:

- **Automatic Context Sync**: Matches the current URL on navigation and synchronizes the active router, options, route, and view component in Relm's global `context`.
- **Pure MVU State Transitions**: Pure `update` message handlers (`::start`, `::stop`, `::set-router`, `::navigate-to`, `::replace-to`, `::route-changed`) without hidden top-level atoms or side effects during state transitions.
- **Dedicated Side Effects**: HTML5 History API interactions and `popstate` event listeners are cleanly managed via `relm/fx` side effects (`::listen-history!`, `::unlisten-history!`, `::nav/push-state!`, `::nav/replace-state!`).
- **Declarative Navigation**: Dispatches pure update messages that update context and trigger History API side effects.
- **Bi-directional Routing**: Reverse URL generation from route names, route parameters, and query parameters.

---

## Quick Start

```clojure
(ns my-app.main
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.reitit :as relm.reitit]
            [reitit.core :as reitit]
            [replicant.dom :as r]))

;; 1. Define route table
(def routes
  [["/" {:name :home
         :view (fn [] [:h1 "Home Page"])}]
   ["/users" {:name :users
              :view (fn [] [:h1 "Users List"])}]
   ["/users/:id" {:name :user
                  :view (fn [{:keys [id]}] [:h1 (str "User Profile: " id)])}]])

(def router (reitit/router routes))

;; 2. Render active view dynamically from context
(defn root-view [_state context]
  (let [route-name (relm.reitit/current-route context)
        view-fn    (relm.reitit/current-view context)
        match      (relm.reitit/current-match context)
        path-params (get-in match [:parameters :path])]
    [:div
     [:nav
      [:button {:on {:click [::relm.reitit/navigate-to "/"]}} "Home"]
      [:button {:on {:click [::relm.reitit/navigate-to "/users"]}} "Users"]
      [:button {:on {:click [::relm.reitit/navigate-to :user {:id 42}]}} "User 42"]]
     [:main
      (when view-fn
        (view-fn path-params))]]))

(def AppRoot
  (relm/component {:view root-view}))

;; 3. Bootstrap application
(r/set-dispatch! relm/dispatch)
(relm.reitit/start! router {:default-path "/"})
(relm/render js/document.body AppRoot)
```

---

## Context Queries & Helpers

Helper functions to query route state from the global Relm `context`:

```clojure
;; Extract the active route name keyword (e.g. :home, :users, :user)
(relm.reitit/current-route context)

;; Extract the view function/component associated with the active route
(relm.reitit/current-view context)

;; Extract the full Reitit Match record (containing :parameters, :path, :data, etc.)
(relm.reitit/current-match context)

;; Extract active Reitit router instance from context
(relm.reitit/router context)

;; Reverses route name + params into a URL path string
(relm.reitit/path-for router :user {:id 42} {:tab "details"})
;; => "/users/42?tab=details"
```

---

## Navigation Messages

Dispatch navigation actions from Hiccup event vectors or `relm/update` message handlers:

### Push State Navigation (`::navigate-to`)

Pushes a new entry to the browser history and updates route context:

```clojure
;; Navigate by path string
[:button {:on {:click [::relm.reitit/navigate-to "/users"]}} "Users"]

;; Navigate by route name with path parameters
[:button {:on {:click [::relm.reitit/navigate-to :user {:id 42}]}} "User 42"]

;; Navigate by route name with path params and query params
[:button {:on {:click [::relm.reitit/navigate-to :user {:id 42} {:tab "settings"}]}} "Settings"]
```

### Replace State Navigation (`::replace-to`)

Replaces the current history entry without pushing a new history item:

```clojure
[:button {:on {:click [::relm.reitit/replace-to "/login"]}} "Redirect to Login"]
```

### Direct Route Updates (`::route-changed`)

Updates route context directly without triggering browser history side effects (used internally by `popstate` listeners):

```clojure
[::relm.reitit/route-changed match-or-target params query-params]
```

### Router Management Messages (`::start`, `::stop`, `::set-router`)

Initialize, update, or stop router integration declaratively:

```clojure
;; Start router and attach popstate listener
[::relm.reitit/start router {:default-path "/"}]

;; Update active router in context and refresh popstate listener
[::relm.reitit/set-router new-router {:default-path "/home"}]

;; Stop popstate listener and clear router context
[::relm.reitit/stop]
```

---

## HTML5 History & Popstate

All side effects (attaching/removing `popstate` event listeners and pushing/replacing browser history) are handled through Relm's `fx` multimethod:
- `::relm.reitit/listen-history!` - Attaches popstate event listener for the active router and default fallback path.
- `::relm.reitit/unlisten-history!` - Cleans up active popstate event listener from `window`.

### Lifecycle Functions

Convenience bootstrap functions dispatch the corresponding MVU messages:

#### `(relm.reitit/start! router opts)`
Dispatches `[::relm.reitit/start router opts]`, registers `popstate` listeners in the browser, and populates `!app-state` context with the initial match:

```clojure
(relm.reitit/start! router {:default-path       "/"
                            :dispatch-initial? true})
```

##### Options
- `:default-path`: Fallback path string if the current URL does not match any route.
- `:dispatch-initial?`: Whether to immediately update `!app-state` context with current matched route (default: `true`).

#### `(relm.reitit/stop!)`
Dispatches `[::relm.reitit/stop]` to remove the browser `popstate` event listener and clear router data from context during application teardown or testing.

---

## Complete Routing Example

```clojure
(ns my-app.routing-example
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.reitit :as relm.reitit]
            [reitit.core :as reitit]
            [replicant.dom :as r]))

(defn home-view [_]
  [:div [:h2 "Home"] [:p "Welcome to the application."]])

(defn user-profile-view [{:keys [id]}]
  [:div
   [:h2 "User Profile"]
   [:p "Viewing profile for user ID: " id]])

(def routes
  [["/" {:name :home :view home-view}]
   ["/users/:id" {:name :user-profile :view user-profile-view}]])

(def router (reitit/router routes))

(defn layout-view [_state context]
  (let [curr-route (relm.reitit/current-route context)
        curr-view  (relm.reitit/current-view context)
        match      (relm.reitit/current-match context)
        params     (get-in match [:parameters :path])]
    [:div {:class "app-container"}
     [:header
      [:nav
       [:a {:class (when (= curr-route :home) "active")
            :on {:click [::relm.reitit/navigate-to "/"]}} "Home"]
       [:a {:class (when (= curr-route :user-profile) "active")
            :on {:click [::relm.reitit/navigate-to :user-profile {:id 101}]}} "Profile 101"]]]
     [:main
      (when curr-view
        (curr-view params))]]))

(def App (relm/component {:view layout-view}))

(defn init! []
  (r/set-dispatch! relm/dispatch)
  (relm.reitit/start! router {:default-path "/"})
  (relm/render js/document.body App))
```
