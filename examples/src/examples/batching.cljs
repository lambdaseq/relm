(ns examples.batching
  "Batching & Multi-Render example demonstrating Relm's requestAnimationFrame/microtask render scheduling.

  Demonstrates:
  - Coalescing high-frequency state dispatches (bursts of 100-1,000 updates) into single frame renders
  - High-frequency timer tickers updating state continuously without DOM thrashing
  - Multi-component cascading updates and re-entrant lifecycle dispatches
  - Real-time execution benchmarking and throughput measurement"
  (:require [examples.snippets :as snippets]
            [examples.ui :as ui]
            [relm.core :as relm]))

;; -----------------------------------------------------------------------------
;; Telemetry & Render Frame Tracker
;; -----------------------------------------------------------------------------

(defonce !telemetry
  (atom {:total-renders 0}))

;; -----------------------------------------------------------------------------
;; Child Component (Multi-Component Stress Test)
;; -----------------------------------------------------------------------------

(defn child-init
  [_context {:keys [id initial-val] :or {initial-val 0}}]
  {:id    id
   :val   initial-val
   :ticks 0})

(defmethod relm/update ::child-tick
  [state context _message _event]
  [(-> state
       (update :val inc)
       (update :ticks inc))
   context])

(defmethod relm/update ::child-reset
  [state context _message _event]
  [(assoc state :val 0 :ticks 0) context])

(defn child-view
  [{:keys [id val ticks]} _context]
  [:div {:class "flex items-center justify-between p-3 rounded-lg bg-slate-50 border border-slate-200 text-xs font-mono"}
   [:div {:class "flex items-center gap-2"}
    [:span {:class "h-2 w-2 rounded-full bg-indigo-500 animate-pulse"}]
    [:span {:class "font-bold text-slate-700"} (str "Worker #" id)]]
   [:div {:class "flex items-center gap-3"}
    [:span {:class "text-slate-500"} (str "ticks: " ticks)]
    [:span {:class "px-2 py-0.5 rounded bg-indigo-100 text-indigo-700 font-bold"} (str "val: " val)]]])

(def BatchWorker
  (relm/component
   {:component-id "batch-worker"
    :init child-init
    :view child-view}))

;; -----------------------------------------------------------------------------
;; Parent Component State & Updates
;; -----------------------------------------------------------------------------

(defn init
  [_context _args]
  {:count               0
   :total-dispatches    0
   :burst-size          100
   :ticker-on?          false
   :ticker-interval-ms  4
   :worker-count        4
   :last-burst          {:size nil :duration-ms nil}})

(defmethod relm/update ::increment-single
  [state context _message _event]
  [(-> state
       (update :count inc)
       (update :total-dispatches inc))
   context])

(defmethod relm/update ::burst-dispatch
  [{:keys [burst-size worker-count] :as state} context [_ explicit-size] _event]
  (let [size (or explicit-size burst-size 100)
        num-workers (or worker-count 4)
        messages (vec (repeat size [::increment-single]))
        worker-msgs (when (pos? num-workers)
                      (let [ticks-per-worker (max 1 (quot size num-workers))]
                        (mapv (fn [idx]
                                [::relm/dispatch-n! {:component-id (str "worker-" idx)}
                                 (vec (repeat ticks-per-worker [::child-tick]))])
                              (range num-workers))))]
    [state context (into [[::run-burst! size messages]] worker-msgs)]))

(defmethod relm/update ::burst-completed
  [state context [_ burst-info] _event]
  [(assoc state :last-burst burst-info) context])

(defmethod relm/update ::toggle-ticker
  [{:keys [ticker-on?] :as state} context _message _event]
  (let [next-state (not ticker-on?)]
    [(assoc state :ticker-on? next-state)
     context
     (if next-state
       [[::start-ticker!]]
       [[::stop-ticker!]])]))

