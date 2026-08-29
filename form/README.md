# relm.form

`com.lambdaseq/relm.form` provides declarative, robust form state management for Relm applications, inspired by Formik and React Hook Form, but tailored to Relm's Model-View-Update (MVU) and effects architecture.

## Table of Contents

- [Installation](#installation)
- [Overview & Architecture](#overview--architecture)
- [Form Initialization (`form/create`)](#form-initialization-formcreate)
- [Field Registration (`form/register`)](#field-registration-formregister)
- [Built-in & Custom Validators](#built-in--custom-validators)
  - [Built-in Rules](#built-in-rules)
  - [Custom & Cross-Field Validation](#custom--cross-field-validation)
- [View Query Helpers](#view-query-helpers)
- [Event Helpers & Form Lifecycle](#event-helpers--form-lifecycle)
- [Message Handlers & Side Effects](#message-handlers--side-effects)
- [Complete Working Example](#complete-working-example)

---

## Installation

Add `com.lambdaseq/relm.form` and `com.lambdaseq/relm.core` to your `deps.edn`:

```clojure
{:deps {com.lambdaseq/relm.core {:git/url "https://github.com/lambdaseq/relm"
                                 :sha     "..."
                                 :deps/root "core"}
        com.lambdaseq/relm.form {:git/url "https://github.com/lambdaseq/relm"
                                 :sha     "..."
                                 :deps/root "form"}}}
```

---

## Overview & Architecture

`relm.form` manages form state directly within your component's isolated local state map (`state`).

```
                +------------------------------------+
                |  Input Event (:input/:change/:blur)|
                +------------------------------------+
                                  |
                                  v
                +------------------------------------+
                |    relm/update ::form/change/blur  |
                +------------------------------------+
                                  |
                                  v
                +------------------------------------+
                |      Pure Form State Reducers      |
                |   (values, touched, errors, dirty) |
                +------------------------------------+
                                  |
                                  v
                +------------------------------------+
                |     Replicant Hiccup View Render   |
                |   (form/value, form/error, etc.)   |
                +------------------------------------+
```

### Key Highlights

- **Field-Colocated Configuration**: Define initial values, built-in validation rules, and custom validators directly in `form/register`.
- **Automatic Semantic HTML Attributes**: Emits semantic attributes (`:required`, `:min`, `:max`, `:minlength`, `:maxlength`, `:pattern`, `:type`) for mobile keyboards and browser accessibility.
- **Granular View Queries**: Pure query functions (`form/value`, `form/error`, `form/touched?`, `form/dirty?`, `form/valid?`) give full control over layout, styling, and conditional rendering.
- **Nested Field Path Support**: Supports flat keywords (`:email`) as well as nested paths (`[:user :address :city]`).
- **Zero Heavy Dependencies**: Pure ClojureScript implementation.

---

## Form Initialization (`form/create`)

Initialize form state in your component's `init` function:

```clojure
(ns my-app.profile
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.form :as form]))

(defn init [_context _args]
  {:form (form/create {:validate-on #{:change :blur :submit}})})
```

### Options for `form/create`

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `:key` / `:form-key` | `keyword \| vector` | `:form` | Key identifying the form state in component local state. |
| `:validate-on` | `set` | `#{:change :blur :submit}` | Set of triggers when validation executes (`:change`, `:blur`, `:submit`). |
| `:initial-values` | `map` | `{}` | Optional base initial values map (can also be defined in `form/register`). |
| `:validators` | `map` | `{}` | Optional map of field paths to validator vectors. |
| `:validate` | `fn` | `nil` | Optional whole-form validation function `(fn [values] errors-map)`. |

---

## Field Registration (`form/register`)

`(form/register form path opts)` generates standard Hiccup input attributes including event listeners (`:input`, `:change`, `:blur`), current value, and semantic HTML attributes:

```clojure
[:input (form/register form :username
          {:type        "text"
           :required    "Username is required"
           :min-length  [3 "Must be at least 3 characters"]
           :default     ""})]
```

### Supported Registration Options

| Option | Emitted HTML Attribute | Validation Rule Applied |
| :--- | :--- | :--- |
| `:type` | `:type "text\|email\|password\|number\|checkbox"` | Drives value extraction and checkbox `:checked` binding. |
| `:default` / `:initial-value` / `:value` | Initial fallback value | Sets initial baseline for dirty tracking and reset. |
| `:required` | `:required true` (when boolean/truthy) | Rejects empty strings, `nil`, or empty collections. |
| `:email` | `:type "email"` (if type omitted) | Validates email format. |
| `:min` | `:min val` | Enforces numeric minimum: `[min-val custom-msg]`. |
| `:max` | `:max val` | Enforces numeric maximum: `[max-val custom-msg]`. |
| `:min-length` / `:minlength` | `:minlength val` | Enforces minimum string length: `[min-len custom-msg]`. |
| `:max-length` / `:maxlength` | `:maxlength val` | Enforces maximum string length: `[max-len custom-msg]`. |
| `:pattern` | `:pattern regex-str` | Matches regular expression pattern: `[regex custom-msg]`. |
| `:one-of` | None | Validates membership in collection: `[coll custom-msg]`. |
| `:validate` | None | Custom validator function: `(fn [val values])` or `(fn [val])`. |
| `:validators` | None | Vector of validator functions. |

`form/field` is available as an alias for `form/register`.

---

## Built-in & Custom Validators

### Built-in Rules

```clojure
(form/required "Field is required")
(form/email "Must be a valid email")
(form/min-num 18 "Must be at least 18")
(form/max-num 100 "Must be 100 or less")
(form/min-length 5 "Must be at least 5 characters")
(form/max-length 50 "Must be at most 50 characters")
(form/pattern #"^[A-Z0-9]+$" "Must be alphanumeric uppercase")
(form/one-of #{"admin" "user" "guest"} "Invalid role")
(form/compose (form/required) (form/email))
```

### Custom & Cross-Field Validation

Functions passed to `:validate` accept `(val values)` to validate dependent fields (e.g. password confirmation):

```clojure
(form/register form :confirm-password
  {:type     "password"
   :required "Please confirm your password"
   :validate (fn [val values]
               (when (and (seq (:password values))
                          (seq val)
                          (not= (:password values) val))
                 "Passwords do not match"))})
```

---

## View Query Helpers

All query helpers accept the form state map and a field path (keyword or vector):

```clojure
(form/value form :email "")             ;; Current field value with optional fallback
(form/values form)                      ;; Complete values map
(form/initial-values form)              ;; Initial values map
(form/error form :email)                ;; Error message (regardless of touch)
(form/error form :email true)           ;; Error message only if field was touched/blurred
(form/errors form)                      ;; Complete map of active errors
(form/touched? form :email)             ;; True if field was touched (blurred)
(form/dirty? form)                      ;; True if any field differs from initial
(form/dirty? form :email)               ;; True if specific field differs from initial
(form/pristine? form)                   ;; True if form is unmodified
(form/valid? form)                      ;; True if form has 0 errors
(form/invalid? form)                    ;; True if form has 1+ errors
(form/submitting? form)                 ;; True if submission is in flight
(form/submit-count form)                ;; Number of submission attempts
```

---

## Event Helpers & Form Lifecycle

- **Submission**: `(form/on-submit form {:on-submit [::save-data] :on-invalid [::show-error]})`
  - Prevents default DOM form reload.
  - Touches all fields and validates the complete form.
  - If valid: marks `:submitting? true`, increments `:submit-count`, and dispatches `:on-submit` with values.
  - If invalid: focuses first error and dispatches `:on-invalid` with errors map.
- **Reset**: `(form/on-reset form)`
  - Resets values back to initial state, clearing touched paths and errors.
- **Change & Blur**: `(form/on-change form path)` and `(form/on-blur form path)`

---

## Message Handlers & Side Effects

### Built-in Update Messages

- `[::form/change form-key path event-or-value]`
- `[::form/blur form-key path]`
- `[::form/set-field form-key path value]`
- `[::form/set-values form-key values-map]`
- `[::form/set-error form-key path error-msg]`
- `[::form/reset form-key]`
- `[::form/submit form-key opts]`

### Built-in Side Effects

- `[::form/focus-field selector-or-name]`
- `[::form/focus-first-error form-errors]`
- `[::form/validate-async {:path path :validator fn :on-success msg :on-error msg}]`

---

## Complete Working Example

```clojure
(ns my-app.registration
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.form :as form]))

(defn init [_context _args]
  {:form (form/create {:validate-on #{:change :blur :submit}})})

(defmethod relm/update ::handle-registration
  [state context [_ values] _event]
  (let [updated-form (form/submit-end (:form state) :success)]
    [(assoc state :form updated-form :submitted-data values)
     context
     [[::log-success values]]]))

(defn- input-field [form path label opts]
  (let [err (form/error form path true)]
    [:div {:class "field-group"}
     [:label label]
     [:input (merge (form/register form path opts)
                    {:class (when err "input-error")})]
     (when err
       [:span {:class "error-text"} err])]))

(defn view [{:keys [form submitted-data]} _context]
  [:div {:class "form-container"}
   [:h2 "Create Account"]

   (when submitted-data
     [:div {:class "alert-success"} "Account registered successfully!"])

   [:form {:on {:submit (form/on-submit form {:on-submit [::handle-registration]})}}
    (input-field form :username "Username"
      {:type "text" :required "Username is required" :min-length [3 "Min 3 chars"]})

    (input-field form :email "Email"
      {:type "email" :required "Email is required" :email "Invalid email"})

    (input-field form :password "Password"
      {:type "password" :required "Password is required" :min-length [6 "Min 6 chars"]})

    (input-field form :confirm-password "Confirm Password"
      {:type     "password"
       :required "Confirm password"
       :validate (fn [val values]
                   (when (and (seq (:password values)) (not= (:password values) val))
                     "Passwords do not match"))})

    [:div {:class "actions"}
     [:button {:type "button" :disabled (not (form/dirty? form)) :on {:click (form/on-reset form)}}
      "Reset"]
     [:button {:type "submit" :disabled (form/submitting? form)}
      (if (form/submitting? form) "Submitting..." "Register")]]]])

(def RegistrationForm
  (relm/component {:init init :view view}))
```
