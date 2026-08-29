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
            [com.lambdaseq.relm.form :as form]))

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
;; Reusable View Components
;; -----------------------------------------------------------------------------

(defn- input-field
  "Reusable input component that pairs a label, form/register attributes, error styling, and inline validation error."
  [form path {:keys [label container-style] :as opts}]
  (let [err (form/error form path true)
        reg-opts (dissoc opts :label :container-style)]
    [:div {:style (merge {:margin-bottom "16px"} container-style)}
     (when label
       [:label {:style {:display       "block"
                        :font-size     "14px"
                        :font-weight   "500"
                        :color         "#374151"
                        :margin-bottom "4px"}}
        label])
     [:input (merge (form/register form path reg-opts)
                    {:style {:width         "100%"
                             :box-sizing    "border-box"
                             :padding       "8px 12px"
                             :font-size     "14px"
                             :border        (str "1px solid " (if err "#ef4444" "#d1d5db"))
                             :border-radius "6px"
                             :outline       "none"
                             :background    (if err "#fef2f2" "#ffffff")}})]
     (when err
       [:div {:style {:color "#dc2626" :font-size "12px" :margin-top "4px"}} err])]))

;; -----------------------------------------------------------------------------
;; Live State Panel
;; -----------------------------------------------------------------------------

(defn- render-state-panel
  [form]
  (let [values (form/values form)
        touched (form/touched form)
        errors (form/errors form)
        is-dirty? (form/dirty? form)
        is-valid? (form/valid? form)
        is-submitting? (form/submitting? form)
        submit-count (form/submit-count form)
        clean-form (dissoc form :validators :validate-fn)]
    [:div {:style {:background    "#0f172a"
                   :color         "#e2e8f0"
                   :border-radius "8px"
                   :padding       "20px"
                   :font-family   "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace"
                   :box-shadow    "0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06)"
                   :font-size     "13px"}}
     [:div {:style {:display "flex" :justify-content "space-between" :align-items "center" :margin-bottom "16px" :border-bottom "1px solid #334155" :padding-bottom "12px"}}
      [:div {:style {:font-weight "700" :font-size "15px" :color "#f8fafc"}} "⚡ Live Form State Inspector"]
      [:span {:style {:background "#1e293b" :padding "2px 8px" :border-radius "4px" :font-size "11px" :color "#94a3b8"}}
       "com.lambdaseq.relm.form"]]

     ;; Status Flags Grid
     [:div {:style {:display "grid" :grid-template-columns "1fr 1fr" :gap "8px" :margin-bottom "16px"}}
      [:div {:style {:background "#1e293b" :padding "8px 12px" :border-radius "6px"}}
       [:div {:style {:color "#94a3b8" :font-size "11px" :text-transform "uppercase"}} "valid?"]
       [:div {:style {:font-weight "600" :color (if is-valid? "#4ade80" "#f87171")}} (str is-valid?)]]
      [:div {:style {:background "#1e293b" :padding "8px 12px" :border-radius "6px"}}
       [:div {:style {:color "#94a3b8" :font-size "11px" :text-transform "uppercase"}} "dirty?"]
       [:div {:style {:font-weight "600" :color (if is-dirty? "#fbbf24" "#94a3b8")}} (str is-dirty?)]]
      [:div {:style {:background "#1e293b" :padding "8px 12px" :border-radius "6px"}}
       [:div {:style {:color "#94a3b8" :font-size "11px" :text-transform "uppercase"}} "submitting?"]
       [:div {:style {:font-weight "600" :color (if is-submitting? "#60a5fa" "#94a3b8")}} (str is-submitting?)]]
      [:div {:style {:background "#1e293b" :padding "8px 12px" :border-radius "6px"}}
       [:div {:style {:color "#94a3b8" :font-size "11px" :text-transform "uppercase"}} "submit-count"]
       [:div {:style {:font-weight "600" :color "#e2e8f0"}} (str submit-count)]]]

     ;; Field Values
     [:div {:style {:margin-bottom "16px"}}
      [:div {:style {:color "#38bdf8" :font-weight "600" :margin-bottom "6px"}} ":values"]
      [:pre {:style {:margin "0" :background "#1e293b" :padding "10px" :border-radius "6px" :overflow-x "auto" :color "#e2e8f0" :font-size "12px"}}
       (pr-str values)]]

     ;; Touched Paths
     [:div {:style {:margin-bottom "16px"}}
      [:div {:style {:color "#a78bfa" :font-weight "600" :margin-bottom "6px"}} ":touched"]
      [:pre {:style {:margin "0" :background "#1e293b" :padding "10px" :border-radius "6px" :overflow-x "auto" :color "#e2e8f0" :font-size "12px"}}
       (if (seq touched) (pr-str touched) "#{} (none)")]]

     ;; Errors Map
     [:div {:style {:margin-bottom "16px"}}
      [:div {:style {:color "#f87171" :font-weight "600" :margin-bottom "6px"}} ":errors"]
      [:pre {:style {:margin "0" :background "#1e293b" :padding "10px" :border-radius "6px" :overflow-x "auto" :color (if (seq errors) "#fca5a5" "#94a3b8") :font-size "12px"}}
       (if (seq errors) (pr-str errors) "{} (no errors)")]]

     ;; Full Form State Map
     [:div
      [:div {:style {:color "#94a3b8" :font-weight "600" :margin-bottom "6px"}} "Complete Form State Map"]
      [:pre {:style {:margin "0" :background "#020617" :padding "10px" :border-radius "6px" :overflow-x "auto" :color "#cbd5e1" :font-size "11px" :max-height "260px"}}
       (pr-str clean-form)]]]))

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defn view
  [{:keys [form submitted-data]} _context]
  (let [is-dirty? (form/dirty? form)
        is-submitting? (form/submitting? form)
        is-valid? (form/valid? form)
        submit-count (form/submit-count form)]
    [:div {:style {:max-width "1100px"
                   :margin    "0 auto"
                   :padding   "12px"}}
     [:div {:style {:margin-bottom "20px" :padding-bottom "12px" :border-bottom "1px solid #e5e7eb"}}
      [:h2 {:style {:margin "0 0 8px 0" :font-size "22px" :color "#111827"}} "Relm Form Module"]
      [:p {:style {:margin "0" :font-size "14px" :color "#6b7280"}}
       "Declarative form state, built-in and custom validators, dirty tracking, touch state, and live state inspector."]]

     [:div {:style {:display "grid"
                    :grid-template-columns "repeat(auto-fit, minmax(420px, 1fr))"
                    :gap "24px"
                    :align-items "start"}}
      ;; Left Column: Registration Form
      [:div {:style {:background "#ffffff"
                     :border-radius "8px"
                     :border "1px solid #e5e7eb"
                     :padding "24px"
                     :box-shadow "0 1px 3px rgba(0,0,0,0.05)"}}
       [:h3 {:style {:margin "0 0 16px 0" :font-size "18px" :color "#111827"}} "Account Registration"]

       (when submitted-data
         [:div {:style {:margin-bottom "20px"
                        :padding       "12px 16px"
                        :background    "#f0fdf4"
                        :border        "1px solid #86efac"
                        :border-radius "6px"
                        :color         "#166534"}}
          [:div {:style {:display "flex" :justify-content "space-between" :align-items "center"}}
           [:strong "Registration submitted successfully!"]
           [:button {:style {:background "none" :border "none" :cursor "pointer" :color "#166534" :font-weight "bold"}
                     :on    {:click [::dismiss-success]}} "✕"]]
          [:pre {:style {:margin-top "8px" :font-size "12px" :overflow-x "auto"}}
           (pr-str submitted-data)]])

       [:form {:on {:submit [
                             [::form/submit (form/extract-form-key form) {:on-submit [::handle-registration-success]}]]}}
        ;; Username Field
        (input-field form :username {:label       "Username"
                                     :type        "text"
                                     :placeholder "johndoe"
                                     :required    "Username is required"
                                     :min-length  [3 "Username must be at least 3 characters"]})

        ;; Email Field
        (input-field form :email {:label       "Email Address"
                                  :type        "email"
                                  :placeholder "john@example.com"
                                  :required    "Email is required"
                                  :email       "Please enter a valid email address"})

        ;; Password and Confirm Password Row
        [:div {:style {:display "grid" :grid-template-columns "1fr 1fr" :gap "12px"}}
         (input-field form :password {:label      "Password"
                                      :type       "password"
                                      :required   "Password is required"
                                      :min-length [6 "Password must be at least 6 characters"]})

         (input-field form :confirm-password {:label    "Confirm Password"
                                              :type     "password"
                                              :required "Please confirm your password"
                                              :validate (fn [val values]
                                                          (when (and (seq (:password values))
                                                                     (seq val)
                                                                     (not= (:password values) val))
                                                            "Passwords do not match"))})]

        ;; Profile Nested Fields: Age & Bio
        [:div {:style {:display "grid" :grid-template-columns "120px 1fr" :gap "12px"}}
         (input-field form [:profile :age] {:label       "Age"
                                            :type        "number"
                                            :placeholder "18+"
                                            :min         [18 "You must be at least 18 years old"]
                                            :max         [120 "Please enter a valid age"]})

         (input-field form [:profile :bio] {:label       "Bio"
                                            :type        "text"
                                            :placeholder "Short bio"})]

        [:div {:style {:margin-bottom "20px"}}
         [:label {:style {:display "flex" :align-items "center" :gap "8px" :font-size "14px" :color "#374151" :cursor "pointer"}}
          [:input (form/register form [:preferences :newsletter] {:type "checkbox" :default true})]
          "Subscribe to monthly newsletter"]]

        ;; Status Summary Badge Bar
        [:div {:style {:display "flex"
                       :gap "8px"
                       :margin-bottom "20px"
                       :font-size "12px"
                       :color "#4b5563"
                       :background "#f9fafb"
                       :padding "8px 12px"
                       :border-radius "6px"}}
         [:span (str "Dirty: " (if is-dirty? "Yes" "No"))]
         [:span "•"]
         [:span (str "Valid: " (if is-valid? "Yes" "No"))]
         [:span "•"]
         [:span (str "Submit attempts: " submit-count)]]

        [:div {:style {:display "flex" :gap "12px" :justify-content "flex-end"}}
         [:button {:type  "button"
                   :style {:padding          "8px 16px"
                           :border-radius    "6px"
                           :border           "1px solid #d1d5db"
                           :background-color "#ffffff"
                           :color            "#374151"
                           :font-size        "14px"
                           :font-weight      "500"
                           :cursor           (if is-dirty? "pointer" "default")
                           :opacity          (if is-dirty? "1" "0.6")}
                   :disabled (not is-dirty?)
                   :on    {:click (form/on-reset form)}}
          "Reset"]
         [:button {:type  "submit"
                   :style {:padding          "8px 20px"
                           :border-radius    "6px"
                           :border           "none"
                           :background-color "#4f46e5"
                           :color            "#ffffff"
                           :font-size        "14px"
                           :font-weight      "600"
                           :cursor           (if is-submitting? "wait" "pointer")
                           :opacity          (if is-submitting? "0.7" "1")}}
          (if is-submitting? "Submitting..." "Register Account")]]]]

      ;; Right Column: Live Form State Inspector Panel
      (render-state-panel form)]]))

;; -----------------------------------------------------------------------------
;; Component Definition
;; -----------------------------------------------------------------------------

(def FormExample
  "Form Example component ready to be mounted."
  (relm/component
    {:init init
     :view view}))
