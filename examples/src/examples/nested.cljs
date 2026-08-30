(ns examples.nested
  "Hierarchical / nested component example for Relm.

  Demonstrates:
  - Multi-level component tree: `NestedExample` (parent) -> `CardComponent` (child) -> `CounterItem` (grandchild)
  - Automatic isolation of local component state across multiple instances using unique component IDs
  - Context-driven reactivity: all components share and react to global context updates (e.g. `:theme`)
  - Dynamic collections of nested components with Hiccup metadata `:key` support"
  (:require [com.lambdaseq.relm.core :as relm]
            [examples.snippets :as snippets]
            [examples.ui :as ui]))

;; -----------------------------------------------------------------------------
;; Level 2: Nested Child Component (CounterItem)
;; -----------------------------------------------------------------------------

(defn- counter-init
  "Initializes local state for an individual CounterItem instance."
  [_context {:keys [id label initial-count step]
             :or {initial-count 0 step 1}}]
  {:id id
   :label label
   :count initial-count
   :step step})

;; Increments CounterItem instance count by configured step value.
(defmethod relm/update ::child-increment
  [state context _ _]
  [(update state :count + (:step state 1)) context])

;; Decrements CounterItem instance count by configured step value.
(defmethod relm/update ::child-decrement
  [state context _ _]
  [(update state :count - (:step state 1)) context])

;; Increments the step value for the CounterItem instance.
(defmethod relm/update ::child-increase-step
  [state context [_ amount] _]
  [(update state :step (fnil + 1) (or amount 1)) context])

;; Resets CounterItem count to initial count.
(defmethod relm/update ::child-reset
  [state context [_ initial-count] _]
  [(assoc state :count (or initial-count 0)) context])

(defn- counter-view
  "Renders a leaf CounterItem component with theme styling from global context."
  [{:keys [label count step]} context]
  (let [dark? (= (:theme context) :dark)]
    [:div {:class (ui/cx "rounded-lg border p-4 transition-colors"
                         (if dark?
                           "bg-slate-900/90 border-slate-700 text-slate-100"
                           "bg-slate-50 border-slate-200 text-slate-900"))}
     [:div {:class "flex items-center justify-between mb-3"}
      [:div
       [:h4 {:class "font-medium text-sm"} label]
       [:span {:class (if dark? "text-slate-400 text-xs font-mono" "text-slate-500 text-xs font-mono")}
        (str "Isolated Local State • Step: " step)]]
      [:span {:class (ui/cx "text-xl font-bold font-mono px-3 py-1 rounded border"
                            (if dark? "bg-slate-800 text-indigo-300 border-slate-700"
                                "bg-white text-indigo-600 border-slate-200 shadow-xs"))}
       count]]

     [:div {:class "flex flex-wrap items-center gap-2 pt-1"}
      (ui/button
       {:variant :default
        :size    :sm
        :class   (if dark? "bg-indigo-600 hover:bg-indigo-500 text-white" "bg-slate-900 hover:bg-slate-800 text-white")
        :on      {:click [::child-increment]}}
       (str "+" step))
      (ui/button
       {:variant :outline
        :size    :sm
        :class   (if dark? "border-slate-700 bg-slate-800 text-slate-200 hover:bg-slate-700" "border-slate-300 bg-white")
        :on      {:click [::child-decrement]}}
       (str "−" step))
      (ui/button
       {:variant :secondary
        :size    :sm
        :class   (if dark? "bg-slate-800 text-slate-300 hover:bg-slate-700 border-slate-700" "bg-slate-200/80")
        :on      {:click [::child-increase-step 1]}}
       "Step +1")
      (ui/button
       {:variant :ghost
        :size    :sm
        :class   (if dark? "text-slate-400 hover:text-slate-200 hover:bg-slate-800" "text-slate-500 hover:bg-slate-200")
        :on      {:click [::child-reset 0]}}
       "Reset")]]))

(def CounterItem
  "Leaf counter component instance used within CardComponent."
  (relm/component
   {:init counter-init
    :view counter-view}))

;; -----------------------------------------------------------------------------
;; Level 1: Nested Child Component (CardComponent)
;; -----------------------------------------------------------------------------

(defn- card-init
  "Initializes local state for an individual CardComponent instance."
  [_context {:keys [card-id title subtitle default-count]
             :or {default-count 0}}]
  {:card-id card-id
   :title title
   :subtitle subtitle
   :collapsed? false
   :initial-count default-count})

;; Toggles collapsible expanded/collapsed view state for this card instance.
(defmethod relm/update ::toggle-card
  [state context _ _]
  [(update state :collapsed? not) context])

(defn- card-view
  "Renders collapsible card containing an inner nested CounterItem child component."
  [{:keys [title subtitle collapsed? initial-count card-id]} context]
  (let [dark? (= (:theme context) :dark)]
    [:div {:class (ui/cx "rounded-xl border shadow-sm transition-all overflow-hidden mb-4"
                         (if dark?
                           "bg-slate-900 border-slate-800 text-slate-100"
                           "bg-white border-slate-200 text-slate-900"))}
     [:div {:class (ui/cx "p-5 flex items-center justify-between border-b"
                          (if dark? "border-slate-800 bg-slate-900" "border-slate-100 bg-slate-50/50"))}
      [:div {:class "flex items-center gap-3"}
       [:span {:class (ui/cx "flex h-7 w-7 items-center justify-center rounded-md font-mono text-xs font-bold"
                             (if dark? "bg-indigo-900/60 text-indigo-300 border border-indigo-700"
                                 "bg-indigo-50 text-indigo-700 border border-indigo-200"))}
        (str "#" card-id)]
       [:div
        [:h3 {:class "font-semibold text-base leading-snug"} title]
        (when subtitle
          [:p {:class (if dark? "text-xs text-slate-400" "text-xs text-slate-500")} subtitle])]]
      (ui/button
       {:variant :ghost
        :size    :sm
        :class   (if dark? "text-slate-300 hover:bg-slate-800" "text-slate-600 hover:bg-slate-200/60")
        :on      {:click [::toggle-card]}}
       (if collapsed? "Expand ↓" "Collapse ↑"))]

     (when-not collapsed?
       [:div {:class "p-5"}
        [:p {:class (ui/cx "text-xs mb-3 italic" (if dark? "text-slate-400" "text-slate-500"))}
         "Child component embedding nested leaf counter with isolated state:"]
        (CounterItem {:id            (str "counter-" card-id)
                      :label         (str title " Counter")
                      :initial-count initial-count
                      :step          1})])]))

