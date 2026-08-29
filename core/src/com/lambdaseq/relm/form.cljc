(ns com.lambdaseq.relm.form
  "Declarative, robust form state management module for Relm applications.

  Provides:
  - Form state initialization (`create`) and pure state reducers (`set-value`, `set-touched`, `reset-form`, etc.)
  - Built-in composable validators (`required`, `email`, `min-num`, `max-num`, `min-length`, `max-length`, `pattern`, `one-of`, `compose`)
  - Whole-form and field-level validation engine (`validate-form`, `validate-field`)
  - Granular view query functions for Hiccup rendering (`value`, `error`, `touched?`, `dirty?`, `valid?`, `invalid?`, `submitting?`)
  - Declarative Relm `update` message handlers (`::change`, `::blur`, `::submit`, `::reset`, `::set-field`, `::set-values`)
  - Side effects for async validation and DOM input focus (`::focus-field`, `::focus-first-error`, `::validate-async`)"
  (:refer-clojure :exclude [key])
  (:require [clojure.string :as string]
            [com.lambdaseq.relm.core :as relm]))

;; -----------------------------------------------------------------------------
;; Path Normalization
;; -----------------------------------------------------------------------------

(defn normalize-path
  "Ensures a field path is represented as a vector of keys/indexes.
  Accepts a keyword (e.g. `:email`), a symbol, or a vector (e.g. `[:user :address :city]`)."
  [path]
  (cond
    (vector? path) path
    (sequential? path) (vec path)
    (nil? path) []
    :else [path]))

;; -----------------------------------------------------------------------------
;; Parsing Helpers
;; -----------------------------------------------------------------------------

(defn- parse-num
  "Attempts to parse `v` as a number. Returns a numeric value or nil."
  [v]
  (cond
    (number? v) v
    (string? v)
    (let [s (string/trim v)]
      (when (seq s)
        #?(:clj  (try (Double/parseDouble s) (catch Exception _ nil))
           :cljs (let [n (js/parseFloat s)]
                   (when-not (js/isNaN n) n)))))
    :else nil))

;; -----------------------------------------------------------------------------
;; Built-in Validators
;; -----------------------------------------------------------------------------

(defn- run-validator
  "Evaluates a validator `val-fn` with `value` and optionally `all-values`.
  Supports validator functions of 1 argument `(f value)` or 2 arguments `(f value all-values)`.
  If `val-fn` returns a function (i.e. uninvoked constructor), evaluates the inner function."
  ([val-fn value]
   (run-validator val-fn value nil))
  ([val-fn value all-values]
   (let [res (try
               (val-fn value all-values)
               (catch #?(:clj clojure.lang.ArityException :cljs :default) _
                 (val-fn value)))]
     (if (fn? res)
       (try
         (res value all-values)
         (catch #?(:clj clojure.lang.ArityException :cljs :default) _
           (res value)))
       res))))

(defn required
  "Validates that a field has a non-empty value.
  Rejects nil, blank strings, empty collections, and boolean false."
  [& [msg]]
  (fn [v]
    (let [message (or msg "This field is required")]
      (cond
        (nil? v) message
        (false? v) message
        (string? v) (if (string/blank? v) message nil)
        (and (coll? v) (empty? v)) message
        :else nil))))

