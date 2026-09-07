(ns examples.messaging
  "Cross-component messaging example demonstrating `[::relm/send target-id messages]`.

  Demonstrates:
  - Targeted message dispatch to any component's isolated local state by component ID
  - Single message delivery (`[::relm/send target-id [::msg arg]]`)
  - Batched multi-message delivery (`[::relm/send target-id [[::msg-1] [::msg-2]] target-event]`)
  - Effect-based messaging: update handlers returning `[[::relm/send target-id ...]]` side effects
  - Peer-to-peer component delegation and broadcast orchestration across isolated component states"
  (:require [clojure.string :as string]
            [examples.snippets :as snippets]
            [examples.ui :as ui]
            [relm.core :as relm]))

;; -----------------------------------------------------------------------------
;; Component 1: Notification Center (Target Component)
;; -----------------------------------------------------------------------------

(defn- inbox-init
  "Initializes notification center local state."
  [_context _args]
  {:notifications [{:id 1 :title "System Ready" :msg "Inbox initialized and listening for cross-component events." :variant :info}]
   :total-received 1})

;; Pushes a new notification into the inbox.
(defmethod relm/update ::push-notification
  [state context [_ {:keys [title msg variant]}] _event]
  (let [next-id (inc (:total-received state 0))
        notif {:id next-id
               :title (or title "Notice")
               :msg (or msg "")
               :variant (or variant :info)}
        updated-notifs (conj (vec (take-last 5 (:notifications state []))) notif)]
    [(assoc state :notifications updated-notifs :total-received next-id)
     context]))

;; Clears all active notifications from the inbox.
(defmethod relm/update ::clear-notifications
  [state context _ _event]
  [(assoc state :notifications []) context])

(defn- inbox-view
  "Renders the Notification Center target component."
  [{:keys [notifications total-received]} _context]
  (ui/card
   {:class "border-slate-200 shadow-sm"}
   (ui/card-header
    [:div {:class "flex items-center justify-between"}
     [:div
      (ui/card-title "📬 System Inbox")
      (ui/card-description "Component ID: `system-inbox` • Receives remote alerts and audit events.")]
     [:div {:class "flex items-center gap-2"}
      (ui/badge {:variant :indigo} (str total-received " Total"))
      (when (seq notifications)
        (ui/button
         {:variant :ghost
          :size    :sm
          :class   "text-xs text-slate-500 hover:text-slate-800"
          :on      {:click [::clear-notifications]}}
         "Clear"))]])

   (ui/card-content
    (if (empty? notifications)
      [:div {:class "py-8 text-center text-slate-400 text-xs italic border border-dashed border-slate-200 rounded-lg"}
       "No active notifications. Send one from the Control Hub!"]
      [:div {:class "space-y-2"}
       (for [{:keys [id title msg variant]} (reverse notifications)]
         ^{:key id}
         (ui/alert
          {:variant variant
           :class   "py-2.5 px-3 text-xs"}
          [:div {:class "flex items-start justify-between gap-2"}
           [:div
            [:span {:class "font-bold mr-1.5"} title]
            [:span {:class "text-slate-700"} msg]]
           [:span {:class "text-[10px] text-slate-400 font-mono shrink-0"} (str "#" id)]]))]))))

(def NotificationCenter
  "Notification center component mounted with explicit ID `system-inbox`."
  (relm/component
   {:component-id "notification-center"
    :init inbox-init
    :view inbox-view}))

;; -----------------------------------------------------------------------------
;; Component 2: Worker Node (Target & Peer Sender Component)
;; -----------------------------------------------------------------------------

(defn- worker-init
  "Initializes worker node state."
  [_context {:keys [id name role initial-tasks] :or {initial-tasks 0}}]
  {:id id
   :name (or name id)
   :role (or role "General Worker")
   :tasks initial-tasks
   :status :idle
   :logs []})

;; Adds tasks to this worker and marks it busy.
(defmethod relm/update ::assign-tasks
  [state context [_ count task-desc] _event]
  (let [n (or count 1)
        desc (or task-desc "Generic Task")
        new-tasks (+ (:tasks state 0) n)
        log-entry (str "+" n " tasks (" desc ")")
        new-logs (conj (vec (take-last 4 (:logs state []))) log-entry)]
    [(assoc state
            :tasks new-tasks
            :status (if (pos? new-tasks) :active :idle)
            :logs new-logs)
     context]))

