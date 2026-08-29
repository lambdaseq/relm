(ns examples.counter
  "Counter example component demonstrating basic Elm architecture in Relm.

  Demonstrates:
  - Component initialization via `init` returning initial local state
  - Rendering Hiccup views bound to local state
  - Event triggers emitting messages `[::increment]`, `[::decrement]`, `[::show-count]`
  - State updates via `relm/update`
  - Side effects via `relm/fx` (`::alert`)"
  (:require [com.lambdaseq.relm.core :as relm]
            [hashp.core]))

;; -----------------------------------------------------------------------------
;; Component Initialization
;; -----------------------------------------------------------------------------

(defn init
  "Initializes the counter component state from arguments.
  Returns local state map `{:count <int>}`."
  [_context {:keys [init-count] :or {init-count 0} :as _args}]
  {:count init-count})

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defn view
  "Renders the counter interface with increment, decrement, and alert buttons."
  [{:keys [count]} _context]
  [:div
   [:h2 "Counter"]
   [:p "Current count: " count]
   [:button {:on {:click [::increment]}} "Increment"]
   [:button {:on {:click [::decrement]}} "Decrement"]
   [:button {:on {:click [::show-count]}} "Show Count"]])

;; -----------------------------------------------------------------------------
;; Update Handlers
;; -----------------------------------------------------------------------------

;; Emits a side effect to show the current count in an alert dialog without modifying state.
(defmethod relm/update ::show-count
  [{:keys [count] :as state} context _message _event]
  [state context [[:alert (str "Count: " count)]]])

;; Increments the local counter by 1.
(defmethod relm/update ::increment
  [state context _message _event]
  [(update state :count inc) context])

;; Decrements the local counter by 1.
(defmethod relm/update ::decrement
  [state context _message _event]
  [(update state :count dec) context])

;; -----------------------------------------------------------------------------
;; Component Definition
;; -----------------------------------------------------------------------------

(def Counter
  "Counter component definition ready to be instantiated as `(Counter {:init-count 0})`."
  (relm/component
    {:init init
     :view view}))
