(ns examples.ui
  "Shared shadcn-inspired UI component primitives and layout helpers for Relm examples."
  (:require [clojure.string :as string]))

;; -----------------------------------------------------------------------------
;; Class Helpers
;; -----------------------------------------------------------------------------

(defn cx
  "Combines class names, filtering out nils, booleans, and empty strings."
  [& classes]
  (->> classes
       (flatten)
       (filter (fn [c] (and (string? c) (not (string/blank? c)))))
       (string/join " ")))

;; -----------------------------------------------------------------------------
;; Badge Component
;; -----------------------------------------------------------------------------

(defn badge
  "Renders a shadcn-styled badge.
   Variants: :default, :secondary, :outline, :destructive, :success, :warning, :indigo, :purple."
  ([text]
   (badge {} text))
  ([{:keys [variant class] :or {variant :default} :as _opts} text]
   (let [variant-classes
         (case variant
           :secondary   "bg-slate-100 text-slate-800 hover:bg-slate-200 border-transparent"
           :outline     "border-slate-300 text-slate-700 hover:bg-slate-50"
           :destructive "bg-red-100 text-red-800 border-red-200"
           :success     "bg-emerald-100 text-emerald-800 border-emerald-200"
           :warning     "bg-amber-100 text-amber-800 border-amber-200"
           :indigo      "bg-indigo-100 text-indigo-800 border-indigo-200"
           :purple      "bg-purple-100 text-purple-800 border-purple-200"
           ;; :default
           "bg-slate-900 text-white border-transparent")]
     [:span {:class (cx "inline-flex items-center gap-1 rounded-full border px-2.5 py-0.5 text-xs font-semibold transition-colors leading-normal"
                        variant-classes
                        class)}
      text])))

;; -----------------------------------------------------------------------------
;; Button Component
;; -----------------------------------------------------------------------------

(defn button
  "Renders a shadcn-styled button.
   Variants: :default, :secondary, :outline, :ghost, :destructive, :link, :success
   Sizes: :default, :sm, :lg, :icon"
  [& args]
  (let [[opts children] (if (map? (first args))
                          [(first args) (rest args)]
                          [{} args])
        {:keys [variant size class disabled? disabled type on]
         :or   {variant :default
                size    :default
                type    :button}} opts
        is-disabled? (or disabled? disabled)
        variant-classes
        (case variant
          :secondary   "bg-slate-100 text-slate-900 hover:bg-slate-200 border border-slate-200 shadow-xs"
          :outline     "border border-slate-300 bg-white hover:bg-slate-100 hover:text-slate-900 text-slate-700 shadow-xs"
          :ghost       "hover:bg-slate-100 hover:text-slate-900 text-slate-700"
          :destructive "bg-red-600 text-white hover:bg-red-700 shadow-xs"
          :link        "text-indigo-600 underline-offset-4 hover:underline p-0 h-auto"
          :success     "bg-emerald-600 text-white hover:bg-emerald-700 shadow-xs"
          ;; :default
          "bg-slate-900 text-white hover:bg-slate-800 shadow-xs")
        size-classes
        (case size
          :sm   "h-8 rounded-md px-3 text-xs"
          :lg   "h-11 rounded-md px-6 text-base"
          :icon "h-9 w-9 rounded-md p-0 flex items-center justify-center"
          ;; :default
          "h-9 px-4 py-2 text-sm")
        base-classes "inline-flex items-center justify-center gap-1.5 rounded-md font-medium transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-400 focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 select-none cursor-pointer"]
    (into [:button (merge (dissoc opts :variant :size :class :disabled? :disabled)
                          {:type     type
                           :disabled is-disabled?
                           :class    (cx base-classes variant-classes size-classes class)
                           :on       on})]
          children)))

;; -----------------------------------------------------------------------------
;; Card Components
;; -----------------------------------------------------------------------------

(defn card
  "Renders a shadcn-styled card container."
  [& args]
  (let [[opts children] (if (map? (first args))
                          [(first args) (rest args)]
                          [{} args])]
    (into [:div (merge (dissoc opts :class)
                       {:class (cx "rounded-xl border border-slate-200 bg-white text-slate-950 shadow-sm transition-all"
                                   (:class opts))})]
          children)))

(defn card-header
  "Renders card header section."
  [& args]
  (let [[opts children] (if (map? (first args))
                          [(first args) (rest args)]
                          [{} args])]
    (into [:div (merge (dissoc opts :class)
                       {:class (cx "flex flex-col space-y-1.5 p-6" (:class opts))})]
          children)))

