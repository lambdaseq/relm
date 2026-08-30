(ns examples.main
  "Main entry point for the Relm examples application.

  Demonstrates:
  - Setting up routing with Metosin Reitit and `com.lambdaseq.relm.reitit`
  - Registering Replicant DOM dispatch via `(replicant.dom/set-dispatch! relm/dispatch!)`
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
            [examples.ui :as ui]
            [reitit.core :as reitit]
            [replicant.dom :as r]))

;; -----------------------------------------------------------------------------
;; Application Routes (Ordered Simplest to Most Complex)
;; -----------------------------------------------------------------------------

(def nav-items
  "Ordered list of example metadata for top-level navigation."
  [{:name :counter    :step "1" :title "Counter"     :subtitle "Elm Basics"     :path "/counter"    :view (fn [] (Counter {:init-count 0}))}
   {:name :http       :step "2" :title "HTTP"        :subtitle "Async Effects"  :path "/http"       :view (fn [] (HttpExample {}))}
   {:name :navigation :step "3" :title "Navigation"  :subtitle "History API"    :path "/navigation" :view (fn [] (NavigationExample {}))}
   {:name :nested     :step "4" :title "Nested"      :subtitle "Tree Hierarchy" :path "/nested"     :view (fn [] (NestedExample {}))}
   {:name :form       :step "5" :title "Form"        :subtitle "Validation Engine" :path "/form"    :view (fn [] (FormExample {}))}
   {:name :query      :step "6" :title "Query"       :subtitle "Server Cache"   :path "/query"      :view (fn [] (QueryExample {}))}])

(def routes
  "Reitit route definitions mapping URL paths to route metadata and view factories."
  (into [["/" {:name :home
               :path "/"
               :view (fn [] (Counter {:init-count 0}))}]]
        (map (fn [{:keys [name path view]}]
               [path {:name name :path path :view view}])
             nav-items)))

(def router
  "Reitit router compiled from application routes table."
  (reitit/router routes))

;; -----------------------------------------------------------------------------
;; Root Navigation & View Layout
;; -----------------------------------------------------------------------------

(defn- nav-tab
  [current-route {:keys [name step title subtitle path]}]
  (let [is-active? (or (= current-route name)
                       (and (= name :counter) (= current-route :home)))]
    [:button {:class (ui/cx "relative flex items-center gap-2 px-3.5 py-2 rounded-lg text-sm font-medium transition-all duration-150 cursor-pointer select-none text-left"
                            (if is-active?
                              "bg-white text-slate-950 shadow-xs border border-slate-200/80 font-semibold"
                              "text-slate-600 hover:text-slate-900 hover:bg-slate-200/60"))
              :on    {:click [::relm.reitit/navigate-to path]}}
     [:span {:class (ui/cx "flex h-5 w-5 items-center justify-center rounded-full text-[10px] font-bold"
                           (if is-active?
                             "bg-slate-900 text-white"
                             "bg-slate-200 text-slate-600"))}
      step]
     [:div {:class "flex flex-col"}
      [:span {:class "leading-none"} title]
      [:span {:class (ui/cx "text-[10px] leading-tight font-normal hidden sm:inline"
                            (if is-active? "text-slate-500" "text-slate-400"))}
       subtitle]]]))

