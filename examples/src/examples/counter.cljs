(ns examples.counter
  (:require [com.lambdaseq.relm.core :as relm]
            [hashp.core]))

(defn init [_context {:keys [init-count] :as _args}]
  {:count init-count})

(defn view [{:keys [count]} _context]
  [:div
   [:h2 "Counter"]
   ; Implement some counter logic here
   [:p "Current count: " count]
   [:button {:on {:click [::increment]}} "Increment"]
   [:button {:on {:click [::decrement]}} "Decrement"]
   [:button {:on {:click [::show-count]}} "Show Count"]])

(defmethod relm/fx ::alert
  [_event [_ message]]
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

(def Counter
  (relm/component
    {:init init
     :view view}))
