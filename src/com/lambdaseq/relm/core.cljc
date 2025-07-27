(ns com.lambdaseq.relm.core
  (:require [replicant.dom :as r]))

(def !context (atom nil))

(def !components (atom {}))

(defmulti transition (fn [_state _context message _event]
                       (first message)))

(defn context-change-watch-reference-key [component-id]
  (str "context-change-" component-id))

(defn state-change-watch-reference-key [component-id]
  (str "state-change-" component-id))

(defn dispatch [{:keys [replicant/node] :as event} [message-type component-id :as message]]
  (case message-type
    ::init-component (let [[_ _ args init view] message
                           !state (atom nil)]
                       (add-watch !context (context-change-watch-reference-key component-id)
                                  (fn [_ _ old-context context]
                                    (when (not= old-context context)
                                      (r/render node (view component-id @!state context)))))
                       (add-watch !state (state-change-watch-reference-key component-id)
                                  (fn [_ _ old-state state]
                                    old-state state
                                    (when (not= old-state state)
                                      (r/render node (view component-id state @!context)))))
                       (swap! !components assoc component-id
                              {:init   init
                               :view   view
                               :!state !state})
                       (let [[state context] (init @!state args)]
                         (reset! !state state)
                         (reset! !context context)))
    ::deinit-component (let [{:keys [!state] :as _component} (get @!components component-id)]
                         (remove-watch !context (context-change-watch-reference-key component-id))
                         (remove-watch !state (state-change-watch-reference-key component-id))
                         (swap! !components dissoc component-id))
    (let [{:keys [!state]} (get #p @!components #p component-id)
          context @!context
          state @!state
          [new-state new-context] (transition state context message event)]
      (reset! !state new-state)
      (reset! !context new-context))))

(defn component [{:keys [init view]}]
  (fn [component-id args]
    [:div {:id                   component-id
           :replicant/on-mount   [::init-component component-id args init view]
           :replicant/on-unmount [::deinit-component component-id]}]))