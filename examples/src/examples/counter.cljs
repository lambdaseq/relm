(ns examples.counter
  (:require [com.lambdaseq.relm.core :as relm]
            [replicant.dom :as r]
            [hashp.core]))

(defn init [_context {:keys [init-count] :as _args}]
  {:count init-count})

(defn view [component-id {:keys [count]} _context]
  [:div
   [:h2 "Counter"]
   ; Implement some counter logic here
   [:p "Current count: " count]
   [:button {:on {:click [::increment component-id]}} "Increment"]
   [:button {:on {:click [::decrement component-id]}} "Decrement"]
   [:button {:on {:click [::show-count component-id]}} "Show Count"]])

(def Counter
  (relm/component
    {:init init
     :view view}))

(defmethod relm/fx ::alert
  [[_ message]]
  (js/alert message))

(defmethod relm/update ::show-count
  [{:keys [count] :as state} context _message _event]
  [state context [::alert (str "Count: " count)]])

(defmethod relm/update ::increment
  [state context _message _event]
  [(update state :count inc) context])

(defmethod relm/update ::decrement
  [state context _message _event]
  [(update state :count dec) context])

; Need to set `relm`'s dispatch function
(r/set-dispatch! relm/dispatch)

; First argument is a globally unique component id and the second argument are the args that will be used by the init function
(r/render js/document.body (Counter :counter
                                    {:init-count 0}))
