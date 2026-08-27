(ns examples.nested
  (:require [com.lambdaseq.relm.core :as relm]))

;; -----------------------------------------------------------------------------
;; Level 2: Nested Child Component (CounterItem)
;; -----------------------------------------------------------------------------

(defn- counter-init [_context {:keys [id label initial-count step]
                               :or {initial-count 0 step 1}}]
  {:id id
   :label label
   :count initial-count
   :step step})

(defmethod relm/update ::child-increment
  [state context _ _]
  [(update state :count + (:step state 1)) context])

(defmethod relm/update ::child-decrement
  [state context _ _]
  [(update state :count - (:step state 1)) context])

(defmethod relm/update ::child-reset
  [state context [_ initial-count] _]
  [(assoc state :count (or initial-count 0)) context])

(defn- counter-view [{:keys [label count step]} context]
  [:div {:style {:border "1px solid #ccc"
                 :border-radius "6px"
                 :padding "12px"
                 :margin "8px 0"
                 :background-color (if (= (:theme context) :dark) "#333333" "#f9f9f9")
                 :color (if (= (:theme context) :dark) "#f0f0f0" "#333333")}}
   [:h4 {:style {:margin "0 0 8px 0"}} label]
   [:p {:style {:margin "4px 0"}} (str "Local Count: " count " (step: " step ")")]
   [:div {:style {:display "flex" :gap "8px"}}
    [:button {:on {:click [::child-increment]}} (str "+" step)]
    [:button {:on {:click [::child-decrement]}} (str "-" step)]
    [:button {:on {:click [::child-reset 0]}} "Reset"]]])

(def CounterItem
  (relm/component
    {:init counter-init
     :view counter-view}))

;; -----------------------------------------------------------------------------
;; Level 1: Nested Child Component (CardComponent)
;; -----------------------------------------------------------------------------

(defn- card-init [_context {:keys [card-id title subtitle default-count]
                            :or {default-count 0}}]
  {:card-id card-id
   :title title
   :subtitle subtitle
   :collapsed? false
   :initial-count default-count})

(defmethod relm/update ::toggle-card
  [state context _ _]
  [(update state :collapsed? not) context])

(defn- card-view [{:keys [title subtitle collapsed? initial-count card-id]} context]
  [:div {:style {:border "2px solid #6366f1"
                 :border-radius "8px"
                 :padding "16px"
                 :margin "12px 0"
                 :background-color (if (= (:theme context) :dark) "#222222" "#ffffff")
                 :color (if (= (:theme context) :dark) "#f0f0f0" "#111111")}}
   [:div {:style {:display "flex"
                  :justify-content "space-between"
                  :align-items "center"}}
    [:div
     [:h3 {:style {:margin "0"}} title]
     (when subtitle
       [:small {:style {:color (if (= (:theme context) :dark) "#aaa" "#666")}} subtitle])]
    [:button {:on {:click [::toggle-card]}}
     (if collapsed? "Expand" "Collapse")]]
   (when-not collapsed?
     [:div {:style {:margin-top "12px"}}
      [:p {:style {:font-style "italic" :font-size "14px"}}
       "This card is a child component containing a nested CounterItem component:"]
      (CounterItem {:id (str "counter-" card-id)
                    :label (str title " - Counter")
                    :initial-count initial-count
                    :step 1})])])

(def CardComponent
  (relm/component
    {:init card-init
     :view card-view}))

;; -----------------------------------------------------------------------------
;; Root / Parent Component: NestedExample (Dashboard)
;; -----------------------------------------------------------------------------

(defn init [_context _args]
  {:title "Nested Components Example"
   :cards [{:id 1 :title "Card A" :subtitle "First nested component section" :count 5}
           {:id 2 :title "Card B" :subtitle "Second nested component section" :count 10}
           {:id 3 :title "Card C" :subtitle "Third nested component section" :count 20}]})

(defmethod relm/update ::toggle-global-theme
  [state context _ _]
  (let [new-theme (if (= (:theme context) :dark) :light :dark)]
    [state (assoc context :theme new-theme)]))

(defmethod relm/update ::add-card
  [state context _ _]
  (let [next-id (inc (count (:cards state)))
        new-card {:id next-id
                  :title (str "Card " (.fromCharCode js/String (+ 64 next-id)))
                  :subtitle "Dynamically added section"
                  :count 0}]
    [(update state :cards conj new-card) context]))

(defn view [{:keys [title cards]} context]
  [:div {:style {:font-family "sans-serif"
                 :max-width "600px"
                 :margin "20px auto"
                 :padding "20px"
                 :background-color (if (= (:theme context) :dark) "#1a1a1a" "#ffffff")
                 :color (if (= (:theme context) :dark) "#f0f0f0" "#111111")
                 :border-radius "12px"
                 :box-shadow "0 4px 12px rgba(0,0,0,0.1)"}}
   [:h1 title]
   [:p "This example demonstrates parent-child nested components in relm."]
   [:ul {:style {:font-size "14px" :color (if (= (:theme context) :dark) "#aaa" "#555")}}
    [:li "Each nested component maintains its own local state independently."]
    [:li "Parent and child components share and react to global context (e.g. Theme)."]
    [:li "Parent component can dynamically render a collection of child components."]]

   [:div {:style {:display "flex" :gap "10px" :margin "16px 0"}}
    [:button {:on {:click [::toggle-global-theme]}}
     (str "Toggle Theme (Current: " (name (get context :theme :light)) ")")]
    [:button {:on {:click [::add-card]}} "Add New Card"]]

   [:div {:style {:margin-top "16px"}}
    (for [card cards]
      ^{:key (:id card)}
      (CardComponent {:id (str "card-" (:id card))
                      :card-id (:id card)
                      :title (:title card)
                      :subtitle (:subtitle card)
                      :default-count (:count card)}))]])

(def NestedExample
  (relm/component
    {:init init
     :view view}))
