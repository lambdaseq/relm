(ns examples.navigation
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.navigation :as nav]))

(defn init [_ _]
  {:current-url (.. js/window -location -href)
   :history-position 0})

(defmethod relm/update ::update-current-url
  [state context _ _]
  [(assoc state :current-url (.. js/window -location -href)) context])

(defmethod relm/update ::navigate-to
  [state context [_ url] _]
  [state context [::nav/navigate-to url]])

(defmethod relm/update ::reload-page
  [state context _ _]
  [state context [::nav/reload]])

(defmethod relm/update ::replace-url
  [state context [_ url] _]
  [state context [::nav/replace url]])

(defmethod relm/update ::go-back
  [state context _ _]
  [state context [::nav/back]])

(defmethod relm/update ::push-state
  [state context [_ url] _]
  [state context [::nav/push-state (js-obj "page" url) url]])

(defmethod relm/update ::replace-state
  [state context [_ url] _]
  [state context [::nav/replace-state (js-obj "page" url) url]])

(defmethod relm/update ::go-to-position
  [state context [_ n] _]
  [state context [::nav/go n]])

(defn view [{:keys [current-url]} _]
  [:div
   [:h1 "Navigation Example"]
   [:p "Current URL: " current-url]
   
   [:h2 "Basic Navigation"]
   [:div.button-group
    [:button {:on {:click [::navigate-to "https://github.com/lambdaseq/relm"]}}
     "Navigate to GitHub"]
    [:button {:on {:click [::reload-page]}}
     "Reload Page"]
    [:button {:on {:click [::replace-url "https://github.com/lambdaseq/relm"]}}
     "Replace URL"]]
   
   [:h2 "History Navigation"]
   [:div.button-group
    [:button {:on {:click [::go-back]}}
     "Go Back"]
    [:button {:on {:click [::push-state "/pushed-state"]}}
     "Push State"]
    [:button {:on {:click [::replace-state "/replaced-state"]}}
     "Replace State"]
    [:button {:on {:click [::go-to-position -1]}}
     "Go Back One Position"]
    [:button {:on {:click [::go-to-position 1]}}
     "Go Forward One Position"]]
   
   [:p "Note: After using navigation effects, you may need to use the browser's back button to return to this page."]])

(def NavigationExample
  (relm/component
   {:init init
    :view view}))