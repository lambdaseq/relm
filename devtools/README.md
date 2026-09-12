# relm.devtools

[![Clojars Project](https://img.shields.io/clojars/v/io.github.conjurernix/relm.devtools.svg)](https://clojars.org/io.github.conjurernix/relm.devtools)

`io.github.conjurernix/relm.devtools` provides time-travel debugging, action inspection, state diffing, and Redux DevTools browser extension integration for Relm applications.

## Table of Contents

- [Installation](#installation)
- [Overview & Architecture](#overview--architecture)
- [Quick Start](#quick-start)
- [Configuration Options](#configuration-options)
- [Time-Travel & History API](#time-travel--history-api)
- [Pure State Replay & Action Skipping](#pure-state-replay--action-skipping)
- [Browser Console Logger](#browser-console-logger)
- [Serialization Utilities](#serialization-utilities)

---

## Installation

Add `io.github.conjurernix/relm.devtools` and `io.github.conjurernix/relm.core` to your `deps.edn`:

```clojure
{:deps {io.github.conjurernix/relm.core     {:mvn/version "0.1.0"}
        io.github.conjurernix/relm.devtools {:mvn/version "0.1.0"}}}
```

For Leiningen / `project.clj`:

```clojure
[io.github.conjurernix/relm.core "0.1.0"]
[io.github.conjurernix/relm.devtools "0.1.0"]
```

---

## Overview & Architecture

Because Relm's state transitions `(update state context message event) -> [new-state new-context effects]` are pure, state evolution is 100% deterministic and replayable.

`relm.devtools` connects to the Redux DevTools extension (`window.__REDUX_DEVTOOLS_EXTENSION__`) and registers a transparent dispatch listener in `relm.core`.

```
                +------------------------------------+
                |        relm/dispatch! [msg]        |
                +------------------------------------+
                                  |
                                  v
                +------------------------------------+
                |       relm/update multimethod      |
                +------------------------------------+
                                  |
                   +--------------+--------------+
                   |                             |
                   v                             v
      +------------------------+   +---------------------------+
      |  Update !app-state     |   | relm.devtools/record-action!|
      +------------------------+   +---------------------------+
                   |                             |
                   v                             +-----------------------+
      +------------------------+                 |                       |
      |   Replicant Re-render  |                 v                       v
      +------------------------+     +-----------------------+ +--------------------+
                                     |  Redux DevTools Bridge| |  Console Logger    |
                                     +-----------------------+ +--------------------+
```

### Key Highlights

- **Redux DevTools Extension Bridge**: Automatically serializes dispatched messages, component states, global context, and returned side effects to standard browser DevTools.
- **Deterministic Time Travel**: Step forward, step backward, or jump directly to any prior snapshot without re-running side effects.
- **Action History Buffer**: In-memory ring buffer tracking action history, timestamps, durations, and state diffs.
- **Pure Replay & Action Toggling**: Skip or disable specific actions and recompute subsequent application states from a baseline.
- **Console Inspection**: Expandable, styled browser console logs detailing message payloads, component IDs, state transitions, context diffs, and side effects.

---

## Quick Start

Initialize DevTools in your application's entry point:

```clojure
(ns my-app.main
  (:require [relm.core :as relm]
            [relm.devtools :as devtools]
            [replicant.dom :as r]))

;; 1. Connect DevTools during development
(devtools/connect! {:name            "My Admin App"
                    :trace-effects?  true
                    :log-to-console? true})

;; 2. Standard Relm bootstrap
(r/set-dispatch! relm/dispatch!)
(relm/render! js/document.body AppRoot)
```

---

## Configuration Options

Pass configuration options to `devtools/connect!`:

```clojure
(devtools/connect!
 {:name               "Relm Application"  ;; App name displayed in DevTools
  :max-age            50                  ;; Maximum actions kept in history buffer
  :trace-effects?     true                ;; Include side-effect vectors in action entries
  :log-to-console?    false               ;; Log action dispatches to browser console
  :collapsed?         true                ;; Collapse console log groups by default
  :filter-fn          (fn [msg-type] ...) ;; Custom predicate to filter recorded messages
  :actions-blacklist  #{::tick ::anim}    ;; Ignore specific message types
  :actions-whitelist  #{}})               ;; Exclusively record specific message types
```

---

## Time-Travel & History API

Control application state programmatically using the DevTools API:

```clojure
;; Inspect recorded actions
(devtools/history)
;; => [{:id 0 :action-type ::inc :message [::inc 5] :comp-id "c1" :prev-state {:count 0} :new-state {:count 5} ...}]

;; Query current position in history
(devtools/current-index)

;; Check navigation availability
(devtools/can-undo?) ;; => true
(devtools/can-redo?) ;; => false

;; Step backward in history (undo state change)
(devtools/undo!)

;; Step forward in history (redo state change)
(devtools/redo!)

;; Jump directly to a specific action index in history
(devtools/jump-to! 2)

;; Reset application state to initial baseline
(devtools/reset-to-initial!)

;; Commit current state as new baseline and clear history
(devtools/commit!)

;; Clear history
(devtools/clear-history!)

;; Disconnect and remove listeners
(devtools/disconnect!)
```

---

## Pure State Replay & Action Skipping

Replay a series of message actions on top of a base state without executing side effects:

```clojure
(let [base-state {:context {}
                  :components {"counter-1" {:state {:count 0}}}
                  :root nil}
      actions [{:comp-id "counter-1" :message [::inc 10]}
               {:comp-id "counter-1" :message [::inc 5]}]
      {:keys [app-state effects]} (devtools/replay-actions base-state actions)]
  (get-in app-state [:components "counter-1" :state :count]))
;; => 15
```

Skip or disable an action in history and recalculate state:

```clojure
;; Toggle action at index 1 (skips or unskips)
(devtools/toggle-action! 1)
```

---

## Browser Console Logger

Enable console logging by setting `:log-to-console? true`:

```clojure
(devtools/connect! {:log-to-console? true :collapsed? true})
```

Output includes:
- Action message type and payload
- Target component ID (e.g. `@counter-1`)
- Previous and new component state
- Context diff (added, removed, or updated keys)
- Returned side effects
- Execution duration in milliseconds

---

## Serialization Utilities

Convert ClojureScript data structures to JSON / Redux DevTools-friendly JavaScript objects:

```clojure
(devtools/clj->js-data {:user/id 42 :tags #{:admin :staff}})
;; => #js {"user/id" 42, "tags" #js [":admin", ":staff"]}
```

Compute granular map diffs:

```clojure
(devtools/diff-state {:count 0 :theme :light}
                     {:count 1 :theme :light :dirty? true})
;; => {:added {:dirty? true}
;;     :updated {:count {:before 0 :after 1}}}
```
