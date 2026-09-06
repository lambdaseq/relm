(ns examples.devtools
  "Time-Travel Debugging & Action Inspection example demonstrating `relm.devtools`.

  Demonstrates:
  - Connecting Relm to the Redux DevTools browser extension via `(relm.devtools/connect!)`
  - Deterministic time-travel debugging: stepping back/forward with `undo!`, `redo!`, `jump-to!`
  - Replaying action history purely without side-effect re-execution
  - Toggling/skipping specific past actions via `toggle-action!`
  - Inspecting message dispatches, state diffs, and context changes in real time"
  (:require [cljs.pprint :refer [pprint]]
            [clojure.string :as string]
            [examples.snippets :as snippets]
            [examples.ui :as ui]
            [relm.core :as relm]
            [relm.devtools :as devtools]))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- format-edn
  [data]
  (with-out-str (pprint data)))

;; -----------------------------------------------------------------------------
;; Local Component State & Updates
;; -----------------------------------------------------------------------------

(defn init
  [_context _args]
  {:counter-val 0
   :last-action nil
   :console-log? true})

(defmethod relm/update ::demo-increment
  [state context _message _event]
  [(-> state
       (update :counter-val inc)
       (assoc :last-action :increment))
   context])

(defmethod relm/update ::demo-decrement
  [state context _message _event]
  [(-> state
       (update :counter-val dec)
       (assoc :last-action :decrement))
   context])

(defmethod relm/update ::demo-reset-count
  [state context _message _event]
  [(assoc state :counter-val 0 :last-action :reset)
   context])

(defmethod relm/update ::demo-change-theme
  [state context [_ new-theme] _event]
  [state
   (assoc context :theme (or new-theme :dark))
   [[::relm/alert! (str "Theme changed in context: " (name (or new-theme :dark)))]]])

(defmethod relm/update ::time-travel-undo
  [state context _message _event]
  [state context [[::devtools/undo!]]])

(defmethod relm/update ::time-travel-redo
  [state context _message _event]
  [state context [[::devtools/redo!]]])

(defmethod relm/update ::time-travel-reset
  [state context _message _event]
  [state context [[::devtools/reset-to-initial!]]])

(defmethod relm/update ::time-travel-jump
  [state context [_ idx] _event]
  [state context [[::devtools/jump-to! idx]]])

(defmethod relm/update ::time-travel-toggle
  [state context [_ idx] _event]
  [state context [[::devtools/toggle-action! idx]]])

(defmethod relm/update ::time-travel-commit
  [state context _message _event]
  [state context [[::devtools/commit!]]])

;; -----------------------------------------------------------------------------
;; View Layout
;; -----------------------------------------------------------------------------