(defmethod relm/update ::ticker-step
  [{:keys [ticker-on? ticker-interval-ms worker-count]
    :or   {ticker-interval-ms 4}
    :as   state} context _message _event]
  (if-not ticker-on?
    [state context]
    (let [child-msgs (mapv (fn [idx]
                             [::relm/dispatch! {:component-id (str "worker-" idx)} [::child-tick]])
                           (range worker-count))
          dispatches-this-step (+ 1 worker-count)]
      [(-> state
           (update :count inc)
           (update :total-dispatches + dispatches-this-step))
       context
       (into [[::relm/dispatch-later! {:ms ticker-interval-ms :dispatch! [::ticker-step]}]]
             child-msgs)])))

(defmethod relm/update ::set-ticker-interval
  [state context [_ ms] _event]
  [(assoc state :ticker-interval-ms ms) context])

(defmethod relm/update ::set-worker-count
  [state context [_ n] _event]
  [(assoc state :worker-count n) context])

(defmethod relm/update ::reset-all
  [state context _message _event]
  (reset! !telemetry {:total-renders 0})
  (let [worker-resets (mapv (fn [idx]
                              [::relm/dispatch! {:component-id (str "worker-" idx)} [::child-reset]])
                            (range (:worker-count state 4)))]
    [(assoc state
            :count 0
            :total-dispatches 0
            :ticker-on? false
            :last-burst {:size nil :duration-ms nil})
     context
     (into [[::stop-ticker!]] worker-resets)]))

;; -----------------------------------------------------------------------------
;; Side Effects (Interval Timers & Burst Benchmarking)
;; -----------------------------------------------------------------------------

(defonce ^:private !ticker-handle (atom nil))

(defmethod relm/fx ::start-ticker!
  [dom-event _effect]
  (when-not @!ticker-handle
    (reset! !ticker-handle true)
    (relm/dispatch! dom-event [::ticker-step])))

(defmethod relm/fx ::stop-ticker!
  [_dom-event _effect]
  (reset! !ticker-handle nil))

