(ns relm.core-test
  "Unit tests for Relm core runtime, state management, and side effects."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [relm.core :as relm]))

;; -----------------------------------------------------------------------------
;; Test Handlers & Fixtures
;; -----------------------------------------------------------------------------

(def test-fx-log (atom []))

(defmethod relm/fx ::log-fx!
  [_ [_ val]]
  (swap! test-fx-log conj val))

(defmethod relm/update ::test-single-fx
  [state context [_ val] _event]
  [(assoc state :msg-received val)
   context
   [[::log-fx! val]]])

(defmethod relm/update ::test-multiple-fx
  [state context [_ val1 val2] _event]
  [(assoc state :msg-received [val1 val2])
   context
   [[::log-fx! val1]
    [::log-fx! val2]]])

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
   [[::relm/dispatch! [::test-no-fx follow-up-val]]]])

(defmethod relm/update ::test-namespaced-dispatch-fx
  [state context [_ follow-up-val] _event]
  [state
   context
   [[::relm/dispatch! [::test-no-fx follow-up-val]]]])

(defmethod relm/update ::test-dispatch-n-fx
  [state context [_ val1 val2] _event]
  [state
   context
   [[::relm/dispatch-n! [[::test-no-fx val1]
                         [::test-no-fx val2]]]]])

(defmethod relm/update ::test-dispatch-later-fx
  [state context [_ val] _event]
  [state
   context
   [[::relm/dispatch-later! {:ms 0 :dispatch! [::test-no-fx val]}]]])

(defmethod relm/update ::increment-test-count
  [state context _message _event]
  [(update state :count (fnil inc 0)) context])

(defmethod relm/update ::set-child-val
  [state context [_ child-id val] _event]
  (swap! relm/!app-state assoc-in [:components child-id :state :val] val)
  [state context])

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

  (testing "::relm/dispatch! effect triggers follow-up update message"
    (reset! test-fx-log [])
    (let [event {:component-id "comp-1"}]
      (relm/dispatch! event [::test-dispatch-fx "dispatched-value"])
      (is (= "dispatched-value" (get-in @relm/!app-state [:components "comp-1" :state :val])))))

  (testing "::relm/dispatch! effect supports explicit target component map"
    (let [event {:component-id "comp-origin"}]
      (swap! relm/!app-state assoc-in [:components "comp-target" :state] {})
      (relm/fx event [::relm/dispatch! {:component-id "comp-target"} [::test-no-fx "targeted-value"]])
      (is (= "targeted-value" (get-in @relm/!app-state [:components "comp-target" :state :val])))))

  (testing "::relm/dispatch-n! effect supports explicit target component map"
    (let [event {:component-id "comp-origin"}]
      (swap! relm/!app-state assoc-in [:components "comp-target-n" :state] {})
      (relm/fx event [::relm/dispatch-n! {:component-id "comp-target-n"}
                      [[::test-no-fx "targeted-n-1"]
                       [::test-no-fx "targeted-n-2"]]])
      (is (= "targeted-n-2" (get-in @relm/!app-state [:components "comp-target-n" :state :val])))))

  (testing "event preserves :component-id through side effects when originating from DOM node"
    (let [dummy-node #js {:getAttribute (fn [attr] (when (= attr "data-relm-component-id") "comp-from-dom"))
                          :parentNode nil}
          event {:replicant/node dummy-node}]
      (swap! relm/!app-state assoc-in [:components "comp-from-dom" :state] {})
      (relm/dispatch! event [::test-dispatch-fx "dispatched-from-dom"])
      (is (= "dispatched-from-dom" (get-in @relm/!app-state [:components "comp-from-dom" :state :val])))))

  (testing "::relm/dispatch-n! effect triggers batch update messages"
    (reset! test-fx-log [])
    (let [event {:component-id "comp-1"}]
      (relm/dispatch! event [::test-dispatch-n-fx "first-n" "second-n"])
      (is (= "second-n" (get-in @relm/!app-state [:components "comp-1" :state :val])))))

  (testing "::relm/dispatch-later! effect triggers scheduled update message"
    (async done
           (let [event {:component-id "comp-later"}]
             (swap! relm/!app-state assoc-in [:components "comp-later" :state] {})
             (relm/dispatch! event [::test-dispatch-later-fx "later-value"])
             (js/setTimeout
              (fn []
                (is (= "later-value" (get-in @relm/!app-state [:components "comp-later" :state :val])))
                (done))
              20))))

  (testing "::relm/dispatch-later! effect supports target event map in item"
    (async done
           (let [event {:component-id "comp-origin"}]
             (swap! relm/!app-state assoc-in [:components "comp-later-target" :state] {})
             (relm/fx event [::relm/dispatch-later!
                             {:ms 0
                              :event {:component-id "comp-later-target"}
                              :dispatch! [::test-no-fx "targeted-later-val"]}])
             (js/setTimeout
              (fn []
                (is (= "targeted-later-val" (get-in @relm/!app-state [:components "comp-later-target" :state :val])))
                (done))
              20)))))

