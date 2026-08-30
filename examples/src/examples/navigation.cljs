(ns examples.navigation
  "Navigation example component demonstrating browser and History API effects via `relm.navigation`.

  Demonstrates:
  - Page redirection via `[::nav/navigate-to url]` (`location.assign`)
  - Page refresh via `[::nav/reload]` (`location.reload`)
  - URL replacement via `[::nav/replace url]` (`location.replace`)
  - Browser history stack manipulation via `[::nav/push-state ...]`, `[::nav/replace-state ...]`, `[::nav/back]`, and `[::nav/go n]`"
  (:require [examples.snippets :as snippets]
            [examples.ui :as ui]
            [relm.core :as relm]
            [relm.navigation :as nav]))

;; -----------------------------------------------------------------------------
;; Component Initialization
;; -----------------------------------------------------------------------------

(defn init
  "Initializes state with current window URL."
  [_context _args]
  {:current-url (if (exists? js/window) (.. js/window -location -href) "/")
   :history-position 0})

;; -----------------------------------------------------------------------------
;; Update Handlers
;; -----------------------------------------------------------------------------

;; Updates local state with the current URL string.
(defmethod relm/update ::update-current-url
  [state context _ _]
  [(assoc state :current-url (if (exists? js/window) (.. js/window -location -href) "/")) context])

;; Dispatches a navigation side effect to assign a new URL.
(defmethod relm/update ::navigate-to
  [state context [_ url] _]
  [state context [[::nav/navigate-to! url]]])

;; Dispatches a page reload side effect.
(defmethod relm/update ::reload-page
  [state context _ _]
  [state context [[::nav/reload!]]])

;; Dispatches a URL replace side effect without adding a history record.
(defmethod relm/update ::replace-url
  [state context [_ url] _]
  [state context [[::nav/replace! url]]])

;; Dispatches a side effect to navigate back in browser history.
(defmethod relm/update ::go-back
  [state context _ _]
  [state context [[::nav/back!]]])

;; Dispatches a push-state effect to push a new entry onto the HTML5 history stack.
(defmethod relm/update ::push-state
  [state context [_ url] _]
  (let [next-state (assoc state :current-url (if (exists? js/window) (str (.. js/window -location -origin) url) url))]
    [next-state context [[::nav/push-state! {:page url} url]]]))

;; Dispatches a replace-state effect to update current history entry.
(defmethod relm/update ::replace-state
  [state context [_ url] _]
  (let [next-state (assoc state :current-url (if (exists? js/window) (str (.. js/window -location -origin) url) url))]
    [next-state context [[::nav/replace-state! {:page url} url]]]))