(def ^:private email-regex
  #"^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$")

(defn email
  "Validates that a field is a valid email address.
  Passes for nil or empty string (use `required` to mandate presence)."
  [& [msg]]
  (fn [v]
    (let [message (or msg "Invalid email address")]
      (if (or (nil? v) (and (string? v) (string/blank? v)))
        nil
        (if (and (string? v) (re-matches email-regex (string/trim v)))
          nil
          message)))))

(defn min-num
  "Validates that a numeric field is at least `min-val`.
  Passes for nil or empty string. Parses string numbers automatically."
  [min-val & [msg]]
  (fn [v]
    (if (or (nil? v) (and (string? v) (string/blank? v)))
      nil
      (if-let [n (parse-num v)]
        (if (< n min-val)
          (or msg (str "Must be at least " min-val))
          nil)
        (or msg "Must be a valid number")))))

(defn max-num
  "Validates that a numeric field is at most `max-val`.
  Passes for nil or empty string. Parses string numbers automatically."
  [max-val & [msg]]
  (fn [v]
    (if (or (nil? v) (and (string? v) (string/blank? v)))
      nil
      (if-let [n (parse-num v)]
        (if (> n max-val)
          (or msg (str "Must be at most " max-val))
          nil)
        (or msg "Must be a valid number")))))

(defn min-length
  "Validates that a string or collection has at least `min-len` items/characters.
  Passes for nil or empty values."
  [min-len & [msg]]
  (fn [v]
    (if (or (nil? v) (and (string? v) (string/blank? v)))
      nil
      (if (< (count v) min-len)
        (or msg (str "Must be at least " min-len " characters"))
        nil))))

(defn max-length
  "Validates that a string or collection has at most `max-len` items/characters.
  Passes for nil or empty values."
  [max-len & [msg]]
  (fn [v]
    (if (or (nil? v) (and (string? v) (string/blank? v)))
      nil
      (if (> (count v) max-len)
        (or msg (str "Must be at most " max-len " characters"))
        nil))))

(defn pattern
  "Validates that a string matches the provided regular expression `regex` (or regex string).
  Passes for nil or empty string."
  [regex & [msg]]
  (let [p (if (string? regex) (re-pattern regex) regex)]
    (fn [v]
      (if (or (nil? v) (and (string? v) (string/blank? v)))
        nil
        (if (re-find p (str v))
          nil
          (or msg "Invalid format"))))))

(defn one-of
  "Validates that a value is contained in `allowed-coll`.
  Passes for nil or empty string."
  [allowed-coll & [msg]]
  (let [allowed-set (set allowed-coll)]
    (fn [v]
      (if (or (nil? v) (and (string? v) (string/blank? v)))
        nil
        (if (contains? allowed-set v)
          nil
          (or msg "Value is not allowed"))))))

(defn compose
  "Combines multiple validator functions in sequence.
  Returns the error message of the first failing validator, or nil if all pass."
  [& validators]
  (fn [v]
    (some (fn [val-fn] (run-validator val-fn v)) validators)))

;; -----------------------------------------------------------------------------
;; Form Construction & Normalization
;; -----------------------------------------------------------------------------

(defonce ^:private !registered-field-configs
  (atom {}))

(defn- deep-merge
  "Recursively merges maps. If both values are maps, merges them; otherwise returns the second."
  [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))

(defn- extract-rule-val-and-msg
  [opt]
  (cond
    (vector? opt) [(first opt) (second opt)]
    (map? opt) [(or (:value opt) (:val opt)) (:message opt)]
    :else [opt nil]))

(defn- extract-initial-val-from-opts
  [opts is-checkbox?]
  (cond
    (contains? opts :initial-value) (:initial-value opts)
    (contains? opts :initial)       (:initial opts)
    (contains? opts :default-value) (:default-value opts)
    (contains? opts :defaultValue)  (:defaultValue opts)
    (contains? opts :default)       (:default opts)
    (contains? opts :value)         (:value opts)
    (and is-checkbox? (contains? opts :checked)) (:checked opts)
    is-checkbox?                   false
    :else                          ""))

(defn- extract-validators-from-opts
  [opts]
  (let [validators (transient [])
        add! (fn [v] (when v (conj! validators v)))]
    ;; required
    (when-let [req (get opts :required)]
      (cond
        (string? req) (add! (required req))
        (map? req) (add! (required (:message req)))
        (vector? req) (add! (required (second req)))
        (true? req) (add! (required))))
    ;; email
    (let [em (get opts :email)]
      (cond
        (string? em) (add! (email em))
        (map? em) (add! (email (:message em)))
        (vector? em) (add! (email (second em)))
        (true? em) (add! (email))
        (and (= (:type opts) "email") (not (false? em))) (add! (email))))
    ;; min-length / minlength
    (when-let [ml (or (get opts :min-length) (get opts :minlength))]
      (let [[val msg] (extract-rule-val-and-msg ml)]
        (when (number? val) (add! (min-length val msg)))))
    ;; max-length / maxlength
    (when-let [ml (or (get opts :max-length) (get opts :maxlength))]
      (let [[val msg] (extract-rule-val-and-msg ml)]
        (when (number? val) (add! (max-length val msg)))))
    ;; min
    (when-let [m (get opts :min)]
      (let [[val msg] (extract-rule-val-and-msg m)]
        (when (number? val) (add! (min-num val msg)))))
    ;; max
    (when-let [m (get opts :max)]
      (let [[val msg] (extract-rule-val-and-msg m)]
        (when (number? val) (add! (max-num val msg)))))
    ;; pattern
    (when-let [p (get opts :pattern)]
      (let [[val msg] (extract-rule-val-and-msg p)]
        (when val (add! (pattern val msg)))))
    ;; one-of
    (when-let [oo (get opts :one-of)]
      (let [[val msg] (extract-rule-val-and-msg oo)]
        (when val (add! (one-of val msg)))))
    ;; custom validate
    (when-let [v (or (get opts :validate) (get opts :validate-fn))]
      (cond
        (fn? v) (add! v)
        (map? v) (doseq [vf (vals v)] (when (fn? vf) (add! vf)))
        (sequential? v) (doseq [vf v] (when (fn? vf) (add! vf)))))
    ;; explicit validators
    (when-let [vs (get opts :validators)]
      (cond
        (fn? vs) (add! vs)
        (sequential? vs) (doseq [vf vs] (when (fn? vf) (add! vf)))))
    (persistent! validators)))

(defn- register-field-config!
  [form-k path val-fns init-val]
  (let [norm-p (normalize-path path)]
    (swap! !registered-field-configs assoc-in [form-k norm-p]
           {:validators    (vec val-fns)
            :initial-value init-val})))

(defn- get-registered-validators
  [form-k]
  (reduce-kv
    (fn [m p cfg]
      (if (seq (:validators cfg))
        (assoc m p (:validators cfg))
        m))
    {}
    (get @!registered-field-configs form-k)))

(defn- get-registered-initial-values-map
  [form-k]
  (reduce-kv
    (fn [m p cfg]
      (if (some? (:initial-value cfg))
        (assoc-in m p (:initial-value cfg))
        m))
    {}
    (get @!registered-field-configs form-k)))

(defn create
  "Creates a normalized form state map.

  Options:
  - `:key` / `:form-key` (keyword or vector, default `:form`): Key identifying form in component state.
  - `:initial-values` (map, default `{}`): Map of initial field values (optional, can also be defined in `register`).
  - `:validators`     (map, default `{}`): Map of field paths to validator or vector of validators (optional, prefer defining in `register`).
  - `:validate`       (fn [values] -> error-map, optional): Form-level custom validation function.
  - `:validate-on`    (set of #{:change :blur :submit}, default `#{:change :blur :submit}`): When validation triggers."
  ([] (create {}))
  ([{:keys [key form-key initial-values validators validate validate-on]
     :or {initial-values {}
          validators {}
          validate-on #{:change :blur :submit}}}]
   (let [k (or key form-key :form)
         _ (swap! !registered-field-configs assoc k {})
         normalized-validators (reduce-kv
                                 (fn [m k-path v]
                                   (let [norm-k (normalize-path k-path)
                                         norm-v (if (sequential? v) (vec v) [v])]
                                     (assoc m norm-k norm-v)))
                                 {}
                                 validators)]
     {:key            k
      :values         (or initial-values {})
      :initial-values (or initial-values {})
      :touched        #{}
      :errors         {}
      :validators     normalized-validators
      :validate-fn    validate
      :validate-on    (if (set? validate-on) validate-on (set validate-on))
      :submitting?    false
      :submit-count   0
      :status         nil})))

(defn form-key
  "Returns the key identifying the form in component state (defaulting to `:form`)."
  [form-state]
  (or (:key form-state) (:form-key form-state) :form))

(defn key
  "Returns the key identifying the form in component state (defaulting to `:form`)."
  [form-state]
  (form-key form-state))

(defn- extract-form-key
  "Extracts the form key from `form-or-key` (either a form state map or a keyword/vector)."
  [form-or-key]
  (if (map? form-or-key)
    (form-key form-or-key)
    (or form-or-key :form)))

;; -----------------------------------------------------------------------------
;; Pure Validation Engine
;; -----------------------------------------------------------------------------

(declare values initial-values)

(defn validate-form
  "Executes all configured field validators and optional form-level `:validate-fn` on `form-state`.
  Merges validators configured via `form/create` with dynamic validators defined via `form/register`.
  Returns updated `form-state` with `:errors` map populated."
  [form-state]
  (let [k (form-key form-state)
        registered (get-registered-validators k)
        all-validators (merge registered (:validators form-state))
        form-state (assoc form-state :validators all-validators)
        current-values (values form-state)
        ;; 1. Field-level validators
        field-errors
        (reduce-kv
          (fn [errs path val-fns]
            (let [val (get-in current-values path)
                  err (some (fn [val-fn] (run-validator val-fn val current-values)) val-fns)]
              (if err
                (assoc errs path err)
                errs)))
          {}
          all-validators)

        ;; 2. Form-level validator function
        custom-errors
        (when-let [v-fn (:validate-fn form-state)]
          (let [res (v-fn current-values)]
            (when (map? res)
              (reduce-kv
                (fn [errs k-path msg]
                  (if msg
                    (assoc errs (normalize-path k-path) msg)
                    errs))
                {}
                res))))

        all-errors (merge field-errors custom-errors)]
    (assoc form-state :errors (or all-errors {}))))

(defn validate-field
  "Validates a single field at `path` against its configured validators and optional `:validate-fn`.
  Updates `form-state` with the field validation result."
  [form-state path]
  (let [norm-p (normalize-path path)
        k (form-key form-state)
        all-validators (merge (get-registered-validators k) (:validators form-state))
        val-fns (get all-validators norm-p)
        current-values (values form-state)
        val (get-in current-values norm-p)
        err (when (seq val-fns)
              (some (fn [val-fn] (run-validator val-fn val current-values)) val-fns))
        custom-err (when-let [v-fn (:validate-fn form-state)]
                     (let [res (v-fn current-values)]
                       (get res norm-p (get res (if (= 1 (count norm-p)) (first norm-p) norm-p)))))]
    (if-let [final-err (or err custom-err)]
      (assoc-in form-state [:errors norm-p] final-err)
      (clojure.core/update form-state :errors dissoc norm-p))))

;; -----------------------------------------------------------------------------
;; Pure State Reducers
;; -----------------------------------------------------------------------------

(defn set-value
  "Updates the value of a field at `path`.
  If `:validate-on` includes `:change`, validates the form."
  [form-state path value]
  (let [norm-p (normalize-path path)
        updated (assoc-in form-state (into [:values] norm-p) value)]
    (if (contains? (:validate-on updated) :change)
      (validate-form updated)
      updated)))

(defn set-values
  "Updates multiple form values.
  If `:validate-on` includes `:change`, re-validates the entire form."
  [form-state new-values]
  (let [updated (assoc form-state :values (or new-values {}))]
    (if (contains? (:validate-on updated) :change)
      (validate-form updated)
      updated)))

(defn set-touched
  "Marks or unmarks a field at `path` as touched.
  If marked touched and `:validate-on` includes `:blur`, validates the form."
  ([form-state path]
   (set-touched form-state path true))
  ([form-state path is-touched?]
   (let [norm-p (normalize-path path)
         updated (clojure.core/update form-state :touched (if is-touched?
                                                           (fnil conj #{})
                                                           (fnil disj #{}))
                                     norm-p)]
     (if (and is-touched? (contains? (:validate-on updated) :blur))
       (validate-form updated)
       updated))))

(defn- collect-all-paths
  "Recursively collects all key paths from a map."
  ([m] (collect-all-paths m []))
  ([m prefix]
   (reduce-kv
     (fn [paths k v]
       (let [current-path (conj prefix k)]
         (if (map? v)
           (into (conj paths current-path) (collect-all-paths v current-path))
           (conj paths current-path))))
     []
     m)))

(defn touch-all
  "Marks all fields (all configured validator paths and value paths) as touched."
  [form-state]
  (let [k (form-key form-state)
        val-paths (collect-all-paths (values form-state))
        init-paths (collect-all-paths (initial-values form-state))
        registered-paths (keys (get @!registered-field-configs k))
        validator-paths (keys (:validators form-state))
        all-paths (into (set validator-paths) (concat val-paths init-paths registered-paths))]
    (assoc form-state :touched all-paths)))

(defn set-error
  "Sets or clears an error message for a field at `path`."
  [form-state path error-msg]
  (let [norm-p (normalize-path path)]
    (if (or (nil? error-msg) (and (string? error-msg) (string/blank? error-msg)))
      (clojure.core/update form-state :errors dissoc norm-p)
      (assoc-in form-state [:errors norm-p] error-msg))))

(defn set-errors
  "Replaces the entire `:errors` map with `errors-map`, normalizing all keys into vector paths."
  [form-state errors-map]
  (let [normalized (reduce-kv
                     (fn [m k v]
                       (if v
                         (assoc m (normalize-path k) v)
                         m))
                     {}
                     errors-map)]
    (assoc form-state :errors (or normalized {}))))

(defn clear-errors
  "Clears all errors from `form-state`."
  [form-state]
  (assoc form-state :errors {}))

(defn reset-form
  "Resets the form state back to initial state or with `new-initial-values`."
  ([form-state]
   (reset-form form-state nil))
  ([form-state new-initial-values]
   (let [initial (or new-initial-values (initial-values form-state) {})]
     (assoc form-state
            :values initial
            :initial-values initial
            :touched #{}
            :errors {}
            :submitting? false
            :submit-count 0
            :status nil))))

(defn submit-start
  "Marks the form as actively submitting and increments `:submit-count`."
  [form-state]
  (-> form-state
      (assoc :submitting? true)
      (clojure.core/update :submit-count (fnil inc 0))))

(defn submit-end
  "Finishes form submission, setting `:submitting? false` and updating `:status`."
  ([form-state]
   (submit-end form-state nil))
  ([form-state status]
   (assoc form-state
          :submitting? false
          :status status)))

;; -----------------------------------------------------------------------------
;; Granular View Queries
;; -----------------------------------------------------------------------------

(defn value
  "Retrieves the current value of a field at `path`, returning registered initial value or `default-val` if not set."
  ([form-state path]
   (value form-state path nil))
  ([form-state path default-val]
   (let [norm-p (normalize-path path)
         curr-val (get-in (:values form-state) norm-p)]
     (if (some? curr-val)
       curr-val
       (let [init-val (get-in (:initial-values form-state) norm-p)]
         (if (some? init-val)
           init-val
           (let [k (form-key form-state)
                 reg-init (get-in @!registered-field-configs [k norm-p :initial-value])]
             (if (some? reg-init)
               reg-init
               default-val))))))))

(defn error
  "Retrieves the validation error for a field at `path`, or nil if valid.
  Optionally checks if the field is touched when `only-if-touched?` is true."
  ([form-state path]
   (get (:errors form-state) (normalize-path path)))
  ([form-state path only-if-touched?]
   (let [norm-p (normalize-path path)]
     (if only-if-touched?
       (when (contains? (:touched form-state) norm-p)
         (get (:errors form-state) norm-p))
       (get (:errors form-state) norm-p)))))

(defn touched?
  "Returns true if the field at `path` has been touched/blurred."
  [form-state path]
  (contains? (:touched form-state) (normalize-path path)))

(defn initial-values
  "Returns the complete `:initial-values` map from `form-state` merged with registered field initial values."
  [form-state]
  (let [k (form-key form-state)
        registered-inits (get-registered-initial-values-map k)
        base-inits (or (:initial-values form-state) {})]
    (deep-merge registered-inits base-inits)))

(defn values
  "Returns the complete `:values` map from `form-state` merged with registered field defaults."
  [form-state]
  (let [inits (initial-values form-state)
        curr-vals (or (:values form-state) {})]
    (deep-merge inits curr-vals)))

(defn dirty?
  "Returns true if the form (or a specific field `path`) has been modified from initial values."
  ([form-state]
   (not= (values form-state) (initial-values form-state)))
  ([form-state path]
   (let [norm-p (normalize-path path)]
     (not= (get-in (values form-state) norm-p)
           (get-in (initial-values form-state) norm-p)))))

(defn pristine?
  "Returns true if the form (or a specific field `path`) is unmodified from initial values."
  ([form-state]
   (not (dirty? form-state)))
  ([form-state path]
   (not (dirty? form-state path))))

(defn errors
  "Returns the complete `:errors` map from `form-state`."
  [form-state]
  (or (:errors form-state) {}))

(defn touched
  "Returns the set of touched paths from `form-state`."
  [form-state]
  (or (:touched form-state) #{}))

(defn valid?
  "Returns true if the form has no validation errors."
  [form-state]
  (empty? (:errors form-state)))

(defn invalid?
  "Returns true if the form contains one or more validation errors."
  [form-state]
  (boolean (seq (:errors form-state))))

(defn submitting?
  "Returns true if the form is currently in a submitting state."
  [form-state]
  (boolean (:submitting? form-state)))

(defn submit-count
  "Returns the number of times form submission has been attempted."
  [form-state]
  (or (:submit-count form-state) 0))

;; -----------------------------------------------------------------------------
;; DOM Event Extraction & Handling
;; -----------------------------------------------------------------------------

(defn- prevent-default!
  "Calls preventDefault on the DOM event if present."
  [event]
  #?(:cljs
     (let [dom-e (cond
                   (and (object? event) (fn? (.-preventDefault event))) event
                   (map? event) (or (when-let [e (:replicant/dom-event event)]
                                      (when (fn? (.-preventDefault e)) e))
                                    (when-let [e (:event event)]
                                      (when (fn? (.-preventDefault e)) e)))
                   :else nil)]
       (when dom-e
         (.preventDefault dom-e)))
     :clj
     (when (map? event)
       (let [dom-e (or (:replicant/dom-event event)
                       (:event event))]
         (when (and (map? dom-e) (fn? (:preventDefault dom-e)))
           ((:preventDefault dom-e)))))))

(defn extract-event-value
  "Extracts the value from a DOM event, event map, or DOM node.
  Handles checkboxes (checked boolean), radio buttons, select dropdowns, numbers, and standard text inputs.
  If passed a primitive value directly, returns it."
  [event-or-val]
  #?(:cljs
     (let [target (cond
                    ;; 1. Replicant event map with :replicant/node or :replicant/dom-event
                    (map? event-or-val)
                    (or (:replicant/node event-or-val)
                        (when-let [dom-e (:replicant/dom-event event-or-val)]
                          (or (.-target dom-e) (.-currentTarget dom-e) dom-e))
                        (:target event-or-val))

                    ;; 2. Direct DOM Event object with .target / .currentTarget
                    (and (object? event-or-val)
                         (or (some? (.-target event-or-val))
                             (some? (.-currentTarget event-or-val))))
                    (or (.-target event-or-val) (.-currentTarget event-or-val))

                    ;; 3. Direct DOM Node object with .value or .checked
                    (and (object? event-or-val)
                         (or (some? (.-value event-or-val))
                             (some? (.-checked event-or-val))))
                    event-or-val

                    :else nil)]
       (if target
         (let [input-type (when target (.-type target))]
           (cond
             (= input-type "checkbox")
             (.-checked target)

             (= input-type "number")
             (let [v (.-value target)]
               (if (or (nil? v) (empty? v)) nil (js/parseFloat v)))

             (some? (.-value target))
             (.-value target)

             :else
             target))
         (if (map? event-or-val)
           (get event-or-val :value event-or-val)
           event-or-val)))
     :clj
     (if (map? event-or-val)
       (let [dom-e (or (:replicant/dom-event event-or-val)
                       (:event event-or-val)
                       (:target event-or-val))]
         (if dom-e
           (extract-event-value dom-e)
           (get event-or-val :value event-or-val)))
       event-or-val)))

;; -----------------------------------------------------------------------------
;; Event Handler Helpers
;; -----------------------------------------------------------------------------

(defn on-change
  "Constructs a message vector for the `:input` or `:change` DOM event.
  Usage:
    (on-change form :email)
    (on-change :form :email)
    (on-change form [:user :email])"
  [form-or-key path]
  [::change (extract-form-key form-or-key) (normalize-path path)])

(defn on-blur
  "Constructs a message vector for the `:blur` DOM event.
  Usage:
    (on-blur form :email)
    (on-blur :form :email)
    (on-blur form [:user :email])"
  [form-or-key path]
  [::blur (extract-form-key form-or-key) (normalize-path path)])

(defn on-submit
  "Constructs a message vector for the `:submit` DOM event.
  Usage:
    (on-submit form {:on-submit [::save-user] :on-invalid [::show-toast]})
    (on-submit :form {:on-submit [::save-user]})"
  [form-or-key opts]
  [::submit (extract-form-key form-or-key) opts])

(defn on-reset
  "Constructs a message vector for the `::form/reset` event.
  Usage:
    (on-reset form)
    (on-reset :form)"
  [form-or-key]
  [::reset (extract-form-key form-or-key)])

;; -----------------------------------------------------------------------------
;; View Helpers & Field Registration
;; -----------------------------------------------------------------------------

(defn- register-attrs
  [form-state form-k path opts]
  (let [norm-p (normalize-path path)
        val-fns (extract-validators-from-opts opts)
        is-checkbox? (= (:type opts) "checkbox")
        init-val (extract-initial-val-from-opts opts is-checkbox?)
        _ (register-field-config! form-k norm-p val-fns init-val)
        val (value form-state norm-p init-val)
        min-val (when-let [m (:min opts)]
                  (let [[v _] (extract-rule-val-and-msg m)]
                    (when (number? v) v)))
        max-val (when-let [m (:max opts)]
                  (let [[v _] (extract-rule-val-and-msg m)]
                    (when (number? v) v)))
        min-len (when-let [ml (or (:min-length opts) (:minlength opts))]
                  (let [[v _] (extract-rule-val-and-msg ml)]
                    (when (number? v) v)))
        max-len (when-let [ml (or (:max-length opts) (:maxlength opts))]
                  (let [[v _] (extract-rule-val-and-msg ml)]
                    (when (number? v) v)))
        pat (when-let [p (:pattern opts)]
              (let [[v _] (extract-rule-val-and-msg p)]
                (when v
                  (if (instance? #?(:clj java.util.regex.Pattern :cljs js/RegExp) v)
                    (str v)
                    v))))
        base-attrs (cond-> {:on (if is-checkbox?
                                  {:change (on-change form-k norm-p)
                                   :blur   (on-blur form-k norm-p)}
                                  {:input  (on-change form-k norm-p)
                                   :change (on-change form-k norm-p)
                                   :blur   (on-blur form-k norm-p)})}
                     is-checkbox? (assoc :checked (boolean val))
                     (not is-checkbox?) (assoc :value (if (nil? val) "" val))
                     (:type opts) (assoc :type (:type opts))
                     (:required opts) (assoc :required true)
                     (some? min-val) (assoc :min min-val)
                     (some? max-val) (assoc :max max-val)
                     (some? min-len) (assoc :minlength min-len)
                     (some? max-len) (assoc :maxlength max-len)
                     (some? pat) (assoc :pattern pat))
        extra-attrs (dissoc opts
                            :default :initial :initial-value :default-value :defaultValue :checked :value
                            :required :email
                            :min :max :min-length :max-length :minlength :maxlength
                            :pattern :type :validate :validate-fn :validators :one-of)]
    (merge base-attrs extra-attrs)))

(defn register
  "Generates standard Hiccup input attributes, event handlers, and registers validation rules and initial values for a form field.
   
   Parameters:
   - `form-state`: The current form state map (extracts `:key` automatically, default `:form`).
   - `path`: The field key or vector path (e.g. `:email` or `[:profile :age]`).
   - `opts`: (Optional) Map of options, initial values, validators, and HTML attributes:
     - `:type`          - Input type (\"text\", \"email\", \"password\", \"number\", \"checkbox\", etc.)
     - `:initial-value` / `:default` / `:value` - Initial/default field value
     - `:required`      - Boolean (true), custom error string, or `{:value true :message \"...\"}`
     - `:email`         - Boolean (true), custom error string, or `{:value true :message \"...\"}`
     - `:min`           - Numeric constraint (sets `:min` HTML attr & validates min-num)
     - `:max`           - Numeric constraint (sets `:max` HTML attr & validates max-num)
     - `:min-length` / `:minlength` - Length constraint (sets `:minlength` HTML attr & validates min-length)
     - `:max-length` / `:maxlength` - Length constraint (sets `:maxlength` HTML attr & validates max-length)
     - `:pattern`       - Regex or `[regex \"msg\"]` (sets `:pattern` HTML attr & validates regex match)
     - `:one-of`        - Collection of allowed values or `[coll \"msg\"]`
     - `:validate`      - Custom validator function `(fn [val values])` or `(fn [val])`
     - `:validators`    - Vector of validator functions
     - Any additional HTML attributes (e.g. `:placeholder`, `:autocomplete`, `:disabled`)
   
   Usage:
     (register form :email {:type \"email\" :required \"Email is required\" :default \"user@example.com\"})
     (register form [:profile :age] {:type \"number\" :min [18 \"Must be 18+\"] :max 120})
     (register form [:preferences :newsletter] {:type \"checkbox\" :default true})
     (register form :confirm-password {:type \"password\" :required true :validate (fn [v values] ...)})
     (register form :form :email {:type \"email\"}) ;; backwards compatibility"
  ([form-state path]
   (register form-state path nil))
  ([form-state path-or-key opts-or-path]
   (if (or (map? opts-or-path) (nil? opts-or-path))
     (register-attrs form-state (extract-form-key form-state) path-or-key opts-or-path)
     (register-attrs form-state path-or-key opts-or-path nil)))
  ([form-state form-key path opts]
   (register-attrs form-state (or form-key (extract-form-key form-state)) path opts)))

(def field
  "Alias for `register`."
  register)

;; -----------------------------------------------------------------------------
;; Form State Component Access Helpers
;; -----------------------------------------------------------------------------

(defn- get-form
  "Extracts the form state map from component local state `state` at `form-key`."
  [state form-key]
  (if (vector? form-key)
    (get-in state form-key)
    (get state form-key)))

(defn- update-form
  "Applies function `f` to the form state inside component local state `state` at `form-key`."
  [state form-key f & args]
  (if (vector? form-key)
    (apply clojure.core/update-in state form-key f args)
    (clojure.core/update state form-key #(apply f % args))))

;; -----------------------------------------------------------------------------
;; Side Effects (relm/fx)
;; -----------------------------------------------------------------------------

(defmethod relm/fx ::focus-field
  [_ [_ field-name-or-id]]
  #?(:cljs
     (when (and (exists? js/document) field-name-or-id)
       (let [selector (str "[name='" (name field-name-or-id) "'], #" (name field-name-or-id))
             elem (.querySelector js/document selector)]
         (when elem
           (.focus elem))))
     :clj nil))

(defmethod relm/fx ::focus-first-error
  [_ [_ errors]]
  #?(:cljs
     (when (and (exists? js/document) (seq errors))
       (let [first-path (first (keys errors))
             field-name (if (vector? first-path) (last first-path) first-path)]
         (when field-name
           (let [selector (str "[name='" (name field-name) "'], #" (name field-name))
                 elem (.querySelector js/document selector)]
             (when elem
               (.focus elem))))))
     :clj nil))

(defmethod relm/fx ::validate-async
  [_ [_ {:keys [path validator on-success on-error]}]]
  #?(:cljs
     (when (fn? validator)
       (let [p (validator)]
         (when (and p (exists? (.-then p)))
           (-> p
               (.then (fn [res]
                        (when on-success
                          (relm/dispatch nil (if (fn? on-success) (on-success res) on-success)))))
               (.catch (fn [err]
                         (when on-error
                           (let [msg (or (.-message err) (str err))]
                             (relm/dispatch nil (if (fn? on-error) (on-error msg) [::set-error path msg]))))))))))
     :clj nil))

;; -----------------------------------------------------------------------------
;; Relm Update Message Handlers
;; -----------------------------------------------------------------------------

;; Message format: `[::change form-key path]` or `[::change form-key path value]`
(defmethod relm/update ::change
  [state context message event]
  (let [[_ form-key path explicit-val] message
        val (if (some? explicit-val) explicit-val (extract-event-value event))
        new-state (update-form state form-key set-value path val)]
    [new-state context]))

;; Message format: `[::blur form-key path]`
(defmethod relm/update ::blur
  [state context message _event]
  (let [[_ form-key path] message
        new-state (update-form state form-key set-touched path true)]
    [new-state context]))

;; Message format: `[::set-field form-key path value]`
(defmethod relm/update ::set-field
  [state context message _event]
  (let [[_ form-key path val] message
        new-state (update-form state form-key set-value path val)]
    [new-state context]))

;; Message format: `[::set-values form-key new-values]`
(defmethod relm/update ::set-values
  [state context message _event]
  (let [[_ form-key vals] message
        new-state (update-form state form-key set-values vals)]
    [new-state context]))

;; Message format: `[::set-error form-key path msg]`
(defmethod relm/update ::set-error
  [state context message _event]
  (let [[_ form-key path msg] message
        new-state (update-form state form-key set-error path msg)]
    [new-state context]))

;; Message format: `[::reset form-key]` or `[::reset form-key new-initial-values]`
(defmethod relm/update ::reset
  [state context message _event]
  (let [[_ form-key initial] message
        new-state (update-form state form-key reset-form initial)]
    [new-state context]))

;; Message format: `[::submit form-key opts]`
(defmethod relm/update ::submit
  [state context message event]
  (prevent-default! event)
  (let [[_ form-key {:keys [on-submit on-invalid validate focus-error?]
                     :or   {focus-error? true}}] message
        current-form (get-form state form-key)
        touched-form (touch-all current-form)
        validated-form (validate-form touched-form)
        form-vals (values validated-form)
        extra-errors (when (fn? validate) (validate form-vals))
        final-form (if (map? extra-errors)
                     (set-errors validated-form (merge (:errors validated-form) extra-errors))
                     validated-form)
        is-valid? (valid? final-form)]
    (if is-valid?
      (let [submitting-form (submit-start final-form)
            new-state (update-form state form-key (constantly submitting-form))
            submitted-vals (values submitting-form)
            effects (cond
                      (nil? on-submit) []
                      (fn? on-submit)
                      (let [res (on-submit submitted-vals)]
                        (if (relm/vector-of-vectors? res) res [[:dispatch res]]))
                      (relm/vector-of-vectors? on-submit)
                      on-submit
                      (vector? on-submit)
                      [[:dispatch (conj on-submit submitted-vals)]]
                      :else [])]
        [new-state context effects])
      (let [failed-form (assoc final-form :submitting? false)
            new-state (update-form state form-key (constantly failed-form))
            errors (:errors failed-form)
            invalid-effects (cond
                              (nil? on-invalid) []
                              (fn? on-invalid)
                              (let [res (on-invalid errors)]
                                (if (relm/vector-of-vectors? res) res [[:dispatch res]]))
                              (relm/vector-of-vectors? on-invalid)
                              on-invalid
                              (vector? on-invalid)
                              [[:dispatch (conj on-invalid errors)]]
                              :else [])
            focus-fx (when (and focus-error? (seq errors))
                       [[::focus-first-error errors]])]
        [new-state context (into (or focus-fx []) invalid-effects)]))))
