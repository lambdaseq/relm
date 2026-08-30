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
         (atom {:context    {}
                :components {}
                :root       nil}))

(defonce ^:private ^{:doc "Flag used to prevent re-entrant rendering cycles."}
         !rendering?
         (atom false))

;; -----------------------------------------------------------------------------
;; Utilities
;; -----------------------------------------------------------------------------

(defn vector-of-vectors?
  "Returns true if `v` is a vector whose first element is also a vector.
  Used to detect batches of messages (e.g. `[[::msg-1] [::msg-2]]`)."
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
          (defmethod relm/fx ::alert!
            [_event [_ message]]
            (js/alert message))
          ```"
          (fn [_ event-or-effect]
            (first event-or-effect)))

(defn -dispatch-fx!
  "Executes side effects returned by an update handler.
  Expects `effects` to be a vector of effect vectors (e.g. `[[::fx-1] [::fx-2]]`)."
  [event effects]
  (doseq [effect effects
          :when (some? effect)]
    (fx event effect)))

;; -----------------------------------------------------------------------------
;; State Update Multimethod
;; -----------------------------------------------------------------------------

(defmulti update
          "Handles state transitions based on dispatched event messages.

          Dispatched on the first element of the message vector (the message type keyword).
          Takes `[state context message event]` and returns a vector of:
            `[new-state new-context effects]` or `[new-state new-context]` or `new-state`

          When side effects are returned, they must always be a vector of effect vectors:
            `[[::fx-type ...]]`

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
  (loop [curr node]
    (when curr
      (if (and (.-getAttribute curr) (.getAttribute curr "data-relm-component-id"))
        (.getAttribute curr "data-relm-component-id")
        (recur (.-parentNode curr))))))

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

(def render!
  "Alias for `render` with exclamation mark denoting DOM mutation."
  render)

;; -----------------------------------------------------------------------------
;; Dispatch and Message Handling
;; -----------------------------------------------------------------------------

(defn- -invoke-lifecycle-fn
  "Invokes a lifecycle hook function (`on-init` or `on-deinit`) passing state, context, args, and event.
  Supports arities from 0 to 4 arguments."
  [f state context args event]
  (when (fn? f)
    (let [len (.-length f)]
      (cond
        (= len 0) (f)
        (= len 1) (f state)
        (= len 2) (f state context)
        (= len 3) (f state context args)
        (= len 4) (f state context args event)
        :else
        (try
          (f state context args event)
          (catch :default e
            (if (or (instance? js/RangeError e)
                    (and (instance? js/Error e)
                         (re-find #"wrong number of args|Invalid arity" (.-message e))))
              (try
                (f state context args)
                (catch :default _
                  (f state context)))
              (throw e))))))))

(defn- -process-lifecycle-result
  "Normalizes the return value of a lifecycle function into `[new-state new-context effects]`."
  [result current-state current-context]
  (cond
    (vector? result)
    (let [new-state (nth result 0 nil)
          new-context (if (>= (count result) 2) (nth result 1) current-context)
          effects (if (>= (count result) 3) (nth result 2) nil)]
      [new-state new-context effects])

    (some? result)
    [result current-context nil]

    :else
    [current-state current-context nil]))

(defn -handle-message
  "Internal message processor for a single message.
  - Handles lifecycle messages (`::init-component`, `::deinit-component`).
  - Resolves target component ID from the event/DOM node.
  - Invokes `update` multimethod with current component state and global context.
  - Updates `!app-state` with new component state and context.
  - Executes any returned side effects."
  [{:keys [replicant/node] :as event} [message-type :as message]]
  (when (some? message)
    (case message-type
      ::init-component
      (let [[_ comp-id initial-state] message
            comp-id-str (str comp-id)]
        (when-not (contains? (:components @!app-state) comp-id-str)
          (swap! !app-state assoc-in [:components comp-id-str :state] initial-state)))

      ::deinit-component
      (let [[_ comp-id] message
            comp-id-str (str comp-id)
            comp-info (get-in @!app-state [:components comp-id-str])
            state (:state comp-info)
            on-deinit (:on-deinit comp-info)
            args (:args comp-info)
            context (:context @!app-state)]
        (when on-deinit
          (let [res (-invoke-lifecycle-fn on-deinit state context args event)
                [_ new-context effects] (-process-lifecycle-result res state context)]
            (when (some? new-context)
              (swap! !app-state assoc :context new-context))
            (when (seq effects)
              (-dispatch-fx! event effects))))
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
        (-dispatch-fx! event effects)))))

(defn dispatch!
  "Handles message dispatching for components.

  This function is the central message handler for the relm system. It processes
  messages and updates component state accordingly. It should be set as the
  dispatch function for `replicant` using `(replicant.dom/set-dispatch! relm/dispatch)`.

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
  3. Executes optional `on-init` lifecycle hook on first mount.
  4. Evaluates `(view state context)` to obtain Hiccup.
  5. Injects `:data-relm-component-id` and `:replicant/on-unmount` hook into root Hiccup element."
  [default-id-or-id init view on-init on-deinit args]
  (let [args (or args {})
        comp-id (str (if (and (string? default-id-or-id) (not (map? default-id-or-id)))
                       (resolve-component-id default-id-or-id args)
                       (resolve-component-id (str default-id-or-id) args)))
        context (:context @!app-state)
        state (if (contains? (:components @!app-state) comp-id)
                (get-in @!app-state [:components comp-id :state])
                (let [initial-state (init context args)
                      _ (swap! !app-state (fn [app]
                                            (-> app
                                                (assoc-in [:components comp-id :state] initial-state)
                                                (assoc-in [:components comp-id :on-deinit] on-deinit)
                                                (assoc-in [:components comp-id :args] args))))
                      event {:component-id comp-id}
                      [init-state new-context effects] (if on-init
                                                         (let [res (-invoke-lifecycle-fn on-init initial-state context args event)]
                                                           (-process-lifecycle-result res initial-state context))
                                                         [initial-state context nil])]
                  (when on-init
                    (when (or (not= init-state initial-state) (some? new-context))
                      (swap! !app-state (fn [app]
                                          (cond-> app
                                            (some? init-state) (assoc-in [:components comp-id :state] init-state)
                                            (some? new-context) (assoc :context new-context)))))
                    (when (seq effects)
                      (-dispatch-fx! event effects)))
                  (or init-state initial-state)))
        hiccup (view state (:context @!app-state))]
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
  "Creates a new Elm-style component with initialization, lifecycle hooks, and view functions.

  Returns a component function that, when invoked (with 0, 1, or 2 arguments),
  produces Hiccup markup managed by the relm runtime.

  Component options map:
  - `:init` Function `(fn [context args] initial-state)` returning initial local state (defaults to `(fn [_ _] nil)`)
  - `:view` Function `(fn [state context] hiccup)` returning Hiccup structure representing the UI
  - `:on-init` Function `(fn [state context args event] [state context effects])` lifecycle hook called on initial mount
  - `:on-deinit` Function `(fn [state context args event] [state context effects])` lifecycle hook called on unmount

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
       :on-init (fn [state context _args _event]
                  [state context [[::log! \"Counter initialized\"]]])
       :view (fn [{:keys [count]} _context]
               [:div
                [:p \"Count: \" count]
                [:button {:on {:click [::increment]}} \"+1\"]])}))

  ;; Render instances with distinct state:
  (Counter {:id \"counter-a\" :initial-count 10})
  (Counter {:id \"counter-b\" :initial-count 20})
  ```"
  [{:keys [init view on-init on-deinit]}]
  (let [default-id (str (random-uuid))
        init-fn (or init (fn [_ _] nil))
        view-fn (or view (constantly nil))]
    (fn
      ([]
       (-render-component default-id init-fn view-fn on-init on-deinit {}))
      ([args]
       (-render-component default-id init-fn view-fn on-init on-deinit args))
      ([id args]
       (-render-component id init-fn view-fn on-init on-deinit args)))))

