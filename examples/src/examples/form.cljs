(ns examples.form
  "Interactive form state management example component using `com.lambdaseq.relm.form`.

  Demonstrates:
  - Form initialization with `form/create` in component `init`
  - Built-in validators (`required`, `email`, `min-num`, `min-length`, `compose`)
  - Custom whole-form validation for password confirmation
  - Nested field paths (`[:profile :age]`, `[:profile :bio]`, `[:preferences :newsletter]`)
  - Granular view query functions (`form/value`, `form/error`, `form/touched?`, `form/dirty?`, `form/submitting?`)
  - Real-time touch tracking on blur and instant feedback
  - Declarative submission with `on-submit` effect and reset with `::form/reset`"
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.form :as form]
            [examples.snippets :as snippets]
            [examples.ui :as ui]))

;; -----------------------------------------------------------------------------
;; Form Initialization
;; -----------------------------------------------------------------------------

(defn init
  "Initializes the component state with a declarative form state map."
  [_context _args]
  {:submitted-data nil
   :form (form/create {:validate-on #{:change :blur :submit}})})

;; -----------------------------------------------------------------------------
;; Update Handlers
;; -----------------------------------------------------------------------------

(defmethod relm/update ::handle-registration-success
  [state context [_ values] _event]
  (let [updated-form (form/submit-end (:form state) :success)]
    [(assoc state
            :form updated-form
            :submitted-data values)
     context]))

(defmethod relm/update ::dismiss-success
  [state context _ _event]
  [(assoc state :submitted-data nil) context])

;; -----------------------------------------------------------------------------
;; Reusable Input Field Component
;; -----------------------------------------------------------------------------

(defn- form-input
  "Renders a shadcn-styled input with label and automatic error messages."
  [form path {:keys [label placeholder type required? min max min-length max-length validate] :as opts}]
  (let [err (form/error form path true)
        reg-opts (dissoc opts :label :required?)
        reg-attrs (form/register form path (cond-> reg-opts
                                             required? (assoc :required (if (string? required?) required? "This field is required"))))]
    [:div {:class "space-y-1.5 mb-4"}
     (when label
       (ui/label {:required? (boolean required?)} label))
     (ui/input
      (merge reg-attrs
             {:type        (or type "text")
              :placeholder placeholder
              :error?      (boolean err)}))
     (when err
       [:p {:class "text-xs font-medium text-red-600 flex items-center gap-1 mt-1"}
        [:span "⚠"] err])]))

;; -----------------------------------------------------------------------------
;; Live State Inspector
;; -----------------------------------------------------------------------------

(defn- render-inspector
  [form]
  (let [values (form/values form)
        touched (form/touched form)
        errors (form/errors form)
        is-dirty? (form/dirty? form)
        is-valid? (form/valid? form)
        is-submitting? (form/submitting? form)
        submit-count (form/submit-count form)
        clean-form (dissoc form :validators :validate-fn)]
    (ui/code-inspector
     {:title      "Form State Inspector"
      :subtitle   "com.lambdaseq.relm.form"
      :badge-text "LIVE TRACKING"}
     [:div {:class "space-y-4 font-mono text-xs"}
       ;; Status KPI Grid
      [:div {:class "grid grid-cols-2 sm:grid-cols-4 gap-2"}
       [:div {:class "bg-slate-900 p-2.5 rounded-lg border border-slate-800"}
        [:div {:class "text-[10px] text-slate-400 uppercase tracking-wider font-semibold"} "valid?"]
        [:div {:class (ui/cx "text-sm font-bold" (if is-valid? "text-emerald-400" "text-red-400"))}
         (str is-valid?)]]
       [:div {:class "bg-slate-900 p-2.5 rounded-lg border border-slate-800"}
        [:div {:class "text-[10px] text-slate-400 uppercase tracking-wider font-semibold"} "dirty?"]
        [:div {:class (ui/cx "text-sm font-bold" (if is-dirty? "text-amber-400" "text-slate-500"))}
         (str is-dirty?)]]
       [:div {:class "bg-slate-900 p-2.5 rounded-lg border border-slate-800"}
        [:div {:class "text-[10px] text-slate-400 uppercase tracking-wider font-semibold"} "submitting?"]
        [:div {:class (ui/cx "text-sm font-bold" (if is-submitting? "text-sky-400" "text-slate-500"))}
         (str is-submitting?)]]
       [:div {:class "bg-slate-900 p-2.5 rounded-lg border border-slate-800"}
        [:div {:class "text-[10px] text-slate-400 uppercase tracking-wider font-semibold"} "submit-count"]
        [:div {:class "text-sm font-bold text-slate-200"}
         (str submit-count)]]]

       ;; Values Dump
      [:div
       [:div {:class "text-[11px] font-semibold text-sky-400 mb-1"} ":values"]
       [:pre {:class "bg-slate-900/90 p-2.5 rounded-md border border-slate-800/80 text-slate-200 overflow-x-auto text-[11px] leading-relaxed"}
        (pr-str values)]]

       ;; Touched Paths
      [:div
       [:div {:class "text-[11px] font-semibold text-purple-400 mb-1"} ":touched"]
       [:pre {:class "bg-slate-900/90 p-2.5 rounded-md border border-slate-800/80 text-slate-200 overflow-x-auto text-[11px] leading-relaxed"}
        (if (seq touched) (pr-str touched) "#{} (none)")]]

       ;; Errors Map
      [:div
       [:div {:class "text-[11px] font-semibold text-rose-400 mb-1"} ":errors"]
       [:pre {:class (ui/cx "p-2.5 rounded-md border text-[11px] overflow-x-auto leading-relaxed"
                            (if (seq errors)
                              "bg-rose-950/40 border-rose-900/60 text-rose-300"
                              "bg-slate-900/90 border-slate-800/80 text-slate-500"))}
        (if (seq errors) (pr-str errors) "{} (no errors)")]]

       ;; Clean Form State Map
      [:div
       [:div {:class "text-[11px] font-semibold text-slate-400 mb-1"} "Complete State Map"]
       [:pre {:class "bg-slate-950 p-2.5 rounded-md border border-slate-900 text-slate-400 text-[10px] max-h-48 overflow-y-auto leading-relaxed"}
        (pr-str clean-form)]]])))

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defn view
  [{:keys [form submitted-data]} _context]
  (let [is-dirty? (form/dirty? form)
        is-submitting? (form/submitting? form)]
    [:div {:class "max-w-5xl mx-auto"}
     (ui/example-header
      {:step        "5"
       :title       "Declarative Forms & Validation"
       :difficulty  "Advanced"
       :description "Declarative form state, built-in composable validators, dirty and touch tracking, cross-field rules, and real-time inspector using `com.lambdaseq.relm.form`."
       :tags        ["relm.form" "Validation" "Dirty Tracking" "Touch Tracking" "Live Inspector"]})

     [:div {:class "grid grid-cols-1 lg:grid-cols-12 gap-8 items-start"}
      ;; Left Column: Registration Form
      [:div {:class "lg:col-span-6"}
       (ui/card
        {:class "border-slate-200 shadow-sm"}
        (ui/card-header
         (ui/card-title "Account Registration")
         (ui/card-description "Fill in the fields below. Validation triggers dynamically on change and blur."))

        (ui/card-content
           ;; Success Alert Banner
         (when submitted-data
           [:div {:class "mb-6"}
            (ui/alert
             {:variant :success}
             [:div {:class "flex items-start justify-between gap-2"}
              [:div
               [:h4 {:class "font-bold text-sm mb-1"} "Account Created Successfully!"]
               [:p {:class "text-xs mb-2"} "The form passed all validations and submitted pure values:"]
               [:pre {:class "bg-emerald-950/20 p-2 rounded text-[11px] font-mono overflow-x-auto"}
                (pr-str submitted-data)]]
              (ui/button
               {:variant :ghost
                :size    :sm
                :class   "h-6 w-6 p-0 text-emerald-800 hover:bg-emerald-200"
                :on      {:click [::dismiss-success]}}
               "✕")])])

           ;; Form Definition
         [:form (form/form-attrs form {:on {:submit [::form/submit form {:on-submit [::handle-registration-success]}]}})
            ;; Username
          (form-input form :username {:label       "Username"
                                      :placeholder "alice_smith"
                                      :required?   "Username is required"
                                      :min-length  [3 "Username must be at least 3 characters"]})

            ;; Email Address
          (form-input form :email {:label       "Email Address"
                                   :type        "email"
                                   :placeholder "alice@example.com"
                                   :required?   "Email is required"
                                   :email       "Please enter a valid email address"})

            ;; Password and Confirm Password Row
          [:div {:class "grid grid-cols-1 sm:grid-cols-2 gap-3"}
           (form-input form :password {:label       "Password"
                                       :type        "password"
                                       :placeholder "••••••••"
                                       :required?   "Password is required"
                                       :min-length  [6 "Minimum 6 characters"]})

           (form-input form :confirm-password {:label       "Confirm Password"
                                               :type        "password"
                                               :placeholder "••••••••"
                                               :required?   "Please confirm your password"
                                               :validate    (fn [val values]
                                                              (when (and (seq (:password values))
                                                                         (seq val)
                                                                         (not= (:password values) val))
                                                                "Passwords do not match"))})]

            ;; Profile Age & Bio Row
          [:div {:class "grid grid-cols-1 sm:grid-cols-3 gap-3"}
           [:div {:class "sm:col-span-1"}
            (form-input form [:profile :age] {:label       "Age"
                                              :type        "number"
                                              :placeholder "25"
                                              :min         [18 "Must be 18+"]
                                              :max         [120 "Invalid age"]})]
           [:div {:class "sm:col-span-2"}
            (form-input form [:profile :bio] {:label       "Short Bio"
                                              :placeholder "Software developer & Clojurist"})]]

            ;; Newsletter Checkbox
          [:div {:class "flex items-center gap-2.5 my-4 pt-1"}
           [:input (merge (form/register form [:preferences :newsletter] {:type "checkbox" :default true})
                          {:id    "newsletter-cb"
                           :class "h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500 cursor-pointer"})]
           [:label {:for "newsletter-cb" :class "text-sm text-slate-700 select-none cursor-pointer"}
            "Subscribe to the monthly product changelog"]]

            ;; Actions Footer
          [:div {:class "flex items-center justify-between pt-4 border-t border-slate-100 mt-6"}
           (ui/button
            {:variant   :outline
             :disabled? (not is-dirty?)
             :on        {:click (form/on-reset form)}}
            "Reset Form")
           (ui/button
            {:type     :submit
             :variant  :default
             :class    "bg-indigo-600 hover:bg-indigo-700 text-white"
             :disabled is-submitting?}
            (if is-submitting? "Submitting..." "Register Account"))]]))]

      ;; Right Column: Live State Inspector
      [:div {:class "lg:col-span-6 lg:sticky lg:top-6"}
       (render-inspector form)]]

     ;; Expandable Source Code Panel
     (ui/code-panel
      {:title    "Form Validation Example Source Code"
       :filename "form.cljs"
       :code     snippets/form-code})]))

;; -----------------------------------------------------------------------------
;; Component Definition
;; -----------------------------------------------------------------------------

(def FormExample
  "Form Example component ready to be mounted."
  (relm/component
   {:init init
    :view view}))