(deftest batch-dispatch-test
  (testing "dispatch accepts a batch of messages in vector-of-vectors form"
    (let [event {:component-id "comp-batch"}]
      (swap! relm/!app-state assoc-in [:components "comp-batch" :state] {})
      (relm/dispatch! event [[::test-no-fx "first"]
                             [::test-no-fx "second"]])
      (is (= "second" (get-in @relm/!app-state [:components "comp-batch" :state :val]))))))

(deftest component-lifecycle-hooks-test
  (testing "component renders and invokes on-init hook with state, context, and effects"
    (reset! test-fx-log [])
    (let [comp (relm/component
                {:init (fn [_ctx {:keys [initial-val]}]
                         {:count (or initial-val 0)})
                 :on-init (fn [state context {:keys [tag]} _event]
                            [(assoc state :tag tag)
                             (assoc context :app-initialized? true)
                             [[::log-fx! (str "initialized-" tag)]]])
                 :view (fn [{:keys [count tag]} ctx]
                         [:div {:id "rendered"} (str "Count: " count ", tag: " tag ", ctx: " (:app-initialized? ctx))])})
          hiccup (comp {:id "test-comp-1" :initial-val 42 :tag "alpha"})]
      (is (vector? hiccup))
      (is (= "test-comp-1" (:replicant/key (second hiccup))))
      (is (= "test-comp-1" (:data-relm-component-id (second hiccup))))
      (is (= {:count 42 :tag "alpha"} (get-in @relm/!app-state [:components "test-comp-1" :state])))
      (is (true? (:app-initialized? (:context @relm/!app-state))))
      (is (= ["initialized-alpha"] @test-fx-log))))

  (testing "component invokes on-deinit hook on ::deinit-component message"
    (reset! test-fx-log [])
    (let [comp (relm/component
                {:init (fn [_ctx _args] {:active true})
                 :on-deinit (fn [_state context _args _event]
                              [nil
                               (assoc context :cleaned-up? true)
                               [[::log-fx! "deinitialized"]]])
                 :view (fn [_state _ctx] [:div "active"])})]
      (comp {:id "test-comp-2"})
      (is (some? (get-in @relm/!app-state [:components "test-comp-2"])))
      (relm/dispatch! nil [::relm/deinit-component "test-comp-2"])
      (is (nil? (get-in @relm/!app-state [:components "test-comp-2"])))
      (is (true? (:cleaned-up? (:context @relm/!app-state))))
      (is (= ["deinitialized"] @test-fx-log))))

  (testing "::init-component and ::deinit-component are standard update multimethod handlers"
    (let [init-res (relm/update nil {:ctx :val} [::relm/init-component "my-comp" {:count 10}] nil)]
      (is (= [{:count 10} {:ctx :val}] init-res)))
    (let [init-res-existing (relm/update {:count 20} {:ctx :val} [::relm/init-component "my-comp" {:count 10}] nil)]
      (is (= [{:count 20} {:ctx :val}] init-res-existing)))
    (let [deinit-res (relm/update {:count 10} {:ctx :val} [::relm/deinit-component "my-comp"] nil)]
      (is (= [nil {:ctx :val} [[::relm/deinit-component "my-comp"]]] deinit-res))))

  (testing "::init-component message dispatched updates state in !app-state"
    (relm/dispatch! nil [::relm/init-component "manual-init-comp" {:initialized? true}])
    (is (= {:initialized? true} (get-in @relm/!app-state [:components "manual-init-comp" :state]))))

  (testing "::init-component and ::deinit-component fx handlers update !app-state"
    (swap! relm/!app-state assoc :components {})
    (relm/fx nil [::relm/init-component "fx-comp" {:from-fx true}])
    (is (= {:from-fx true} (get-in @relm/!app-state [:components "fx-comp" :state])))
    (relm/fx nil [::relm/deinit-component "fx-comp"])
    (is (nil? (get-in @relm/!app-state [:components "fx-comp"])))))