(def CardComponent
  "Card container component embedding nested CounterItem."
  (relm/component
   {:init card-init
    :view card-view}))

;; -----------------------------------------------------------------------------
;; Root / Parent Component: NestedExample (Dashboard)
;; -----------------------------------------------------------------------------

(defn init
  "Initializes root dashboard state with a collection of card descriptors."
  [_context _args]
  {:title "Component Hierarchy & Context Reactivity"
   :cards [{:id 1 :title "Alpha Section" :subtitle "First nested branch instance" :count 5}
           {:id 2 :title "Beta Section" :subtitle "Second nested branch instance" :count 12}
           {:id 3 :title "Gamma Section" :subtitle "Third nested branch instance" :count 42}]})

;; Toggles global application theme (`:light` <-> `:dark`) inside context.
(defmethod relm/update ::toggle-global-theme
  [state context _ _]
  (let [new-theme (if (= (:theme context) :dark) :light :dark)]
    [state (assoc context :theme new-theme)]))

;; Appends a new card descriptor into the dashboard's local card list.
(defmethod relm/update ::add-card
  [state context _ _]
  (let [next-id (inc (count (:cards state)))
        new-card {:id next-id
                  :title (str "Section " (char (+ 64 next-id)))
                  :subtitle "Dynamically spawned component instance"
                  :count 0}]
    [(update state :cards conj new-card) context]))

(defn view
  "Renders the dashboard parent component containing dynamic list of child cards."
  [{:keys [title cards]} context]
  (let [dark? (= (:theme context) :dark)]
    [:div {:class "max-w-4xl mx-auto"}
     (ui/example-header
      {:step        "4"
       :title       "Nested Components"
       :difficulty  "Intermediate"
       :description "Demonstrates multi-level component trees with automatic state isolation per instance ID, coupled with global context reactivity for theme distribution."
       :tags        ["Component Tree" "Local State Isolation" "Context Reactivity" "Dynamic Collections"]})

     ;; Dashboard Context Controls Box
     (ui/card
      {:class (ui/cx "mb-6 border-slate-200 transition-colors"
                     (if dark? "bg-slate-900 border-slate-800 text-white" "bg-white text-slate-900"))}
      [:div
       (ui/card-header
        [:div {:class "flex flex-wrap items-center justify-between gap-4"}
         [:div
          (ui/card-title (if dark? {:class "text-white"} {}) "Global Context & Dynamic Hierarchy")
          (ui/card-description (if dark? {:class "text-slate-400"} {})
                               "Manage global context properties and spawn new component subtrees dynamically.")]
         [:div {:class "flex items-center gap-2"}
          (ui/badge {:variant (if dark? :purple :indigo)} (str "Theme: " (name (get context :theme :light))))
          (ui/badge {:variant :secondary} (str (count cards) " Active Cards"))]])

       (ui/card-footer
        [:div {:class "flex flex-wrap items-center gap-3 w-full"}
         (ui/button
          {:variant :default
           :class   (if dark? "bg-indigo-600 hover:bg-indigo-500" "bg-slate-900 hover:bg-slate-800")
           :on      {:click [::toggle-global-theme]}}
          (str (if dark? "☀️ Switch to Light Theme" "🌙 Switch to Dark Theme")))
         (ui/button
          {:variant :outline
           :class   (if dark? "border-slate-700 text-slate-200 hover:bg-slate-800" "")
           :on      {:click [::add-card]}}
          "+ Add New Component Section")])])

     ;; List of Child Cards
     [:div {:class "space-y-4 mb-6"}
      (for [card cards]
        ^{:key (:id card)}
        (CardComponent {:id            (str "card-" (:id card))
                        :card-id       (:id card)
                        :title         (:title card)
                        :subtitle      (:subtitle card)
                        :default-count (:count card)}))]

     ;; Explanatory Callout
     (ui/alert
      {:variant :info}
      [:div {:class "flex items-start gap-3"}
       [:span {:class "text-base"} "💡"]
       [:div
        [:h4 {:class "font-semibold text-sm mb-0.5"} "State Isolation Mechanism"]
        [:p {:class "text-xs text-slate-600 leading-relaxed"}
         "Each nested component instance automatically maintains its own isolated state tree identified by its unique component ID. Modifying one counter never affects adjacent siblings, while context updates propagate seamlessly to all branches."]]])

     ;; Expandable Source Code Panel
     (ui/code-panel
      {:title    "Nested Components Example Source Code"
       :filename "nested.cljs"
       :code     snippets/nested-code})]))

(def NestedExample
  "Root nested component container for the example dashboard."
  (relm/component
   {:init init
    :view view}))
