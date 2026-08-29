(ns com.lambdaseq.relm.reitit
  "Reitit routing integration for Relm applications.

  Provides seamless integration between Metosin's Reitit library and Relm:
  - Automatic synchronization of the current route match inside Relm's global `:context`
  - Browser HTML5 history integration via `popstate` listeners
  - Helper functions for querying routes and views from the context (`current-route`, `current-view`, `current-match`, `path-for`)
  - Declarative Relm `update` message handlers for navigation (`::navigate-to`, `::replace-to`, `::route-changed`, etc.)"
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.navigation :as nav]
            [reitit.core :as r]))

;; -----------------------------------------------------------------------------
;; Module State
;; -----------------------------------------------------------------------------

(defonce ^:private ^{:doc "Holds the active Reitit router instance registered via `start!`."}
  !router
  (atom nil))

(defonce ^:private ^{:doc "Holds router configuration options (such as `:default-path` and `:dispatch-initial?`)."}
  !options
  (atom {}))

(defonce ^:private ^{:doc "Stores the active browser `popstate` event listener function for cleanup on `stop!`."}
  !history-listener
  (atom nil))

;; -----------------------------------------------------------------------------
;; Router Lookup and Matching Helpers
;; -----------------------------------------------------------------------------

(defn router
  "Returns the Reitit router instance from the `context` map (under `:router`),
  falling back to the globally configured router atom if not present in context."
  [context]
  (or (:router context) @!router))

(defn current-path
  "Returns the current window pathname in browser environments, or \"/\" if window is not available."
  []
  (if (exists? js/window)
    (.. js/window -location -pathname)
    "/"))

(defn match-by-path
  "Matches a URL path string against the provided Reitit router instance `r-router`.
  Returns a `reitit.core/Match` record, or nil if no match is found."
  [r-router path]
  (when (and r-router path)
    (r/match-by-path r-router path)))

(defn match-by-name
  "Matches a route name keyword (and optional path params map) against `r-router`.
  Returns a `reitit.core/Match` record, or nil if no match is found."
  ([r-router route-name]
   (match-by-name r-router route-name nil))
  ([r-router route-name params]
   (when (and r-router route-name)
     (r/match-by-name r-router route-name (or params {})))))

(defn match-target
  "Matches a target against the Reitit router `r-router`.
  The target can be:
  - Path string (e.g. `\"/nested\"` or `\"/users/42\"`)
  - Route name keyword (e.g. `:user` with params `{:id 42}`)
  - Existing Reitit Match map (e.g. `{:data {:name :user} ...}`)

  If no match is found, attempts to fall back to `default-path`."
  ([r-router target]
   (match-target r-router target nil nil nil))
  ([r-router target params]
   (match-target r-router target params nil nil))
  ([r-router target params query-params]
   (match-target r-router target params query-params nil))
  ([r-router target params query-params default-path]
   (let [match (cond
                 (string? target)
                 (match-by-path r-router target)

                 (keyword? target)
                 (match-by-name r-router target params)

                 (and (map? target) (contains? target :data))
                 target

                 :else nil)]
     (or match
         (when default-path
           (match-by-path r-router default-path))))))

(defn resolve-path
  "Resolves a URL path string from `target` and its matched Reitit `match` record.
  Appends serialized query parameters if `query-params` is provided."
  ([target match]
   (resolve-path target match nil))
  ([target match query-params]
   (cond
     (string? target)
     target

     match
     (if (seq query-params)
       (r/match->path match query-params)
       (:path match))

     :else nil)))

;; -----------------------------------------------------------------------------
;; Context Route Helpers
;; -----------------------------------------------------------------------------

(defn set-route-context
  "Updates the `context` map with the current Reitit match:
  - Sets `:route` to the Reitit Match map
  - Sets `:current-route` to the route name keyword (from `[:data :name]`)"
  [context match]
  (assoc context
         :route match
         :current-route (get-in match [:data :name])))

(defn current-route
  "Helper to extract the active route name keyword (e.g. `:home`, `:counter`) from the context map."
  [context]
  (or (:current-route context)
      (get-in context [:route :data :name])))

(defn current-match
  "Helper to extract the full active Reitit `Match` record from the context map (under `:route`)."
  [context]
  (:route context))

(defn current-view
  "Helper to extract the matched view component/function from the context map (from `[:route :data :view]`)."
  [context]
  (get-in context [:route :data :view]))

(defn path-for
  "Constructs a URL path string for the given route name keyword, path parameters, and query parameters.
  Uses `r-router` for reverse route resolution."
  ([r-router route-name]
   (path-for r-router route-name nil nil))
  ([r-router route-name params]
   (path-for r-router route-name params nil))
  ([r-router route-name params query-params]
  (when-let [m (match-by-name r-router route-name params)]
     (if (seq query-params)
       (r/match->path m query-params)
       (:path m)))))

;; -----------------------------------------------------------------------------
;; Lifecycle & History Management
;; -----------------------------------------------------------------------------

(defn stop!
  "Stops listening for browser `popstate` events and clears the registered history listener."
  []
  (when (exists? js/window)
    (when-let [listener @!history-listener]
      (.removeEventListener js/window "popstate" listener)
      (reset! !history-listener nil))))

