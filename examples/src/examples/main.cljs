(ns examples.main
  "Main entry point for the Relm examples application.

  Demonstrates:
  - Setting up routing with Metosin Reitit and `com.lambdaseq.relm.reitit`
  - Registering Replicant DOM dispatch via `(replicant.dom/set-dispatch! relm/dispatch)`
  - Subscribing to active route information via Relm context (`current-route`, `current-view`)
  - Rendering top-level navigation with declarative route navigation events (`::relm.reitit/navigate-to`)
  - Mounting the root application component into `js/document.body`"
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.reitit :as relm.reitit]
            [examples.counter :refer [Counter]]
            [examples.form :refer [FormExample]]
            [examples.http :refer [HttpExample]]
            [examples.navigation :refer [NavigationExample]]
            [examples.nested :refer [NestedExample]]
            [examples.query :refer [QueryExample]]
            [reitit.core :as reitit]
            [replicant.dom :as r]))

;; -----------------------------------------------------------------------------
;; Application Routes & Router Definition
;; -----------------------------------------------------------------------------

(def routes
  "Reitit route definitions mapping URL paths to route metadata and view factories."
  [["/" {:name :home
         :path "/"
         :view (fn [] (NestedExample {}))}]
   ["/nested" {:name :nested
               :path "/nested"
               :view (fn [] (NestedExample {}))}]
   ["/counter" {:name :counter
                :path "/counter"
                :view (fn [] (Counter {:init-count 0}))}]
   ["/form" {:name :form
             :path "/form"
             :view (fn [] (FormExample {}))}]
   ["/http" {:name :http
             :path "/http"
             :view (fn [] (HttpExample {}))}]
   ["/query" {:name :query
              :path "/query"
              :view (fn [] (QueryExample {}))}]
   ["/navigation" {:name :navigation
                   :path "/navigation"
                   :view (fn [] (NavigationExample {}))}]])

(def router
  "Reitit router compiled from application routes table."
  (reitit/router routes))

;; -----------------------------------------------------------------------------
;; Root Navigation & View Layout
;; -----------------------------------------------------------------------------

(defn view
  "Renders the root application layout containing:
  1. Top navigation bar with active route highlighting.
  2. Dynamically rendered page view resolved from the current Reitit match in context."
  [_state context]
  (let [current-route (relm.reitit/current-route context)
        view-fn (relm.reitit/current-view context)]
    [:div {:style {:font-family "system-ui, -apple-system, sans-serif"
                   :padding     "20px"}}
     [:div {:style {:display        "flex"
                    :gap            "8px"
                    :margin-bottom  "20px"
                    :padding-bottom "12px"
                    :border-bottom  "1px solid #e5e7eb"}}
      [:button {:style {:padding          "8px 16px"
                        :border-radius    "6px"
                        :border           "1px solid #d1d5db"
                        :background-color (if (#{:nested :home} current-route) "#4f46e5" "#f3f4f6")
                        :color            (if (#{:nested :home} current-route) "#ffffff" "#111827")
                        :font-weight      (if (#{:nested :home} current-route) "600" "normal")
                        :cursor           "pointer"}
                :on    {:click [::relm.reitit/navigate-to "/nested"]}}
       "Nested Components"]
      [:button {:style {:padding          "8px 16px"
                        :border-radius    "6px"
                        :border           "1px solid #d1d5db"
                        :background-color (if (= current-route :counter) "#4f46e5" "#f3f4f6")
                        :color            (if (= current-route :counter) "#ffffff" "#111827")
                        :font-weight      (if (= current-route :counter) "600" "normal")
                        :cursor           "pointer"}
                :on    {:click [::relm.reitit/navigate-to "/counter"]}}
       "Counter"]
      [:button {:style {:padding          "8px 16px"
                        :border-radius    "6px"
                        :border           "1px solid #d1d5db"
                        :background-color (if (= current-route :form) "#4f46e5" "#f3f4f6")
                        :color            (if (= current-route :form) "#ffffff" "#111827")
                        :font-weight      (if (= current-route :form) "600" "normal")
                        :cursor           "pointer"}
                :on    {:click [::relm.reitit/navigate-to "/form"]}}
       "Form"]
      [:button {:style {:padding          "8px 16px"
                        :border-radius    "6px"
                        :border           "1px solid #d1d5db"
                        :background-color (if (= current-route :http) "#4f46e5" "#f3f4f6")
                        :color            (if (= current-route :http) "#ffffff" "#111827")
                        :font-weight      (if (= current-route :http) "600" "normal")
                        :cursor           "pointer"}
                :on    {:click [::relm.reitit/navigate-to "/http"]}}
       "HTTP"]
      [:button {:style {:padding          "8px 16px"
                        :border-radius    "6px"
                        :border           "1px solid #d1d5db"
                        :background-color (if (= current-route :query) "#4f46e5" "#f3f4f6")
                        :color            (if (= current-route :query) "#ffffff" "#111827")
                        :font-weight      (if (= current-route :query) "600" "normal")
                        :cursor           "pointer"}
                :on    {:click [::relm.reitit/navigate-to "/query"]}}
       "Query"]
      [:button {:style {:padding          "8px 16px"
                        :border-radius    "6px"
                        :border           "1px solid #d1d5db"
                        :background-color (if (= current-route :navigation) "#4f46e5" "#f3f4f6")
                        :color            (if (= current-route :navigation) "#ffffff" "#111827")
                        :font-weight      (if (= current-route :navigation) "600" "normal")
                        :cursor           "pointer"}
                :on    {:click [::relm.reitit/navigate-to "/navigation"]}}
       "Navigation"]]
     (if view-fn
       (view-fn)
       [:div {:style {:display         "flex"
                      :justify-content "center"
                      :align-items     "center"
                      :padding         "40px"}}
        [:div {:style {:color     "#6b7280"
                       :font-size "16px"}}
         "Loading..."]])]))

(def Examples
  "Root Relm component wrapping the example application shell."
  (relm/component
    {:view view}))

;; -----------------------------------------------------------------------------
;; Bootstrap
;; -----------------------------------------------------------------------------

;; Register Relm's message dispatcher as the Replicant event handler
(r/set-dispatch! relm/dispatch!)

;; Initialize router, history listener, and sync route match into Relm context
(relm.reitit/start! router {:default-path "/nested"})

;; Mount root component to DOM body
(relm/render js/document.body Examples {:id "examples-root"})
