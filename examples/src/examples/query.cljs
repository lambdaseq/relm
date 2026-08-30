(ns examples.query
  "TanStack Query showcase component for Relm.

  Demonstrates:
  - Declarative data fetching and caching with vector keys (`[::query/update [:posts {:_limit 5}]]`)
  - Context view inspection with `query/data`, `query/loading?`, `query/fetching?`, `query/stale?`, `query/error`
  - Optimistic mutations with automatic query invalidation and refetching
  - Form state management using `com.lambdaseq.relm.form` (`form/create`, `form/register`, `form/on-submit`)
  - Real-time cache inspector visualizing queries and mutations in the global Relm context"
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.form :as form]
            [com.lambdaseq.relm.query :as query]
            [examples.snippets :as snippets]
            [examples.ui :as ui]))

;; -----------------------------------------------------------------------------
;; Query Keys
;; -----------------------------------------------------------------------------

(def posts-query-key
  [:posts {:_limit 5}])

;; -----------------------------------------------------------------------------
;; Update Handlers
;; -----------------------------------------------------------------------------

(defmethod relm/update ::add-post
  [state context [_ values] _event]
  (let [new-state (clojure.core/update state :form form/reset-form)]
    [new-state
     context
     [[::relm/dispatch!
       [::query/mutate [:posts]
        {:base-url  "https://jsonplaceholder.typicode.com"
         :data      values
         :on-mutate [::query/set-query-data posts-query-key
                     (fn [current-posts]
                       (into [values] (or current-posts [])))]}]]]]))

;; -----------------------------------------------------------------------------
;; Component Initialization
;; -----------------------------------------------------------------------------

