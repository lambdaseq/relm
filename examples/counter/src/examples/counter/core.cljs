(ns examples.counter.core
  (:require [com.lambdaseq.relm.core :as relm]
            [replicant.dom :as r]
            [hashp.core]))

(defn init [context]
  [{:count 0} context])

(defn transition [state context [type :as _message] _event]
  (case type
    :increment
    [(update state :count inc) context]
    :decrement
    [(update state :count dec) context]))

(defn view [{:keys [count]} _context]
  [:div
   [:h2 "Counter"]
   ; Implement some counter logic here
   [:p "Current count: " count]
   [:button {:on {:click [:increment]}} "Increment"]
   [:button {:on {:click [:decrement]}} "Decrement"]])

(def Counter
  (relm/component
    {:init init
     :transition transition
     :view view}))

(js/console.log "Relm Counter Example")

(r/render js/document.body (Counter))