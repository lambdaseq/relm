(ns com.lambdaseq.relm.core
  (:require [replicant.dom :as r]
            [replicant.hiccup :as rh]))

(def !context (atom nil))

(def !components (atom {}))

(defmulti transition
  "Handles state transitions based on messages.

  This multimethod is dispatched on the first element of the message vector.
  It takes the current state, context, message, and event as arguments and
  should return a vector of [new-state new-context].

  Example:
  ```clojure
  (defmethod transition ::increment
    [state context _message _event]
    [(update state :count inc) context])
  ```"
  (fn [_state _context message _event]
    (first message)))

(defn- -context-change-watch-reference-key [component-id]
  (str "context-change-" component-id))

(defn- -state-change-watch-reference-key [component-id]
  (str "state-change-" component-id))

(defn -handle-message [{:keys [replicant/node] :as event} [message-type component-id :as message]]
  (case message-type
    ::init-component (let [[_ _ state view] message
                           !state (atom state)]
                       (add-watch !context (-context-change-watch-reference-key component-id)
                                  (fn [_ _ old-context context]
                                    (when (not= old-context context)
                                      (r/render node (view component-id @!state context)))))
                       (add-watch !state (-state-change-watch-reference-key component-id)
                                  (fn [_ _ old-state state]
                                    old-state state
                                    (when (not= old-state state)
                                      (r/render node (view component-id state @!context)))))
                       (swap! !components assoc component-id
                              {:view   view
                               :!state !state}))
    ::deinit-component (let [{:keys [!state] :as _component} (get @!components component-id)]
                         (remove-watch !context (-context-change-watch-reference-key component-id))
                         (remove-watch !state (-state-change-watch-reference-key component-id))
                         (swap! !components dissoc component-id))
    (let [{:keys [!state]} (get @!components component-id)
          context @!context
          state @!state
          [new-state new-context] (transition state context message event)]
      (reset! !state new-state)
      (reset! !context new-context))))

(defn dispatch
  "Handles message dispatching for components.

  This function is the central message handler for the relm system. It processes
  messages and updates component state accordingly. It should be set as the
  dispatch function for replicant using `(r/set-dispatch! relm/dispatch)`.

  The dispatch function can handle both single messages and collections of messages:
  - Single message: `(dispatch event [::message-type component-id])`
  - Multiple messages: `(dispatch event [[::message-type-1 component-id] [::message-type-2 component-id]])`

  Special message types:
  - `::init-component`: Initializes a component with the given args, init function, and view function
  - `::deinit-component`: Cleans up a component when it's unmounted

  For other message types, it calls the appropriate `transition` multimethod implementation."
  [event message-or-messages]
  (if (and (vector? message-or-messages)
           (vector? (first message-or-messages)))
    (doseq [message message-or-messages]
      (-handle-message event message))
    (-handle-message event message-or-messages)))

(defn component
  "Creates a new component with the specified initialization and view functions.

  Returns a function that, when called with a (globally unique) component-id and args, creates a
  replicant component that will be managed by the relm system.

  Parameters:
  - `init`: A function that takes the current context and component args and returns
            an initial state
  - `view`: A function that takes component-id, state, and context and returns
            a hiccup-style representation of the component's view

  Example:
  ```clojure
  (def Counter
    (component
      {:init (fn [context args] {:count 0})
       :view (fn [id state context] [:div \"Count: \" (:count state)])}))

  ;; Usage:
  (Counter :my-counter {:some \"args\"})
  ```"
  [{:keys [init view]}]
  (fn [component-id args]
    (let [context @!context
          state (init context args)]
      (-> (view component-id state context)
          (rh/update-attrs
            update :replicant/on-mount
            (fn [on-mount]
              (into [[::init-component component-id state view]] on-mount)))
          (rh/update-attrs
            update :replicant/on-unmount
            (fn [on-unmount]
              (into [[::deinit-component component-id]] on-unmount)))))))