(defn card-title
  "Renders card title text."
  ([text]
   (card-title {} text))
  ([{:keys [class] :as opts} text]
   [:h3 (merge (dissoc opts :class)
               {:class (cx "font-semibold tracking-tight text-slate-900 text-lg leading-none" class)})
    text]))

(defn card-description
  "Renders card description subtitle text."
  ([text]
   (card-description {} text))
  ([{:keys [class] :as opts} text]
   [:p (merge (dissoc opts :class)
              {:class (cx "text-sm text-slate-500 leading-relaxed" class)})
    text]))

(defn card-content
  "Renders card body content."
  [& args]
  (let [[opts children] (if (map? (first args))
                          [(first args) (rest args)]
                          [{} args])]
    (into [:div (merge (dissoc opts :class)
                       {:class (cx "p-6 pt-0" (:class opts))})]
          children)))

(defn card-footer
  "Renders card footer action bar."
  [& args]
  (let [[opts children] (if (map? (first args))
                          [(first args) (rest args)]
                          [{} args])]
    (into [:div (merge (dissoc opts :class)
                       {:class (cx "flex items-center p-6 pt-0 border-t border-slate-100 mt-4" (:class opts))})]
          children)))

;; -----------------------------------------------------------------------------
;; Form Field Components (Input, Label, Error)
;; -----------------------------------------------------------------------------

(defn label
  "Renders a standard form label."
  ([text]
   (label {} text))
  ([{:keys [class for required?] :as opts} text]
   [:label (merge (dissoc opts :class :required?)
                  {:for   for
                   :class (cx "text-sm font-medium text-slate-700 leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70 flex items-center gap-1 mb-1.5"
                              class)})
    text
    (when required?
      [:span {:class "text-red-500 text-xs"} "*"])]))

(defn input
  "Renders a shadcn-styled input field."
  [opts]
  (let [{:keys [class error?]} opts
        base-classes "flex h-9 w-full rounded-md border bg-white px-3 py-1 text-sm shadow-xs transition-colors file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-slate-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-1 disabled:cursor-not-allowed disabled:opacity-50"
        state-classes (if error?
                        "border-red-500 text-red-900 focus-visible:ring-red-400"
                        "border-slate-300 text-slate-900 focus-visible:ring-slate-400")]
    [:input (merge (dissoc opts :class :error?)
                   {:class (cx base-classes state-classes class)})]))

;; -----------------------------------------------------------------------------
;; Alert Component
;; -----------------------------------------------------------------------------

(defn alert
  "Renders a shadcn-styled alert banner.
   Variants: :default, :destructive, :success, :warning, :info"
  [& args]
  (let [[opts children] (if (map? (first args))
                          [(first args) (rest args)]
                          [{} args])
        variant (get opts :variant :default)
        variant-classes
        (case variant
          :destructive "border-red-200 bg-red-50/80 text-red-900"
          :success     "border-emerald-200 bg-emerald-50/80 text-emerald-900"
          :warning     "border-amber-200 bg-amber-50/80 text-amber-900"
          :info        "border-sky-200 bg-sky-50/80 text-sky-900"
          ;; :default
          "border-slate-200 bg-slate-50 text-slate-900")]
    (into [:div (merge (dissoc opts :variant :class)
                       {:class (cx "relative w-full rounded-lg border p-4 shadow-xs"
                                   variant-classes
                                   (:class opts))
                        :role  "alert"})]
          children)))

;; -----------------------------------------------------------------------------
;; Code & State Inspector Container
;; -----------------------------------------------------------------------------

(defn code-inspector
  "Renders a dark-themed, sleek live state inspector box."
  [{:keys [title subtitle badge-text class]} content]
  [:div {:class (cx "rounded-xl border border-slate-800 bg-slate-950 text-slate-100 shadow-xl overflow-hidden font-mono" class)}
   [:div {:class "flex items-center justify-between border-b border-slate-800 bg-slate-900/90 px-4 py-3"}
    [:div {:class "flex items-center gap-2"}
     [:div {:class "flex gap-1.5"}
      [:span {:class "h-3 w-3 rounded-full bg-red-500/80 inline-block"}]
      [:span {:class "h-3 w-3 rounded-full bg-amber-500/80 inline-block"}]
      [:span {:class "h-3 w-3 rounded-full bg-emerald-500/80 inline-block"}]]
     [:span {:class "text-xs font-semibold tracking-wide text-slate-300 ml-2"} (or title "State Inspector")]
     (when subtitle
       [:span {:class "text-[11px] text-slate-500"} (str "• " subtitle)])]
    (when badge-text
      [:span {:class "rounded bg-slate-800 px-2 py-0.5 text-[10px] font-medium text-slate-400 border border-slate-700"}
       badge-text])]
   [:div {:class "p-4 text-xs overflow-x-auto"}
    content]])