(defmethod relm/fx ::run-burst!
  [dom-event [_ size messages]]
  (let [t0 (if (and (exists? js/performance) (exists? (.-now js/performance)))
             (.now js/performance)
             0)]
    (relm/dispatch! dom-event messages)
    (let [t1 (if (and (exists? js/performance) (exists? (.-now js/performance)))
               (.now js/performance)
               0)
          duration-ms (max 0.01 (- t1 t0))]
      (relm/dispatch! dom-event [::burst-completed {:size size :duration-ms duration-ms}]))))

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defn view
  [{:keys [count total-dispatches ticker-on? ticker-interval-ms worker-count last-burst]
    :or   {ticker-interval-ms 4
           total-dispatches 0}} _context]
  (let [{:keys [total-renders]} (swap! !telemetry update :total-renders inc)
        saved-renders (max 0 (- total-dispatches total-renders))
        efficiency (if (pos? total-dispatches)
                     (min 100 (max 0 (Math/round (* 100 (/ saved-renders total-dispatches)))))
                     0)
        {:keys [size duration-ms]} last-burst
        burst-rate (when (and size duration-ms (pos? duration-ms))
                     (Math/round (/ (* 1000 size) duration-ms)))]
    [:div {:class "max-w-4xl mx-auto space-y-6"}
     (ui/example-header
      {:step        "8"
       :title       "Render Batching & Scheduling"
       :difficulty  "Advanced"
       :description "Demonstrates how Relm coalesces rapid bursts of synchronous state dispatches and high-frequency tickers into single-frame DOM updates via requestAnimationFrame / microtasks."
       :tags        ["requestAnimationFrame" "render-batching" "queueMicrotask" "coalescing" "relm/dispatch-n!"]})

     ;; Telemetry Dashboard Grid
     [:div {:class "grid grid-cols-1 sm:grid-cols-4 gap-4"}
      (ui/card
       {:class "border-slate-200 bg-white shadow-xs"}
       [:div {:class "p-4 flex flex-col"}
        [:span {:class "text-xs font-semibold text-slate-500 uppercase tracking-wider"} "State Updates"]
        [:span {:class "text-3xl font-extrabold font-mono text-slate-900 mt-1"} total-dispatches]
        [:span {:class "text-[11px] text-slate-400 mt-1"} "Total messages applied"]])

      (ui/card
       {:class "border-slate-200 bg-white shadow-xs"}
       [:div {:class "p-4 flex flex-col"}
        [:span {:class "text-xs font-semibold text-slate-500 uppercase tracking-wider"} "DOM Render Passes"]
        [:span {:class "text-3xl font-extrabold font-mono text-indigo-600 mt-1"} total-renders]
        [:span {:class "text-[11px] text-slate-400 mt-1"} "Replicant VSync frame diffs"]])

      (ui/card
       {:class "border-slate-200 bg-white shadow-xs"}
       [:div {:class "p-4 flex flex-col"}
        [:span {:class "text-xs font-semibold text-slate-500 uppercase tracking-wider"} "Renders Coalesced"]
        [:span {:class "text-3xl font-extrabold font-mono text-emerald-600 mt-1"} saved-renders]
        [:span {:class "text-[11px] text-slate-400 mt-1"} "Unnecessary repaints avoided"]])

      (ui/card
       {:class "border-slate-200 bg-white shadow-xs"}
       [:div {:class "p-4 flex flex-col"}
        [:span {:class "text-xs font-semibold text-slate-500 uppercase tracking-wider"} "Batch Efficiency"]
        [:span {:class "text-3xl font-extrabold font-mono text-amber-600 mt-1"} (str efficiency "%")]
        [:span {:class "text-[11px] text-slate-400 mt-1"} "Frames saved via RAF"]])]

     ;; Interactive Test Controls
     (ui/card
      {:class "shadow-md border-slate-200"}
      [:div
       (ui/card-header
        [:div {:class "flex items-center justify-between"}
         [:div
          (ui/card-title "Multi-Render Stress Controls")
          (ui/card-description "Trigger synchronous bursts or continuous high-frequency streams to verify batching.")]
         (if ticker-on?
           (ui/badge {:variant :success} (str "Ticker Active (" ticker-interval-ms "ms)"))
           (ui/badge {:variant :secondary} "Idle"))])

       (ui/card-content
        [:div {:class "space-y-4"}
         [:div {:class "flex flex-col sm:flex-row items-center justify-between p-4 bg-slate-900 text-white rounded-xl"}
          [:div
           [:span {:class "text-xs text-slate-400 uppercase tracking-wider font-semibold"} "Main Counter State"]
           [:div {:class "text-4xl font-extrabold font-mono text-indigo-300 mt-0.5"} count]]
          [:div {:class "flex items-center gap-4 mt-3 sm:mt-0"}
           [:div {:class "flex items-center gap-1 text-xs"}
            [:span {:class "text-slate-400 mr-1"} "Speed:"]
            (for [[ms label] [[4 "4ms"] [16 "16ms"] [50 "50ms"]]]
              (ui/button
               {:variant (if (= ticker-interval-ms ms) :default :outline)
                :size    :sm
                :class   (str "h-7 px-2 text-xs font-mono " (when (= ticker-interval-ms ms) "bg-indigo-600 border-indigo-600 text-white"))
                :on      {:click [::set-ticker-interval ms]}}
               label))]
           [:div {:class "flex items-center gap-1 text-xs"}
            [:span {:class "text-slate-400"} "Workers:"]
            [:span {:class "px-2 py-0.5 rounded bg-slate-800 text-slate-200 font-mono font-bold text-xs"} worker-count]]]]

         [:div {:class "flex flex-wrap items-center gap-3 pt-2"}
          (ui/button
           {:variant :default
            :class   "bg-indigo-600 hover:bg-indigo-700 text-white shadow-sm"
            :on      {:click [::burst-dispatch 100]}}
           "⚡ Burst 100 Updates")

          (ui/button
           {:variant :outline
            :class   "border-indigo-300 text-indigo-700 hover:bg-indigo-50 font-semibold"
            :on      {:click [::burst-dispatch 1000]}}
           "🚀 Burst 1,000 Updates")

          (ui/button
           {:variant (if ticker-on? :destructive :secondary)
            :on      {:click [::toggle-ticker]}}
           (if ticker-on? (str "⏹ Stop " ticker-interval-ms "ms Ticker") (str "▶ Start " ticker-interval-ms "ms Ticker")))

          (ui/button
           {:variant :ghost
            :class   "text-slate-600 hover:text-slate-900"
            :on      {:click [::reset-all]}}
           "🔄 Reset All")]

         (when (and size duration-ms)
           [:div {:class "flex items-center gap-2 text-xs font-mono text-indigo-200 bg-slate-800/90 px-3 py-2 rounded-lg border border-slate-700/70"}
            [:span "⚡"]
            [:span (str "Last Burst: " size " updates processed in " (.toFixed duration-ms 2) " ms (" (.toLocaleString burst-rate) " msgs/sec)")]])])])

     ;; Multi-Component Subtree
     (ui/card
      {:class "border-slate-200 bg-white shadow-sm"}
      [:div
       (ui/card-header
        [:div {:class "flex items-center justify-between"}
         [:div
          (ui/card-title "Multi-Component Parallel Workers")
          (ui/card-description "Multiple isolated child components updating concurrently during batching passes.")]
         (into [:div {:class "flex items-center gap-1"}]
               (for [n [2 4 8]]
                 (ui/button
                  {:variant (if (= worker-count n) :default :outline)
                   :size    :sm
                   :class   (when (= worker-count n) "bg-slate-800")
                   :on      {:click [::set-worker-count n]}}
                  (str n " Workers"))))])

       (ui/card-content
        (into [:div {:class "grid grid-cols-1 sm:grid-cols-2 gap-3"}]
              (for [idx (range worker-count)]
                (BatchWorker {:id (str "worker-" idx) :initial-val 0}))))])

     ;; Architecture Explanation Card
     (ui/card
      {:class "bg-slate-900 text-slate-100 border-slate-800"}
      [:div
       (ui/card-header
        [:div {:class "flex items-center gap-2 text-indigo-400"}
         [:span {:class "text-base"} "💡"]
         (ui/card-title {:class "text-slate-100 text-base"} "Why Single-Flag RAF Batching Matters")])
       (ui/card-content
        [:div {:class "grid grid-cols-1 sm:grid-cols-3 gap-4 text-xs font-mono"}
         [:div {:class "bg-slate-800/80 p-3 rounded-lg border border-slate-700/60"}
          [:div {:class "text-indigo-300 font-bold mb-1"} "1. Burst Coalescing"]
          [:p {:class "text-slate-400 leading-normal"} "When 1,000 updates fire synchronously, compare-and-set! schedules exactly 1 frame callback."]]
         [:div {:class "bg-slate-800/80 p-3 rounded-lg border border-slate-700/60"}
          [:div {:class "text-emerald-300 font-bold mb-1"} "2. No Re-entrant Thrash"]
          [:p {:class "text-slate-400 leading-normal"} "Updates triggered during mount or view evaluation queue for the next tick rather than locking the UI thread."]]
         [:div {:class "bg-slate-800/80 p-3 rounded-lg border border-slate-700/60"}
          [:div {:class "text-amber-300 font-bold mb-1"} "3. VSync Alignment"]
          [:p {:class "text-slate-400 leading-normal"} "DOM diffing strictly aligns with browser paint cycles (60Hz / 120Hz), eliminating dropped frames."]]])])

     ;; Source Code Snippet Panel
     (ui/code-panel
      {:title    "Batching & Multi-Render Example Code"
       :filename "batching.cljs"
       :code     snippets/batching-code})]))

;; -----------------------------------------------------------------------------
;; Component Definition
;; -----------------------------------------------------------------------------

(def BatchingExample
  (relm/component
   {:component-id "batching-example"
    :init init
    :view view}))
