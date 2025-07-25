(ns examples.counter.core
  (:require [com.lambdaseq.relm.core :as relm :refer-macros [defcomponent]]
            [replicant.dom :as r]
            [hashp.core]))

(def counter-id 0)

(defcomponent Counter
  (init [context]
        [{:count 0} context])
  (view [{:keys [count]} _context]
        [:div
         [:h2 "Counter"]
         ; Implement some counter logic here
         [:p "Current count: " count]
         [:button {:on {:click [::increment counter-id]}} "Increment"]
         [:button {:on {:click [::decrement counter-id]}} "Decrement"]]))

(defmethod relm/transition ::increment
  [state context _message _event]
  [(update state :count inc) context])

(defmethod relm/transition ::decrement
  [state context _message _event]
  [(update state :count dec) context])

(r/set-dispatch! relm/dispatch)

(r/render js/document.body (Counter counter-id))