;; -----------------------------------------------------------------------------
;; Expandable Source Code Panel
;; -----------------------------------------------------------------------------

(defn code-panel
  "Renders a collapsible/expandable source code panel with shadcn styling.
   Opts:
   - :title        Title text (default: 'Source Code')
   - :filename     Source filename badge (e.g. 'counter.cljs')
   - :code         ClojureScript code snippet string
   - :default-open? Whether the panel is expanded by default (default: false)"
  [{:keys [title filename code default-open? class]
    :or   {title        "Source Code"
           default-open? false}}]
  [:details {:class (cx "group rounded-xl border border-slate-200 bg-white shadow-xs overflow-hidden transition-all duration-200 my-6" class)
             :open  (when default-open? true)}
   [:summary {:class "flex items-center justify-between p-4 cursor-pointer select-none bg-slate-50/80 hover:bg-slate-100/90 transition-colors list-none [&::-webkit-details-marker]:hidden"}
    [:div {:class "flex items-center gap-2.5"}
     [:div {:class "flex h-7 w-7 items-center justify-center rounded-md bg-indigo-50 border border-indigo-200 text-indigo-600 font-mono text-xs font-bold"}
      "</>"]
     [:div {:class "flex items-center gap-2"}
      [:span {:class "text-sm font-semibold text-slate-900"} title]
      (when filename
        [:span {:class "rounded bg-slate-200/80 px-2 py-0.5 text-[11px] font-mono font-medium text-slate-700"}
         filename])]]
    [:div {:class "flex items-center gap-2 text-xs font-medium text-slate-500"}
     [:span {:class "group-open:hidden"} "Click to expand code"]
     [:span {:class "hidden group-open:inline"} "Click to collapse"]
     [:span {:class "text-slate-400 group-open:rotate-180 transition-transform duration-200 text-xs font-mono"}
      "▼"]]]
   [:div {:class "border-t border-slate-800 bg-slate-950 p-4 font-mono text-xs text-slate-100 overflow-x-auto"}
    [:div {:class "flex items-center justify-between pb-3 mb-3 border-b border-slate-800 text-[11px] text-slate-400"}
     [:span {:class "flex items-center gap-2"}
      [:span {:class "h-2 w-2 rounded-full bg-emerald-400 inline-block"}]
      (or filename "source.cljs")]
     [:span {:class "text-slate-500 hidden sm:inline"} "ClojureScript • Replicant • Relm"]]
    [:pre {:class "leading-relaxed text-slate-200 selection:bg-indigo-700 selection:text-white font-mono"}
     [:code code]]]])

;; -----------------------------------------------------------------------------
;; Standard Example Header Banner
;; -----------------------------------------------------------------------------

(defn example-header
  "Standardized header banner for each example displaying title, description, step difficulty, and concept tags."
  [{:keys [step title difficulty description tags]}]
  [:div {:class "mb-8 pb-6 border-b border-slate-200"}
   [:div {:class "flex flex-wrap items-center gap-3 mb-2"}
    (when step
      [:span {:class "inline-flex items-center justify-center h-6 w-6 rounded-full bg-indigo-600 text-white text-xs font-bold"}
       step])
    [:h2 {:class "text-2xl font-bold tracking-tight text-slate-900"} title]
    (when difficulty
      (let [variant (case difficulty
                      "Beginner"     :success
                      "Intermediate" :indigo
                      "Advanced"     :purple
                      :secondary)]
        (badge {:variant variant} difficulty)))]
   [:p {:class "text-base text-slate-600 max-w-3xl mb-4 leading-relaxed"} description]
   (when (seq tags)
     [:div {:class "flex flex-wrap items-center gap-2"}
      [:span {:class "text-xs font-medium text-slate-400 mr-1"} "Concepts:"]
      (for [tag tags]
        ^{:key tag}
        (badge {:variant :secondary :class "text-xs font-mono"} tag))])])
