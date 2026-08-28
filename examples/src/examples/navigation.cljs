(ns examples.navigation
  "Navigation example component demonstrating browser and History API effects via `com.lambdaseq.relm.navigation`.

  Demonstrates:
  - Page redirection via `[::nav/navigate-to url]` (`location.assign`)
  - Page refresh via `[::nav/reload]` (`location.reload`)
  - URL replacement via `[::nav/replace url]` (`location.replace`)
  - Browser history stack manipulation via `[::nav/push-state ...]`, `[::nav/replace-state ...]`, `[::nav/back]`, and `[::nav/go n]`"
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.navigation :as nav]))

;; -----------------------------------------------------------------------------
;; Component Initialization
;; -----------------------------------------------------------------------------

(defn init
  "Initializes state with current window URL."
  [_context _args]
  {:current-url (if (exists? js/window) (.. js/window -location -href) "/")
   :history-position 0})

;; -----------------------------------------------------------------------------
;; Update Handlers
;; -----------------------------------------------------------------------------

;; Updates local state with the current URL string.
(defmethod relm/update ::update-current-url
  [state context _ _]
  [(assoc state :current-url (if (exists? js/window) (.. js/window -location -href) "/")) context])

;; Dispatches a navigation side effect to assign a new URL.
(defmethod relm/update ::navigate-to
  [state context [_ url] _]
  [state context [::nav/navigate-to url]])

;; Dispatches a page reload side effect.
(defmethod relm/update ::reload-page
  [state context _ _]
  [state context [::nav/reload]])

;; Dispatches a URL replace side effect without adding a history record.
(defmethod relm/update ::replace-url
  [state context [_ url] _]
  [state context [::nav/replace url]])

;; Dispatches a side effect to navigate back in browser history.
(defmethod relm/update ::go-back
  [state context _ _]
  [state context [::nav/back]])

;; Dispatches a push-state effect to push a new entry onto the HTML5 history stack.
(defmethod relm/update ::push-state
  [state context [_ url] _]
  [state context [::nav/push-state (js-obj "page" url) url]])

;; Dispatches a replace-state effect to update current history entry.
(defmethod relm/update ::replace-state
  [state context [_ url] _]
  [state context [::nav/replace-state (js-obj "page" url) url]])

;; Dispatches a history.go effect with relative step integer `n`.
(defmethod relm/update ::go-to-position
  [state context [_ n] _]
  [state context [::nav/go n]])

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defn view
  "Renders controls to test location and HTML5 history API side effects."
  [{:keys [current-url]} _context]
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

;; -----------------------------------------------------------------------------
;; Component Definition
;; -----------------------------------------------------------------------------

(def NavigationExample
  "Navigation Example component."
  (relm/component
   {:init init
    :view view}))