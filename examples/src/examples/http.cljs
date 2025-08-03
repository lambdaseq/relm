(ns examples.http
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.http :as relm.http]))

; Example usage doing API calls to JSON placeholder

(defn init [_ _]
  {:posts []})

(defmethod relm/update ::posts-fetched
  [state context [_ {:keys [body]}] _event]
  (let [posts (js->clj (js/JSON.parse body) :keywordize-keys true)]
    [(assoc state :posts posts) context]))

(defmethod relm/update ::posts-failed
  [state context [_ _ error] _event]
  [state context])

(defmethod relm/update ::fetch-posts
  [state context _ _event]
  [state context [::relm.http/fetch
                  {:url        "https://jsonplaceholder.typicode.com/posts"
                   :method     :get
                   :mode       :cors
                   :on-success [::posts-fetched]}]])

(defn view [{:keys [posts]} _]
  [:div [:h1 "Posts"]
   (if (seq posts)
     [:ul
      (for [post posts]
        [:li [:a {:href (:url post)} (:title post)]])]
     [:p "No posts yet"])
   [:button {:on {:click [::fetch-posts]}}
    "Fetch Posts"]])


(def HttpExample
  (relm/component
    {:init init
     :view view}))

