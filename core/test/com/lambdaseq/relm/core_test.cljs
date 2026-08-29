(ns com.lambdaseq.relm.core-test
  "Unit tests for Relm core runtime, state management, and side effects."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [com.lambdaseq.relm.core :as relm]))

;; -----------------------------------------------------------------------------
;; Test Handlers & Fixtures
;; -----------------------------------------------------------------------------

(def test-fx-log (atom []))

(defmethod relm/fx ::log-fx
  [_ [_ val]]
  (swap! test-fx-log conj val))

(defmethod relm/update ::test-single-fx
  [state context [_ val] _event]
  [(assoc state :msg-received val)
   context
   [[::log-fx val]]])

(defmethod relm/update ::test-multiple-fx
  [state context [_ val1 val2] _event]
  [(assoc state :msg-received [val1 val2])
   context
   [[::log-fx val1]
    [::log-fx val2]]])

(defmethod relm/update ::test-no-fx
  [state context [_ val] _event]
  [(assoc state :val val)
   context])

(defmethod relm/update ::test-empty-fx
  [state context [_ val] _event]
  [(assoc state :val val)
   context
   []])

(defmethod relm/update ::test-dispatch-fx
  [state context [_ follow-up-val] _event]
  [state
   context
   [[:dispatch [::test-no-fx follow-up-val]]]])

(defmethod relm/update ::test-namespaced-dispatch-fx
  [state context [_ follow-up-val] _event]
  [state
   context
   [[::relm/dispatch [::test-no-fx follow-up-val]]]])

(defmethod relm/update ::test-dispatch-n-fx
  [state context [_ val1 val2] _event]
  [state
   context
   [[:dispatch-n [[::test-no-fx val1]
                  [::test-no-fx val2]]]]])

(defmethod relm/update ::test-dispatch-later-fx
  [state context [_ val] _event]
  [state
   context
   [[:dispatch-later {:ms 0 :dispatch [::test-no-fx val]}]]])

;; -----------------------------------------------------------------------------
;; Unit Tests
;; -----------------------------------------------------------------------------

(deftest vector-of-vectors-test
  (testing "vector-of-vectors? correctly identifies nested vector batches"
    (is (true? (relm/vector-of-vectors? [[:a]])))
    (is (true? (relm/vector-of-vectors? [[:a 1] [:b 2]])))
    (is (false? (relm/vector-of-vectors? [:a 1])))
    (is (false? (relm/vector-of-vectors? [])))
    (is (false? (relm/vector-of-vectors? nil)))
    (is (false? (relm/vector-of-vectors? "not a vector")))))

