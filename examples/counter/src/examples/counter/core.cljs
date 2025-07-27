(ns examples.counter.core
  (:require [com.lambdaseq.relm.core :as relm]
            [hashp.core]))

(def state (atom {:count 0}))

(defn Counter [{:keys [count] :as _state}]
  [:div
   [:h1 "Counter: " count]
   [:button {:on {:click [::increment]}} "+"]
   [:button {:on {:click [::decrement]}} "-"]])

(defmethod relm/transition ::increment
  [state _message _event]
  (update state :count inc))

(defmethod relm/transition ::decrement
  [state _message _event]
  (update state :count dec))

(relm/start-app! {:state state
                  :root  Counter})
