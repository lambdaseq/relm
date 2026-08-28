(ns examples.http
  "HTTP request example component demonstrating asynchronous fetch side effects with `com.lambdaseq.relm.http`.

  Demonstrates:
  - Initializing local state for async data (`:posts`)
  - Triggering HTTP fetch side effect via `[::relm.http/fetch ...]`
  - Handling successful responses and parsing JSON payloads into state
  - Handling fetch failure callbacks
  - Rendering loading / data states in Hiccup"
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.http :as relm.http]))

;; -----------------------------------------------------------------------------
;; Component Initialization
;; -----------------------------------------------------------------------------

(defn init
  "Initializes the component state with an empty list of posts."
  [_context _args]
  {:posts []})

;; -----------------------------------------------------------------------------
;; Update Handlers
;; -----------------------------------------------------------------------------

;; Handles successful fetch response, parsing JSON response body into Clojure data.
(defmethod relm/update ::posts-fetched
  [state context [_ {:keys [body]}] _event]
  (let [posts (if (string? body)
                (js->clj (js/JSON.parse body) :keywordize-keys true)
                body)]
    [(assoc state :posts posts) context]))

;; Handles fetch failure or network error.
(defmethod relm/update ::posts-failed
  [state context [_ response] _event]
  [(assoc state :error (:problem response)) context])

;; Emits the `::relm.http/fetch` side effect to retrieve posts from JSONPlaceholder API.
(defmethod relm/update ::fetch-posts
  [state context _ _event]
  [state context [::relm.http/fetch
                  {:url        "https://jsonplaceholder.typicode.com/posts"
                   :method     :get
                   :mode       :cors
                   :on-success [::posts-fetched]
                   :on-failure [::posts-failed]}]])

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defn view
  "Renders list of fetched posts or an empty state placeholder with fetch button."
  [{:keys [posts]} _context]
  [:div [:h1 "Posts"]
   (if (seq posts)
     [:ul
      (for [post posts]
        [:li {:key (:id post)}
         [:a {:href (:url post)} (:title post)]])]
     [:p "No posts yet"])
   [:button {:on {:click [::fetch-posts]}}
    "Fetch Posts"]])

;; -----------------------------------------------------------------------------
;; Component Definition
;; -----------------------------------------------------------------------------

(def HttpExample
  "HTTP Example component ready to be mounted."
  (relm/component
    {:init init
     :view view}))

