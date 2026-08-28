(ns examples.main
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.navigation :as nav]
            [examples.counter :refer [Counter]]
            [examples.http :refer [HttpExample]]
            [examples.navigation :refer [NavigationExample]]
            [examples.nested :refer [NestedExample]]
            [reitit.core :as reitit]
            [replicant.dom :as r]))

(def routes
  [["/" {:name :home
         :path "/"
         :view (fn [] (NestedExample {}))}]
   ["/nested" {:name :nested
               :path "/nested"
               :view (fn [] (NestedExample {}))}]
   ["/counter" {:name :counter
                :path "/counter"
                :view (fn [] (Counter {:init-count 0}))}]
   ["/http" {:name :http
             :path "/http"
             :view (fn [] (HttpExample {}))}]
   ["/navigation" {:name :navigation
                   :path "/navigation"
                   :view (fn [] (NavigationExample {}))}]])

(def router
  (reitit/router routes))

(def default-match
  (reitit/match-by-path router "/nested"))

(defn match-path [path]
      (or (reitit/match-by-path router path)
          default-match))

(defn current-path []
      (if (exists? js/window)
        (.. js/window -location -pathname)
        "/"))

(defn match-current-path []
      (match-path (current-path)))

(defn init [_context _args]
      (let [match (match-current-path)]
           {:current-match match
            :current-route (get-in match [:data :name] :nested)}))

(defmethod relm/update ::navigate-to-route
           [state context [_ path] _event]
           (let [match (match-path path)
                 route-name (get-in match [:data :name] :nested)]
                [(assoc state :current-match match :current-route route-name)
                 context
                 [[::nav/push-state nil path]]]))

(defmethod relm/update ::route-changed
           [state context [_ match] _event]
           (let [route-name (get-in match [:data :name] :nested)]
                [(assoc state :current-match match :current-route route-name)
                 context]))

(defn view [{:keys [current-route current-match]} _context]
      (let [view-fn (get-in current-match [:data :view])]
           [:div {:style {:font-family "system-ui, -apple-system, sans-serif"
                          :padding     "20px"}}
            [:div {:style {:display        "flex"
                           :gap            "8px"
                           :margin-bottom  "20px"
                           :padding-bottom "12px"
                           :border-bottom  "1px solid #e5e7eb"}}
             [:button {:style {:padding          "8px 16px"
                               :border-radius    "6px"
                               :border           "1px solid #d1d5db"
                               :background-color (if (#{:nested :home} current-route) "#4f46e5" "#f3f4f6")
                               :color            (if (#{:nested :home} current-route) "#ffffff" "#111827")
                               :font-weight      (if (#{:nested :home} current-route) "600" "normal")
                               :cursor           "pointer"}
                       :on    {:click [::navigate-to-route "/nested"]}}
              "Nested Components"]
             [:button {:style {:padding          "8px 16px"
                               :border-radius    "6px"
                               :border           "1px solid #d1d5db"
                               :background-color (if (= current-route :counter) "#4f46e5" "#f3f4f6")
                               :color            (if (= current-route :counter) "#ffffff" "#111827")
                               :font-weight      (if (= current-route :counter) "600" "normal")
                               :cursor           "pointer"}
                       :on    {:click [::navigate-to-route "/counter"]}}
              "Counter"]
             [:button {:style {:padding          "8px 16px"
                               :border-radius    "6px"
                               :border           "1px solid #d1d5db"
                               :background-color (if (= current-route :http) "#4f46e5" "#f3f4f6")
                               :color            (if (= current-route :http) "#ffffff" "#111827")
                               :font-weight      (if (= current-route :http) "600" "normal")
                               :cursor           "pointer"}
                       :on    {:click [::navigate-to-route "/http"]}}
              "HTTP"]
             [:button {:style {:padding          "8px 16px"
                               :border-radius    "6px"
                               :border           "1px solid #d1d5db"
                               :background-color (if (= current-route :navigation) "#4f46e5" "#f3f4f6")
                               :color            (if (= current-route :navigation) "#ffffff" "#111827")
                               :font-weight      (if (= current-route :navigation) "600" "normal")
                               :cursor           "pointer"}
                       :on    {:click [::navigate-to-route "/navigation"]}}
              "Navigation"]]
            (if view-fn
              (view-fn)
              [:div {:style {:display         "flex"
                             :justify-content "center"
                             :align-items     "center"
                             :padding         "40px"}}
               [:div {:style {:color     "#6b7280"
                              :font-size "16px"}}
                "Loading..."]])]))

(def Examples
  (relm/component
    {:init init
     :view view}))

; Need to set `relm`'s dispatch function
(r/set-dispatch! relm/dispatch)

(when (exists? js/window)
      (.addEventListener js/window "popstate"
                         (fn [_]
                             (relm/dispatch {:component-id "examples-root"}
                                            [::route-changed (match-current-path)]))))

(relm/render js/document.body Examples {:id "examples-root"})
