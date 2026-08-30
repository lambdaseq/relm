(ns examples.counter
  "Counter example component demonstrating the fundamental Elm architecture in Relm.

  Demonstrates:
  - Pure state initialization via `init` returning initial state `{:count <int>}`
  - Declarative Hiccup views styled with Tailwind & shadcn UI primitives
  - Pure state transitions with `relm/update` (`::increment`, `::decrement`, `::reset`)
  - Isolated side effects with `relm/fx` (`::relm/alert!` via `::show-count`)"
  (:require [relm.core :as relm]
            [examples.snippets :as snippets]
            [examples.ui :as ui]))

;; -----------------------------------------------------------------------------
;; Component Initialization
;; -----------------------------------------------------------------------------

(defn init
  "Initializes the counter component state from arguments.
  Returns local state map `{:count <int>}`."
  [_context {:keys [init-count] :or {init-count 0} :as _args}]
  {:count init-count})

;; -----------------------------------------------------------------------------
;; Update Handlers
;; -----------------------------------------------------------------------------

;; Increments the counter by 1.
(defmethod relm/update ::increment
  [state context _message _event]
  [(update state :count inc) context])

;; Decrements the counter by 1.
(defmethod relm/update ::decrement
  [state context _message _event]
  [(update state :count dec) context])

;; Resets the counter back to 0.
(defmethod relm/update ::reset
  [state context _message _event]
  [(assoc state :count 0) context])

;; Emits a side effect to show the current count in an alert dialog without modifying state.
(defmethod relm/update ::show-count
  [{:keys [count] :as state} context _message _event]
  [state context [[::relm/alert! (str "Current Count is: " count)]]])

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defn view
  "Renders the interactive counter interface styled with shadcn card and button primitives."
  [{:keys [count]} _context]
  [:div {:class "max-w-2xl mx-auto"}
   (ui/example-header
    {:step        "1"
     :title       "Counter"
     :difficulty  "Beginner"
     :description "The canonical Elm Architecture building block in Relm. Demonstrates pure local state, deterministic event message handling, and side-effect dispatch."
     :tags        ["init" "view" "relm/update" "relm/alert!" "Local State"]})

   ;; Interactive Counter Card
   (ui/card
    {:class "mb-6 shadow-md border-slate-200"}
    [:div
     (ui/card-header
      [:div {:class "flex items-center justify-between"}
       [:div
        (ui/card-title "Interactive Counter")
        (ui/card-description "Click the action buttons below to trigger messages.")]
       (cond
         (pos? count) (ui/badge {:variant :success} (str "+" count))
         (neg? count) (ui/badge {:variant :destructive} (str count))
         :else        (ui/badge {:variant :secondary} "Zero"))])

     (ui/card-content
      [:div {:class "flex flex-col items-center justify-center py-8 bg-slate-50/70 rounded-lg border border-dashed border-slate-200 my-2"}
       [:span {:class "text-xs uppercase tracking-widest font-semibold text-slate-400 mb-1"}
        "Current State Value"]
       [:span {:class (ui/cx "text-7xl font-extrabold tracking-tight font-mono transition-all duration-150"
                             (cond
                               (pos? count) "text-emerald-600"
                               (neg? count) "text-rose-600"
                               :else        "text-slate-800"))}
        count]
       [:span {:class "text-xs text-slate-500 mt-2"}
        "Bound directly to component local state `{:count ...}`"]])

     (ui/card-footer
      [:div {:class "flex flex-wrap items-center justify-between w-full gap-3"}
       [:div {:class "flex items-center gap-2"}
        (ui/button
         {:variant :default
          :class   "bg-slate-900 hover:bg-slate-800"
          :on      {:click [::increment]}}
         [:span {:class "font-bold mr-1"} "+"] "Increment")
        (ui/button
         {:variant :outline
          :on      {:click [::decrement]}}
         [:span {:class "font-bold mr-1"} "−"] "Decrement")
        (ui/button
         {:variant :ghost
          :disabled? (zero? count)
          :on      {:click [::reset]}}
         "Reset")]
       (ui/button
        {:variant :secondary
         :class   "text-indigo-700 bg-indigo-50 hover:bg-indigo-100 border-indigo-200"
         :on      {:click [::show-count]}}
        "Trigger Alert Effect")])])

   ;; Architecture Explanatory Box
   (ui/card
    {:class "bg-slate-900 text-slate-100 border-slate-800"}
    [:div
     (ui/card-header
      [:div {:class "flex items-center gap-2 text-indigo-400"}
       [:span {:class "text-base"} "💡"]
       (ui/card-title {:class "text-slate-100 text-base"} "How It Works")])
     (ui/card-content
      [:div {:class "grid grid-cols-1 sm:grid-cols-3 gap-4 text-xs font-mono"}
       [:div {:class "bg-slate-800/80 p-3 rounded-lg border border-slate-700/60"}
        [:div {:class "text-indigo-300 font-bold mb-1"} "1. View Emits"]
        [:p {:class "text-slate-400 leading-normal"} "Buttons emit declarative message vectors like `[::increment]` upon click."]]
       [:div {:class "bg-slate-800/80 p-3 rounded-lg border border-slate-700/60"}
        [:div {:class "text-emerald-300 font-bold mb-1"} "2. Pure Update"]
        [:p {:class "text-slate-400 leading-normal"} "`defmethod relm/update` receives the message and returns `[new-state context fx]`."]]
       [:div {:class "bg-slate-800/80 p-3 rounded-lg border border-slate-700/60"}
        [:div {:class "text-amber-300 font-bold mb-1"} "3. Replicant Renders"]
        [:p {:class "text-slate-400 leading-normal"} "Relm calculates minimal DOM diffs and patches the browser view."]]])])

   ;; Expandable Source Code Panel
   (ui/code-panel
    {:title    "Counter Example Source Code"
     :filename "counter.cljs"
     :code     snippets/counter-code})])

;; -----------------------------------------------------------------------------
;; Component Definition
;; -----------------------------------------------------------------------------

(def Counter
  "Counter component definition ready to be instantiated as `(Counter {:init-count 0})`."
  (relm/component
   {:init init
    :view view}))
