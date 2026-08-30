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
            [com.lambdaseq.relm.query :as query]))

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
    [:div {:style {:max-width "800px" :margin "0 auto"}}
     [:div {:style {:margin-bottom "24px"}}
      [:h1 {:style {:font-size "24px" :font-weight "700" :margin-bottom "8px"}}
       "Relm Query (TanStack Query Port)"]
      [:p {:style {:color "#4b5563" :font-size "14px"}}
       "Declarative server-state caching, automatic URL inference, optimistic mutations, and hierarchical cache invalidation."]]

     ;; Controls Bar
     [:div {:style {:display          "flex"
                    :gap              "12px"
                    :align-items      "center"
                    :padding          "16px"
                    :background-color "#f9fafb"
                    :border           "1px solid #e5e7eb"
                    :border-radius    "8px"
                    :margin-bottom    "20px"}}
      [:button {:style {:padding          "8px 16px"
                        :background-color "#4f46e5"
                        :color            "white"
                        :border           "none"
                        :border-radius    "6px"
                        :cursor           "pointer"
                        :font-weight      "500"}
                :on    {:click [::query/fetch posts-query-key {:base-url   "https://jsonplaceholder.typicode.com"
                                                               :stale-time 10000}]}}
       (if fetching? "Fetching..." "Fetch Posts (Cache-First)")]

      [:button {:style {:padding          "8px 16px"
                        :background-color "#059669"
                        :color            "white"
                        :border           "none"
                        :border-radius    "6px"
                        :cursor           "pointer"
                        :font-weight      "500"}
                :on    {:click [::query/fetch posts-query-key {:base-url "https://jsonplaceholder.typicode.com"
                                                               :force?   true}]}}
       "Force Refetch"]

      [:button {:style {:padding          "8px 16px"
                        :background-color "#d97706"
                        :color            "white"
                        :border           "none"
                        :border-radius    "6px"
                        :cursor           "pointer"
                        :font-weight      "500"}
                :on    {:click [::query/invalidate [:posts] {:refetch-active? true}]}}
       "Invalidate [:posts]"]

      [:div {:style {:margin-left "auto" :display "flex" :gap "8px" :align-items "center"}}
       [:span {:style {:font-size        "13px"
                       :padding          "4px 8px"
                       :border-radius    "4px"
                       :background-color (if stale? "#fee2e2" "#dcfce7")
                       :color            (if stale? "#991b1b" "#166534")
                       :font-weight      "600"}}
        (if stale? "STALE" "FRESH")]
       (when fetching?
         [:span {:style {:font-size        "13px"
                         :padding          "4px 8px"
                         :border-radius    "4px"
                         :background-color "#dbeafe"
                         :color            "#1e40af"
                         :font-weight      "600"}}
          "FETCHING"])]]

     ;; Mutation Form
     [:div {:style {:padding       "16px"
                    :border        "1px solid #e5e7eb"
                    :border-radius "8px"
                    :margin-bottom "20px"}}
      [:h2 {:style {:font-size "16px" :font-weight "600" :margin-bottom "12px"}}
       "Optimistic Mutation: Add Post"]
      [:form (form/form-attrs form {:on    {:submit [::form/submit form {:on-submit [::add-post]}]}
                                    :style {:display "flex" :gap "12px" :margin-bottom "8px"}})
       [:input (merge (form/register form :title {:placeholder "Post Title..."
                                                  :required    "Title is required"})
                      {:style {:flex          "1"
                               :padding       "8px 12px"
                               :border        (str "1px solid " (if title-err "#ef4444" "#d1d5db"))
                               :border-radius "6px"
                               :outline       "none"}})]
       [:input (merge (form/register form :body {:placeholder "Post Body..."})
                      {:style {:flex          "2"
                               :padding       "8px 12px"
                               :border        "1px solid #d1d5db"
                               :border-radius "6px"
                               :outline       "none"}})]
       [:button {:style    {:padding          "8px 20px"
                            :background-color "#2563eb"
                            :color            "white"
                            :border           "none"
                            :border-radius    "6px"
                            :cursor           (if mutation-loading? "not-allowed" "pointer")
                            :opacity          (if mutation-loading? "0.7" "1")
                            :font-weight      "500"}
                 :disabled mutation-loading?
                 :type     :submit}
        (if mutation-loading? "Submitting..." "Create Post")]]
      (when title-err
        [:div {:style {:color "#dc2626" :font-size "12px" :margin-top "4px"}}
         title-err])]

     ;; Data & Loading States
     [:div {:style {:margin-bottom "24px"}}
      [:h2 {:style {:font-size "18px" :font-weight "600" :margin-bottom "12px"}}
       "Posts List"]
      (cond
        loading?
        [:div {:style {:padding "32px" :text-align "center" :color "#6b7280"}}
         "Loading posts..."]

        err
        [:div {:style {:padding "16px" :background-color "#fef2f2" :border "1px solid #f87171" :border-radius "6px" :color "#991b1b"}}
         (str "Error: " (or (:problem-message err) (str err)))]

        (seq posts)
        [:div {:style {:display "flex" :flex-direction "column" :gap "12px"}}
         (for [p posts]
           [:div {:key   (or (:id p) (str (rand)))
                  :style {:padding          "12px 16px"
                          :border           "1px solid #e5e7eb"
                          :border-radius    "6px"
                          :background-color "white"}}
            [:h3 {:style {:font-size "15px" :font-weight "600" :color "#111827" :margin-bottom "4px"}}
             (:title p)]
            [:p {:style {:font-size "13px" :color "#4b5563" :margin 0}}
             (:body p)]])]

        :else
        [:div {:style {:padding "32px" :text-align "center" :color "#6b7280" :background-color "#f9fafb" :border-radius "6px"}}
         "No posts loaded. Click 'Fetch Posts' above to query API."])]

     ;; Context Cache Inspector
     [:div {:style {:padding          "16px"
                    :background-color "#111827"
                    :color            "#f3f4f6"
                    :border-radius    "8px"
                    :font-family      "monospace"
                    :font-size        "12px"}}
      [:div {:style {:font-weight "700" :color "#93c5fd" :margin-bottom "8px"}}
       "Relm Context Cache Inspector (:queries & :mutations)"]
      [:pre {:style {:margin 0 :overflow-x "auto"}}
       (let [cache-state {:queries   (into {} (map (fn [[k v]] [k (dissoc v :options)]) (:queries context)))
                          :mutations (:mutations context)}]
         (pr-str cache-state))]]]))

;; -----------------------------------------------------------------------------
;; Component Export
;; -----------------------------------------------------------------------------

(def QueryExample
  "Query example component."
  (relm/component
    {:init init
     :view view}))
