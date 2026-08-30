(ns examples.http
  "HTTP request example component demonstrating asynchronous fetch side effects with `relm.http`.

  Demonstrates:
  - Initializing local state for async data (`:posts`, `:loading?`, `:error`)
  - Triggering HTTP fetch side effect via `[::relm.http/fetch ...]`
  - Handling successful responses with automatically decoded JSON payloads into state
  - Handling fetch failure callbacks
  - Rendering loading, empty, and data states using shadcn UI components"
  (:require [examples.snippets :as snippets]
            [examples.ui :as ui]
            [relm.core :as relm]
            [relm.http :as relm.http]))

;; -----------------------------------------------------------------------------
;; Component Initialization
;; -----------------------------------------------------------------------------

(defn init
  "Initializes the component state with an empty list of posts and loading false."
  [_context _args]
  {:posts    []
   :loading? false
   :error    nil})

;; -----------------------------------------------------------------------------
;; Update Handlers
;; -----------------------------------------------------------------------------

;; Handles successful fetch response with automatically decoded JSON response body.
(defmethod relm/update ::posts-fetched
  [state context [_ {:keys [body]}] _event]
  [(assoc state
          :posts (vec (take 6 (or body [])))
          :loading? false
          :error nil)
   context])

;; Handles fetch failure or network error.
(defmethod relm/update ::posts-failed
  [state context [_ response] _event]
  [(assoc state
          :loading? false
          :error (or (:problem response) "Failed to load posts from API"))
   context])

;; Emits the `::relm.http/fetch!` side effect to retrieve posts from JSONPlaceholder API.
(defmethod relm/update ::fetch-posts
  [state context _ _event]
  [(assoc state :loading? true :error nil)
   context
   [[::relm.http/fetch!
     {:url        "https://jsonplaceholder.typicode.com/posts"
      :method     :get
      :mode       :cors
      :on-success [::posts-fetched]
      :on-failure [::posts-failed]}]]])

;; Clears the loaded posts from local state.
(defmethod relm/update ::clear-posts
  [state context _ _event]
  [(assoc state :posts [] :error nil :loading? false) context])

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defn view
  "Renders list of fetched posts or an empty/loading state placeholder with fetch button."
  [{:keys [posts loading? error]} _context]
  [:div {:class "max-w-4xl mx-auto"}
   (ui/example-header
    {:step        "2"
     :title       "HTTP Requests"
     :difficulty  "Beginner"
     :description "Demonstrates asynchronous, side-effect-driven network requests using `relm.http` to communicate with external JSON REST APIs without coupling views to IO."
     :tags        ["relm.http/fetch!" "async" "REST API" "JSON decoding" "Error Handling"]})

   ;; Controls Bar Card
   (ui/card
    {:class "mb-6 border-slate-200"}
    [:div
     (ui/card-header
      [:div {:class "flex flex-wrap items-center justify-between gap-4"}
       [:div
        (ui/card-title "JSONPlaceholder API Fetcher")
        (ui/card-description "Dispatches `::relm.http/fetch!` to fetch sample posts over CORS.")]
       [:div {:class "flex items-center gap-2"}
        (cond
          loading?
          (ui/badge {:variant :indigo} "Fetching API...")

          (seq posts)
          (ui/badge {:variant :success} (str (count posts) " Posts Loaded"))

          error
          (ui/badge {:variant :destructive} "Network Error")

          :else
          (ui/badge {:variant :secondary} "Idle"))]])

     (ui/card-footer
      [:div {:class "flex items-center gap-3 w-full"}
       (ui/button
        {:variant  :default
         :disabled loading?
         :on       {:click [::fetch-posts]}}
        (if loading? "Requesting JSON..." "Fetch Posts from API"))
       (ui/button
        {:variant   :outline
         :disabled? (and (empty? posts) (nil? error))
         :on        {:click [::clear-posts]}}
        "Clear")])])

   ;; Error Banner
   (when error
     [:div {:class "mb-6"}
      (ui/alert
       {:variant :destructive}
       [:div {:class "flex items-center justify-between"}
        [:div
         [:h4 {:class "font-semibold mb-1"} "Request Failed"]
         [:p {:class "text-sm"} (str error)]]
        (ui/button
         {:variant :outline
          :size    :sm
          :class   "border-red-300 text-red-900 hover:bg-red-100"
          :on      {:click [::fetch-posts]}}
         "Retry")])])

   ;; Posts Content / Skeletons / Empty State
   (cond
     loading?
     [:div {:class "grid grid-cols-1 md:grid-cols-2 gap-4"}
      (for [i (range 4)]
        ^{:key i}
        (ui/card
         {:class "border-slate-200 animate-pulse bg-white p-6"}
         [:div {:class "space-y-3"}
          [:div {:class "h-4 bg-slate-200 rounded w-1/4"}]
          [:div {:class "h-5 bg-slate-200 rounded w-3/4"}]
          [:div {:class "h-12 bg-slate-100 rounded w-full"}]]))]

     (seq posts)
     [:div {:class "space-y-4"}
      [:div {:class "flex items-center justify-between"}
       [:h3 {:class "text-lg font-semibold text-slate-800"} "Fetched API Records"]
       [:span {:class "text-xs text-slate-500 font-mono"} "endpoint: /posts?limit=6"]]
      [:div {:class "grid grid-cols-1 md:grid-cols-2 gap-4"}
       (for [post posts]
         ^{:key (:id post)}
         (ui/card
          {:class "hover:shadow-md hover:border-slate-300 transition-all border-slate-200"}
          [:div
           (ui/card-header
            [:div {:class "flex items-center justify-between mb-2"}
             (ui/badge {:variant :secondary :class "font-mono"} (str "POST #" (:id post)))
             [:span {:class "text-xs text-slate-400 font-mono"} (str "User " (:userId post))]]
            (ui/card-title {:class "text-base capitalize line-clamp-1"} (:title post)))
           (ui/card-content
            [:p {:class "text-sm text-slate-600 line-clamp-3 leading-relaxed"}
             (:body post)])]))]]

     :else
     (ui/card
      {:class "border-dashed border-2 border-slate-300 bg-slate-50/50"}
      [:div {:class "flex flex-col items-center justify-center p-12 text-center"}
       [:div {:class "h-12 w-12 rounded-full bg-slate-100 flex items-center justify-center text-slate-400 mb-3 text-xl"}
        "🌐"]
       [:h3 {:class "text-base font-semibold text-slate-800 mb-1"} "No Posts in Local State"]
       [:p {:class "text-sm text-slate-500 max-w-sm mb-4"}
        "Click the 'Fetch Posts from API' button to dispatch an asynchronous HTTP effect to JSONPlaceholder."]
       (ui/button
        {:variant :outline
         :on      {:click [::fetch-posts]}}
        "Fetch Posts Now")]))

   ;; Expandable Source Code Panel
   (ui/code-panel
    {:title    "HTTP Fetch Example Source Code"
     :filename "http.cljs"
     :code     snippets/http-code})])

;; -----------------------------------------------------------------------------
;; Component Definition
;; -----------------------------------------------------------------------------

(def HttpExample
  "HTTP Example component ready to be mounted."
  (relm/component
   {:init init
    :view view}))
