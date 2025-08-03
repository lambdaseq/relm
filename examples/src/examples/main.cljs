(ns examples.main
  (:require [com.lambdaseq.relm.core :as relm]
            [examples.counter :refer [Counter]]
            [examples.http :refer [HttpExample]]
            [replicant.dom :as r]))

(defn init [_ _])

(defn view [_ _ _]
  #_(Counter {:init-count 0})
  (HttpExample :http-example {}))

(def Examples
  (relm/component
    {:init init
     :view view}))

; Need to set `relm`'s dispatch function
(r/set-dispatch! relm/dispatch)

; First argument is a globally unique component id and the second argument are the args that will be used by the init function
(r/render js/document.body (Examples :examples {}))
