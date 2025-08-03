(ns com.lambdaseq.relm.navigation
  (:require [com.lambdaseq.relm.core :as core]))

(defmethod core/fx
  ::navigate-to
  [_ [_ url]]
  (.assign js/location url))

(defmethod core/fx
  ::reload
  [_ _]
  (.reload js/location))

(defmethod core/fx
  ::replace
  [_ [_ url]]
  (.replace js/location url))

(defmethod core/fx
  ::back
  [_ _]
  (.back js/history))

(defmethod core/fx
  ::push-state
  [_ [_ state url]]
  (.pushState js/history state nil url))

(defmethod core/fx
  ::replace-state
  [_ [_ state url]]
  (.replaceState js/history state nil url))

(defmethod core/fx
  ::go
  [_ [_ n]]
  (.go js/history n))