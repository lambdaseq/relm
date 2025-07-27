(ns com.lambdaseq.relm.core
  (:require [replicant.dom :as r]))

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

(defn dispatch
  "Handles message dispatching for components.

  This function is the central message handler for the relm system. It processes
  messages and updates component state accordingly. It should be set as the
  dispatch function for replicant using `(r/set-dispatch! relm/dispatch)`.

  Special message types:
  - `::init-component`: Initializes a component with the given args, init function, and view function
  - `::deinit-component`: Cleans up a component when it's unmounted

  For other message types, it calls the appropriate `transition` multimethod implementation."
  [{:keys [replicant/node] :as event} [message-type component-id :as message]]
  (case message-type
    ::init-component (let [[_ _ args init view] message
                           !state (atom nil)]
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
                              {:init   init
                               :view   view
                               :!state !state})
                       (let [[state context] (init @!state args)]
                         (reset! !state state)
                         (reset! !context context)))
    ::deinit-component (let [{:keys [!state] :as _component} (get @!components component-id)]
                         (remove-watch !context (-context-change-watch-reference-key component-id))
                         (remove-watch !state (-state-change-watch-reference-key component-id))
                         (swap! !components dissoc component-id))
    (let [{:keys [!state]} (get #p @!components #p component-id)
          context @!context
          state @!state
          [new-state new-context] (transition state context message event)]
      (reset! !state new-state)
      (reset! !context new-context))))

(defn component
  "Creates a new component with the specified initialization and view functions.

  Returns a function that, when called with a (globally unique) component-id and args, creates a
  replicant component that will be managed by the relm system.

  Parameters:
  - `init`: A function that takes the current context and component args and returns
            a vector of [initial-state updated-context]
  - `view`: A function that takes component-id, state, and context and returns
            a hiccup-style representation of the component's view

  Example:
  ```clojure
  (def Counter
    (component
      {:init (fn [context args] [{:count 0} context])
       :view (fn [id state context] [:div \"Count: \" (:count state)])}))

  ;; Usage:
  (Counter :my-counter {:some \"args\"})
  ```"
  [{:keys [init view]}]
  (fn [component-id args]
    [:div {:replicant/on-mount   [::init-component component-id args init view]
           :replicant/on-unmount [::deinit-component component-id]}]))
