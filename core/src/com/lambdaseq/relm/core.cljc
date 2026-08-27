(ns com.lambdaseq.relm.core
  (:refer-clojure :exclude [update render])
  (:require [clojure.string :as string]
            [replicant.dom :as r]
            [replicant.hiccup :as rh]))

(defonce !app-state
  (atom {:context {}
         :components {}
         :root nil}))

(defonce ^:private !rendering? (atom false))

(defn vector-of-vectors? [v]
  (and (vector? v)
       (vector? (first v))))

(defmulti fx
  "Multimethod for handling side effects dispatched by message handlers.

  Dispatches on the first element of the effect vector. Effect handlers should perform
  side-effectful operations like network requests, storage operations, etc.

  Arguments:
    effect - A vector where the first element is the effect type keyword
             and remaining elements are effect-specific arguments.

  Example:
  ```clojure
  (defmethod fx! ::http-request
    [[_ url options callback]]
    (http/request url options callback))
  ```"
  (fn [_ effect]
    (first effect)))

(defn -dispatch-fx! [event effects]
  (when effects
    (if (vector-of-vectors? effects)
      (doseq [effect effects]
        (fx event effect))
      (fx event effects))))

(defmulti update
  "Handles state updates based on event messages.

  This multimethod is dispatched on the first element of the message vector.
  It takes the current state, context, message, and event as arguments and
  should return a vector of [new-state new-context effects].

  Example:
  ```clojure
  (defmethod relm/update ::increment
    [state context _message _event]
    ; No effects dispatched
    [(update state :count inc) context []])
  ```"
  (fn [_state _context message _event]
    (first message)))

(defn- -get-component-id [node]
  #?(:cljs
     (loop [curr node]
       (when curr
         (if (and (.-getAttribute curr) (.getAttribute curr "data-relm-component-id"))
           (.getAttribute curr "data-relm-component-id")
           (recur (.-parentNode curr)))))
     :clj nil))

(defn- -eval-root [component args]
  (if (fn? component)
    (if (some? args)
      (component args)
      (component))
    component))

(defn- -do-render-root! []
  (when-not @!rendering?
    (when-let [{:keys [node component args]} (:root @!app-state)]
      (when (and node component)
        (reset! !rendering? true)
        (try
          (r/render node (-eval-root component args))
          (finally
            (reset! !rendering? false)))))))

(defn- -on-app-state-change [_ _ old-state new-state]
  (when (and (:root new-state)
             (or (not= (:context old-state) (:context new-state))
                 (not= (:components old-state) (:components new-state))))
    (-do-render-root!)))

(defonce ^:private -init-watch
  (add-watch !app-state :relm/root-render -on-app-state-change))

(defn render
  "Renders the root component into the given DOM node and tracks it in `!app-state`.

  Parameters:
  - `node`: DOM element node to render into
  - `root-component`: Root component function created with `relm/component` (or hiccup)
  - `args`: Optional arguments map to pass to `root-component` (defaults to {})

  Example:
  ```clojure
  (relm/render js/document.body Examples)
  ;; or
  (relm/render js/document.body Examples {:some \"args\"})
  ```"
  ([node root-component]
   (render node root-component {}))
  ([node root-component args]
   (swap! !app-state assoc :root {:node node :component root-component :args (or args {})})
   (-do-render-root!)))

(defn -handle-message [{:keys [replicant/node] :as event} [message-type :as message]]
  (case message-type
    ::init-component
    (let [[_ comp-id initial-state] message
          comp-id-str (str comp-id)]
      (when-not (contains? (:components @!app-state) comp-id-str)
        (swap! !app-state assoc-in [:components comp-id-str :state] initial-state)))

    ::deinit-component
    (let [[_ comp-id] message
          comp-id-str (str comp-id)]
      (swap! !app-state update :components dissoc comp-id-str))

    (let [comp-id (-get-component-id node)
          component-info (when comp-id
                           (get-in @!app-state [:components comp-id]))
          state (:state component-info)
          context (:context @!app-state)
          result (update state context message event)
          [new-state new-context effects] (if (vector? result)
                                            result
                                            [result context])]
      (swap! !app-state (fn [app]
                          (cond-> app
                            comp-id (assoc-in [:components comp-id :state] new-state)
                            (some? new-context) (assoc :context new-context))))
      (-dispatch-fx! event effects))))

(defn dispatch
  "Handles message dispatching for components.

  This function is the central message handler for the relm system. It processes
  messages and updates component state accordingly. It should be set as the
  dispatch function for replicant using `(r/set-dispatch! relm/dispatch)`.

  The dispatch function can handle both single messages and collections of messages:
  - Single message: `(dispatch event [::message-type])`
  - Multiple messages: `(dispatch event [[::message-type-1] [::message-type-2]])`

  Special message types:
  - `::init-component`: Initializes a component in global state
  - `::deinit-component`: Cleans up a component when it's unmounted

  For other message types, it calls the appropriate `update` multimethod implementation."
  [event message-or-messages]
  (if (vector-of-vectors? message-or-messages)
    (doseq [message message-or-messages
            :when (some? message)]
      (-handle-message event message))
    (-handle-message event message-or-messages)))

(defn- resolve-component-id [default-id args]
  (cond
    (map? args)
    (or (:id args)
        (:component-id args)
        (:key (meta args))
        (:key args)
        (some (fn [[k v]]
                (when (and (keyword? k)
                           (string/ends-with? (name k) "-id"))
                  v))
              args)
        default-id)

    (some? args)
    args

    :else
    default-id))

(defn- -render-component [default-id-or-id init view args]
  (let [args (or args {})
        comp-id (str (if (and (string? default-id-or-id) (not (map? default-id-or-id)))
                       (resolve-component-id default-id-or-id args)
                       (resolve-component-id (str default-id-or-id) args)))
        context (:context @!app-state)
        state (if (contains? (:components @!app-state) comp-id)
                (get-in @!app-state [:components comp-id :state])
                (let [initial-state (init context args)]
                  (swap! !app-state assoc-in [:components comp-id :state] initial-state)
                  initial-state))
        hiccup (view state context)]
    (when hiccup
      (-> hiccup
          (rh/update-attrs assoc :data-relm-component-id comp-id)
          (rh/update-attrs
            clojure.core/update :replicant/on-unmount
            (fn [on-unmount]
              (if (vector-of-vectors? on-unmount)
                (into [[::deinit-component comp-id]] on-unmount)
                [[::deinit-component comp-id]
                 on-unmount])))))))

(defn component
  "Creates a new component with the specified initialization and view functions.

  Returns a function that, when called with args, creates a replicant component 
  that will be managed by the relm system.

  Parameters:
  - `init`: A function that takes the current context and component args and returns
            an initial state (defaults to `(fn [_ _] nil)`)
  - `view`: A function that takes state and context and returns
            a hiccup-style representation of the component's view

  Example:
  ```clojure
  (def Counter
    (component
      {:init (fn [context args] {:count 0})
       :view (fn [state context] [:div \"Count: \" (:count state)])}))

  ;; Usage:
  (Counter {:some \"args\"})
  ```"
  [{:keys [init view]}]
  (let [default-id (str (random-uuid))
        init-fn (or init (fn [_ _] nil))
        view-fn (or view (constantly nil))]
    (fn
      ([]
       (-render-component default-id init-fn view-fn {}))
      ([args]
       (-render-component default-id init-fn view-fn args))
      ([id args]
       (-render-component id init-fn view-fn args)))))

(defmethod fx :dispatch
  [dom-event [_ event]]
  (dispatch dom-event event))