(defn start!
  "Initializes the Reitit router, stores it and initial route match in Relm's context,
  and attaches a browser `popstate` listener for history navigation.

  Options map:
  - `:default-path`: Fallback path string when a route is not matched (e.g. `\"/\"`)
  - `:dispatch-initial?`: Whether to immediately update `!app-state` context with current route (default: `true`)"
  ([r-router]
   (start! r-router {}))
  ([r-router opts]
   (let [options (merge {:dispatch-initial? true} opts)
         default-path (:default-path options)
         path (current-path)
         match (match-target r-router path nil nil default-path)]
     (reset! !router r-router)
     (reset! !options options)
     (stop!)
     (when (exists? js/window)
       (let [listener (fn [_]
                        (let [curr-p (current-path)
                              curr-m (match-target r-router curr-p nil nil default-path)]
                          (relm/dispatch nil [::route-changed curr-m])))]
         (reset! !history-listener listener)
         (.addEventListener js/window "popstate" listener)))
     (when (:dispatch-initial? options)
       (swap! relm/!app-state (fn [app]
                                (clojure.core/update app :context (fn [ctx]
                                                                    (-> (or ctx {})
                                                                        (assoc :router r-router)
                                                                        (set-route-context match)))))))
     match)))

;; -----------------------------------------------------------------------------
;; Relm Update Message Handlers
;; -----------------------------------------------------------------------------

;; Navigates to a target route (by path string or route name keyword), updates context with the new match,
;; and emits a `::nav/push-state` side effect.
;; Message format: `[::navigate-to target params? query-params?]`
(defmethod relm/update ::navigate-to
  [state context [_ target params query-params] _event]
  (let [r-router (router context)
        default-path (or (:default-path context) (:default-path @!options))
        match (match-target r-router target params query-params default-path)
        path (resolve-path target match query-params)]
    [state
     (set-route-context context match)
     (when path [[::nav/push-state nil path]])]))

;; Alias for `::navigate-to`.
(defmethod relm/update ::navigate
  [state context message event]
  (let [[_ target params query-params] message]
    (relm/update state context [::navigate-to target params query-params] event)))

;; Navigates to a target URL path string.
;; Message format: `[::navigate-to-path path query-params?]`
(defmethod relm/update ::navigate-to-path
  [state context message event]
  (let [[_ path query-params] message]
    (relm/update state context [::navigate-to path nil query-params] event)))

;; Navigates to a target route name keyword with route params and query params.
;; Message format: `[::navigate-to-route route-name params? query-params?]`
(defmethod relm/update ::navigate-to-route
  [state context message event]
  (let [[_ route-name params query-params] message]
    (relm/update state context [::navigate-to route-name params query-params] event)))

;; Replaces current route (by path string or route name keyword) in context and emits a `::nav/replace-state` effect.
;; Message format: `[::replace-to target params? query-params?]`
(defmethod relm/update ::replace-to
  [state context [_ target params query-params] _event]
  (let [r-router (router context)
        default-path (or (:default-path context) (:default-path @!options))
        match (match-target r-router target params query-params default-path)
        path (resolve-path target match query-params)]
    [state
     (set-route-context context match)
     (when path [[::nav/replace-state nil path]])]))

;; Alias for `::replace-to`.
(defmethod relm/update ::replace
  [state context message event]
  (let [[_ target params query-params] message]
    (relm/update state context [::replace-to target params query-params] event)))

;; Replaces current URL path string without creating a new browser history entry.
;; Message format: `[::replace-path path query-params?]`
(defmethod relm/update ::replace-path
  [state context message event]
  (let [[_ path query-params] message]
    (relm/update state context [::replace-to path nil query-params] event)))

;; Replaces current route by route name keyword without creating a new browser history entry.
;; Message format: `[::replace-route route-name params? query-params?]`
(defmethod relm/update ::replace-route
  [state context message event]
  (let [[_ route-name params query-params] message]
    (relm/update state context [::replace-to route-name params query-params] event)))

;; Updates the matched route in context without emitting history effects.
;; Typically invoked by browser `popstate` listeners.
;; Message format: `[::route-changed match-or-target params? query-params?]`
(defmethod relm/update ::route-changed
  [state context [_ match-or-target params query-params] _event]
  (let [r-router (router context)
        default-path (or (:default-path context) (:default-path @!options))
        match (if (and (map? match-or-target) (contains? match-or-target :data))
                match-or-target
                (match-target r-router match-or-target params query-params default-path))]
    [state (set-route-context context match)]))

;; Alias for `::route-changed`.
(defmethod relm/update ::set-route
  [state context message event]
  (let [[_ match-or-target params query-params] message]
    (relm/update state context [::route-changed match-or-target params query-params] event)))

;; Updates the active Reitit router instance in context and re-evaluates the current route match.
;; Message format: `[::set-router new-router opts?]`
(defmethod relm/update ::set-router
  [state context [_ new-router opts] _event]
  (let [options (merge @!options opts)
        default-path (:default-path options)
        path (current-path)
        match (match-target new-router path nil nil default-path)]
    (reset! !router new-router)
    (reset! !options options)
    [state (-> context
               (assoc :router new-router)
               (set-route-context match))]))