;; Completes processed tasks on this worker.
(defmethod relm/update ::complete-tasks
  [state context [_ count] _event]
  (let [n (or count 1)
        remaining (max 0 (- (:tasks state 0) n))
        log-entry (str "Completed " (min n (:tasks state 0)) " tasks")
        new-logs (conj (vec (take-last 4 (:logs state []))) log-entry)]
    [(assoc state
            :tasks remaining
            :status (if (pos? remaining) :active :idle)
            :logs new-logs)
     context]))

;; Sets explicit status on the worker.
(defmethod relm/update ::set-worker-status
  [state context [_ new-status] _event]
  [(assoc state :status new-status) context])

;; Resets worker state.
(defmethod relm/update ::reset-worker
  [state context _ _event]
  [(assoc state :tasks 0 :status :idle :logs ["State reset"]) context])

;; Peer-to-peer delegation: Worker sends tasks to a peer worker AND sends a notice to the inbox.
;; Demonstrates returning multiple `[::relm/send target ...]` side-effects from an update handler!
(defmethod relm/update ::delegate-work
  [state context [_ target-worker-id amount] _event]
  (let [n (min (or amount 1) (:tasks state 0))]
    (if (pos? n)
      (let [remaining (- (:tasks state 0) n)
            sender-name (:name state)
            log-entry (str "Delegated " n " tasks to " target-worker-id)]
        [(assoc state
                :tasks remaining
                :status (if (pos? remaining) :active :idle)
                :logs (conj (vec (take-last 4 (:logs state []))) log-entry))
         context
         [[::relm/send target-worker-id [::assign-tasks n (str "Delegated from " sender-name)]]
          [::relm/send "system-inbox" [::push-notification
                                       {:title "Peer Work Transfer"
                                        :msg (str sender-name " transferred " n " tasks to " target-worker-id)
                                        :variant :warning}]]]])
      [state context])))

(defn- worker-view
  "Renders a worker node instance."
  [{:keys [id name role tasks status logs]} _context]
  (let [peer-id (if (= id "worker-alpha") "worker-beta" "worker-alpha")
        peer-label (if (= id "worker-alpha") "Beta" "Alpha")
        status-variant (case status
                         :active :success
                         :busy   :warning
                         :idle   :secondary
                         :secondary)]
    (ui/card
     {:class "border-slate-200 shadow-sm flex flex-col justify-between"}
     (ui/card-header
      [:div {:class "flex items-center justify-between"}
       [:div
        (ui/card-title (str (if (= id "worker-alpha") "⚡ " "⚙️ ") name))
        (ui/card-description (str "ID: `" id "` • " role))]
       (ui/badge {:variant status-variant} (string/capitalize (clojure.core/name status)))])

     (ui/card-content
      [:div {:class "space-y-3"}
       [:div {:class "flex items-baseline justify-between p-3 bg-slate-50 rounded-lg border border-slate-100"}
        [:span {:class "text-xs font-medium text-slate-500 uppercase tracking-wider"} "Pending Tasks"]
        [:span {:class (ui/cx "text-2xl font-bold font-mono"
                              (if (pos? tasks) "text-indigo-600" "text-slate-400"))}
         tasks]]

       ;; Mini Log
       (when (seq logs)
         [:div {:class "p-2.5 bg-slate-900 rounded-md text-[11px] font-mono text-slate-300 space-y-1"}
          [:div {:class "text-[10px] text-slate-500 font-bold uppercase"} "Recent Activity"]
          (for [[idx log-item] (map-indexed vector (reverse logs))]
            ^{:key idx}
            [:div {:class "truncate"} (str "• " log-item)])])])

     (ui/card-footer
      [:div {:class "flex flex-wrap items-center justify-between gap-2 w-full pt-2"}
       [:div {:class "flex items-center gap-1.5"}
        (ui/button
         {:variant :default
          :size    :sm
          :class   "bg-slate-900 hover:bg-slate-800 text-xs"
          :on      {:click [::assign-tasks 1 "Direct Local Click"]}}
         "+1 Task")
        (ui/button
         {:variant :outline
          :size    :sm
          :disabled? (zero? tasks)
          :class   "text-xs"
          :on      {:click [::complete-tasks 1]}}
         "Done -1")]

       [:div {:class "flex items-center gap-1.5"}
        (ui/button
         {:variant :secondary
          :size    :sm
          :disabled? (zero? tasks)
          :class   "text-xs text-indigo-700 bg-indigo-50 hover:bg-indigo-100 border-indigo-200"
          :on      {:click [::delegate-work peer-id 1]}}
         (str "Delegate 1 → " peer-label))
        (ui/button
         {:variant :ghost
          :size    :sm
          :class   "text-xs text-slate-400 hover:text-slate-700"
          :on      {:click [::reset-worker]}}
         "Reset")]]))))

