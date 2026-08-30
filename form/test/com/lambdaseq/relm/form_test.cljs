(ns com.lambdaseq.relm.form-test
  "Unit tests for Relm form state management, reducers, validators, and update lifecycle."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.form :as form]))

;; -----------------------------------------------------------------------------
;; 1. Initialization Tests
;; -----------------------------------------------------------------------------

(deftest create-form-test
  (testing "creates a normalized form state with default values"
    (let [f (form/create)]
      (is (= {} (:values f)))
      (is (= {} (:initial-values f)))
      (is (= #{} (:touched f)))
      (is (= {} (:errors f)))
      (is (= {} (:validators f)))
      (is (false? (form/submitting? f)))
      (is (= 0 (form/submit-count f)))
      (is (true? (form/valid? f)))
      (is (false? (form/dirty? f)))))

  (testing "initializes with provided initial values and validators"
    (let [f (form/create {:initial-values {:email "user@test.com"
                                           :profile {:age 25}}
                          :validators     {:email [(form/required) (form/email)]
                                           [:profile :age] (form/min-num 18)}})]
      (is (= "user@test.com" (form/value f :email)))
      (is (= 25 (form/value f [:profile :age])))
      (is (contains? (:validators f) [:email]))
      (is (contains? (:validators f) [:profile :age]))
      (is (false? (form/dirty? f)))
      (is (false? (form/touched? f :email))))))

;; -----------------------------------------------------------------------------
;; 2. Built-in Validator Tests
;; -----------------------------------------------------------------------------

(deftest built-in-validators-test
  (testing "required validator"
    (let [v (form/required "Required field")]
      (is (= "Required field" (v nil)))
      (is (= "Required field" (v "")))
      (is (= "Required field" (v "   ")))
      (is (= "Required field" (v [])))
      (is (= "Required field" (v {})))
      (is (= "Required field" (v false)))
      (is (nil? (v "hello")))
      (is (nil? (v 0)))
      (is (nil? (v true)))
      (is (nil? (v [1 2 3])))))

  (testing "email validator"
    (let [v (form/email "Invalid email")]
      (is (nil? (v nil)))
      (is (nil? (v "")))
      (is (nil? (v "test@example.com")))
      (is (nil? (v "user.name+tag@sub.domain.co")))
      (is (= "Invalid email" (v "not-an-email")))
      (is (= "Invalid email" (v "missing@domain")))
      (is (= "Invalid email" (v "@missing-user.com")))))

  (testing "min-num & max-num validators"
    (let [min-v (form/min-num 18 "Too young")
          max-v (form/max-num 100 "Too old")]
      (is (nil? (min-v nil)))
      (is (nil? (min-v "")))
      (is (nil? (min-v 18)))
      (is (nil? (min-v 25)))
      (is (nil? (min-v "25")))
      (is (= "Too young" (min-v 17)))
      (is (= "Too young" (min-v "16")))
      (is (= "Too young" (min-v "not-a-number")))

      (is (nil? (max-v 100)))
      (is (nil? (max-v "50")))
      (is (= "Too old" (max-v 101)))
      (is (= "Too old" (max-v "105")))))

  (testing "min-length & max-length validators"
    (let [min-l (form/min-length 3 "Too short")
          max-l (form/max-length 5 "Too long")]
      (is (nil? (min-l nil)))
      (is (nil? (min-l "")))
      (is (nil? (min-l "abc")))
      (is (nil? (min-l [1 2 3])))
      (is (= "Too short" (min-l "ab")))
      (is (= "Too short" (min-l [1])))

      (is (nil? (max-l "abcde")))
      (is (nil? (max-l [1 2 3])))
      (is (= "Too long" (max-l "abcdef")))
      (is (= "Too long" (max-l [1 2 3 4 5 6])))))

  (testing "pattern validator"
    (let [v (form/pattern #"^\d{3}-\d{3}$" "Format: 123-456")]
      (is (nil? (v nil)))
      (is (nil? (v "")))
      (is (nil? (v "123-456")))
      (is (= "Format: 123-456" (v "123456")))
      (is (= "Format: 123-456" (v "abc")))))

  (testing "one-of validator"
    (let [v (form/one-of ["admin" "user" "guest"] "Invalid role")]
      (is (nil? (v nil)))
      (is (nil? (v "")))
      (is (nil? (v "admin")))
      (is (nil? (v "user")))
      (is (= "Invalid role" (v "superadmin")))))

  (testing "compose validator"
    (let [v (form/compose (form/required "Required")
                          (form/email "Must be email")
                          (form/min-length 10 "Min 10 chars"))]
      (is (= "Required" (v "")))
      (is (= "Must be email" (v "notemail")))
      (is (= "Min 10 chars" (v "a@b.co")))
      (is (nil? (v "john.doe@example.com"))))))

;; -----------------------------------------------------------------------------
;; 3. State Reducers & Validation Engine Tests
;; -----------------------------------------------------------------------------

(deftest state-reducers-test
  (testing "set-value updates values and handles nested paths and validation"
    (let [f (form/create {:initial-values {:user {:name "Alice"}}
                          :validators     {[:user :name] (form/min-length 3 "Too short")}})
          f1 (form/set-value f [:user :name] "Al")
          f2 (form/set-value f1 [:user :name] "Alice")]
      (is (= "Al" (form/value f1 [:user :name])))
      (is (= "Too short" (form/error f1 [:user :name])))
      (is (form/invalid? f1))
      (is (form/dirty? f1 [:user :name]))

      (is (= "Alice" (form/value f2 [:user :name])))
      (is (nil? (form/error f2 [:user :name])))
      (is (form/valid? f2))
      (is (false? (form/dirty? f2 [:user :name])))))

  (testing "set-values updates multiple values"
    (let [f (form/create {:initial-values {:a 1 :b 2}})
          f1 (form/set-values f {:a 10 :b 20})]
      (is (= 10 (form/value f1 :a)))
      (is (= 20 (form/value f1 :b)))
      (is (form/dirty? f1))))

  (testing "set-touched and touch-all"
    (let [f (form/create {:initial-values {:email "a@b.com" :name "Test"}
                          :validators     {:email (form/required)
                                           :name (form/required)}})
          f1 (form/set-touched f :email)
          f2 (form/touch-all f)]
      (is (form/touched? f1 :email))
      (is (false? (form/touched? f1 :name)))
      (is (form/touched? f2 :email))
      (is (form/touched? f2 :name))))

  (testing "error filtering with only-if-touched?"
    (let [f (form/create {:initial-values {:email ""}
                          :validators     {:email (form/required "Email required")}})
          f-val (form/validate-form f)]
      (is (= "Email required" (form/error f-val :email)))
      (is (nil? (form/error f-val :email true)))
      (let [f-touched (form/set-touched f-val :email)]
        (is (= "Email required" (form/error f-touched :email true))))))

  (testing "reset-form clears errors, touched, and restores initial values"
    (let [f (form/create {:initial-values {:name "Init"}
                          :validators     {:name (form/min-length 5 "Too short")}})
          f-dirty (-> f
                      (form/set-value :name "Bob")
                      (form/set-touched :name)
                      (form/submit-start))
          f-reset (form/reset-form f-dirty)]
      (is (= "Init" (form/value f-reset :name)))
      (is (false? (form/dirty? f-reset)))
      (is (false? (form/touched? f-reset :name)))
      (is (form/valid? f-reset))
      (is (false? (form/submitting? f-reset)))
      (is (= 0 (form/submit-count f-reset))))))

(deftest whole-form-validation-test
  (testing "form-level validate-fn runs and combines errors with field validators"
    (let [validate-fn (fn [values]
                        (when (and (= (:password values) "123456")
                                   (= (:confirm-password values) "654321"))
                          {:confirm-password "Passwords must match"}))
          f (form/create {:initial-values {:password "123456"
                                           :confirm-password "654321"}
                          :validators     {:password (form/min-length 6)}
                          :validate       validate-fn})
          f-validated (form/validate-form f)]
      (is (= "Passwords must match" (form/error f-validated :confirm-password)))
      (is (form/invalid? f-validated))))

  (testing "cross-field validation updates dynamically on field changes"
    (let [password-match (fn [{:keys [password confirm-password]}]
                           (when (and (seq password) (seq confirm-password) (not= password confirm-password))
                             {:confirm-password "Passwords do not match"}))
          state {:form (form/create {:initial-values {:password "" :confirm-password ""}
                                     :validate       password-match})}
          ;; Type password
          [s1] (relm/update state {} (form/on-change :form :password) {:value "secret123"})
          ;; Type mismatching confirm-password -> should immediately have error
          [s2] (relm/update s1 {} (form/on-change :form :confirm-password) {:value "secret999"})]
      (is (= "Passwords do not match" (form/error (:form s2) :confirm-password)))
      (is (form/invalid? (:form s2)))

      ;; Now change password to match confirm-password -> error should be cleared immediately!
      (let [[s3] (relm/update s2 {} (form/on-change :form :password) {:value "secret999"})]
        (is (nil? (form/error (:form s3) :confirm-password)))
        (is (form/valid? (:form s3)))))))

;; -----------------------------------------------------------------------------
;; 4. Update Multimethod & Lifecycle Tests
;; -----------------------------------------------------------------------------

(deftest event-extraction-test
  (testing "extract-event-value extracts from various inputs"
    (is (= "plain-text" (form/extract-event-value "plain-text")))
    (is (= 42 (form/extract-event-value 42)))
    (is (= "mapped" (form/extract-event-value {:value "mapped"})))
    (is (= "replicant-val" (form/extract-event-value {:replicant/dom-event {:value "replicant-val"}})))))

(deftest update-messages-test
  (testing "::form/change updates field value in component state"
    (let [state {:form (form/create {:initial-values {:search ""}})}
          [new-state] (relm/update state {} [::form/change :form :search "Clojure"] nil)]
      (is (= "Clojure" (form/value (:form new-state) :search)))))

  (testing "::form/change with on-change message vector and event map"
    (let [state {:form (form/create {:initial-values {:username ""}
                                     :validators     {:username (form/required "Username required")}})}
          msg (form/on-change :form :username)
          [new-state] (relm/update state {} msg {:value "john_doe"})]
      (is (= "john_doe" (form/value (:form new-state) :username)))
      (is (nil? (form/error (:form new-state) :username)))
      (is (form/valid? (:form new-state)))))

  (testing "full submission and revalidation flow"
    (let [initial-state {:form (form/create {:initial-values {:username "" :email ""}
                                             :validators     {:username [(form/required "Username required")
                                                                         (form/min-length 3 "At least 3 chars")]
                                                              :email    [(form/required "Email required")]}})}
          ;; 1. Submit empty form -> should fail and populate errors
          [after-submit] (relm/update initial-state {} (form/on-submit :form {:on-submit [::save]}) nil)]
      (is (form/invalid? (:form after-submit)))
      (is (= "Username required" (form/error (:form after-submit) :username)))
      (is (= "Email required" (form/error (:form after-submit) :email)))
      (is (true? (form/touched? (:form after-submit) :username)))

      ;; 2. Type username -> error on username should be cleared immediately
      (let [[after-username] (relm/update after-submit {} (form/on-change :form :username) {:value "johndoe"})]
        (is (= "johndoe" (form/value (:form after-username) :username)))
        (is (nil? (form/error (:form after-username) :username)))
        (is (= "Email required" (form/error (:form after-username) :email)))
        (is (form/invalid? (:form after-username)))

        ;; 3. Type email -> all errors cleared, form becomes valid
        (let [[after-email] (relm/update after-username {} (form/on-change :form :email) {:value "john@example.com"})]
          (is (= "john@example.com" (form/value (:form after-email) :email)))
          (is (nil? (form/error (:form after-email) :email)))
          (is (form/valid? (:form after-email)))))))

  (testing "::form/change with custom form key and synthetic event"
    (let [state {:my-form (form/create {:initial-values {:term ""}})}
          [new-state] (relm/update state {} [::form/change :my-form :term] {:value "query"})]
      (is (= "query" (form/value (:my-form new-state) :term)))))

  (testing "::form/blur marks field touched"
    (let [state {:form (form/create {:initial-values {:email ""}})}
          [new-state] (relm/update state {} [::form/blur :form :email] nil)]
      (is (true? (form/touched? (:form new-state) :email)))))

  (testing "::form/set-field updates single field directly"
    (let [state {:form (form/create {:initial-values {:name "Old"}})}
          [new-state] (relm/update state {} [::form/set-field :form :name "New"] nil)]
      (is (= "New" (form/value (:form new-state) :name)))))

  (testing "::form/set-values updates full form values"
    (let [state {:form (form/create {:initial-values {:x 1 :y 2}})}
          [new-state] (relm/update state {} [::form/set-values :form {:x 100 :y 200}] nil)]
      (is (= 100 (form/value (:form new-state) :x)))
      (is (= 200 (form/value (:form new-state) :y)))))

  (testing "::form/set-error explicitly adds validation error"
    (let [state {:form (form/create {:initial-values {:email "taken@test.com"}})}
          [new-state] (relm/update state {} [::form/set-error :form :email "Email already in use"] nil)]
      (is (= "Email already in use" (form/error (:form new-state) :email)))))

  (testing "::form/reset restores initial state"
    (let [state {:form (-> (form/create {:initial-values {:count 0}})
                           (form/set-value :count 42)
                           (form/set-touched :count))}
          [new-state] (relm/update state {} [::form/reset :form] nil)]
      (is (= 0 (form/value (:form new-state) :count)))
      (is (false? (form/dirty? (:form new-state))))))

  (testing "::form/submit on valid form triggers on-submit effect and prevents default"
    (let [prevented? (atom false)
          mock-event {:replicant/dom-event #js {:preventDefault #(reset! prevented? true)}}
          state {:form (form/create {:initial-values {:email "valid@example.com"}
                                     :validators     {:email (form/required)}})}
          [new-state _ effects] (relm/update state {} [::form/submit :form {:on-submit [::save-data]}] mock-event)]
      (is (true? (form/submitting? (:form new-state))))
      (is (= 1 (form/submit-count (:form new-state))))
      (is (= [[::relm/prevent-default! mock-event] [::relm/dispatch! [::save-data {:email "valid@example.com"}]]] effects))
      (relm/fx mock-event (first effects))
      (is (true? @prevented?))))

  (testing "::form/submit with custom on-submit function"
    (let [state {:form (form/create {:initial-values {:email "valid@example.com"}})}
          [new-state _ effects] (relm/update state {} [::form/submit :form {:on-submit (fn [vals] [::custom-save (:email vals)])}] nil)]
      (is (true? (form/submitting? (:form new-state))))
      (is (= [[::relm/prevent-default! nil] [::relm/dispatch! [::custom-save "valid@example.com"]]] effects))))

  (testing "::form/submit on invalid form prevents on-submit and invokes on-invalid"
    (let [state {:form (form/create {:initial-values {:email ""}
                                     :validators     {:email (form/required "Email required")}})}
          [new-state _ effects] (relm/update state {} [::form/submit :form {:on-submit  [::save-data]
                                                                            :on-invalid [::show-error-toast]
                                                                            :focus-error? false}] nil)]
      (is (false? (form/submitting? (:form new-state))))
      (is (form/invalid? (:form new-state)))
      (is (form/touched? (:form new-state) :email))
      (is (= [[::relm/prevent-default! nil] [::relm/dispatch! [::show-error-toast {[:email] "Email required"}]]] effects)))))

;; -----------------------------------------------------------------------------
;; 5. Event Helpers Tests
;; -----------------------------------------------------------------------------

(deftest query-functions-test
  (testing "pristine?, values, initial-values, errors, touched, key query helpers"
    (let [f (form/create {:initial-values {:user {:name "Sam" :age 30}}
                          :validators     {[:user :age] (form/min-num 18)}})
          custom-f (form/create {:key :signup-form
                                 :initial-values {:email "test@example.com"}})]
      (is (= :form (form/key f)))
      (is (= :signup-form (form/key custom-f)))
      (is (= :signup-form (form/form-key custom-f)))
      (is (true? (form/pristine? f)))
      (is (true? (form/pristine? f [:user :name])))
      (is (= {:user {:name "Sam" :age 30}} (form/values f)))
      (is (= {:user {:name "Sam" :age 30}} (form/initial-values f)))
      (is (= {} (form/errors f)))
      (is (= #{} (form/touched f)))

      (let [f-mod (-> f
                      (form/set-value [:user :name] "Samuel")
                      (form/set-touched [:user :name]))]
        (is (false? (form/pristine? f-mod)))
        (is (false? (form/pristine? f-mod [:user :name])))
        (is (true? (form/pristine? f-mod [:user :age])))
        (is (true? (form/dirty? f-mod [:user :name])))
        (is (= "Samuel" (form/value f-mod [:user :name])))
        (is (= #{[:user :name]} (form/touched f-mod)))))))

(deftest event-helpers-test
  (let [f (form/create {:initial-values {:email ""}})
        custom-f (form/create {:key :my-form :initial-values {:user {:email ""}}})]
    (testing "on-change, on-blur, on-submit, on-reset generate correct message vectors with form-key or form map"
      (is (= [::form/change :form [:email]] (form/on-change :form :email)))
      (is (= [::form/change :form [:email]] (form/on-change f :email)))
      (is (= [::form/change :my-form [:user :email]] (form/on-change :my-form [:user :email])))
      (is (= [::form/change :my-form [:user :email]] (form/on-change custom-f [:user :email])))

      (is (= [::form/blur :form [:email]] (form/on-blur :form :email)))
      (is (= [::form/blur :form [:email]] (form/on-blur f :email)))
      (is (= [::form/blur :my-form [:email]] (form/on-blur :my-form [:email])))
      (is (= [::form/blur :my-form [:user :email]] (form/on-blur custom-f [:user :email])))

      (is (= [::form/submit :form {:on-submit [::save]}] (form/on-submit :form {:on-submit [::save]})))
      (is (= [::form/submit :form {:on-submit [::save]}] (form/on-submit f {:on-submit [::save]})))
      (is (= [::form/submit :custom-form {:on-submit [::save]}] (form/on-submit :custom-form {:on-submit [::save]})))
      (is (= [::form/submit :my-form {:on-submit [::save]}] (form/on-submit custom-f {:on-submit [::save]})))

      (is (= [::form/reset :form] (form/on-reset :form)))
      (is (= [::form/reset :form] (form/on-reset f)))
      (is (= [::form/reset :my-form] (form/on-reset custom-f))))))

;; -----------------------------------------------------------------------------
;; 6. Field Registration & View Helper Tests
;; -----------------------------------------------------------------------------

(deftest register-field-test
  (let [f (form/create {:initial-values {:username "john"
                                         :email    ""
                                         :age      25
                                         :agree    true}})
        custom-f (form/create {:key :my-form
                               :initial-values {:bio "hello"}})]
    (testing "standard text input registration using form map directly"
      (let [attrs (form/register f :username {:type "text" :placeholder "Your name"})]
        (is (= "john" (:value attrs)))
        (is (= "text" (:type attrs)))
        (is (= "Your name" (:placeholder attrs)))
        (is (= [::form/change :form [:username]] (get-in attrs [:on :input])))
        (is (= [::form/change :form [:username]] (get-in attrs [:on :change])))
        (is (= [::form/blur :form [:username]] (get-in attrs [:on :blur])))))

    (testing "standard text input registration on custom form key"
      (let [attrs (form/register custom-f :bio {:type "text"})]
        (is (= "hello" (:value attrs)))
        (is (= [::form/change :my-form [:bio]] (get-in attrs [:on :input])))
        (is (= [::form/blur :my-form [:bio]] (get-in attrs [:on :blur])))))

    (testing "input with HTML validation constraint attributes without passing form key"
      (let [attrs (form/register f :email {:type        "email"
                                           :required    true
                                           :min-length  3
                                           :max-length  50
                                           :pattern     "^[a-z]+$"})]
        (is (= "" (:value attrs)))
        (is (= "email" (:type attrs)))
        (is (true? (:required attrs)))
        (is (= 3 (:minlength attrs)))
        (is (= 50 (:maxlength attrs)))
        (is (= "^[a-z]+$" (:pattern attrs)))
        (is (= [::form/change :form [:email]] (get-in attrs [:on :input])))))

    (testing "number input with min and max"
      (let [attrs (form/register f :age {:type "number" :min 18 :max 100})]
        (is (= 25 (:value attrs)))
        (is (= "number" (:type attrs)))
        (is (= 18 (:min attrs)))
        (is (= 100 (:max attrs)))))

    (testing "checkbox input registration without explicit form key"
      (let [attrs (form/register f :agree {:type "checkbox"})]
        (is (true? (:checked attrs)))
        (is (nil? (:value attrs)))
        (is (= "checkbox" (:type attrs)))
        (is (= [::form/change :form [:agree]] (get-in attrs [:on :change])))
        (is (= [::form/blur :form [:agree]] (get-in attrs [:on :blur])))
        (is (nil? (get-in attrs [:on :input])))))

    (testing "field alias produces identical output to register"
      (let [reg-attrs   (form/register f :username {:type "text"})
            field-attrs (form/field f :username {:type "text"})]
        (is (= reg-attrs field-attrs))))

    (testing "backwards compatibility: explicit form-key as 2nd argument"
      (let [attrs-3 (form/register f :explicit-key :username)
            attrs-4 (form/register f :explicit-key :username {:type "text"})]
        (is (= [::form/change :explicit-key [:username]] (get-in attrs-3 [:on :input])))
        (is (= [::form/change :explicit-key [:username]] (get-in attrs-4 [:on :input])))))))

(deftest register-validation-rules-and-lifecycle-test
  (testing "validators and custom validate defined solely in register"
    (let [f (form/create {:initial-values {:username         ""
                                           :email            ""
                                           :password         ""
                                           :confirm-password ""
                                           :age              ""}})]
      ;; Register fields in the view
      (form/register f :username {:type        "text"
                                  :required    "Username is required"
                                  :min-length  [3 "Username must be at least 3 characters"]})
      (form/register f :email {:type     "email"
                               :required "Email is required"
                               :email    "Please enter a valid email address"})
      (form/register f :password {:type       "password"
                                  :required   "Password is required"
                                  :min-length [6 "Password must be at least 6 characters"]})
      (form/register f :confirm-password {:type     "password"
                                          :required "Please confirm your password"
                                          :validate (fn [val values]
                                                      (when (and (seq (:password values))
                                                                 (seq val)
                                                                 (not= (:password values) val))
                                                        "Passwords do not match"))})
      (form/register f :age {:type "number"
                             :min  [18 "Must be at least 18 years old"]
                             :max  [120 "Max age is 120"]})

      ;; 1. Validate empty form
      (let [f-invalid (form/validate-form f)]
        (is (false? (form/valid? f-invalid)))
        (is (= "Username is required" (form/error f-invalid :username)))
        (is (= "Email is required" (form/error f-invalid :email)))
        (is (= "Password is required" (form/error f-invalid :password)))
        (is (= "Please confirm your password" (form/error f-invalid :confirm-password))))

      ;; 2. Update fields step by step
      (let [f1 (form/set-value f :username "jo")
            f2 (form/set-value f1 :username "john")
            f3 (form/set-value f2 :email "invalid-email")
            f4 (form/set-value f3 :email "john@example.com")
            f5 (form/set-value f4 :password "secret123")
            f6 (form/set-value f5 :confirm-password "different")
            f7 (form/set-value f6 :confirm-password "secret123")
            f8 (form/set-value f7 :age 15)
            f9 (form/set-value f8 :age 25)]
        ;; Short username error
        (is (= "Username must be at least 3 characters" (form/error f1 :username)))
        ;; Valid username
        (is (nil? (form/error f2 :username)))
        ;; Invalid email
        (is (= "Please enter a valid email address" (form/error f3 :email)))
        ;; Valid email
        (is (nil? (form/error f4 :email)))
        ;; Password mismatch via custom :validate
        (is (= "Passwords do not match" (form/error f6 :confirm-password)))
        ;; Matching passwords
        (is (nil? (form/error f7 :confirm-password)))
        ;; Underage error
        (is (= "Must be at least 18 years old" (form/error f8 :age)))
        ;; Valid age and complete valid form
        (is (nil? (form/error f9 :age)))
        (is (true? (form/valid? f9))))

      ;; 3. Submit flow with update handlers
      (let [initial-state {:form f :submitted nil}
            [sub-state1] (relm/update initial-state {} [::form/submit :form {:on-submit [::success]}] nil)
            form1 (:form sub-state1)]
        (is (false? (form/valid? form1)))
        (is (true? (form/touched? form1 :username)))
        (is (true? (form/touched? form1 :email)))
        (is (= "Username is required" (form/error form1 :username true)))
        (is (nil? (:submitted sub-state1)))

        ;; Fill valid values and submit
        (let [valid-form (-> f
                             (form/set-value :username "alice")
                             (form/set-value :email "alice@example.com")
                             (form/set-value :password "password123")
                             (form/set-value :confirm-password "password123")
                             (form/set-value :age 30))
              state-with-valid {:form valid-form :submitted nil}
              [sub-state2] (relm/update state-with-valid {} [::form/submit :form {:on-submit [::success]}] nil)
              form2 (:form sub-state2)]
          (is (true? (form/valid? form2)))
          (is (true? (form/submitting? form2)))
          (is (= 1 (form/submit-count form2))))))))

(deftest register-initial-values-test
  (testing "initial values and defaults defined solely in register with (form/create)"
    (let [f (form/create)]
      ;; Register fields in the view
      (let [u-attrs  (form/register f :username {:type "text" :default "johndoe" :required "Required"})
            e-attrs  (form/register f :email {:type "email" :initial-value "john@example.com"})
            n-attrs  (form/register f [:preferences :newsletter] {:type "checkbox" :default true})
            b-attrs  (form/register f [:profile :bio] {:type "text" :default "Developer"})]
        ;; Input attributes reflect initial values
        (is (= "johndoe" (:value u-attrs)))
        (is (= "john@example.com" (:value e-attrs)))
        (is (true? (:checked n-attrs)))
        (is (= "Developer" (:value b-attrs))))

      ;; Query functions reflect registered initial values
      (is (= "johndoe" (form/value f :username)))
      (is (= "john@example.com" (form/value f :email)))
      (is (true? (form/value f [:preferences :newsletter])))
      (is (= "Developer" (form/value f [:profile :bio])))

      ;; form/values and form/initial-values return complete merged map
      (is (= {:username "johndoe"
              :email "john@example.com"
              :preferences {:newsletter true}
              :profile {:bio "Developer"}}
             (form/values f)))
      (is (= {:username "johndoe"
              :email "john@example.com"
              :preferences {:newsletter true}
              :profile {:bio "Developer"}}
             (form/initial-values f)))

      ;; Pristine initially
      (is (false? (form/dirty? f)))
      (is (true? (form/pristine? f)))
      (is (false? (form/dirty? f :username)))
      (is (false? (form/dirty? f [:preferences :newsletter])))

      ;; Modifying field makes form dirty
      (let [f-mod (form/set-value f [:preferences :newsletter] false)]
        (is (true? (form/dirty? f-mod)))
        (is (true? (form/dirty? f-mod [:preferences :newsletter])))
        (is (false? (form/value f-mod [:preferences :newsletter])))
        (is (= {:username "johndoe"
                :email "john@example.com"
                :preferences {:newsletter false}
                :profile {:bio "Developer"}}
               (form/values f-mod)))

        ;; Resetting restores initial registered values
        (let [f-reset (form/reset-form f-mod)]
          (is (false? (form/dirty? f-reset)))
          (is (true? (form/value f-reset [:preferences :newsletter])))
          (is (= {:username "johndoe"
                  :email "john@example.com"
                  :preferences {:newsletter true}
                  :profile {:bio "Developer"}}
                 (form/values f-reset))))

        ;; ::form/reset update message restores initial registered values
        (let [[state-after-reset] (relm/update {:form f-mod} {} [::form/reset :form] nil)]
          (is (false? (form/dirty? (:form state-after-reset))))
          (is (true? (form/value (:form state-after-reset) [:preferences :newsletter]))))))))

(deftest form-navigation-and-isolation-test
  (testing "independent form instances sharing :form key isolate their validators and errors"
    ;; Step 1: FormExample component state with :username and :email required
    (let [form1 (form/create {:validators {:username (form/required "Username is required")
                                           :email    (form/required "Email is required")}})
          _ (form/register form1 :username {:required "Username is required"})
          _ (form/register form1 :email {:required "Email is required"})
          state1 {:form form1}
          ;; Trigger error in FormExample
          [invalid-state1] (relm/update state1 {} [::form/submit :form {:on-submit [::save]}] nil)]
      (is (= "Username is required" (form/error (:form invalid-state1) :username)))
      (is (= "Email is required" (form/error (:form invalid-state1) :email)))

      ;; Step 2: Navigate to QueryExample view, initializing a new form state for posts
      (let [form2 (form/create {:initial-values {:title "" :body ""}
                                :validators     {:title (form/required "Title is required")}})
            _ (form/register form2 :title {:required "Title is required"})
            _ (form/register form2 :body {})
            state2 {:form form2}]
        ;; Query form should not have username/email errors from FormExample
        (is (nil? (form/error (:form state2) :username)))
        (is (nil? (form/error (:form state2) :email)))
        (is (nil? (form/error (:form state2) :title)))

        ;; Touching or submitting in QueryExample MUST validate and produce error for post title
        (let [[invalid-state2] (relm/update state2 {} [::form/submit :form {:on-submit [::add-post]}] nil)]
          (is (= "Title is required" (form/error (:form invalid-state2) :title)))
          (is (nil? (form/error (:form invalid-state2) :username)))
          (is (nil? (form/error (:form invalid-state2) :email)))
          (is (false? (form/valid? (:form invalid-state2))))))))

  (testing "form-attrs attaches on-unmount clear message and clear removes form state"
    (let [form (form/create {:initial-values {:username "alice"}})
          attrs (form/form-attrs form {:class "form-class"})]
      (is (= "form-class" (:class attrs)))
      (is (= [::form/clear form] (:replicant/on-unmount attrs)))
      (let [state {:form form}
            [cleaned-state] (relm/update state {} (:replicant/on-unmount attrs) nil)]
        (is (nil? (:form cleaned-state)))))))