;; Dispatches a history.go effect with relative step integer `n`.
(defmethod relm/update ::go-to-position
  [state context [_ n] _]
  [state context [[::nav/go! n]]])

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defn view
  "Renders controls to test location and HTML5 history API side effects."
  [{:keys [current-url]} _context]
  [:div {:class "max-w-4xl mx-auto"}
   (ui/example-header
    {:step        "3"
     :title       "Navigation & History FX"
     :difficulty  "Beginner"
     :description "Declarative browser window location and HTML5 History API side effects using `relm.navigation`."
     :tags        ["relm.navigation" "push-state!" "replace-state!" "History API" "Location FX"]})

   ;; Current Browser URL Inspector
   (ui/card
    {:class "mb-6 border-slate-200 bg-slate-900 text-white"}
    [:div
     (ui/card-header
      [:div {:class "flex items-center justify-between"}
       [:div {:class "flex items-center gap-2"}
        [:span {:class "h-2.5 w-2.5 rounded-full bg-emerald-400 animate-pulse"}]
        (ui/card-title {:class "text-white text-base font-mono"} "Browser Location State")]
       (ui/button
        {:variant :ghost
         :size    :sm
         :class   "text-slate-300 hover:text-white hover:bg-slate-800"
         :on      {:click [::update-current-url]}}
        "↻ Sync URL")])
     (ui/card-content
      [:div {:class "bg-slate-950 p-3 rounded-lg border border-slate-800 font-mono text-xs text-emerald-400 break-all select-all"}
       current-url])])

   [:div {:class "grid grid-cols-1 md:grid-cols-2 gap-6 mb-6"}
    ;; Basic Page Navigation Card
    (ui/card
     {:class "border-slate-200"}
     [:div
      (ui/card-header
       (ui/card-title "Location Actions")
       (ui/card-description "Effects modifying `window.location` directly."))
      (ui/card-content
       [:div {:class "space-y-3"}
        [:div {:class "flex items-center justify-between p-3 rounded-lg bg-slate-50 border border-slate-200"}
         [:div
          [:div {:class "text-sm font-medium text-slate-800"} "GitHub Repository"]
          [:div {:class "text-xs text-slate-500"} "navigate-to! location.assign"]]
         (ui/button
          {:variant :outline
           :size    :sm
           :on      {:click [::navigate-to "https://github.com/conjurernix/relm"]}}
          "Open ↗")]

        [:div {:class "flex items-center justify-between p-3 rounded-lg bg-slate-50 border border-slate-200"}
         [:div
          [:div {:class "text-sm font-medium text-slate-800"} "Reload Current Window"]
          [:div {:class "text-xs text-slate-500"} "reload! location.reload"]]
         (ui/button
          {:variant :outline
           :size    :sm
           :on      {:click [::reload-page]}}
          "Reload")]

        [:div {:class "flex items-center justify-between p-3 rounded-lg bg-slate-50 border border-slate-200"}
         [:div
          [:div {:class "text-sm font-medium text-slate-800"} "Replace URL"]
          [:div {:class "text-xs text-slate-500"} "replace! location.replace"]]
         (ui/button
          {:variant :outline
           :size    :sm
           :on      {:click [::replace-url "https://github.com/conjurernix/relm"]}}
          "Replace")]])])

    ;; History Navigation Card
    (ui/card
     {:class "border-slate-200"}
     [:div
      (ui/card-header
       (ui/card-title "History Stack Actions")
       (ui/card-description "Manipulate HTML5 history without full reload."))
      (ui/card-content
       [:div {:class "space-y-3"}
        [:div {:class "flex items-center justify-between p-3 rounded-lg bg-slate-50 border border-slate-200"}
         [:div
          [:div {:class "text-sm font-medium text-slate-800"} "Push Route State"]
          [:div {:class "text-xs text-slate-500"} "push-state! /pushed-demo"]]
         (ui/button
          {:variant :default
           :size    :sm
           :class   "bg-indigo-600 hover:bg-indigo-700"
           :on      {:click [::push-state "/pushed-demo"]}}
          "Push Entry")]

        [:div {:class "flex items-center justify-between p-3 rounded-lg bg-slate-50 border border-slate-200"}
         [:div
          [:div {:class "text-sm font-medium text-slate-800"} "Replace Route State"]
          [:div {:class "text-xs text-slate-500"} "replace-state! /replaced-demo"]]
         (ui/button
          {:variant :outline
           :size    :sm
           :on      {:click [::replace-state "/replaced-demo"]}}
          "Replace Entry")]

        [:div {:class "grid grid-cols-2 gap-2 pt-1"}
         (ui/button
          {:variant :secondary
           :size    :sm
           :on      {:click [::go-back]}}
          "← Back (back!)")
         (ui/button
          {:variant :secondary
           :size    :sm
           :on      {:click [::go-to-position 1]}}
          "Forward (go! 1) →")]])])]

   ;; Note Box
   (ui/alert
    {:variant :info}
    [:div {:class "flex items-start gap-3"}
     [:span {:class "text-base"} "ℹ️"]
     [:div
      [:h4 {:class "font-semibold text-sm mb-0.5"} "Browser Integration Note"]
      [:p {:class "text-xs text-slate-600 leading-relaxed"}
       "Navigation effects operate directly on standard browser APIs (`window.location` & `window.history`). When combined with Reitit routing (`relm.reitit`), route changes automatically synchronise with component state."]]])

   ;; Expandable Source Code Panel
   (ui/code-panel
    {:title    "Navigation Example Source Code"
     :filename "navigation.cljs"
     :code     snippets/navigation-code})])

;; -----------------------------------------------------------------------------
;; Component Definition
;; -----------------------------------------------------------------------------

(def NavigationExample
  "Navigation Example component."
  (relm/component
   {:init init
    :view view}))
