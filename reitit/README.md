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

- **Automatic Context Sync**: Matches the current URL on navigation and synchronizes the active route and view component in Relm's global `context`.
- **HTML5 History Integration**: Listens to browser `popstate` events to update route context on back/forward buttons.
- **Declarative Navigation**: Dispatches pure update messages (`::navigate-to`, `::replace-to`) that update context and trigger History API side effects.
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

---

## HTML5 History & Popstate

### Starting the Router (`start!`)

`(relm.reitit/start! router opts)` initializes routing, registers `popstate` listeners in the browser, and populates `!app-state` context with the initial match:

```clojure
(relm.reitit/start! router {:default-path       "/"
                            :dispatch-initial? true})
```

#### Options

- `:default-path`: Fallback path string if the current URL does not match any route.
- `:dispatch-initial?`: Whether to immediately update `!app-state` context with current matched route (default: `true`).

### Stopping the Router (`stop!`)

`(relm.reitit/stop!)` removes the browser `popstate` event listener during application teardown or testing.

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