(defn init
  "Initializes form state for creating a new post."
  [_context _args]
  {:form (form/create {:initial-values {:title ""
                                        :body  ""}
                       :validators     {:title (form/required "Title is required")}})})

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defn view
  "Renders the Query showcase UI."
  [{:keys [form]} context]
  (let [posts (query/data context posts-query-key [])
        loading? (query/loading? context posts-query-key)
        fetching? (query/fetching? context posts-query-key)
        stale? (query/stale? context posts-query-key 5000)
        err (query/error context posts-query-key)
        mutation-loading? (query/mutation-loading? context [:posts])
        title-err (form/error form :title)]
    [:div {:class "max-w-5xl mx-auto"}
     (ui/example-header
      {:step        "6"
       :title       "Relm Query (TanStack Query Port)"
       :difficulty  "Advanced"
       :description "Declarative server-state caching, automatic URL inference from vector keys, optimistic mutations, background fetching, and cache invalidation using `com.lambdaseq.relm.query`."
       :tags        ["relm.query" "Server-State Cache" "Optimistic Mutations" "Stale-While-Revalidate" "Cache Invalidation"]})

     ;; Query Controls Bar
     (ui/card
      {:class "mb-6 border-slate-200"}
      [:div
       (ui/card-header
        [:div {:class "flex flex-wrap items-center justify-between gap-4"}
         [:div
          (ui/card-title "Query Key: [:posts {:_limit 5}]")
          (ui/card-description "Trigger cache-first queries, manual background refetches, or hierarchical invalidations.")]
         [:div {:class "flex items-center gap-2"}
          (if (seq posts)
            (if stale?
              (ui/badge {:variant :warning} "STALE (5s+ old)")
              (ui/badge {:variant :success} "FRESH CACHE"))
            (ui/badge {:variant :secondary} "UNFETCHED"))
          (when fetching?
            (ui/badge {:variant :indigo} "FETCHING IN BACKGROUND"))]])

       (ui/card-footer
        [:div {:class "flex flex-wrap items-center gap-3 w-full"}
         (ui/button
          {:variant :default
           :class   "bg-indigo-600 hover:bg-indigo-700 text-white"
           :disabled fetching?
           :on      {:click [::query/fetch posts-query-key {:base-url   "https://jsonplaceholder.typicode.com"
                                                            :stale-time 10000}]}}
          (if fetching? "Fetching..." "Fetch Posts (Cache-First)"))

         (ui/button
          {:variant :outline
           :on      {:click [::query/fetch posts-query-key {:base-url "https://jsonplaceholder.typicode.com"
                                                            :force?   true}]}}
          "Force Refetch (Bypass Cache)")

         (ui/button
          {:variant :secondary
           :class   "text-amber-800 bg-amber-50 hover:bg-amber-100 border-amber-200"
           :on      {:click [::query/invalidate [:posts] {:refetch-active? true}]}}
          "Invalidate [:posts]")])])

     ;; Main Grid: Mutation Form + Posts List
     [:div {:class "grid grid-cols-1 lg:grid-cols-12 gap-8 mb-8 items-start"}
      ;; Optimistic Mutation Form
      [:div {:class "lg:col-span-5"}
       (ui/card
        {:class "border-slate-200 shadow-sm"}
        [:div
         (ui/card-header
          (ui/card-title "Optimistic Mutation: Add Post")
          (ui/card-description "Immediately prepends new post to cache UI before network responds."))
         (ui/card-content
          [:form (form/form-attrs form {:on {:submit [::form/submit form {:on-submit [::add-post]}]}})
           [:div {:class "space-y-3"}
            [:div
             (ui/label {:required? true} "Post Title")
             (ui/input (merge (form/register form :title {:placeholder "e.g., Relm Architecture in Practice"
                                                          :required    "Title is required"})
                              {:error? (boolean title-err)}))
             (when title-err
               [:p {:class "text-xs font-medium text-red-600 mt-1"} title-err])]

            [:div
             (ui/label "Post Body Content")
             (ui/input (merge (form/register form :body {:placeholder "Brief description or message..."})))]

            [:div {:class "pt-2"}
             (ui/button
              {:type     :submit
               :variant  :default
               :class    "w-full bg-slate-900 hover:bg-slate-800 text-white"
               :disabled mutation-loading?}
              (if mutation-loading? "Committing Mutation..." "Create Post (Optimistic)"))]]])])]

      ;; Posts List Panel
      [:div {:class "lg:col-span-7 space-y-4"}
       [:div {:class "flex items-center justify-between"}
        [:h3 {:class "text-base font-semibold text-slate-900"} "Cached Server-State Posts"]
        [:span {:class "text-xs font-mono text-slate-500"} (str (count posts) " items")]]

       (cond
         loading?
         [:div {:class "space-y-3"}
          (for [i (range 3)]
            ^{:key i}
            (ui/card
             {:class "border-slate-200 animate-pulse p-4"}
             [:div {:class "space-y-2"}
              [:div {:class "h-4 bg-slate-200 rounded w-1/3"}]
              [:div {:class "h-8 bg-slate-100 rounded w-full"}]]))]

         err
         (ui/alert
          {:variant :destructive}
          [:div
           [:h4 {:class "font-bold text-sm mb-1"} "Query Execution Error"]
           [:p {:class "text-xs"} (str (or (:problem-message err) err))]])

         (seq posts)
         [:div {:class "space-y-3"}
          (for [p posts]
            ^{:key (or (:id p) (str (rand)))}
            (ui/card
             {:class "border-slate-200 hover:border-slate-300 transition-all shadow-2xs"}
             [:div {:class "p-4"}
              [:div {:class "flex items-center justify-between gap-2 mb-1"}
               [:h4 {:class "font-semibold text-sm text-slate-900 capitalize"}
                (:title p)]
               (when-let [id (:id p)]
                 (ui/badge {:variant :secondary :class "font-mono text-[10px]"} (str "#" id)))]
              (when-let [body (:body p)]
                [:p {:class "text-xs text-slate-600 leading-relaxed line-clamp-2 mt-1"}
                 body])]))]

         :else
         (ui/card
          {:class "border-dashed border-2 border-slate-300 bg-slate-50/50"}
          [:div {:class "flex flex-col items-center justify-center p-8 text-center"}
           [:span {:class "text-2xl mb-2"} "⚡"]
           [:h4 {:class "text-sm font-semibold text-slate-800 mb-1"} "No Cached Server Records"]
           [:p {:class "text-xs text-slate-500 mb-3"} "Click 'Fetch Posts (Cache-First)' to load query data from API."]
           (ui/button
            {:variant :outline
             :size    :sm
             :on      {:click [::query/fetch posts-query-key {:base-url   "https://jsonplaceholder.typicode.com"
                                                              :stale-time 10000}]}}
            "Fetch Now")]))]]

     ;; Context Cache Inspector
     (ui/code-inspector
      {:title      "Global Relm Context Cache"
       :subtitle   ":queries & :mutations"
       :badge-text "SERVER STATE"}
      [:div {:class "space-y-3"}
       [:pre {:class "bg-slate-900/90 p-3 rounded-lg border border-slate-800 text-slate-200 overflow-x-auto text-[11px] leading-relaxed"}
        (let [cache-state {:queries   (into {} (map (fn [[k v]] [k (dissoc v :options)]) (:queries context)))
                           :mutations (:mutations context)}]
          (pr-str cache-state))]])

     ;; Expandable Source Code Panel
     (ui/code-panel
      {:title    "Relm Query Example Source Code"
       :filename "query.cljs"
       :code     snippets/query-code})]))

;; -----------------------------------------------------------------------------
;; Component Export
;; -----------------------------------------------------------------------------

(def QueryExample
  "Query example component."
  (relm/component
   {:init init
    :view view}))
