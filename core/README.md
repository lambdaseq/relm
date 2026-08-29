# relm.core

`com.lambdaseq/relm.core` is the foundational module of Relm, providing an Elm-architecture (Model-View-Update + Effects) runtime on top of [Replicant](https://github.com/cjohansen/replicant) for Clojure and ClojureScript.

## Table of Contents

- [Installation](#installation)
- [Module Overview](#module-overview)
- [Core Architecture (`com.lambdaseq.relm.core`)](#core-architecture-comlambdaseqrelmcore)
  - [Component Lifecycle](#component-lifecycle)
  - [State & Context Model](#state--context-model)
  - [Message Handlers (`relm/update`)](#message-handlers-relmupdate)
  - [Side-Effect Handlers (`relm/fx`)](#side-effect-handlers-relmfx)
  - [Nested Components](#nested-components)
- [Form State Management (`com.lambdaseq.relm.form`)](#form-state-management-comlambdaseqrelmform)
  - [Form Initialization (`form/create`)](#form-initialization-formcreate)
  - [Field Registration (`form/register`)](#field-registration-formregister)
  - [Built-in & Custom Validators](#built-in--custom-validators)
  - [View Query Helpers](#view-query-helpers)
  - [Event Helpers & Submission](#event-helpers--submission)
  - [Form Update Messages & Effects](#form-update-messages--effects)
  - [Complete Form Example](#complete-form-example)
- [HTTP Client (`com.lambdaseq.relm.http`)](#http-client-comlambdaseqrelmhttp)
  - [Fetch Effect (`::fetch`)](#fetch-effect-fetch)
  - [Abort Effect (`::abort`)](#abort-effect-abort)
- [Browser Navigation (`com.lambdaseq.relm.navigation`)](#browser-navigation-comlambdaseqrelmnavigation)

---

## Installation

Add the dependency to your `deps.edn`:

```clojure
{:deps {com.lambdaseq/relm.core {:git/url "https://github.com/lambdaseq/relm"
                                 :sha     "..."
                                 :deps/root "core"}}}
```

---

## Module Overview

The `core` module includes four functional namespaces:

| Namespace | Role |
| :--- | :--- |
| `com.lambdaseq.relm.core` | Component runtime, lifecycle management, Replicant dispatcher, `relm/update`, and `relm/fx`. |
| `com.lambdaseq.relm.form` | Declarative form state management, validation pipelines, dirty/touch tracking, and Hiccup bindings. |
| `com.lambdaseq.relm.http` | Fetch API integration, asynchronous HTTP requests, cancellation, and response decoders. |
| `com.lambdaseq.relm.navigation` | Browser History API side effects (`pushState`, `replaceState`, `back`, `forward`, `reload`). |

---

## Core Architecture (`com.lambdaseq.relm.core`)

Relm structures applications using the Elm Architecture:

```
DOM Event -> relm/dispatch -> relm/update -> [new-state new-context effects]
                                                     |             |
                                            Replicant Render    relm/fx
```

### Component Lifecycle

Components are defined with `relm/component` by passing an `:init` function and a `:view` function:

```clojure
(ns my-app.counter
  (:require [com.lambdaseq.relm.core :as relm]
            [replicant.dom :as r]))

(defn init
  "Initializes local component state. Receives global context and mount arguments."
  [_context {:keys [initial-count] :or {initial-count 0}}]
  {:count initial-count})

(defn view
  "Pure function of (state, context) returning Replicant Hiccup."
  [{:keys [count]} _context]
  [:div
   [:h2 "Counter: " count]
   [:button {:on {:click [::increment]}} "+1"]
   [:button {:on {:click [::decrement]}} "-1"]])

(def Counter
  (relm/component
    {:init init
     :view view}))

;; Mount into the DOM
(r/set-dispatch! relm/dispatch)
(relm/render js/document.body Counter {:initial-count 10})
```

### State & Context Model

- **Component Local State (`state`)**: Private to each component instance. Ideal for UI toggles, input values, and component-specific counters.
- **Global Shared Context (`context`)**: Shared across all active components. Ideal for current user data, theme settings, and active router state.

### Message Handlers (`relm/update`)

The `relm/update` multimethod handles actions dispatched by the view. Every handler returns a 3-element vector: `[new-state new-context effects]`. If `effects` is omitted, it defaults to empty.

```clojure
(defmethod relm/update ::increment
  [state context _message _event]
  [(update state :count inc) context])

(defmethod relm/update ::decrement
  [state context _message _event]
  [(update state :count dec) context])
```

### Side-Effect Handlers (`relm/fx`)

Side effects are declared as vectors `[[::effect-type arg1 arg2 ...]]` and executed by the `relm/fx` multimethod:

```clojure
;; 1. Update handler emits an effect
(defmethod relm/update ::notify-user
  [state context [_ text] _event]
  [state context [[::log-message text]]])

;; 2. Effect handler executes the side effect
(defmethod relm/fx ::log-message
  [_event [_ text]]
  (js/console.log "Notification:" text))
```

### Nested Components

Components can be nested arbitrarily. Each instance maintains isolated local state identified by an `:id` or `:key`:

```clojure
(defn dashboard-view
  [{:keys [widget-ids]} _context]
  [:div
   [:h1 "Dashboard"]
   (for [id widget-ids]
     ^{:key id}
     (Counter {:id (str "counter-" id) :initial-count 0}))])
```

---

## Form State Management (`com.lambdaseq.relm.form`)

`com.lambdaseq.relm.form` provides declarative form state management inspired by Formik and React Hook Form, built for Relm's MVU architecture.

### Key Capabilities

- **Field-Colocated Configuration**: Define initial values, built-in validation rules, and custom validators directly in `form/register`.
- **Automatic HTML Attributes**: Emits semantic attributes (`:required`, `:min`, `:max`, `:minlength`, `:maxlength`, `:pattern`, `:type`) for mobile keyboards and browser accessibility.
- **Granular View Queries**: Pure functions (`form/value`, `form/error`, `form/touched?`, `form/dirty?`, `form/valid?`) give complete control over Hiccup styling.
- **Nested Field Support**: Supports nested paths such as `[:profile :address :city]`.
- **Async Validation & Effects**: Built-in effects for field focusing and async validation.

### Form Initialization (`form/create`)

Initialize form state in your component's `init` function:

```clojure
(defn init [_context _args]
  {:form (form/create {:validate-on #{:change :blur :submit}})})
```

#### Options for `form/create`

- `:key` (optional): Form key in component state (default `:form`).
- `:validate-on` (optional): Set of triggers when validation runs (`#{:change :blur :submit}`, default `#{:change :blur :submit}`).
- `:initial-values` (optional): Map of initial values (optional when using `form/register`).
- `:validators` (optional): Map of field paths to validator vectors.
- `:validate` (optional): Custom whole-form validation function `(fn [values] errors-map)`.

### Field Registration (`form/register`)

`(form/register form path opts)` generates Hiccup input attributes including event bindings (`:input`, `:change`, `:blur`), values, and semantic HTML constraints:

```clojure
[:input (form/register form :username {:type        "text"
                                      :required    "Username is required"
                                      :min-length  [3 "Must be at least 3 characters"]
                                      :default     ""})]
```

#### Supported Registration Options

| Option | Emitted HTML Attribute | Validation Rule Applied |
| :--- | :--- | :--- |
| `:type` | `:type "text\|email\|password\|number\|checkbox"` | Drives value extraction and checkbox `:checked` binding. |
| `:default` / `:initial-value` | Initial value fallback | Sets initial baseline for dirty tracking and reset. |
| `:required` | `:required true` (when boolean/truthy) | Rejects empty strings, `nil`, or empty collections. |
| `:email` | `:type "email"` (if type omitted) | Validates email format. |
| `:min` | `:min val` | Enforces numeric minimum: `[min-val custom-msg]`. |
| `:max` | `:max val` | Enforces numeric maximum: `[max-val custom-msg]`. |
| `:min-length` | `:minlength val` | Enforces minimum string length: `[min-len custom-msg]`. |
| `:max-length` | `:maxlength val` | Enforces maximum string length: `[max-len custom-msg]`. |
| `:pattern` | `:pattern regex-str` | Matches regular expression pattern. |
| `:validate` | None | Custom validator function: `(fn [val values])` or `(fn [val])`. |
| `:validators` | None | Vector of validator functions. |

### Built-in & Custom Validators

Relm provides composable validator constructors:

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

#### Cross-Field Custom Validation

Custom functions passed to `:validate` accept `(val values)` to validate dependent fields (e.g. password confirmation):

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

### View Query Helpers

All query helpers accept the form state map and a field path (keyword or vector):

```clojure
(form/value form :email "")             ;; Get field value with optional fallback
(form/values form)                      ;; Get complete values map
(form/error form :email)                ;; Get error message (regardless of touch)
(form/error form :email true)           ;; Get error message only if field is touched
(form/errors form)                      ;; Get map of active errors
(form/touched? form :email)             ;; Check if field was touched (blurred)
(form/dirty? form)                      ;; Check if any field differs from initial
(form/dirty? form :email)               ;; Check if specific field is dirty
(form/valid? form)                      ;; Check if form has no errors
(form/invalid? form)                    ;; Check if form has errors
(form/submitting? form)                 ;; Check if submission is in flight
(form/submit-count form)                ;; Number of submission attempts
```

### Event Helpers & Submission

- `(form/on-submit form {:on-submit [::success-msg] :on-invalid [::error-msg]})`: Submits form, touches all fields, and triggers `on-submit` with values if valid.
- `(form/on-reset form)`: Resets form values to initial state and clears touched/error states.
- `(form/on-change form path)`: Event vector for value change.
- `(form/on-blur form path)`: Event vector for blur.

### Form Update Messages & Effects

#### Built-in Update Messages

- `[::form/change form-key path event-or-value]`
- `[::form/blur form-key path]`
- `[::form/set-field form-key path value]`
- `[::form/set-values form-key values-map]`
- `[::form/set-error form-key path error-msg]`
- `[::form/reset form-key]`
- `[::form/submit form-key opts]`

#### Built-in Side Effects

- `[::form/focus-field selector-or-name]`
- `[::form/focus-first-error form-state]`
- `[::form/validate-async {:path path :validator-fn fn :on-result msg}]`

### Complete Form Example

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

(defn- text-input [form path label opts]
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
     [:div {:class "alert-success"} "Account created successfully!"])

   [:form {:on {:submit (form/on-submit form {:on-submit [::handle-registration]})}}
    (text-input form :username "Username"
      {:type "text" :required "Username is required" :min-length [3 "Min 3 chars"]})

    (text-input form :email "Email"
      {:type "email" :required "Email is required" :email "Invalid email"})

    (text-input form :password "Password"
      {:type "password" :required "Password is required" :min-length [6 "Min 6 chars"]})

    (text-input form :confirm-password "Confirm Password"
      {:type     "password"
       :required "Confirm password"
       :validate (fn [val values]
                   (when (and (seq (:password values)) (not= (:password values) val))
                     "Passwords do not match"))})

    [:div {:class "actions"}
     [:button {:type "button" :disabled (not (form/dirty? form)) :on {:click (form/on-reset form)}}
      "Reset"]
     [:button {:type "submit" :disabled (form/submitting? form)}
      (if (form/submitting? form) "Saving..." "Register")]]]])

(def RegistrationForm
  (relm/component {:init init :view view}))
```

---

## HTTP Client (`com.lambdaseq.relm.http`)

The `http` module provides declarative, asynchronous Fetch API requests with automatic JSON decoding and request cancellation.

### Fetch Effect (`::fetch`)

Dispatch `::relm.http/fetch` in your update handler's effects vector:

```clojure
(ns my-app.posts
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.http :as relm.http]))

(defmethod relm/update ::load-posts
  [state context _ _]
  [(assoc state :loading? true)
   context
   [[::relm.http/fetch
     {:url         "https://api.example.com/posts"
      :method      :get
      :mode        :cors
      :headers     {"Accept" "application/json"}
      :on-success  [::posts-loaded]
      :on-failure  [::posts-failed]
      :request-id  :posts-request}]]])

(defmethod relm/update ::posts-loaded
  [state context [_ {:keys [status body headers]}] _]
  [(assoc state :loading? false :posts body) context])

(defmethod relm/update ::posts-failed
  [state context [_ {:keys [problem problem-message status]}] _]
  [(assoc state :loading? false :error problem-message) context])
```

#### Fetch Configuration Options

- `:url`: Target URL string.
- `:method`: HTTP verb (`:get`, `:post`, `:put`, `:patch`, `:delete`, `:head`).
- `:headers`: Map of header key-value pairs.
- `:body`: Request payload (maps are JSON-encoded automatically).
- `:mode`: CORS mode (`:cors`, `:no-cors`, `:same-origin`).
- `:credentials`: Credentials policy (`:include`, `:same-origin`, `:omit`).
- `:request-id`: Identifier keyword used for cancellation.
- `:on-success`: Message vector dispatched on success `[msg-name response-map]`.
- `:on-failure`: Message vector dispatched on error `[msg-name error-map]`.

### Abort Effect (`::abort`)

Cancel an active in-flight request using its `:request-id`:

```clojure
(defmethod relm/update ::cancel-posts
  [state context _ _]
  [(assoc state :loading? false)
   context
   [[::relm.http/abort {:request-id :posts-request}]]])
```

---

## Browser Navigation (`com.lambdaseq.relm.navigation`)

`com.lambdaseq.relm.navigation` provides effect handlers for browser history manipulation:

```clojure
(ns my-app.nav
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.navigation :as nav]))

;; Push a new history entry
(defmethod relm/update ::go-to-profile
  [state context [_ user-id] _]
  [state context [[::nav/push-state nil (str "/users/" user-id)]]])

;; Replace current history entry
(defmethod relm/update ::replace-url
  [state context [_ path] _]
  [state context [[::nav/replace-state nil path]]])

;; History back / forward / reload
(defmethod relm/update ::back [state context _ _] [state context [[::nav/back]]])
(defmethod relm/update ::forward [state context _ _] [state context [[::nav/forward]]])
(defmethod relm/update ::reload [state context _ _] [state context [[::nav/reload]]])
```
