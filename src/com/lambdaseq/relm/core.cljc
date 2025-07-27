(ns com.lambdaseq.relm.core
  (:require [replicant.dom :as r]))

(defmulti transition (fn [_state message _event]
                       (first message)))

(defn dispatch [state]
  (fn [event message]
    (swap! state transition message event)))


#?(:cljs (defn start-app! [{:keys [state root]}]
           (let [render #(r/render js/document.body (root @state))]
             (r/set-dispatch! (dispatch state))
             (add-watch state :rerender-on-state-change render)
             (render))))