# relm

A simple abstraction layer on top of replicant inspired by Elm architecture for Clojure/ClojureScript applications.

## Status

This library is a Work In Progress (WIP) and the API may change.

## Overview

`relm` provides a clean, functional approach to building user interfaces in Clojure/ClojureScript using the Elm architecture pattern. It wraps [replicant](https://github.com/replicant) to provide a simpler, more declarative API for building components with managed state.

Key features:
- Component-based architecture
- Centralized (global and local) state management
- Predictable data flow
- Pure functional view rendering

## Installation

Add the following dependency to your `deps.edn`:

```clojure
{:deps {com.lambdaseq/relm {:git/url "https://github.com/lambdaseq/relm"
                            :sha "..."}}}
```

## Usage

The library follows the Elm architecture with three main parts:
- **Model**: The state of your application
- **View**: A way to render your state as HTML
- **Update**: A way to update your state based on messages

### Basic Example

Here's a simple counter component:

```clojure
(ns examples.counter
  (:require [com.lambdaseq.relm.core :as relm]
            [replicant.dom :as r]
            [hashp.core]))

;; Initialize the component state
(defn init [_context {:keys [init-count] :as _args}]
  {:count init-count})

;; Render the view based on the current state
(defn view [component-id {:keys [count]} _context]
  [:div
   [:h2 "Counter"]
   [:p "Current count: " count]
   [:button {:on {:click [::increment component-id]}} "Increment"]
   [:button {:on {:click [::decrement component-id]}} "Decrement"]
   [:button {:on {:click [::show-count component-id]}} "Show Count"]])

;; Define the component
(def Counter
  (relm/component
    {:init init
     :view view}))

;; Define an effect handler for alerts
(defmethod relm/fx ::alert
  [[_ message]]
  (js/alert message))

;; Define message handlers
(defmethod relm/update ::show-count
  [{:keys [count] :as state} context _message _event]
  [state context [::alert (str "Count: " count)]])

(defmethod relm/update ::increment
  [state context _message _event]
  [(update state :count inc) context])

(defmethod relm/update ::decrement
  [state context _message _event]
  [(update state :count dec) context])

;; Set up the dispatch function
(r/set-dispatch! relm/dispatch)

;; Render the component to the DOM
(r/render js/document.body (Counter :counter
                                   {:init-count 0}))
```

## API

### Component Creation

```clojure
(relm/component {:init init-fn :view view-fn})
```

Creates a new component with the specified initialization and view functions.

The `init-fn` should take two arguments:
- `context`: The current global context
- `args`: Component-specific arguments

And return the initial state for the component (not a vector of [state, context] as in previous versions).

### Message Handling

Define message handlers using the `relm/update` multimethod:

```clojure
(defmethod relm/update ::message-type
  [state context message event]
  [new-state new-context effects])
```

The update function should return a vector of three elements:
- `new-state`: The updated component state
- `new-context`: The updated global context
- `effects`: Side effects to be executed (can be a single effect vector or a vector of effect vectors)

The dispatch function can handle both single messages and collections of messages:

```clojure
;; Handling a single message
(relm/dispatch event [::message-type component-id])

;; Handling multiple messages at once
(relm/dispatch event [[::message-type-1 component-id] 
                      [::message-type-2 component-id]])
```

### Side Effects

Define side effect handlers using the `relm/fx` multimethod:

```clojure
(defmethod relm/fx ::effect-type
  [[_ & args]]
  ;; Perform side effect here
  )
```

Side effects are dispatched by returning them from update handlers. You can return:
- A single effect vector: `[::effect-type arg1 arg2]`
- Multiple effects: `[[::effect-type-1 arg1] [::effect-type-2 arg2]]`
- No effects: `[]`

Example:

```clojure
;; Define an effect handler
(defmethod relm/fx ::http-request
  [[_ url options callback]]
  (http/request url options callback))

;; Use the effect in an update handler
(defmethod relm/update ::fetch-data
  [state context _message _event]
  [state context [::http-request "/api/data" {:method "GET"} ::handle-response]])
```

### Rendering

Use replicant's rendering with relm's dispatch:

```clojure
(r/set-dispatch! relm/dispatch)
(r/render target-element (component component-id args))
```

## License

Copyright © 2023 LambdaSeq

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
