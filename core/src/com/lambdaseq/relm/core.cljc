(ns com.lambdaseq.relm.core
  "Core Elm-architecture implementation on top of Replicant for Clojure/ClojureScript.

  Provides:
  - Component lifecycle management (`component`, `render`) with isolated local states
  - Global application context shared across all components
  - Message-based state updates via the `update` multimethod
  - Side-effect handling via the `fx` multimethod
  - Centralized message dispatching (`dispatch`) integrated with Replicant DOM events"
  (:refer-clojure :exclude [update render])
  (:require [clojure.string :as string]
            [replicant.dom :as r]
            [replicant.hiccup :as rh]))

;; -----------------------------------------------------------------------------
;; Global Application State
;; -----------------------------------------------------------------------------

(defonce ^{:doc "Central atom holding the entire application runtime state.
  Structure:
  - `:context`     Map of global context shared by all components (e.g. routing, themes, user session)
  - `:components`  Map of `{<comp-id-str> {:state <local-state>}}` storing isolated local states
  - `:root`        Map of `{:node <dom-node> :component <root-comp> :args <args-map>}`"}
  !app-state
  (atom {:context {}
         :components {}
         :root nil}))

(defonce ^:private ^{:doc "Flag used to prevent re-entrant rendering cycles."}
  !rendering?
  (atom false))

;; -----------------------------------------------------------------------------
;; Utilities
;; -----------------------------------------------------------------------------

(defn vector-of-vectors?
  "Returns true if `v` is a vector whose first element is also a vector.
  Used to detect batches of messages or side effects (e.g. `[[::fx-1] [::fx-2]]`)."
  [v]
  (and (vector? v)
       (vector? (first v))))

;; -----------------------------------------------------------------------------
;; Side Effects Multimethod
;; -----------------------------------------------------------------------------

(defmulti fx
  "Multimethod for handling side effects returned by message handlers.

  Dispatches on the first element of the effect vector. Effect handlers perform
  asynchronous or side-effectful operations (HTTP requests, DOM changes, navigation, timers)
  and can dispatch follow-up messages back to the relm runtime.

  Arguments:
    event  - The triggering DOM/synthetic event map (or nil if triggered programmatically)
    effect - Vector where the first element is the effect type keyword and remaining
             elements are effect arguments (e.g. `[::fetch request-map]`).

  Example:
  ```clojure
  (defmethod relm/fx ::alert
    [_event [_ message]]
    (js/alert message))
  ```"
  (fn [_ event-or-effect]
    (first event-or-effect)))

(defn -dispatch-fx!
  "Executes one or more side effects returned by an update handler.
  Handles either a single effect vector `[::fx-type ...]` or a batch `[[::fx-1] [::fx-2]]`."
  [event effects]
  (when effects
    (if (vector-of-vectors? effects)
      (doseq [effect effects]
        (fx event effect))
      (fx event effects))))

;; -----------------------------------------------------------------------------
;; State Update Multimethod
;; -----------------------------------------------------------------------------

(defmulti update
  "Handles state transitions based on dispatched event messages.

  Dispatched on the first element of the message vector (the message type keyword).
  Takes `[state context message event]` and returns a vector of:
    `[new-state new-context effects]` or `[new-state new-context]` or `new-state`

  Arguments:
  - `state`   Current local state of the component receiving the event
  - `context` Current global application context map
  - `message` Dispatched message vector (e.g. `[::increment 5]`)
  - `event`   DOM/synthetic event map provided by Replicant (contains target node, etc.)

  Example:
  ```clojure
  (defmethod relm/update ::increment
    [state context [_ by] _event]
    [(clojure.core/update state :count + (or by 1))
     context
     [[::log-analytics \"incremented\"]]])
  ```"
  (fn [_state _context message _event]
    (first message)))

;; -----------------------------------------------------------------------------
;; Component Identification & Rendering Internals
;; -----------------------------------------------------------------------------

(defn- -get-component-id
  "Traverses up the DOM tree from `node` to locate the enclosing `data-relm-component-id` attribute."
  [node]
  #?(:cljs
     (loop [curr node]
       (when curr
         (if (and (.-getAttribute curr) (.getAttribute curr "data-relm-component-id"))
           (.getAttribute curr "data-relm-component-id")
           (recur (.-parentNode curr)))))
     :clj nil))

(defn- -eval-root
  "Evaluates the root component function with optional arguments to obtain Hiccup data."
  [component args]
  (if (fn? component)
    (if (some? args)
      (component args)
      (component))
    component))

(defn- -do-render-root!
  "Performs a Replicant render pass for the registered root component into its target DOM node."
  []
  (when-not @!rendering?
    (when-let [{:keys [node component args]} (:root @!app-state)]
      (when (and node component)
        (reset! !rendering? true)
        (try
          (r/render node (-eval-root component args))
          (finally
            (reset! !rendering? false)))))))

(defn- -on-app-state-change
  "Watch function invoked whenever `!app-state` changes. Triggers re-rendering when context
  or component local state is modified."
  [_ _ old-state new-state]
  (when (and (:root new-state)
             (or (not= (:context old-state) (:context new-state))
                 (not= (:components old-state) (:components new-state))))
    (-do-render-root!)))