(def WorkerNode
  "Worker component definition with isolated state."
  (relm/component
   {:component-id "worker-node"
    :init worker-init
    :view worker-view}))

;; -----------------------------------------------------------------------------
;; Component 3: Orchestrator / Command Hub (Root Example Component)
;; -----------------------------------------------------------------------------

(defn- hub-init
  "Initializes orchestrator command hub state."
  [_context _args]
  {:last-action "Ready to dispatch cross-component messages."})

;; Dispatches a broadcast side-effect to all components in the cluster.
(defmethod relm/update ::broadcast-cluster-work
  [state context [_ amount] _event]
  (let [n (or amount 5)]
    [(assoc state :last-action (str "Broadcasted " n " tasks to Alpha & Beta + logged to Inbox."))
     context
     [[::relm/send "worker-alpha" [::assign-tasks n "Cluster Broadcast Batch"]]
      [::relm/send "worker-beta" [::assign-tasks n "Cluster Broadcast Batch"]]
      [::relm/send "system-inbox" [::push-notification
                                   {:title "Cluster Broadcast"
                                    :msg (str "Dispatched " n " tasks simultaneously to all workers.")
                                    :variant :info}]]]]))

;; Dispatches reset to all components via effect.
(defmethod relm/update ::reset-all-cluster
  [state context _ _event]
  [(assoc state :last-action "Reset signal broadcasted to all cluster nodes.")
   context
   [[::relm/send "worker-alpha" [::reset-worker]]
    [::relm/send "worker-beta" [::reset-worker]]
    [::relm/send "system-inbox" [[::clear-notifications]
                                 [::push-notification {:title "Cluster Reset"
                                                       :msg "All worker states and inbox history were reset."
                                                       :variant :warning}]]]]])

