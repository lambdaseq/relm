(ns relm.navigation
  "Browser navigation and History API side effects for Relm applications.

  Provides `core/fx` implementations for browser navigation actions:
  - `::navigate-to!`   Navigate to a new URL via `location.assign`
  - `::replace!`       Replace current URL without adding a history entry via `location.replace`
  - `::reload!`        Reload current page via `location.reload`
  - `::back!`          Move back one step in browser history via `history.back`
  - `::push-state!`    Push a new entry onto browser history stack via `history.pushState`
  - `::replace-state!` Replace current history entry via `history.replaceState`
  - `::go!`            Navigate by relative delta `n` via `history.go`"
  (:require [relm.core :as core]))

;; -----------------------------------------------------------------------------
;; Location / Page Navigation Effects
;; -----------------------------------------------------------------------------

;; Navigates to `url` using `window.location.assign(url)`.
;; Effect format: `[::navigate-to! url]`
(defmethod core/fx ::navigate-to!
  [_ [_ url]]
  (.assign js/location url))

;; Reloads the current page using `window.location.reload()`.
;; Effect format: `[::reload!]`
(defmethod core/fx ::reload!
  [_ _]
  (.reload js/location))

;; Replaces current URL using `window.location.replace(url)` without adding to session history.
;; Effect format: `[::replace! url]`
(defmethod core/fx ::replace!
  [_ [_ url]]
  (.replace js/location url))

;; -----------------------------------------------------------------------------
;; HTML5 History API Effects
;; -----------------------------------------------------------------------------

;; Navigates back one step in the browser session history using `window.history.back()`.
;; Effect format: `[::back!]`
(defmethod core/fx ::back!
  [_ _]
  (.back js/history))

;; Pushes a new state object and URL onto the browser history stack via `window.history.pushState(state, nil, url)`.
;; Effect format: `[::push-state! state-obj url]` or `[::push-state! nil url]`
(defmethod core/fx ::push-state!
  [_ [_ state url]]
  (.pushState js/history (if (and state (not (object? state))) (clj->js state) state) nil url))

;; Updates current history entry with new state object and URL via `window.history.replaceState(state, nil, url)`.
;; Effect format: `[::replace-state! state-obj url]` or `[::replace-state! nil url]`
(defmethod core/fx ::replace-state!
  [_ [_ state url]]
  (.replaceState js/history (if (and state (not (object? state))) (clj->js state) state) nil url))

;; Moves forward or backward through history by relative delta integer `n` via `window.history.go(n)`.
;; Effect format: `[::go! delta-int]`
(defmethod core/fx ::go!
  [_ [_ n]]
  (.go js/history n))