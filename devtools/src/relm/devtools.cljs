(ns relm.devtools
  "Time-travel debugging, action inspection, state diffing, and Redux DevTools
  browser extension integration for Relm applications.

  Provides:
  - Redux DevTools Extension bridge (`connect!`, `disconnect!`)
  - Deterministic time-travel debugging (`jump-to!`, `undo!`, `redo!`)
  - Action history logging, filtering, and inspection (`history`, `clear-history!`)
  - Pure state replay without side-effect re-execution (`replay-actions`)
  - Formatted browser console logging with expandable groups and diffs
  - ClojureScript data serialization for Redux DevTools / JSON"
  (:require [cljs.reader :as reader]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [goog.object :as gobj]
            [relm.core :as relm]))

;; -----------------------------------------------------------------------------
;; Serialization Helpers (ClojureScript -> JS / JSON)
;; -----------------------------------------------------------------------------

(defn clj->js-data
  "Recursively converts ClojureScript data structures into plain JavaScript
  objects and arrays suitable for Redux DevTools inspection and JSON serialization.

  Handles:
  - Keywords & Symbols -> strings (e.g. `:user/name` -> \":user/name\")
  - Sets -> JavaScript Arrays
  - Maps -> JavaScript Objects with stringified keys
  - Vectors & Lists -> JavaScript Arrays
  - UUIDs, Insts -> string representation
  - Functions -> string representation \"#<fn>\"
  - DOM Elements, Events, RegExps, Atoms -> safe string representations"
  [val]
  (cond
    (nil? val) nil
    (keyword? val) (str val)
    (symbol? val) (str val)
    (string? val) val
    (number? val) (if (js/isNaN val) "NaN" val)
    (boolean? val) val
    (uuid? val) (str val)
    (inst? val) (if (and (exists? (.-toISOString val)) (fn? (.-toISOString val)))
                  (.toISOString val)
                  (str val))
    (fn? val) (or (.-name val) "#<fn>")
    (and (exists? js/Node) (instance? js/Node val))
    (str "#<DOMNode:" (.-nodeName val) ">")
    (and (exists? js/Event) (instance? js/Event val))
    (str "#<DOMEvent:" (.-type val) ">")
    (and (exists? js/Window) (instance? js/Window val))
    "#<Window>"
    (and (exists? js/Document) (instance? js/Document val))
    "#<Document>"
    (instance? js/Error val)
    (str "#<Error:" (.-message val) ">")
    (instance? js/RegExp val)
    (str val)
    (map? val)
    (let [obj #js {}]
      (doseq [[k v] val]
        (let [k-str (cond
                      (keyword? k) (str (if-let [ns (namespace k)] (str ns "/") "") (name k))
                      (string? k) k
                      :else (str k))]
          (aset obj k-str (clj->js-data v))))
      obj)
    (set? val)
    (let [arr #js []]
      (doseq [v val]
        (.push arr (clj->js-data v)))
      arr)
    (sequential? val)
    (let [arr #js []]
      (doseq [v val]
        (.push arr (clj->js-data v)))
      arr)
    (js/Array.isArray val)
    (let [arr #js []]
      (doseq [v val]
        (.push arr (clj->js-data v)))
      arr)
    (and (object? val) (not (nil? val)))
    (let [obj #js {}]
      (try
        (doseq [k (js/Object.keys val)]
          (try
            (aset obj k (clj->js-data (gobj/get val k)))
            (catch :default _ nil)))
        (catch :default _ nil))
      obj)
    :else (str val)))

(defn sanitize-state-for-devtools
  "Strips internal relm runtime properties (e.g. `:root` with DOM nodes and component fns)
  from the application state before sending to Redux DevTools or diffing."
  [app-state]
  (if (map? app-state)
    (dissoc app-state :root)
    app-state))

;; -----------------------------------------------------------------------------
;; State Diff Utilities
;; -----------------------------------------------------------------------------

(defn diff-state
  "Computes a map of differences between `old-state` and `new-state`.
  Returns a map containing:
  - `:added`    Keys present in `new-state` but not in `old-state`
  - `:removed`  Keys present in `old-state` but not in `new-state`
  - `:updated`  Map of `{key {:before old-val :after new-val}}` for changed keys"
  [old-state new-state]
  (if (and (map? old-state) (map? new-state))
    (let [old-keys (set (keys old-state))
          new-keys (set (keys new-state))
          added-keys (remove old-keys new-keys)
          removed-keys (remove new-keys old-keys)
          shared-keys (filter old-keys new-keys)
          updated (reduce (fn [acc k]
                            (let [v1 (get old-state k)
                                  v2 (get new-state k)]
                              (if (not= v1 v2)
                                (assoc acc k {:before v1 :after v2})
                                acc)))
                          {}
                          shared-keys)]
      (cond-> {}
        (seq added-keys) (assoc :added (select-keys new-state added-keys))
        (seq removed-keys) (assoc :removed (select-keys old-state removed-keys))
        (seq updated) (assoc :updated updated)))
    (if (not= old-state new-state)
      {:before old-state :after new-state}
      {})))

;; -----------------------------------------------------------------------------
;; DevTools Internal State
;; -----------------------------------------------------------------------------

(defonce ^:private !devtools-state
  (atom {:connected?           false
         :instance             nil
         :options              {}
         :initial-state        nil
         :last-committed-state nil
         :history              []
         :current-index        -1
         :paused?              false}))

(defn- default-options
  []
  {:name               "Relm App"
   :max-age            50
   :trace-effects?     true
   :log-to-console?    false
   :collapsed?         true
   :filter-fn          (constantly true)
   :serialize?         true
   :actions-blacklist  #{}
   :actions-whitelist  #{}})

;; -----------------------------------------------------------------------------
;; Pure State Replay
;; -----------------------------------------------------------------------------

(defn replay-actions
  "Purely replays a sequence of action entries or message maps starting from `base-app-state`.
  Uses `relm/update` to calculate final state and aggregated side effects without executing them.

  Each entry can be:
  - An action history entry map containing `:message` and optional `:comp-id`
  - Or a raw message vector `[::msg ...]`

  Returns `{:app-state final-app-state :effects all-effects}`."
  [base-app-state action-entries]
  (reduce
   (fn [{:keys [app-state effects]} entry]
     (let [is-map? (map? entry)
           message (if is-map? (:message entry) entry)
           comp-id (when is-map? (:comp-id entry))
           skipped? (when is-map? (:skipped? entry))]
       (if (or skipped? (nil? message))
         {:app-state app-state :effects effects}
         (let [component-info (when comp-id
                                (get-in app-state [:components comp-id]))
               state (if (some? component-info)
                       (:state component-info)
                       (when is-map?
                         (or (:prev-state entry)
                             (get-in (:prev-app-state entry) [:components comp-id :state]))))
               context (:context app-state)
               event (when comp-id {:component-id comp-id})
               result (relm/update state context message event)
               [new-state new-context step-fx] (cond
                                                 (and (vector? result) (= (count result) 3))
                                                 result

                                                 (and (vector? result) (= (count result) 2))
                                                 [(first result) (second result) nil]

                                                 (and (vector? result) (= (count result) 1))
                                                 [(first result) context nil]

                                                 (some? result)
                                                 [result context nil]

                                                 :else
                                                 [state context nil])
               next-app-state (cond-> app-state
                                comp-id (assoc-in [:components comp-id :state] new-state)
                                (some? new-context) (assoc :context new-context))]
           {:app-state next-app-state
            :effects   (if (seq step-fx)
                         (into (or effects []) step-fx)
                         effects)}))))
   {:app-state base-app-state :effects []}
   action-entries))

;; -----------------------------------------------------------------------------
;; Formatted Console Logger
;; -----------------------------------------------------------------------------

(defn- format-action-name
  [message comp-id]
  (let [msg-type (if (vector? message) (first message) message)
        target (if comp-id (str " @" comp-id) "")]
    (str "%c relm:action %c " (pr-str msg-type) target)))

(defn log-action!
  "Logs action dispatch details, state transitions, context diffs, and side effects
  to the browser console with expandable grouping."
  [{:keys [message comp-id prev-state new-state prev-context new-context effects duration-ms] :as _entry}
   {:keys [collapsed?] :or {collapsed? true}}]
  (when (exists? js/console)
    (let [has-group? (exists? (.-groupCollapsed js/console))
          badge-style "color: #ffffff; background: #6366f1; font-weight: bold; padding: 2px 5px; border-radius: 3px;"
          action-style "color: #4338ca; font-weight: bold;"
          title (format-action-name message comp-id)]
      (if has-group?
        (if collapsed?
          (.groupCollapsed js/console title badge-style action-style)
          (.group js/console title badge-style action-style))
        (.log js/console (str "[relm:action] " (pr-str (first message)) (when comp-id (str " @" comp-id)))))

      (when (exists? js/console.log)
        (.log js/console "%c message  " "color: #0284c7; font-weight: bold;" (clj->js-data message))
        (when comp-id
          (.log js/console "%c prev state" "color: #64748b; font-weight: bold;" (clj->js-data prev-state))
          (.log js/console "%c next state" "color: #10b981; font-weight: bold;" (clj->js-data new-state)))
        (let [ctx-diff (diff-state prev-context new-context)]
          (if (seq ctx-diff)
            (.log js/console "%c context diff" "color: #f59e0b; font-weight: bold;" (clj->js-data ctx-diff))
            (.log js/console "%c context    " "color: #64748b; font-weight: bold;" (clj->js-data new-context))))
        (when (seq effects)
          (.log js/console "%c effects   " "color: #ec4899; font-weight: bold;" (clj->js-data effects)))
        (when (some? duration-ms)
          (.log js/console "%c duration  " "color: #64748b; font-weight: bold;" (str duration-ms " ms"))))

      (when (and has-group? (exists? (.-groupEnd js/console)))
        (.groupEnd js/console)))))

;; -----------------------------------------------------------------------------
;; History Management & Time Travel
;; -----------------------------------------------------------------------------

(defn- apply-state-safely!
  "Applies target application state to `relm/!app-state` while preserving
  active root component and DOM node mounting metadata."
  [target-state]
  (let [current-root (:root @relm/!app-state)
        state-to-apply (cond-> target-state
                         (map? target-state) (assoc :root current-root))]
    (reset! relm/!app-state state-to-apply)
    (relm/flush-render!)))

(defn history
  "Returns the vector of recorded action entries."
  []
  (:history @!devtools-state))

(defn current-index
  "Returns the current 0-based position index in history."
  []
  (:current-index @!devtools-state))

(defn can-undo?
  "Returns true if there are prior states in history to undo."
  []
  (>= (:current-index @!devtools-state) 0))

(defn can-redo?
  "Returns true if there are subsequent states in history to redo."
  []
  (let [{:keys [history current-index]} @!devtools-state]
    (< current-index (dec (count history)))))

(defn jump-to!
  "Time-travels to the application state at history index `idx` without executing side effects.
  Updates `relm/!app-state` and triggers synchronous re-rendering."
  [idx]
  (let [{:keys [history initial-state instance]} @!devtools-state]
    (when (and (>= idx -1) (< idx (count history)))
      (let [base-state (or (when (seq history)
                             (:prev-app-state (first history)))
                           initial-state)
            target-state (if (= idx -1)
                           base-state
                           (let [active-actions (subvec history 0 (inc idx))
                                 {:keys [app-state]} (replay-actions base-state active-actions)]
                             app-state))]
        (when target-state
          (swap! !devtools-state assoc :current-index idx)
          (apply-state-safely! target-state)
          (when instance
            (try
              (.send instance #js {:type (str "JUMP_TO_ACTION [#" idx "]")} (clj->js-data (sanitize-state-for-devtools target-state)))
              (catch :default _ nil))))))))

(defn undo!
  "Steps backward by one action in history."
  []
  (when (can-undo?)
    (jump-to! (dec (:current-index @!devtools-state)))))

(defn redo!
  "Steps forward by one action in history."
  []
  (when (can-redo?)
    (jump-to! (inc (:current-index @!devtools-state)))))

(defn reset-to-initial!
  "Restores application state to the initial state recorded when DevTools connected."
  []
  (let [{:keys [initial-state history instance]} @!devtools-state]
    (let [target-state (or (when (seq history)
                             (:prev-app-state (first history)))
                           initial-state)]
      (when target-state
        (swap! !devtools-state assoc :current-index -1)
        (apply-state-safely! target-state)
        (when instance
          (try
            (.init instance (clj->js-data (sanitize-state-for-devtools target-state)))
            (catch :default _ nil)))))))

(defn commit!
  "Commits the current application state as the new baseline state and clears history."
  []
  (let [current-app-state @relm/!app-state
        {:keys [instance]} @!devtools-state]
    (swap! !devtools-state assoc
           :initial-state current-app-state
           :last-committed-state current-app-state
           :history []
           :current-index -1)
    (when instance
      (try
        (.init instance (clj->js-data (sanitize-state-for-devtools current-app-state)))
        (catch :default _ nil)))))

(defn clear-history!
  "Clears action history."
  []
  (commit!))

(defn toggle-action!
  "Toggles the skipped status of the action entry at `idx` and recalculates
  the current application state from initial baseline."
  [idx]
  (let [{:keys [history initial-state instance]} @!devtools-state]
    (when (and (>= idx 0) (< idx (count history)))
      (let [updated-history (update-in history [idx :skipped?] not)
            cur-idx (:current-index @!devtools-state)
            base-state (or (when (seq updated-history)
                             (:prev-app-state (first updated-history)))
                           initial-state)
            active-actions (if (neg? cur-idx)
                             []
                             (subvec updated-history 0 (inc cur-idx)))
            {:keys [app-state]} (replay-actions base-state active-actions)]
        (swap! !devtools-state assoc :history updated-history)
        (apply-state-safely! app-state)
        (when instance
          (try
            (.send instance #js {:type (str "TOGGLE_ACTION [#" idx "]")} (clj->js-data (sanitize-state-for-devtools app-state)))
            (catch :default _ nil)))))))

;; -----------------------------------------------------------------------------
;; Built-in Effect Handlers for DevTools Operations
;; -----------------------------------------------------------------------------

(defmethod relm/fx ::jump-to!
  [_event [_ idx]]
  (jump-to! idx))

(defmethod relm/fx ::toggle-action!
  [_event [_ idx]]
  (toggle-action! idx))

(defmethod relm/fx ::undo!
  [_event _effect]
  (undo!))

(defmethod relm/fx ::redo!
  [_event _effect]
  (redo!))

(defmethod relm/fx ::reset-to-initial!
  [_event _effect]
  (reset-to-initial!))

(defmethod relm/fx ::commit!
  [_event _effect]
  (commit!))

;; -----------------------------------------------------------------------------
;; Action Processing & DevTools Extension Bridge
;; -----------------------------------------------------------------------------

(defn- internal-devtools-action?
  [msg-type]
  (when (keyword? msg-type)
    (let [ns-str (namespace msg-type)
          name-str (name msg-type)]
      (or (= ns-str "relm.devtools")
          (string/starts-with? name-str "time-travel-")
          (string/starts-with? name-str "devtools-")))))

(defn- action-allowed?
  [message-type {:keys [filter-fn actions-blacklist actions-whitelist]}]
  (and (not (internal-devtools-action? message-type))
       (or (empty? actions-whitelist)
           (contains? actions-whitelist message-type))
       (not (contains? actions-blacklist message-type))
       (if (fn? filter-fn)
         (filter-fn message-type)
         true)))

(defn- record-action!
  [{:keys [event message comp-id prev-state new-state prev-context new-context prev-app-state new-app-state effects]}]
  (let [opts (:options @!devtools-state)
        msg-type (if (vector? message) (first message) message)]
    (when (action-allowed? msg-type opts)
      (let [entry {:id             (count (:history @!devtools-state))
                   :timestamp      (if (exists? js/Date) (.now js/Date) 0)
                   :action-type    msg-type
                   :message        message
                   :comp-id        comp-id
                   :prev-state     prev-state
                   :new-state      new-state
                   :prev-context   prev-context
                   :new-context    new-context
                   :prev-app-state prev-app-state
                   :new-app-state  new-app-state
                   :effects        (when (:trace-effects? opts) effects)
                   :duration-ms    0
                   :skipped?       false}
            max-age (or (:max-age opts) 50)]
        ;; Update DevTools in-memory history
        (swap! !devtools-state
               (fn [st]
                 (let [hist (conj (:history st) entry)
                       trimmed-hist (if (> (count hist) max-age)
                                      (subvec hist (- (count hist) max-age))
                                      hist)]
                   (assoc st
                          :history trimmed-hist
                          :current-index (dec (count trimmed-hist))))))

        ;; Console logging if enabled
        (when (:log-to-console? opts)
          (log-action! entry opts))

        ;; Send to Redux DevTools extension
        (when-let [instance (:instance @!devtools-state)]
          (try
            (let [clean-state (sanitize-state-for-devtools new-app-state)
                  action-obj (clj->js-data
                              {:type (str (if-let [ns (namespace msg-type)] (str ns "/") "")
                                          (name msg-type))
                               :message message
                               :comp-id comp-id
                               :effects (when (:trace-effects? opts) effects)})
                  state-obj (clj->js-data clean-state)]
              (.send instance action-obj state-obj))
            (catch :default _e
              nil)))))))

;; -----------------------------------------------------------------------------
;; Redux DevTools Extension Interop
;; -----------------------------------------------------------------------------

(defn has-redux-devtools?
  "Checks if the Redux DevTools browser extension is installed and available."
  []
  (and (exists? js/window)
       (some? (.-__REDUX_DEVTOOLS_EXTENSION__ js/window))))

(defn- handle-extension-message
  [raw-msg]
  (let [msg-type (gobj/get raw-msg "type")
        payload (gobj/get raw-msg "payload")]
    (cond
      (= msg-type "DISPATCH")
      (let [dispatch-type (when payload (gobj/get payload "type"))]
        (cond
          (or (= dispatch-type "JUMP_TO_STATE")
              (= dispatch-type "JUMP_TO_ACTION"))
          (let [idx (when payload (gobj/get payload "actionId"))]
            (when (some? idx)
              (jump-to! (dec (js/parseInt idx 10)))))

          (= dispatch-type "RESET")
          (reset-to-initial!)

          (= dispatch-type "ROLLBACK")
          (when-let [committed (:last-committed-state @!devtools-state)]
            (apply-state-safely! committed))

          (= dispatch-type "COMMIT")
          (commit!)

          (= dispatch-type "SWEEP")
          (swap! !devtools-state update :history (fn [hist] (filterv (complement :skipped?) hist)))

          (= dispatch-type "TOGGLE_ACTION")
          (when-let [action-id (when payload (or (gobj/get payload "id") (gobj/get payload "actionId")))]
            (let [parsed-id (js/parseInt action-id 10)
                  idx (if (and (> parsed-id 0) (<= parsed-id (count (:history @!devtools-state))))
                        (dec parsed-id)
                        parsed-id)]
              (toggle-action! idx)))

          (= dispatch-type "IMPORT_STATE")
          (when-let [lifted-state (when payload (gobj/get payload "nextLiftedState"))]
            (when-let [computed-states (gobj/get lifted-state "computedStates")]
              (when-let [last-state (aget computed-states (dec (.-length computed-states)))]
                (when-let [st (gobj/get last-state "state")]
                  (apply-state-safely! (walk/keywordize-keys (js->clj st)))))))))

      (= msg-type "ACTION")
      (when (string? payload)
        (try
          (let [parsed (reader/read-string payload)]
            (when (vector? parsed)
              (relm/dispatch! nil parsed)))
          (catch :default _ nil))))))

;; -----------------------------------------------------------------------------
;; Public API: Connect & Disconnect
;; -----------------------------------------------------------------------------

(defn connected?
  "Returns true if DevTools is currently connected."
  []
  (:connected? @!devtools-state))

(defn disconnect!
  "Disconnects from Redux DevTools and unregisters dispatch listeners."
  []
  (relm/remove-dispatch-listener! ::relm-devtools)
  (when-let [instance (:instance @!devtools-state)]
    (when (exists? (.-unsubscribe instance))
      (try (.unsubscribe instance) (catch :default _ nil))))
  (reset! !devtools-state {:connected?           false
                           :instance             nil
                           :options              {}
                           :initial-state        nil
                           :last-committed-state nil
                           :history              []
                           :current-index        -1
                           :paused?              false}))

(defn connect!
  "Connects Relm to the Redux DevTools browser extension and sets up action tracing.

  Options:
  - `:name`               App name displayed in Redux DevTools (default: \"Relm App\")
  - `:max-age`            Maximum number of actions retained in history (default: 50)
  - `:trace-effects?`     Whether to record side effects returned by update handlers (default: true)
  - `:log-to-console?`    Whether to log action dispatches to the browser console (default: false)
  - `:collapsed?`         Whether console groups should be collapsed (default: true)
  - `:filter-fn`          Predicate `(fn [msg-type] boolean)` to filter recorded actions
  - `:actions-blacklist`  Set of action message keywords to ignore
  - `:actions-whitelist`  Set of action message keywords to exclusively record

  Example:
  ```clojure
  (relm.devtools/connect! {:name \"My Relm App\" :log-to-console? true})
  ```"
  ([]
   (connect! {}))
  ([opts]
   (when (connected?)
     (disconnect!))
   (let [merged-opts (merge (default-options) opts)
         initial-app-state @relm/!app-state
         extension (when (has-redux-devtools?)
                     (.-__REDUX_DEVTOOLS_EXTENSION__ js/window))
         instance (when extension
                    (try
                      (let [inst (.connect extension
                                           #js {:name               (:name merged-opts)
                                                :maxAge             (:max-age merged-opts)
                                                :features           #js {:pause     true
                                                                         :lock      true
                                                                         :persist   true
                                                                         :export    true
                                                                         :import    "custom"
                                                                         :jump      true
                                                                         :skip      true
                                                                         :reorder   true
                                                                         :dispatch  true
                                                                         :test      true}})]
                        (.init inst (clj->js-data (sanitize-state-for-devtools initial-app-state)))
                        (.subscribe inst handle-extension-message)
                        inst)
                      (catch :default _ nil)))]

     (reset! !devtools-state
             {:connected?           true
              :instance             instance
              :options              merged-opts
              :initial-state        initial-app-state
              :last-committed-state initial-app-state
              :history              []
              :current-index        -1
              :paused?              false})

     ;; Register core dispatch listener
     (relm/add-dispatch-listener! ::relm-devtools record-action!)

     true)))
