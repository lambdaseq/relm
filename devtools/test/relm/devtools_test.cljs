(ns relm.devtools-test
  "Unit tests for relm.devtools module: Redux DevTools serialization,
  action history, time travel, diffing, and pure replay."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [goog.object :as gobj]
            [relm.core :as relm]
            [relm.devtools :as devtools]))

;; -----------------------------------------------------------------------------
;; Test Handlers
;; -----------------------------------------------------------------------------

(defmethod relm/fx ::dummy-fx
  [_ _]
  nil)

(defmethod relm/update ::inc-count
  [state context [_ by] _event]
  [(update state :count (fnil + 0) (or by 1))
   context
   [[::dummy-fx "counted"]]])

(defmethod relm/update ::set-user-theme
  [state context [_ theme] _event]
  [state
   (assoc context :theme theme)
   [[::dummy-fx (str "theme-" (name theme))]]])

(defmethod relm/update ::ignore-action
  [state context _msg _event]
  [state context])

;; -----------------------------------------------------------------------------
;; Serialization Tests
;; -----------------------------------------------------------------------------

(deftest clj->js-data-test
  (testing "serializes primitives and collections"
    (is (nil? (devtools/clj->js-data nil)))
    (is (= 42 (devtools/clj->js-data 42)))
    (is (= "hello" (devtools/clj->js-data "hello")))
    (is (= ":a/b" (devtools/clj->js-data :a/b)))
    (is (= "my-sym" (devtools/clj->js-data 'my-sym))))

  (testing "serializes maps to JS objects with stringified keys"
    (let [res (devtools/clj->js-data {:name "Alice" :user/id 10})]
      (is (= "Alice" (gobj/get res "name")))
      (is (= 10 (gobj/get res "user/id")))))

  (testing "serializes sets and vectors to JS arrays"
    (let [v-res (devtools/clj->js-data [1 2 3])
          s-res (devtools/clj->js-data #{:a :b})]
      (is (= 3 (.-length v-res)))
      (is (= 1 (aget v-res 0)))
      (is (= 2 (.-length s-res)))))

  (testing "sanitizes non-serializable objects and DOM-like constructs safely"
    (is (= "#<Error:something broke>" (devtools/clj->js-data (js/Error. "something broke"))))
    (is (string? (devtools/clj->js-data #"^test.*")))
    (let [app-state {:context {:theme :dark}
                     :components {"c1" {:state {:count 1}}}
                     :root {:node "DOM-NODE" :component (fn []) :args {}}}
          sanitized (devtools/sanitize-state-for-devtools app-state)]
      (is (nil? (:root sanitized)))
      (is (= :dark (get-in sanitized [:context :theme])))
      (is (= 1 (get-in sanitized [:components "c1" :state :count]))))))

;; -----------------------------------------------------------------------------
;; State Diff Tests
;; -----------------------------------------------------------------------------

(deftest diff-state-test
  (testing "computes added, removed, and updated keys"
    (let [old-st {:a 1 :b 2 :c 3}
          new-st {:b 20 :c 3 :d 4}
          diff   (devtools/diff-state old-st new-st)]
      (is (= {:d 4} (:added diff)))
      (is (= {:a 1} (:removed diff)))
      (is (= {:b {:before 2 :after 20}} (:updated diff)))))

  (testing "identical states produce empty diff"
    (is (= {} (devtools/diff-state {:a 1 :b 2} {:a 1 :b 2})))))

;; -----------------------------------------------------------------------------
;; History & Dispatch Recording Tests
;; -----------------------------------------------------------------------------

(deftest devtools-recording-and-history-test
  (testing "records actions upon dispatch when connected"
    (reset! relm/!app-state {:context {} :components {"c1" {:state {:count 0}}} :root nil})
    (devtools/connect! {:name "Test App" :trace-effects? true})
    (is (devtools/connected?))

    (relm/dispatch! {:component-id "c1"} [::inc-count 5])
    (is (= 1 (count (devtools/history))))

    (let [entry (first (devtools/history))]
      (is (= ::inc-count (:action-type entry)))
      (is (= [::inc-count 5] (:message entry)))
      (is (= "c1" (:comp-id entry)))
      (is (= {:count 0} (:prev-state entry)))
      (is (= {:count 5} (:new-state entry)))
      (is (= [[::dummy-fx "counted"]] (:effects entry))))

    (relm/dispatch! {:component-id "c1"} [::inc-count 2])
    (is (= 2 (count (devtools/history))))
    (is (= 1 (devtools/current-index)))

    (devtools/disconnect!)
    (is (not (devtools/connected?)))
    ;; Dispatching after disconnect should not record
    (relm/dispatch! {:component-id "c1"} [::inc-count 1])
    (is (= 0 (count (devtools/history))))))

(deftest devtools-action-filtering-test
  (testing "respects blacklist and filter-fn"
    (reset! relm/!app-state {:context {} :components {"c1" {:state {:count 0}}} :root nil})
    (devtools/connect! {:actions-blacklist #{::ignore-action}
                        :max-age 2})

    (relm/dispatch! {:component-id "c1"} [::ignore-action])
    (is (= 0 (count (devtools/history))))

    (relm/dispatch! {:component-id "c1"} [::inc-count 1])
    (relm/dispatch! {:component-id "c1"} [::inc-count 2])
    (relm/dispatch! {:component-id "c1"} [::inc-count 3])
    ;; max-age is 2, so history should be trimmed to 2 entries
    (is (= 2 (count (devtools/history))))
    (devtools/disconnect!)))

;; -----------------------------------------------------------------------------
;; Time Travel Tests
;; -----------------------------------------------------------------------------

(deftest devtools-time-travel-test
  (testing "jump-to!, undo!, redo!, and reset-to-initial!"
    (reset! relm/!app-state {:context {} :components {"c1" {:state {:count 0}}} :root nil})
    (devtools/connect!)

    (relm/dispatch! {:component-id "c1"} [::inc-count 10])
    (relm/dispatch! {:component-id "c1"} [::inc-count 20])
    (relm/dispatch! {:component-id "c1"} [::set-user-theme :dark])

    (is (= 30 (get-in @relm/!app-state [:components "c1" :state :count])))
    (is (= :dark (get-in @relm/!app-state [:context :theme])))
    (is (devtools/can-undo?))
    (is (not (devtools/can-redo?)))

    ;; Undo last action (:set-user-theme)
    (devtools/undo!)
    (is (nil? (get-in @relm/!app-state [:context :theme])))
    (is (= 30 (get-in @relm/!app-state [:components "c1" :state :count])))
    (is (devtools/can-redo?))

    ;; Undo second action (second inc-count)
    (devtools/undo!)
    (is (= 10 (get-in @relm/!app-state [:components "c1" :state :count])))

    ;; Undo first action (first inc-count) -> back to initial
    (is (devtools/can-undo?))
    (devtools/undo!)
    (is (= 0 (get-in @relm/!app-state [:components "c1" :state :count])))
    (is (= -1 (devtools/current-index)))
    (is (not (devtools/can-undo?)))
    (is (devtools/can-redo?))

    ;; Redo
    (devtools/redo!)
    (is (= 10 (get-in @relm/!app-state [:components "c1" :state :count])))

    ;; Redo again
    (devtools/redo!)
    (is (= 30 (get-in @relm/!app-state [:components "c1" :state :count])))

    ;; Jump to initial
    (devtools/reset-to-initial!)
    (is (= 0 (get-in @relm/!app-state [:components "c1" :state :count])))

    (devtools/disconnect!)))

(deftest devtools-effects-and-filtering-test
  (testing "built-in devtools fx do not pollute history and mutate state cleanly"
    (reset! relm/!app-state {:context {} :components {"c1" {:state {:count 0}}} :root nil})
    (devtools/connect!)

    (relm/dispatch! {:component-id "c1"} [::inc-count 10])
    (relm/dispatch! {:component-id "c1"} [::inc-count 20])
    (is (= 2 (count (devtools/history))))

    ;; Dispatch time-travel effect via relm/fx
    (relm/fx nil [::devtools/jump-to! 0])
    (is (= 10 (get-in @relm/!app-state [:components "c1" :state :count])))
    (is (= 2 (count (devtools/history))))
    (is (= 0 (devtools/current-index)))

    ;; Dispatch toggle effect via relm/fx
    (relm/fx nil [::devtools/toggle-action! 0])
    (is (= 0 (get-in @relm/!app-state [:components "c1" :state :count])))
    (is (= 2 (count (devtools/history))))

    (devtools/disconnect!)))

(deftest devtools-jump-with-skipped-actions-test
  (testing "jump-to! respects skipped actions"
    (reset! relm/!app-state {:context {} :components {"c1" {:state {:count 0}}} :root nil})
    (devtools/connect!)

    (relm/dispatch! {:component-id "c1"} [::inc-count 10]) ;; action 0
    (relm/dispatch! {:component-id "c1"} [::inc-count 20]) ;; action 1
    (relm/dispatch! {:component-id "c1"} [::inc-count 30]) ;; action 2

    (is (= 60 (get-in @relm/!app-state [:components "c1" :state :count])))

    ;; Skip action 1 (+20)
    (devtools/toggle-action! 1)
    (is (= 40 (get-in @relm/!app-state [:components "c1" :state :count])))

    ;; Jump to action 0
    (devtools/jump-to! 0)
    (is (= 10 (get-in @relm/!app-state [:components "c1" :state :count])))

    ;; Jump to action 2 (should skip action 1 and compute 10 + 30 = 40)
    (devtools/jump-to! 2)
    (is (= 40 (get-in @relm/!app-state [:components "c1" :state :count])))

    (devtools/disconnect!)))

;; -----------------------------------------------------------------------------
;; Pure Replay & Toggle Tests
;; -----------------------------------------------------------------------------

(deftest devtools-replay-actions-test
  (testing "replay-actions computes final state without side effects"
    (let [base-state {:context {}
                      :components {"c1" {:state {:count 0}}}
                      :root nil}
          actions [{:comp-id "c1" :message [::inc-count 10]}
                   {:comp-id "c1" :message [::inc-count 5]}
                   {:comp-id nil  :message [::set-user-theme :light]}]
          {:keys [app-state effects]} (devtools/replay-actions base-state actions)]
      (is (= 15 (get-in app-state [:components "c1" :state :count])))
      (is (= :light (get-in app-state [:context :theme])))
      (is (= [[::dummy-fx "counted"]
              [::dummy-fx "counted"]
              [::dummy-fx "theme-light"]]
             effects)))))

(deftest devtools-toggle-action-test
  (testing "toggle-action! disables action and recalculates state"
    (reset! relm/!app-state {:context {} :components {"c1" {:state {:count 0}}} :root nil})
    (devtools/connect!)

    (relm/dispatch! {:component-id "c1"} [::inc-count 10]) ;; action 0
    (relm/dispatch! {:component-id "c1"} [::inc-count 20]) ;; action 1
    (relm/dispatch! {:component-id "c1"} [::inc-count 30]) ;; action 2

    (is (= 60 (get-in @relm/!app-state [:components "c1" :state :count])))

    ;; Skip action 1 (+20)
    (devtools/toggle-action! 1)
    (is (= 40 (get-in @relm/!app-state [:components "c1" :state :count])))
    (is (true? (:skipped? (get (devtools/history) 1))))

    ;; Re-enable action 1 (+20)
    (devtools/toggle-action! 1)
    (is (= 60 (get-in @relm/!app-state [:components "c1" :state :count])))
    (is (false? (:skipped? (get (devtools/history) 1))))

    (devtools/disconnect!)))