(defonce ^:private -init-watch
  (add-watch !app-state :relm/root-render -on-app-state-change))

;; -----------------------------------------------------------------------------
;; Public Rendering API
;; -----------------------------------------------------------------------------

(defn render
  "Renders the root component into the given DOM node and tracks it in `!app-state`.

  Parameters:
  - `node`: DOM element node to render into (e.g. `js/document.body` or element from `getElementById`)
  - `root-component`: Root component function created with `relm/component` (or Hiccup structure)
  - `args`: Optional arguments map to pass to `root-component` (defaults to `{}`)

  Example:
  ```clojure
  (relm/render js/document.body AppRoot {:initial-theme :dark})
  ```"
  ([node root-component]
   (render node root-component {}))
  ([node root-component args]
   (swap! !app-state assoc :root {:node node :component root-component :args (or args {})})
   (-do-render-root!)))

;; -----------------------------------------------------------------------------
;; Dispatch and Message Handling
;; -----------------------------------------------------------------------------

(defn -handle-message
  "Internal message processor for a single message.
  - Handles lifecycle messages (`::init-component`, `::deinit-component`).
  - Resolves target component ID from the event/DOM node.
  - Invokes `update` multimethod with current component state and global context.
  - Updates `!app-state` with new component state and context.
  - Executes any returned side effects."
  [{:keys [replicant/node] :as event} [message-type :as message]]
  (case message-type
    ::init-component
    (let [[_ comp-id initial-state] message
          comp-id-str (str comp-id)]
      (when-not (contains? (:components @!app-state) comp-id-str)
        (swap! !app-state assoc-in [:components comp-id-str :state] initial-state)))

    ::deinit-component
    (let [[_ comp-id] message
          comp-id-str (str comp-id)]
      (swap! !app-state clojure.core/update :components dissoc comp-id-str))

    (let [comp-id (or (:component-id event)
                      (-get-component-id node))
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
  dispatch function for replicant using `(replicant.dom/set-dispatch! relm/dispatch)`.

  The dispatch function can handle both single messages and collections of messages:
  - Single message: `(dispatch event [::message-type])`
  - Multiple messages: `(dispatch event [[::message-type-1] [::message-type-2]])`

  Lifecycle message types handled internally:
  - `::init-component`: Initializes a component's local state in `!app-state`
  - `::deinit-component`: Cleans up a component when it is unmounted from the DOM

  For other message types, it calls the appropriate `update` multimethod implementation."
  [event message-or-messages]
  (if (vector-of-vectors? message-or-messages)
    (doseq [message message-or-messages
            :when (some? message)]
      (-handle-message event message))
    (-handle-message event message-or-messages)))

;; -----------------------------------------------------------------------------
;; Component Constructor
;; -----------------------------------------------------------------------------

(defn- resolve-component-id
  "Extracts or derives a stable unique component ID from component arguments map
  (checking `:id`, `:component-id`, `:key`, metadata `:key`, or `*-id` keys),
  falling back to `default-id`."
  [default-id args]
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

(defn- -render-component
  "Renders an individual component instance:
  1. Resolves a unique ID for the component instance.
  2. Initializes state via `(init context args)` on first mount.
  3. Evaluates `(view state context)` to obtain Hiccup.
  4. Injects `:data-relm-component-id` and `:replicant/on-unmount` hook into root Hiccup element."
  [default-id-or-id init view args]
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
              (cond
                (nil? on-unmount)
                [::deinit-component comp-id]

                (vector-of-vectors? on-unmount)
                (conj on-unmount [::deinit-component comp-id])

                (vector? on-unmount)
                [on-unmount [::deinit-component comp-id]]

                :else
                [::deinit-component comp-id])))))))

(defn component
  "Creates a new Elm-style component with initialization and view functions.

  Returns a component function that, when invoked (with 0, 1, or 2 arguments),
  produces Hiccup markup managed by the relm runtime.

  Component options map:
  - `:init` Function `(fn [context args] initial-state)` returning initial local state (defaults to `(fn [_ _] nil)`)
  - `:view` Function `(fn [state context] hiccup)` returning Hiccup structure representing the UI

  Invocation arities for returned component:
  - `(MyComponent)` - Renders with auto-generated ID and empty args
  - `(MyComponent args-map)` - Renders with ID resolved from `args-map` (e.g. `:id`)
  - `(MyComponent explicit-id args-map)` - Renders with explicit instance ID

  Example:
  ```clojure
  (def Counter
    (relm/component
      {:init (fn [_context {:keys [initial-count] :or {initial-count 0}}]
               {:count initial-count})
       :view (fn [{:keys [count]} _context]
               [:div
                [:p \"Count: \" count]
                [:button {:on {:click [::increment]}} \"+1\"]])}))

  ;; Render instances with distinct state:
  (Counter {:id \"counter-a\" :initial-count 10})
  (Counter {:id \"counter-b\" :initial-count 20})
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

;; Built-in effect handler for dispatching follow-up messages from inside effect flows.
;; Format: `[:dispatch [::message-name ...]]`
(defmethod fx :dispatch
  [dom-event [_ event]]
  (dispatch dom-event event))