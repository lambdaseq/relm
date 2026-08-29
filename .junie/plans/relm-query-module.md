---
sessionId: session-260829-213129-grt9
---

# Requirements

### Overview & Goals
Simplify input event handling in `examples/src/examples/query.cljs` by replacing manual `#(relm/dispatch % [::set-input :field (.. % -target -value)])` anonymous functions with declarative Replicant / Relm event vectors (`[::set-input :field :event.target/value]`).

### Scope
- **In Scope:**
  - Update `examples/src/examples/query.cljs` input fields for `:title` and `:body` to use `[::set-input :title :event.target/value]` and `[::set-input :body :event.target/value]` in `:on {:input ...}` handlers.
  - Ensure consistency with Replicant's data-driven event extraction and Relm's architectural idioms.
- **Out of Scope:**
  - Changing query module core runtime (`query/src/com/lambdaseq/relm/query.cljs`).
  - Modifications to other unrelated example pages.

### User Stories
- **As a developer reading Relm examples**, I want idiomatic, declarative Replicant event vectors in UI inputs so that I don't see unnecessary `relm/dispatch` function wrappers in Hiccup templates.

### Functional Requirements
- Remove manual `relm/dispatch` lambda calls from input elements in `examples/src/examples/query.cljs`.
- Use declarative event vector syntax `[::set-input :title :event.target/value]` and `[::set-input :body :event.target/value]` for `:input` event bindings.

# Technical Design

### Current Implementation
In `examples/src/examples/query.cljs`, the mutation form currently binds `:on {:input ...}` using an inline Clojure function wrapping `relm/dispatch`:
```clojure
[:input {:placeholder "Post Title..."
         :value title
         :on {:input #(relm/dispatch % [::set-input :title (.. % -target -value)])}}]
[:input {:placeholder "Post Body..."
         :value body
         :on {:input #(relm/dispatch % [::set-input :body (.. % -target -value)])}}]
```

Replicant and Relm support data-driven event vectors directly in `:on` event maps, where Replicant extracts `:event.target/value` from DOM events before forwarding to the registered dispatcher (`relm/dispatch`).

### Proposed Changes
Update `examples/src/examples/query.cljs` to use pure event data vectors:
```clojure
[:input {:style {:flex "1" :padding "8px 12px" :border "1px solid #d1d5db" :border-radius "6px"}
         :placeholder "Post Title..."
         :value title
         :on {:input [::set-input :title :event.target/value]}}]
[:input {:style {:flex "2" :padding "8px 12px" :border "1px solid #d1d5db" :border-radius "6px"}
         :placeholder "Post Body..."
         :value body
         :on {:input [::set-input :body :event.target/value]}}]
```

### File Structure
- `examples/src/examples/query.cljs` (Modified: declarative event vectors for input handlers)

# Testing

### Validation Approach
- Verify compilation of `:examples` via shadow-cljs.
- Ensure all test suites continue to pass cleanly.

### Key Scenarios
1. **Event Vector Dispatch:** Ensure `[::set-input :title :event.target/value]` correctly triggers the `relm/update ::set-input` handler with the updated string value.
2. **Form Interaction:** Ensure typing into `:title` and `:body` fields updates component state and enables the Create Post button.

# Delivery Steps

### ✓ Step 1: Update input event handlers in examples.query
Refactor the input fields in `examples/src/examples/query.cljs` from anonymous dispatch functions to declarative event vectors.

- Replace `#(relm/dispatch % [::set-input :title (.. % -target -value)])` with `[::set-input :title :event.target/value]`.
- Replace `#(relm/dispatch % [::set-input :body (.. % -target -value)])` with `[::set-input :body :event.target/value]`.

### ✓ Step 2: Verify examples compilation and test execution
Verify that the ClojureScript builds compile cleanly and test suites pass without regressions.

- Run shadow-cljs compilation for the examples build.
- Run the unit test suite to confirm overall project integrity.