(ns com.lambdaseq.relm.core
  (:require [replicant.dom :as r]
            [replicant.core :as rc]))

(def !context (atom nil))

(defn component [{:keys [init transition view effect]}]
  (let [[state context effects] (init @!context)

        !state (atom state)

        !node (atom nil)

        render-component (fn [el state context]
                           (r/render el (view state context)))

        element-id (str (random-uuid))]

    (binding [rc/*dispatch* (fn [event message]
                              (let [context @!context

                                    state @!state

                                    [new-state new-context effects]
                                    (transition state context message event)]

                                (reset! !state new-state)

                                (when (not= context new-context)
                                  (let [node @!node]
                                    (render-component node state context)))

                                (reset! !context new-context)))]

      (add-watch !context
                 (keyword "listen-context" element-id)
                 (fn [_ _ _ context]
                   (let [state @!state

                         node @!node]

                     (render-component node state context))))

      (fn []
        [:div {:id element-id
               :replicant/on-mount
               (fn [{:keys [replicant/node]}]
                 (reset! !node node)
                 (render-component node state context))}]))))