;; Built-in effect handlers for dispatching follow-up messages from inside effect flows.
;; Formats:
;; - `[::relm/dispatch! [::message ...]]`
;; - `[::relm/dispatch-n! [[::msg-1] [::msg-2]]]`
;; - `[::relm/dispatch-later! [{:ms 100 :dispatch! [::msg]} ...]]`

(defmethod fx ::dispatch!
  [dom-event [_ event]]
  (dispatch! dom-event event))

(defmethod fx ::dispatch-n!
  [dom-event [_ events]]
  (dispatch! dom-event events))

(defn- -handle-dispatch-later-item
  [dom-event item]
  (let [msg (or (:dispatch! item) (:dispatch item) (:message item))
        delay-ms (or (:ms item) 0)]
    (when msg
      (if (exists? js/setTimeout)
        (js/setTimeout #(dispatch! dom-event msg) delay-ms)
        (dispatch! dom-event msg)))))

(defmethod fx ::dispatch-later!
  [dom-event [_ items]]
  (if (vector? items)
    (doseq [item items]
      (-handle-dispatch-later-item dom-event item))
    (-handle-dispatch-later-item dom-event items)))

;; -----------------------------------------------------------------------------
;; Built-in Browser & DOM Side Effects (Handled in Core)
;; -----------------------------------------------------------------------------

;; Displays a browser alert dialog.
;; Format: `[::relm/alert! "message"]`
(defmethod fx ::alert!
  [_dom-event [_ message]]
  (when (exists? js/alert)
    (js/alert (str message))))

(defn- -call-prevent-default!
  "Extracts and invokes .preventDefault() on native DOM event or map containing event."
  [event-or-dom]
  (let [dom-e (cond
                (nil? event-or-dom) nil
                (map? event-or-dom) (or (:replicant/dom-event event-or-dom)
                                        (:replicant/js-event event-or-dom)
                                        (:event event-or-dom)
                                        (:dom-event event-or-dom)
                                        event-or-dom)
                :else event-or-dom)]
    (when dom-e
      (cond
        (and (not (map? dom-e)) (exists? (.-preventDefault dom-e)))
        (.preventDefault dom-e)

        (and (map? dom-e) (fn? (:preventDefault dom-e)))
        ((:preventDefault dom-e))))))

;; Prevents default browser event action.
;; Format: `[::relm/prevent-default!]` or `[::relm/prevent-default! event]`
(defmethod fx ::prevent-default!
  [dom-event [_ explicit-event]]
  (-call-prevent-default! (or explicit-event dom-event)))

(defn- -call-stop-propagation!
  "Extracts and invokes .stopPropagation() on native DOM event or map containing event."
  [event-or-dom]
  (let [dom-e (cond
                (nil? event-or-dom) nil
                (map? event-or-dom) (or (:replicant/dom-event event-or-dom)
                                        (:replicant/js-event event-or-dom)
                                        (:event event-or-dom)
                                        (:dom-event event-or-dom)
                                        event-or-dom)
                :else event-or-dom)]
    (when dom-e
      (cond
        (and (not (map? dom-e)) (exists? (.-stopPropagation dom-e)))
        (.stopPropagation dom-e)

        (and (map? dom-e) (fn? (:stopPropagation dom-e)))
        ((:stopPropagation dom-e))))))

;; Stops event propagation in DOM tree.
;; Format: `[::relm/stop-propagation!]` or `[::relm/stop-propagation! event]`
(defmethod fx ::stop-propagation!
  [dom-event [_ explicit-event]]
  (-call-stop-propagation! (or explicit-event dom-event)))

(defn- -focus-element!
  "Focuses a DOM input element by name attribute or ID selector."
  [field-name-or-id]
  (when (and (exists? js/document) field-name-or-id)
    (let [selector (str "[name='" (name field-name-or-id) "'], #" (name field-name-or-id))
          elem (.querySelector js/document selector)]
      (when elem
        (.focus elem)))))

;; Focuses a specific field or element in the DOM.
;; Format: `[::relm/focus! :field-name]`
(defmethod fx ::focus!
  [_dom-event [_ field-name-or-id]]
  (-focus-element! field-name-or-id))

(defmethod fx ::focus-field!
  [_dom-event [_ field-name-or-id]]
  (-focus-element! field-name-or-id))

;; Focuses the first field with a validation error.
;; Format: `[::relm/focus-first-error! errors-map]`
(defmethod fx ::focus-first-error!
  [_dom-event [_ errors]]
  (when (and (exists? js/document) (seq errors))
    (let [first-path (first (keys errors))
          field-name (if (vector? first-path) (last first-path) first-path)]
      (when field-name
        (-focus-element! field-name)))))

;; Executes an asynchronous validator returning a Promise and dispatches result.
;; Format: `[::relm/validate-async! {:path ... :validator ... :on-success ... :on-error ...}]`
(defmethod fx ::validate-async!
  [dom-event [_ {:keys [path validator on-success on-error]}]]
  (when (fn? validator)
    (let [p (validator)]
      (when (and p (exists? (.-then p)))
        (-> p
            (.then (fn [res]
                     (when on-success
                       (dispatch! dom-event (if (fn? on-success) (on-success res) on-success)))))
            (.catch (fn [err]
                      (when on-error
                        (let [msg (or (.-message err) (str err))]
                          (dispatch! dom-event (if (fn? on-error) (on-error msg) [:com.lambdaseq.relm.form/set-error path msg])))))))))))