(defn view
  [{:keys [counter-val last-action]} context]
  (let [hist (devtools/history)
        cur-idx (devtools/current-index)
        has-ext? (devtools/has-redux-devtools?)
        connected? (devtools/connected?)]
    [:div {:class "max-w-4xl mx-auto space-y-6"}
     ;; Header
     (ui/example-header
      {:step        "8"
       :title       "DevTools"
       :difficulty  "Advanced"
       :description "Time-travel debugging, Redux DevTools extension integration, deterministic history navigation, and side-effect-free action replay."
       :tags        ["relm.devtools" "Time Travel" "Redux DevTools" "diff-state" "toggle-action!"]})

     ;; Interactive Sandbox Card
     (ui/card
      {:class "shadow-md border-slate-200"}
      [:div
       (ui/card-header
        [:div {:class "flex items-center justify-between"}
         [:div
          (ui/card-title "Interactive Action Generator & Time Travel")
          (ui/card-description "Dispatch actions to mutate local state, alter global context, or trigger effects.")]
         [:div {:class "flex items-center gap-2"}
          (if connected?
            (ui/badge {:variant :success} "DevTools Active")
            (ui/badge {:variant :outline} "Disconnected"))
          (if has-ext?
            (ui/badge {:variant :indigo} "Extension Found")
            (ui/badge {:variant :secondary} "No Extension"))]])
       (ui/card-content
        [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-6"}
         ;; Counter Controls
         [:div {:class "p-4 rounded-xl bg-slate-50 border border-slate-200 flex flex-col justify-between gap-4"}
          [:div
           [:span {:class "text-xs font-semibold text-slate-500 uppercase tracking-wider"} "Local State"]
           [:div {:class "mt-2 text-3xl font-extrabold text-slate-900 font-mono"} counter-val]
           [:p {:class "text-xs text-slate-500 mt-1"}
            "Last dispatched: " [:code {:class "font-bold text-indigo-600"} (pr-str (or last-action :none))]]]
          [:div {:class "flex flex-wrap gap-2"}
           (ui/button {:variant :default :size :sm :on {:click [::demo-increment]}} "+1 Inc")
           (ui/button {:variant :outline :size :sm :on {:click [::demo-decrement]}} "-1 Dec")
           (ui/button {:variant :ghost :size :sm :on {:click [::demo-reset-count]}} "Reset")]]

         ;; Global Context Controls
         [:div {:class "p-4 rounded-xl bg-slate-50 border border-slate-200 flex flex-col justify-between gap-4"}
          [:div
           [:span {:class "text-xs font-semibold text-slate-500 uppercase tracking-wider"} "Global Context"]
           [:div {:class "mt-2 text-sm font-mono text-slate-800 bg-white p-2.5 rounded-lg border border-slate-200 overflow-x-auto"}
            (str "Theme: " (pr-str (get context :theme :default)))]
           [:p {:class "text-xs text-slate-500 mt-1"} "Updates context & returns alert side effect."]]
          [:div {:class "flex flex-wrap gap-2"}
           (ui/button {:variant :secondary :size :sm :on {:click [::demo-change-theme :emerald]}} "Theme: Emerald")
           (ui/button {:variant :secondary :size :sm :on {:click [::demo-change-theme :indigo]}} "Theme: Indigo")]]

         ;; Extension Status & Time Travel Controls
         [:div {:class "p-4 rounded-xl bg-slate-50 border border-slate-200 flex flex-col justify-between gap-4"}
          [:div
           [:span {:class "text-xs font-semibold text-slate-500 uppercase tracking-wider"} "History Navigation"]
           [:div {:class "mt-2 flex items-center gap-2 text-xs text-slate-700"}
            [:span "Position:"]
            [:span {:class "px-2 py-0.5 rounded bg-indigo-100 text-indigo-700 font-mono font-bold"}
             (str (if (neg? cur-idx) "Initial" (str "#" (inc cur-idx))) " / " (count hist))]]]
          [:div {:class "flex flex-wrap gap-2"}
           (ui/button {:variant :outline :size :sm :disabled? (not (devtools/can-undo?)) :on {:click [::time-travel-undo]}} "← Undo")
           (ui/button {:variant :outline :size :sm :disabled? (not (devtools/can-redo?)) :on {:click [::time-travel-redo]}} "Redo →")
           (ui/button {:variant :ghost :size :sm :on {:click [::time-travel-reset]}} "Reset Initial")
           (ui/button {:variant :ghost :size :sm :on {:click [::time-travel-commit]}} "Commit Baseline")]]])])

     ;; Action History Inspector Card
     (ui/card
      {:class "shadow-md border-slate-200"}
      [:div
       (ui/card-header
        [:div {:class "flex items-center justify-between"}
         [:div
          (ui/card-title "Action History & Time-Travel Timeline")
          (ui/card-description "Jump to any past state or toggle individual actions without executing side effects.")]
         [:span {:class "text-xs font-mono text-slate-400"} (str (count hist) " actions recorded")]])
       (ui/card-content
        (if (empty? hist)
          [:div {:class "p-8 text-center text-slate-400 text-sm"}
           "No actions recorded yet. Dispatch some actions above to populate history."]
          [:div {:class "space-y-2"}
           (for [[idx entry] (map-indexed vector hist)]
             (let [is-current? (= idx cur-idx)
                   is-skipped? (:skipped? entry)]
               ^{:key (:id entry)}
               [:div {:class (ui/cx "flex items-center justify-between p-3 rounded-lg border text-xs font-mono transition-all"
                                     (cond
                                       is-skipped? "bg-slate-100/60 border-dashed border-slate-300 text-slate-400 line-through opacity-70"
                                       is-current? "bg-indigo-50/80 border-indigo-300 text-slate-900 shadow-xs font-medium"
                                       :else "bg-white border-slate-200 text-slate-700 hover:bg-slate-50"))}
                [:div {:class "flex items-center gap-3 overflow-x-auto"}
                 [:span {:class (ui/cx "h-5 w-5 rounded-full flex items-center justify-center text-[10px] font-bold"
                                       (if is-current? "bg-indigo-600 text-white" "bg-slate-200 text-slate-600"))}
                  (str (inc idx))]
                 [:span {:class "font-bold text-indigo-700"}
                  (pr-str (:action-type entry))]
                 (when-let [cid (:comp-id entry)]
                  [:span {:class "text-slate-400"} (str "@" cid)])
                 (when (seq (:effects entry))
                  [:span {:class "px-1.5 py-0.5 rounded bg-pink-100 text-pink-700 text-[10px]"} "effects"])]

                [:div {:class "flex items-center gap-2 shrink-0"}
                 (ui/button {:variant (if is-current? :default :outline)
                             :size    :sm
                             :class   "h-7 text-xs px-2.5"
                             :on      {:click [::time-travel-jump idx]}}
                            (if is-current? "Current" "Jump To"))
                 (ui/button {:variant :ghost
                             :size    :sm
                             :class   "h-7 text-xs px-2"
                             :on      {:click [::time-travel-toggle idx]}}
                            (if is-skipped? "Unskip" "Skip"))]]))]))])

     ;; Architecture Explanatory Box
     (ui/card
      {:class "bg-slate-900 text-slate-100 border-slate-800"}
      [:div
       (ui/card-header
        [:div {:class "flex items-center gap-2 text-indigo-400"}
         [:span {:class "text-base"} "💡"]
         (ui/card-title {:class "text-slate-100 text-base"} "How DevTools & Time Travel Work")])
       (ui/card-content
        [:div {:class "grid grid-cols-1 sm:grid-cols-3 gap-4 text-xs font-mono"}
         [:div {:class "bg-slate-800/80 p-3 rounded-lg border border-slate-700/60"}
          [:div {:class "text-indigo-300 font-bold mb-1"} "1. Dispatch Interception"]
          [:p {:class "text-slate-400 leading-normal"} "Relm core transparently notifies registered listeners of dispatches, state transitions, and effects."]]
         [:div {:class "bg-slate-800/80 p-3 rounded-lg border border-slate-700/60"}
          [:div {:class "text-emerald-300 font-bold mb-1"} "2. Extension Bridge"]
          [:p {:class "text-slate-400 leading-normal"} "Serialized actions & states stream to Redux DevTools for rich visual timeline inspection."]]
         [:div {:class "bg-slate-800/80 p-3 rounded-lg border border-slate-700/60"}
          [:div {:class "text-amber-300 font-bold mb-1"} "3. Side-Effect Free Replay"]
          [:p {:class "text-slate-400 leading-normal"} "Time travel and action toggling compute target state purely via `relm/update` without re-running effects."]]])])

     ;; Expandable Source Code Panel
     (ui/code-panel
      {:title    "DevTools Example Source Code"
       :filename "devtools.cljs"
       :code     snippets/devtools-code})]))

(def DevtoolsExample
  "Relm component demonstrating DevTools time travel and inspection."
  (relm/component
   {:init init
    :view view}))