(defn view
  "Renders the interactive Cross-Component Messaging example showcase."
  [{:keys [last-action]} _context]
  [:div {:class "max-w-5xl mx-auto space-y-6"}
   (ui/example-header
    {:step        "5"
     :title       "Cross-Component Messaging"
     :difficulty  "Intermediate"
     :description "Execute messages directly against isolated local states of remote components using `[::relm/send target-id messages]`. Supports direct UI triggers, batched events, and effect-based orchestration."
     :tags        ["::relm/send" "Target Component ID" "Batch Messaging" "Side Effects" "Peer Delegation"]})

   ;; Control Hub Card
   (ui/card
    {:class "border-indigo-100 bg-gradient-to-br from-white via-indigo-50/20 to-slate-50 shadow-md"}
    (ui/card-header
     [:div {:class "flex flex-wrap items-center justify-between gap-3"}
      [:div
       (ui/card-title "Control Hub (Dispatcher)")
       (ui/card-description "Send targeted commands across components using single messages, batches, or effect pipelines.")]
      (ui/badge {:variant :indigo} "Cross-Component Dispatcher")])

    (ui/card-content
     [:div {:class "space-y-4"}
      ;; Quick Action Buttons Group
      [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-3"}
       ;; 1. Direct Single Message
       [:div {:class "p-3.5 bg-white rounded-lg border border-slate-200 shadow-2xs flex flex-col justify-between"}
        [:div {:class "mb-2"}
         [:div {:class "text-xs font-bold text-slate-800 mb-0.5"} "1. Direct Message Send"]
         [:p {:class "text-[11px] text-slate-500 leading-tight"}
          "Sends a single `[::assign-tasks]` message directly to `worker-alpha` from UI click."]]
        (ui/button
         {:variant :default
          :size    :sm
          :class   "w-full bg-slate-900 hover:bg-slate-800 text-xs"
          :on      {:click [::relm/send "worker-alpha" [::assign-tasks 3 "Direct Hub Dispatch"]]}}
         "Send 3 Tasks → Alpha")]

       ;; 2. Batch Multi-Message Send
       [:div {:class "p-3.5 bg-white rounded-lg border border-slate-200 shadow-2xs flex flex-col justify-between"}
        [:div {:class "mb-2"}
         [:div {:class "text-xs font-bold text-slate-800 mb-0.5"} "2. Batched Multi-Message"]
         [:p {:class "text-[11px] text-slate-500 leading-tight"}
          "Delivers a vector of messages `[[::assign-tasks ...] [::push-notification ...]]` atomically."]]
        (ui/button
         {:variant :default
          :size    :sm
          :class   "w-full bg-indigo-600 hover:bg-indigo-700 text-xs text-white"
          :on      {:click [[::relm/send "worker-beta" [[::assign-tasks 5 "Batch Operation"]
                                                        [::set-worker-status :busy]]]
                            [::relm/send "system-inbox" [::push-notification
                                                         {:title "Batch Dispatched"
                                                          :msg "Hub sent a 5-task batch to Worker Beta"
                                                          :variant :success}]]]}}
         "Send Batch → Beta + Inbox")]

       ;; 3. Effect-Based Broadcast
       [:div {:class "p-3.5 bg-white rounded-lg border border-slate-200 shadow-2xs flex flex-col justify-between"}
        [:div {:class "mb-2"}
         [:div {:class "text-xs font-bold text-slate-800 mb-0.5"} "3. Update Handler FX"]
         [:p {:class "text-[11px] text-slate-500 leading-tight"}
          "Hub's `update` handler returns multiple `[::relm/send ...]` effect vectors to broadcast."]]
        (ui/button
         {:variant :secondary
          :size    :sm
          :class   "w-full bg-indigo-100 hover:bg-indigo-200 text-indigo-900 border border-indigo-200 text-xs font-semibold"
          :on      {:click [::broadcast-cluster-work 5]}}
         "📢 Broadcast +5 to All Nodes")]]

      ;; Dispatch Status Bar
      [:div {:class "flex items-center justify-between p-2.5 bg-slate-900 rounded-md text-xs font-mono text-slate-200"}
       [:div {:class "flex items-center gap-2 truncate"}
        [:span {:class "text-indigo-400 font-bold"} "Last Event:"]
        [:span {:class "text-slate-300 truncate"} last-action]]
       (ui/button
        {:variant :ghost
         :size    :sm
         :class   "text-rose-400 hover:text-rose-300 hover:bg-slate-800 text-xs h-6 px-2 ml-2 shrink-0"
         :on      {:click [::reset-all-cluster]}}
        "Reset Cluster")]]))

   ;; Worker Grid (2 Independent Target Components)
   [:div {:class "grid grid-cols-1 md:grid-cols-2 gap-4"}
    (WorkerNode {:id            "worker-alpha"
                 :name          "Worker Alpha"
                 :role          "Data Ingestion Node"
                 :initial-tasks 2})

    (WorkerNode {:id            "worker-beta"
                 :name          "Worker Beta"
                 :role          "Analytics Processing Node"
                 :initial-tasks 0})]

   ;; Notification Center (Target Component)
   (NotificationCenter {:id "system-inbox"})

   ;; Architecture Explanatory Box
   (ui/card
    {:class "bg-slate-900 text-slate-100 border-slate-800"}
    (ui/card-header
     [:div {:class "flex items-center gap-2 text-indigo-400"}
      [:span {:class "text-base"} "💡"]
      (ui/card-title {:class "text-slate-100 text-base"} "How Cross-Component Messaging Works in Relm")])
    (ui/card-content
     [:div {:class "grid grid-cols-1 sm:grid-cols-3 gap-4 text-xs font-mono"}
      [:div {:class "bg-slate-800/80 p-3 rounded-lg border border-slate-700/60"}
       [:div {:class "text-indigo-300 font-bold mb-1"} "1. Target Resolution"]
       [:p {:class "text-slate-400 leading-normal"}
        "`::relm/send` looks up the target component by ID (`:id` or `:component-id`) in `@!app-state`."]]
      [:div {:class "bg-slate-800/80 p-3 rounded-lg border border-slate-700/60"}
       [:div {:class "text-emerald-300 font-bold mb-1"} "2. Local Execution"]
       [:p {:class "text-slate-400 leading-normal"}
        "The target's `update` multimethod executes with its own isolated local state and context."]]
      [:div {:class "bg-slate-800/80 p-3 rounded-lg border border-slate-700/60"}
       [:div {:class "text-amber-300 font-bold mb-1"} "3. Side Effects Flow"]
       [:p {:class "text-slate-400 leading-normal"}
        "Any effects returned by the target component are executed with the target component event."]]]))

   ;; Expandable Source Code Panel
   (ui/code-panel
    {:title    "Cross-Component Messaging Example Source Code"
     :filename "messaging.cljs"
     :code     snippets/messaging-code})])

;; -----------------------------------------------------------------------------
;; Component Constructor
;; -----------------------------------------------------------------------------

(def MessagingExample
  "Cross-component messaging example component ready to be mounted."
  (relm/component
   {:component-id "messaging-example"
    :init hub-init
    :view view}))