(deftest built-in-fx-test
  (testing "::relm/prevent-default! effect calls preventDefault on event"
    (let [prevented? (atom false)
          mock-event {:replicant/dom-event #js {:preventDefault #(reset! prevented? true)}}]
      (relm/fx mock-event [::relm/prevent-default!])
      (is (true? @prevented?))))

  (testing "::relm/stop-propagation! effect calls stopPropagation on event"
    (let [stopped? (atom false)
          mock-event {:replicant/dom-event #js {:stopPropagation #(reset! stopped? true)}}]
      (relm/fx mock-event [::relm/stop-propagation!])
      (is (true? @stopped?))))

  (testing "::relm/validate-async! effect executes promise and dispatches result"
    (async done
           (let [event {:component-id "comp-async"}]
             (swap! relm/!app-state assoc-in [:components "comp-async" :state] {})
             (relm/fx event [::relm/validate-async!
                             {:path :test-field
                              :validator (fn [] (js/Promise.resolve "resolved-value"))
                              :on-success (fn [res] [::test-no-fx res])}])
             (js/setTimeout
              (fn []
                (is (= "resolved-value" (get-in @relm/!app-state [:components "comp-async" :state :val])))
                (done))
              20)))))

(deftest component-instance-id-uniqueness-test
  (testing "multiple instances of the same component without explicit IDs receive unique, stable IDs"
    (let [item-comp (relm/component
                     {:init (fn [_ctx {:keys [initial-count] :or {initial-count 0}}]
                              {:count initial-count})
                      :view (fn [{:keys [count]} _ctx]
                              [:span (str "Count: " count)])})
          parent-comp (relm/component
                       {:view (fn [_state _ctx]
                                [:div
                                 (item-comp {:initial-count 10})
                                 (item-comp {:initial-count 20})
                                 (item-comp {:id "explicit-item" :initial-count 30})])})
          ;; Render parent component tree within root render evaluation
          hiccup (relm/-eval-root parent-comp {})
          child-1 (nth hiccup 2)
          child-2 (nth hiccup 3)
          child-3 (nth hiccup 4)
          id-1 (:data-relm-component-id (second child-1))
          id-2 (:data-relm-component-id (second child-2))
          id-3 (:data-relm-component-id (second child-3))]
      ;; Instances without ID receive unique IDs; explicit ID is preserved
      (is (some? id-1))
      (is (some? id-2))
      (is (= "explicit-item" id-3))
      (is (not= id-1 id-2))
      (is (not= id-1 id-3))
      ;; States in !app-state must be isolated
      (is (= 10 (get-in @relm/!app-state [:components id-1 :state :count])))
      (is (= 20 (get-in @relm/!app-state [:components id-2 :state :count])))
      (is (= 30 (get-in @relm/!app-state [:components id-3 :state :count])))

      ;; Re-rendering the tree produces the exact same IDs and preserves state
      (let [hiccup-2 (relm/-eval-root parent-comp {})
            child-1-re (nth hiccup-2 2)
            child-2-re (nth hiccup-2 3)
            child-3-re (nth hiccup-2 4)
            id-1-re (:data-relm-component-id (second child-1-re))
            id-2-re (:data-relm-component-id (second child-2-re))
            id-3-re (:data-relm-component-id (second child-3-re))]
        (is (= id-1 id-1-re))
        (is (= id-2 id-2-re))
        (is (= "explicit-item" id-3-re))
        (is (= 10 (get-in @relm/!app-state [:components id-1-re :state :count])))
        (is (= 20 (get-in @relm/!app-state [:components id-2-re :state :count])))
        (is (= 30 (get-in @relm/!app-state [:components id-3-re :state :count])))))))

(deftest render-queue-and-batching-test
  (testing "flush-render! executes pending render passes synchronously"
    (let [rendered-count (atom 0)
          test-comp (fn []
                      (swap! rendered-count inc)
                      [:div "rendered"])]
      (relm/render nil test-comp)
      (is (= 1 @rendered-count))
      (relm/flush-render!)
      (is (>= @rendered-count 1))))

  (testing "re-entrant state changes during render are not dropped"
    (let [render-passes (atom 0)
          trigger-comp (relm/component
                        {:init (fn [_ctx _args] {:step 1})
                         :on-init (fn [state context _args _event]
                                    ;; Update global context during init/mount
                                    [(assoc state :mounted? true)
                                     (assoc context :re-entrant-triggered? true)
                                     nil])
                         :view (fn [{:keys [step mounted?]} ctx]
                                 (swap! render-passes inc)
                                 [:div (str "Step: " step ", mounted: " mounted? ", ctx: " (:re-entrant-triggered? ctx))])})
          root-comp (relm/component
                     {:view (fn [_state _ctx]
                              [:div (trigger-comp)])})]
      (relm/render nil root-comp)
      (relm/flush-render!)
      (is (true? (:re-entrant-triggered? (:context @relm/!app-state))))
      (is (>= @render-passes 1))))

  (testing "rapid synchronous dispatches update state and render once flushed"
    (let [render-count (atom 0)
          counter-comp (relm/component
                        {:init (fn [_ _] {:count 0})
                         :view (fn [{:keys [count]} _]
                                 (swap! render-count inc)
                                 [:div (str "Count: " count)])})]
      (relm/render nil counter-comp {:id "batch-counter"})
      (let [initial-renders @render-count]
        (relm/dispatch! {:component-id "batch-counter"}
                        [[::test-no-fx 10]
                         [::test-no-fx 20]
                         [::test-no-fx 30]])
        (is (= 30 (get-in @relm/!app-state [:components "batch-counter" :state :val])))
        (relm/flush-render!)
        (is (> @render-count initial-renders)))))

  (testing "large burst of 1,000 synchronous dispatches updates state accurately and renders once"
    (let [render-count (atom 0)
          burst-comp (relm/component
                      {:init (fn [_ _] {:count 0})
                       :view (fn [{:keys [count]} _]
                               (swap! render-count inc)
                               [:div {:id "burst-count"} (str "Count: " count)])})]
      (relm/render nil burst-comp {:id "burst-test-comp"})
      (let [initial-renders @render-count]
        (dotimes [_ 1000]
          (relm/dispatch! {:component-id "burst-test-comp"} [::increment-test-count]))
        ;; All 1,000 dispatches must be applied to state
        (is (= 1000 (get-in @relm/!app-state [:components "burst-test-comp" :state :count])))
        ;; Synchronous DOM render hasn't run 1,000 times
        (relm/flush-render!)
        ;; Renders cleanly to final state
        (is (<= @render-count (+ initial-renders 2))))))

  (testing "concurrent updates across multiple child components coalesce without conflicts"
    (let [child-renders (atom {})
          child-comp (relm/component
                      {:init (fn [_ {:keys [id]}] {:id id :val 0})
                       :view (fn [{:keys [id val]} _]
                               (swap! child-renders update id (fnil inc 0))
                               [:span {:id id} (str val)])})
          root-comp (relm/component
                     {:view (fn [_state _ctx]
                              [:div
                               (child-comp {:id "c1"})
                               (child-comp {:id "c2"})
                               (child-comp {:id "c3"})])})]
      (relm/render nil root-comp)
      (relm/flush-render!)
      (relm/dispatch! nil
                      [[::test-no-fx]
                       [::set-child-val "c1" 10]
                       [::set-child-val "c2" 20]
                       [::set-child-val "c3" 30]])
      (relm/flush-render!)
      (is (= 10 (get-in @relm/!app-state [:components "c1" :state :val])))
      (is (= 20 (get-in @relm/!app-state [:components "c2" :state :val])))
      (is (= 30 (get-in @relm/!app-state [:components "c3" :state :val]))))))

(deftest dispatch-listener-test
  (testing "add-dispatch-listener! receives event data and state changes"
    (let [dispatches (atom [])
          listener (fn [data] (swap! dispatches conj data))]
      (relm/add-dispatch-listener! ::test listener)
      (swap! relm/!app-state assoc-in [:components "test-comp" :state] {:count 0})
      (relm/dispatch! {:component-id "test-comp"} [::increment-test-count])
      (is (= 1 (count @dispatches)))
      (let [entry (first @dispatches)]
        (is (= [::increment-test-count] (:message entry)))
        (is (= "test-comp" (:comp-id entry)))
        (is (= {:count 0} (:prev-state entry)))
        (is (= {:count 1} (:new-state entry))))
      (relm/remove-dispatch-listener! ::test)
      (relm/dispatch! {:component-id "test-comp"} [::increment-test-count])
      (is (= 1 (count @dispatches))))))

(deftest cross-component-send-test
  (testing "::send event updates target component state with a single message"
    (swap! relm/!app-state assoc-in [:components "sender-comp" :state] {:role "sender"})
    (swap! relm/!app-state assoc-in [:components "target-comp" :state] {:count 5})
    (relm/dispatch! {:component-id "sender-comp"}
                    [::relm/send "target-comp" [::increment-test-count]])
    (is (= {:role "sender"} (get-in @relm/!app-state [:components "sender-comp" :state])))
    (is (= {:count 6} (get-in @relm/!app-state [:components "target-comp" :state]))))

  (testing "::send event updates target component state with a vector of messages"
    (swap! relm/!app-state assoc-in [:components "target-comp" :state] {:count 10})
    (relm/dispatch! {:component-id "sender-comp"}
                    [::relm/send "target-comp" [[::increment-test-count]
                                                [::increment-test-count]
                                                [::increment-test-count]]])
    (is (= {:count 13} (get-in @relm/!app-state [:components "target-comp" :state]))))

  (testing "::send effect dispatches ::send message"
    (reset! test-fx-log [])
    (swap! relm/!app-state assoc-in [:components "target-comp" :state] {:count 20})
    (relm/-dispatch-fx! {:component-id "sender-comp"}
                        [[::relm/send "target-comp" [::increment-test-count]]])
    (is (= {:count 21} (get-in @relm/!app-state [:components "target-comp" :state]))))

  (testing "::send with effects returned from target component updates target"
    (reset! test-fx-log [])
    (swap! relm/!app-state assoc-in [:components "target-comp" :state] {:msg-received nil})
    (relm/dispatch! {:component-id "sender-comp"}
                    [::relm/send "target-comp" [::test-single-fx 42]])
    (is (= {:msg-received 42} (get-in @relm/!app-state [:components "target-comp" :state])))
    (is (= [42] @test-fx-log))))

(deftest component-id-option-test
  (testing "component created with :component-id uses that identifier"
    (let [comp (relm/component
                {:component-id "my-custom-component"
                 :init (fn [_ _] {:initialized true})
                 :view (fn [state _] [:div (str "Init: " (:initialized state))])})
          hiccup (comp)]
      (is (= "my-custom-component" (:data-relm-component-id (second hiccup)))))))
