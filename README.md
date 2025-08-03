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
(defn view [{:keys [count]} _context]
  [:div
   [:h2 "Counter"]
   [:p "Current count: " count]
   [:button {:on {:click [::increment]}} "Increment"]
   [:button {:on {:click [::decrement]}} "Decrement"]
   [:button {:on {:click [::show-count]}} "Show Count"]])

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
(r/render js/document.body (Counter {:init-count 0}))
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
(relm/dispatch event [::message-type])

;; Handling multiple messages at once
(relm/dispatch event [[::message-type-1] 
                      [::message-type-2]])
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

#### Dispatching Additional Events from Effects

You can use the `:dispatch` effect to trigger additional events from within an event handler:

```clojure
;; Define an update handler that dispatches multiple events
(defmethod relm/update ::process-data
  [state context _message _event]
  [state 
   context 
   [[:dispatch [::log-activity "Data processing started"]]]])

;; Chain multiple events with a single effect
(defmethod relm/update ::complex-operation
  [state context _message _event]
  [state 
   context 
   [[:dispatch [[::update-status "Processing..."]
                [::fetch-data]
                [::notify-user "Operation in progress"]]]]])
```

In the second example, the `:dispatch` effect is used to trigger multiple events at once, allowing you to chain operations together.

### HTTP Requests

The `com.lambdaseq.relm.http` namespace provides functionality for making HTTP requests. It's a port of [re-frame-fetch-fx](https://github.com/superstructor/re-frame-fetch-fx) adapted for the relm architecture.

To use it, require the namespace:

```clojure
(ns your.namespace
  (:require [com.lambdaseq.relm.core :as relm]
            [com.lambdaseq.relm.http :as relm.http]))
```

#### Making HTTP Requests

Use the `::relm.http/fetch` effect to make HTTP requests:

```clojure
(defmethod relm/update ::fetch-data
  [state context _ _event]
  [state context [::relm.http/fetch
                  {:url        "https://api.example.com/data"
                   :method     :get
                   :mode       :cors
                   :on-success [::data-fetched]
                   :on-failure [::data-fetch-failed]}]])
```

#### Handling Responses

Define handlers for successful and failed requests:

```clojure
(defmethod relm/update ::data-fetched
  [state context [_ {:keys [body]}] _event]
  [(assoc state :data body) context])

(defmethod relm/update ::data-fetch-failed
  [state context [_ error] _event]
  [(assoc state :error error) context])
```

#### Request Options

The fetch effect accepts various options:

- `:url` - The URL to request (required)
- `:method` - HTTP method (:get, :post, :put, etc.)
- `:params` - Query parameters to append to the URL
- `:headers` - HTTP headers to include
- `:body` - Request body
- `:request-content-type` - Content type of the request (:json will automatically stringify the body)
- `:timeout` - Request timeout in milliseconds
- `:mode` - CORS mode (:cors, :no-cors, :same-origin)
- `:credentials` - Credentials mode (:include, :omit, :same-origin)
- `:on-success` - Event vector to dispatch on successful response
- `:on-failure` - Event vector to dispatch on failed response

#### Aborting Requests

Use the `::relm.http/abort` effect to abort in-flight requests:

```clojure
(defmethod relm/update ::abort-request
  [state context [_ request-id] _event]
  [state context [::relm.http/abort {:request-id request-id}]])
```

### Rendering

Use replicant's rendering with relm's dispatch:

```clojure
(r/set-dispatch! relm/dispatch)
(r/render target-element (component args))
```

## License

Copyright © 2023 LambdaSeq

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