(defn view
  "Renders the root application layout containing:
  1. Hero branding header.
  2. Segmented shadcn navigation tabs.
  3. Dynamically rendered page view resolved from the current Reitit match in context.
  4. Footer."
  [_state context]
  (let [current-route (relm.reitit/current-route context)
        view-fn (relm.reitit/current-view context)]
    [:div {:class "min-h-screen bg-slate-50 text-slate-900 flex flex-col antialiased"}
     ;; Top Brand Header
     [:header {:class "border-b border-slate-200 bg-white sticky top-0 z-30 shadow-2xs"}
      [:div {:class "max-w-6xl mx-auto px-4 py-3 sm:px-6 lg:px-8 flex items-center justify-between"}
       [:div {:class "flex items-center gap-3"}
        [:div {:class "h-9 w-9 rounded-lg bg-indigo-600 flex items-center justify-center text-white font-mono font-bold text-lg shadow-sm"}
         "λ"]
        [:div
         [:div {:class "flex items-center gap-2"}
          [:h1 {:class "text-base font-bold text-slate-900 tracking-tight leading-none"} "Relm"]
          [:span {:class "rounded-full bg-indigo-50 border border-indigo-200 text-indigo-700 px-2 py-0.5 text-[10px] font-semibold tracking-wide uppercase"}
           "Architecture Showcase"]]
         [:p {:class "text-xs text-slate-500 leading-tight hidden sm:block"}
          "Functional, declarative Elm Architecture for ClojureScript applications"]]]

       [:div {:class "flex items-center gap-3"}
        [:a {:href   "https://github.com/lambdaseq/relm"
             :target "_blank"
             :rel    "noopener noreferrer"
             :class  "inline-flex items-center gap-1.5 text-xs font-medium text-slate-600 hover:text-slate-900 bg-slate-100 hover:bg-slate-200 px-3 py-1.5 rounded-md transition-colors border border-slate-200"}
         [:span {:class "font-mono font-bold"} "GitHub"]
         [:span "↗"]]]]]

     ;; Main Shell Container
     [:main {:class "max-w-6xl mx-auto px-4 py-6 sm:px-6 lg:px-8 w-full flex-1 flex flex-col"}
      ;; Navigation Bar Tabs
      [:div {:class "mb-8"}
       [:div {:class "flex items-center justify-between mb-3"}
        [:span {:class "text-xs text-slate-400 font-mono"} "Ordered Learning Path"]]
       [:nav {:class "flex flex-wrap gap-1.5 p-1.5 rounded-xl bg-slate-200/70 border border-slate-200/80 shadow-inner"
              :aria-label "Examples Navigation"}
        (for [item nav-items]
          ^{:key (:name item)}
          (nav-tab current-route item))]]

      ;; Active Route View Container
      [:div {:class "flex-1"}
       (if view-fn
         (view-fn)
         [:div {:class "flex flex-col items-center justify-center p-16 text-center"}
          [:div {:class "h-8 w-8 border-2 border-indigo-600 border-t-transparent rounded-full animate-spin mb-3"}]
          [:p {:class "text-sm text-slate-500 font-medium"} "Loading example..."]])]]

     ;; Footer
     [:footer {:class "border-t border-slate-200 bg-white py-6 mt-12"}
      [:div {:class "max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 flex flex-col sm:flex-row items-center justify-between gap-4 text-xs text-slate-500"}
       [:div {:class "flex items-center gap-2"}
        [:span {:class "font-bold text-slate-700"} "Relm"]
        [:span "•"]
        [:span "Composable Elm Architecture & Reactive State for ClojureScript"]]
       [:div {:class "flex items-center gap-4"}
        [:a {:href "https://github.com/lambdaseq/relm" :target "_blank" :rel "noopener noreferrer" :class "hover:underline"} "Source Code"]
        [:span "•"]
        [:span "Built with Replicant & Tailwind CSS"]]]]]))

(defn init
  "Initializes the root component local state."
  [_context _args]
  nil)

(defn on-init
  "Lifecycle hook that starts the Reitit router upon component initialization."
  [state context _args _event]
  [state context [[::relm/dispatch! [::relm.reitit/start router {:default-path "/counter"}]]]])

(defn on-deinit
  "Lifecycle hook that stops the Reitit router upon component deinitialization."
  [state context _args _event]
  [state context [[::relm/dispatch! [::relm.reitit/stop]]]])

(def Examples
  "Root Relm component wrapping the example application shell."
  (relm/component
   {:init      init
    :on-init   on-init
    :on-deinit on-deinit
    :view      view}))

;; -----------------------------------------------------------------------------
;; Bootstrap
;; -----------------------------------------------------------------------------

;; Register Relm's message dispatcher as the Replicant event handler
(r/set-dispatch! relm/dispatch!)

;; Mount root component to DOM body
(relm/render js/document.body Examples {:id "examples-root"})
