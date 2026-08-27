(ns examples.main
  (:require [com.lambdaseq.relm.core :as relm]
            [examples.counter :refer [Counter]]
            [examples.http :refer [HttpExample]]
            [examples.navigation :refer [NavigationExample]]
            [examples.nested :refer [NestedExample]]
            [replicant.dom :as r]))

(defn init [_context _args]
  {:current-example :nested})

(defmethod relm/update ::select-example
  [state context [_ example] _event]
  [(assoc state :current-example example) context])

(defn view [{:keys [current-example]} _context]
  [:div {:style {:font-family "system-ui, -apple-system, sans-serif"
                 :padding "20px"}}
   [:div {:style {:display "flex"
                  :gap "8px"
                  :margin-bottom "20px"
                  :padding-bottom "12px"
                  :border-bottom "1px solid #e5e7eb"}}
    [:button {:style {:padding "8px 16px"
                      :border-radius "6px"
                      :border "1px solid #d1d5db"
                      :background-color (if (= current-example :nested) "#4f46e5" "#f3f4f6")
                      :color (if (= current-example :nested) "#ffffff" "#111827")
                      :font-weight (if (= current-example :nested) "600" "normal")
                      :cursor "pointer"}
              :on {:click [::select-example :nested]}}
     "Nested Components"]
    [:button {:style {:padding "8px 16px"
                      :border-radius "6px"
                      :border "1px solid #d1d5db"
                      :background-color (if (= current-example :counter) "#4f46e5" "#f3f4f6")
                      :color (if (= current-example :counter) "#ffffff" "#111827")
                      :font-weight (if (= current-example :counter) "600" "normal")
                      :cursor "pointer"}
              :on {:click [::select-example :counter]}}
     "Counter"]
    [:button {:style {:padding "8px 16px"
                      :border-radius "6px"
                      :border "1px solid #d1d5db"
                      :background-color (if (= current-example :http) "#4f46e5" "#f3f4f6")
                      :color (if (= current-example :http) "#ffffff" "#111827")
                      :font-weight (if (= current-example :http) "600" "normal")
                      :cursor "pointer"}
              :on {:click [::select-example :http]}}
     "HTTP"]
    [:button {:style {:padding "8px 16px"
                      :border-radius "6px"
                      :border "1px solid #d1d5db"
                      :background-color (if (= current-example :navigation) "#4f46e5" "#f3f4f6")
                      :color (if (= current-example :navigation) "#ffffff" "#111827")
                      :font-weight (if (= current-example :navigation) "600" "normal")
                      :cursor "pointer"}
              :on {:click [::select-example :navigation]}}
     "Navigation"]]
   (case current-example
     :nested (NestedExample {})
     :counter (Counter {:init-count 0})
     :http (HttpExample {})
     :navigation (NavigationExample {}))])

(def Examples
  (relm/component
    {:init init
     :view view}))

; Need to set `relm`'s dispatch function
(r/set-dispatch! relm/dispatch)

(relm/render js/document.body Examples)
