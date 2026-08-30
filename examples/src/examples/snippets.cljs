(ns examples.snippets
  "Annotated ClojureScript source code snippets for each Relm example.
   Highlights core Elm architecture, state transitions, side effects, and declarative views.")

(def counter-code
  "(ns examples.counter
  \"Counter example demonstrating the fundamental Elm architecture in Relm.\"
  (:require [relm.core :as relm]))

;; 1. Initialize local state
(defn init
  [_context {:keys [init-count] :or {init-count 0}}]
  {:count init-count})

;; 2. Pure state transitions (relm/update)
(defmethod relm/update ::increment
  [state context _message _event]
  [(update state :count inc) context])

(defmethod relm/update ::decrement
  [state context _message _event]
  [(update state :count dec) context])

(defmethod relm/update ::reset
  [state context _message _event]
  [(assoc state :count 0) context])

;; 3. Side-effect dispatch (relm/alert!)
(defmethod relm/update ::show-count
  [{:keys [count] :as state} context _message _event]
  [state context [[::relm/alert! (str \"Current Count: \" count)]]])

;; 4. Declarative Hiccup view with message triggers
(defn view
  [{:keys [count]} _context]
  [:div.counter-card
   [:h3 \"Current Count: \" count]
   [:button {:on {:click [::increment]}} \"+ Increment\"]
   [:button {:on {:click [::decrement]}} \"− Decrement\"]
   [:button {:on {:click [::reset]}} \"Reset\"]
   [:button {:on {:click [::show-count]}} \"Show Alert Effect\"]])

;; 5. Component definition
(def Counter
  (relm/component
    {:init init
     :view view}))")

(def http-code
  "(ns examples.http
  \"HTTP request example demonstrating async fetch effects with relm.http.\"
  (:require [relm.core :as relm]
            [relm.http :as relm.http]))

;; 1. Initialize async state
(defn init
  [_context _args]
  {:posts    []
   :loading? false
   :error    nil})

;; 2. Dispatch ::relm.http/fetch! side effect
(defmethod relm/update ::fetch-posts
  [state context _ _event]
  [(assoc state :loading? true :error nil)
   context
   [[::relm.http/fetch!
     {:url        \"https://jsonplaceholder.typicode.com/posts\"
      :method     :get
      :mode       :cors
      :on-success [::posts-fetched]
      :on-failure [::posts-failed]}]]])

;; 3. Handle success response with automatically decoded JSON body
(defmethod relm/update ::posts-fetched
  [state context [_ {:keys [body]}]]
  [(assoc state
          :posts (vec (take 6 (or body [])))
          :loading? false)
   context])

;; 4. Handle failure callback
(defmethod relm/update ::posts-failed
  [state context [_ response]]
  [(assoc state :loading? false :error (:problem response)) context])

;; 5. Declarative view handling empty, loading, and populated states
(defn view
  [{:keys [posts loading? error]} _context]
  [:div
   [:button {:on {:click [::fetch-posts]} :disabled loading?}
    (if loading? \"Requesting JSON...\" \"Fetch Posts\")]
   (when error [:p.error (str error)])
   [:ul
    (for [post posts]
      ^{:key (:id post)}
      [:li [:h4 (:title post)] [:p (:body post)]])]])

(def HttpExample
  (relm/component
    {:init init
     :view view}))")

(def navigation-code
  "(ns examples.navigation
  \"Browser History and Location API side effects with relm.navigation.\"
  (:require [relm.core :as relm]
            [relm.navigation :as nav]))

(defn init
  [_context _args]
  {:current-url (if (exists? js/window) (.. js/window -location -href) \"/\")})

;; Push state to HTML5 history stack
(defmethod relm/update ::push-state
  [state context [_ url] _]
  (let [next-url (if (exists? js/window) (str (.. js/window -location -origin) url) url)]
    [(assoc state :current-url next-url)
     context
     [[::nav/push-state! {:page url} url]]]))

;; Replace state on HTML5 history stack
(defmethod relm/update ::replace-state
  [state context [_ url] _]
  (let [next-url (if (exists? js/window) (str (.. js/window -location -origin) url) url)]
    [(assoc state :current-url next-url)
     context
     [[::nav/replace-state! {:page url} url]]]))

;; Relative history navigation (history.go)
(defmethod relm/update ::go-to-position
  [state context [_ n] _]
  [state context [[::nav/go! n]]])

(defn view
  [{:keys [current-url]} _context]
  [:div
   [:p \"Current Location: \" current-url]
   [:button {:on {:click [::push-state \"/users\"]}} \"Push /users\"]
   [:button {:on {:click [::replace-state \"/dashboard\"]}} \"Replace /dashboard\"]
   [:button {:on {:click [::go-to-position -1]}} \"Go Back (-1)\"]])

(def NavigationExample
  (relm/component
    {:init init
     :view view}))")

(def nested-code
  "(ns examples.nested
  \"Hierarchical multi-level component trees with isolated local states.\"
  (:require [relm.core :as relm]))

;; -----------------------------------------------------------------------------
;; Level 1: Child Component with isolated state per ID
;; -----------------------------------------------------------------------------

(defn- counter-init
  [_context {:keys [id label initial-count step] :or {initial-count 0 step 1}}]
  {:id id :label label :count initial-count :step step})

(defmethod relm/update ::child-increment
  [state context _ _]
  [(update state :count + (:step state 1)) context])

(defmethod relm/update ::child-decrement
  [state context _ _]
  [(update state :count - (:step state 1)) context])

(defn- counter-view
  [{:keys [label count step]} _context]
  [:div.counter-item
   [:h4 label \" (Step: \" step \")\"]
   [:span \"Count: \" count]
   [:button {:on {:click [::child-increment]}} (str \"+\" step)]
   [:button {:on {:click [::child-decrement]}} (str \"−\" step)]])

(def CounterItem
  (relm/component
    {:init counter-init
     :view counter-view}))

;; -----------------------------------------------------------------------------
;; Level 2: Parent Component hosting multiple child instances
;; -----------------------------------------------------------------------------

(defn view
  [_state context]
  [:div.nested-container
   ;; Multiple instances maintain independent, isolated state automatically:
   (CounterItem {:id :c1 :label \"Alpha Counter\"   :initial-count 0  :step 1})
   (CounterItem {:id :c2 :label \"Beta Counter\"    :initial-count 50 :step 5})
   (CounterItem {:id :c3 :label \"Gamma Counter\"   :initial-count 100 :step 10})])

(def NestedExample
  (relm/component
    {:view view}))")

(def form-code
  "(ns examples.form
  \"Reactive form state management and declarative validation with relm.form.\"
  (:require [relm.core :as relm]
            [relm.form :as form]))

;; 1. Initialize form state with validation triggers & schema
(defn init
  [_context _args]
  {:submitted-data nil
   :form (form/create
           {:validate-on #{:change :blur :submit}
            :initial-values {:username \"\" :email \"\"}
            :validators {:username (form/required \"Username is required\")
                         :email    (form/compose
                                     (form/required \"Email is required\")
                                     (form/email \"Must be a valid email\"))}})})

;; 2. Handle successful form submission
(defmethod relm/update ::handle-submit
  [state context [_ form-values] _event]
  (let [updated-form (form/submit-end (:form state) :success)]
    [(assoc state :form updated-form :submitted-data form-values) context]))

;; 3. Declarative view using form/register, form/error, and form/on-submit
(defn view
  [{:keys [form submitted-data]} _context]
  (let [username-attrs (form/register form :username)
        email-attrs    (form/register form :email)
        submit-handler (form/on-submit form [::handle-submit])]
    [:form {:on {:submit submit-handler}}
     ;; Username field
     [:div
      [:label \"Username\"]
      [:input (merge username-attrs {:placeholder \"johndoe\"})]
      (when-let [err (form/error form :username)]
        [:span.error err])]

     ;; Email field
     [:div
      [:label \"Email Address\"]
      [:input (merge email-attrs {:placeholder \"john@example.com\"})]
      (when-let [err (form/error form :email)]
        [:span.error err])]

     ;; Submit button with validation disabled state
     [:button {:type \"submit\" :disabled (not (form/valid? form))}
      \"Submit Form\"]]))

(def FormExample
  (relm/component
    {:init init
     :view view}))")

(def query-code
  "(ns examples.query
  \"Declarative server-state caching & optimistic mutations with relm.query.\"
  (:require [relm.core :as relm]
            [relm.query :as query]))

(def posts-query-key
  [:posts {:_limit 5}])

;; 1. Optimistic mutation with query cache update
(defmethod relm/update ::add-post
  [state context [_ values] _event]
  [state
   context
   [[::relm/dispatch!
     [::query/mutate [:posts]
      {:base-url  \"https://jsonplaceholder.typicode.com\"
       :data      values
       :on-mutate [::query/set-query-data posts-query-key
                   (fn [current-posts]
                     (into [values] (or current-posts [])))]}]]]])

;; 2. Reactive view inspecting cache state from context
(defn view
  [_state context]
  (let [posts     (query/data context posts-query-key [])
        loading?  (query/loading? context posts-query-key)
        fetching? (query/fetching? context posts-query-key)
        stale?    (query/stale? context posts-query-key 5000)]
    [:div
     ;; Cache-first query trigger
     [:button {:on {:click [::query/fetch posts-query-key
                            {:base-url   \"https://jsonplaceholder.typicode.com\"
                             :stale-time 10000}]}}
      (if fetching? \"Fetching...\" \"Fetch Posts\")]

     ;; Hierarchical cache invalidation trigger
     [:button {:on {:click [::query/invalidate [:posts] {:refetch-active? true}]}}
      \"Invalidate Cache\"]

     ;; Rendered cached data
     [:ul
      (for [post posts]
        ^{:key (:id post)}
        [:li (:title post)])]]))

(def QueryExample
  (relm/component
    {:view view}))")
