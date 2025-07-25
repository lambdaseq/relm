(ns com.lambdaseq.relm.core
  (:require [replicant.dom :as r]
            #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(def !context (atom nil))

(def !state (atom {}))

(def !init (atom {}))

(def !view (atom {}))

(def !node (atom {}))

(def ^:dynamic *element-id*)

(defn- -render-component [el view state context]
  (r/render el (view state context)))

(defmulti transition (fn [_state _context message _event]
                       (first message)))

(defn dispatch [{:keys [replicant/node] :as event} [message-type element-id :as message]]
  (let [context @!context]
    (case message-type
      ::init-element (let [init (get @!init element-id)
                           view (get @!view element-id)
                           [new-state new-context] (init context)]
                       (swap! !node assoc element-id node)
                       (swap! !state assoc element-id new-state)
                       (swap! !view assoc element-id view)
                       (reset! !context new-context)
                       (r/render node (view new-state context)))
      (let [node (get @!node element-id)
            state (get @!state element-id)
            view (get @!view element-id)
            [new-state new-context] (transition state context message event)]
        (reset! !context new-context)
        (swap! !state assoc element-id new-state)

        ; when state or context changed, rerender element
        (when (or (not= state new-state)
                  (not= context new-context))
          (-render-component node view new-state new-context))))))

(defprotocol Init
  (init [context]))

(defprotocol View
  (view [state context]))

(s/def ::init-name #{'init})
(s/def ::init-args (s/coll-of any? :kind vector? :count 1))
(s/def ::init-definition (s/cat :init-name ::init-name :init-args ::init-args :init-body (s/* any?)))
(s/def ::view-name #{'view})
(s/def ::view-args (s/coll-of any? :kind vector? :count 2))
(s/def ::view-definition (s/cat :view-name ::view-name :view-args ::view-args :view-body (s/* any?)))



(defmacro defcomponent [component-name init-definition view-definition]
  (let [conformed (s/conform symbol? component-name)
        _ (when (= conformed ::s/invalid)
            (throw (ex-info "Invalid defcomponent name" (s/explain symbol? conformed))))
        {:keys [init-args init-body] :as conformed} (s/conform ::init-definition init-definition)
        _ (when (= conformed ::s/invalid)
            (throw (ex-info "Invalid init definition" (s/explain ::init-definition conformed))))
        {:keys [view-args view-body] :as conformed} (s/conform ::view-definition view-definition)
        _ (when (= conformed ::s/invalid)
            (throw (ex-info "Invalid view definition" (s/explain ::view-definition conformed))))]
    `(defn ~component-name [~'& [~'element-id]]
       (let [element-id# (or ~'element-id (random-uuid))]
         (swap! !init assoc element-id# (fn ~init-args ~@init-body))
         (swap! !view assoc element-id# (fn ~view-args ~@view-body))
         [:div {:replicant/on-mount
                [::init-element element-id#]}]))))

(comment
  (macroexpand-1
    '(defcomponent Counter
       (init [context]
             [{:count 0} context])
       (view [state _context]
             [:div
              [:h2 "Counter"]
              ; Implement some counter logic here
              [:p "Current count: " count]
              [:button {:on {:click [::increment]}} "Increment"]
              [:button {:on {:click [::decrement]}} "Decrement"]])))
  )