(deftest update-effects-as-vector-of-vectors-test
  (testing "single effect in vector of vectors is executed"
    (reset! test-fx-log [])
    (let [event {:component-id "comp-1"}]
      (swap! relm/!app-state assoc-in [:components "comp-1" :state] {})
      (relm/dispatch! event [::test-single-fx "effect-1"])
      (is (= "effect-1" (get-in @relm/!app-state [:components "comp-1" :state :msg-received])))
      (is (= ["effect-1"] @test-fx-log))))

  (testing "multiple effects in vector of vectors are executed sequentially"
    (reset! test-fx-log [])
    (let [event {:component-id "comp-1"}]
      (swap! relm/!app-state assoc-in [:components "comp-1" :state] {})
      (relm/dispatch! event [::test-multiple-fx "fx-a" "fx-b"])
      (is (= ["fx-a" "fx-b"] (get-in @relm/!app-state [:components "comp-1" :state :msg-received])))
      (is (= ["fx-a" "fx-b"] @test-fx-log))))

  (testing "update handler returning no effects executes without side effects"
    (reset! test-fx-log [])
    (let [event {:component-id "comp-1"}]
      (relm/dispatch! event [::test-no-fx "pure-value"])
      (is (= "pure-value" (get-in @relm/!app-state [:components "comp-1" :state :val])))
      (is (= [] @test-fx-log))))

  (testing "update handler returning empty vector effects executes without side effects"
    (reset! test-fx-log [])
    (let [event {:component-id "comp-1"}]
      (relm/dispatch! event [::test-empty-fx "empty-fx-val"])
      (is (= "empty-fx-val" (get-in @relm/!app-state [:components "comp-1" :state :val])))
      (is (= [] @test-fx-log))))

  (testing ":dispatch effect triggers follow-up update message"
    (reset! test-fx-log [])
    (let [event {:component-id "comp-1"}]
      (relm/dispatch! event [::test-dispatch-fx "dispatched-value"])
      (is (= "dispatched-value" (get-in @relm/!app-state [:components "comp-1" :state :val])))))

  (testing "::relm/dispatch effect triggers follow-up update message"
    (reset! test-fx-log [])
    (let [event {:component-id "comp-1"}]
      (relm/dispatch! event [::test-namespaced-dispatch-fx "namespaced-dispatched-value"])
      (is (= "namespaced-dispatched-value" (get-in @relm/!app-state [:components "comp-1" :state :val])))))

  (testing ":dispatch-n effect triggers batch update messages"
    (reset! test-fx-log [])
    (let [event {:component-id "comp-1"}]
      (relm/dispatch! event [::test-dispatch-n-fx "first-n" "second-n"])
      (is (= "second-n" (get-in @relm/!app-state [:components "comp-1" :state :val])))))

  (testing ":dispatch-later effect triggers scheduled update message"
    (async done
      (let [event {:component-id "comp-later"}]
        (swap! relm/!app-state assoc-in [:components "comp-later" :state] {})
        (relm/dispatch! event [::test-dispatch-later-fx "later-value"])
        (js/setTimeout
          (fn []
            (is (= "later-value" (get-in @relm/!app-state [:components "comp-later" :state :val])))
            (done))
          20)))))

(deftest batch-dispatch-test
  (testing "dispatch accepts a batch of messages in vector-of-vectors form"
    (let [event {:component-id "comp-batch"}]
      (swap! relm/!app-state assoc-in [:components "comp-batch" :state] {})
      (relm/dispatch! event [[::test-no-fx "first"]
                            [::test-no-fx "second"]])
      (is (= "second" (get-in @relm/!app-state [:components "comp-batch" :state :val]))))))

(deftest built-in-fx-test
  (testing ":prevent-default effect calls preventDefault on event"
    (let [prevented? (atom false)
          mock-event {:replicant/dom-event {:preventDefault #(reset! prevented? true)}}]
      (relm/fx mock-event [:prevent-default])
      (is (true? @prevented?)))
    (let [prevented? (atom false)
          mock-event {:event {:preventDefault #(reset! prevented? true)}}]
      (relm/fx mock-event [::relm/prevent-default])
      (is (true? @prevented?))))

  (testing ":stop-propagation effect calls stopPropagation on event"
    (let [stopped? (atom false)
          mock-event {:replicant/dom-event {:stopPropagation #(reset! stopped? true)}}]
      (relm/fx mock-event [:stop-propagation])
      (is (true? @stopped?)))
    (let [stopped? (atom false)
          mock-event {:event {:stopPropagation #(reset! stopped? true)}}]
      (relm/fx mock-event [::relm/stop-propagation])
      (is (true? @stopped?))))

  (testing ":validate-async effect executes promise and dispatches result"
    (async done
      (let [event {:component-id "comp-async"}]
        (swap! relm/!app-state assoc-in [:components "comp-async" :state] {})
        (relm/fx event [:validate-async
                        {:path :test-field
                         :validator (fn [] (js/Promise.resolve "resolved-value"))
                         :on-success (fn [res] [::test-no-fx res])}])
        (js/setTimeout
          (fn []
            (is (= "resolved-value" (get-in @relm/!app-state [:components "comp-async" :state :val])))
            (done))
          20)))))
