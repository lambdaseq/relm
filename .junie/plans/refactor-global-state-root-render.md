---
sessionId: session-260827-215700-1vug
---

# Requirements

### Overview & Goals
Resolve route name collision in Reitit router configuration in `examples.main` where both `"/"` and `"/nested"` are assigned the duplicate route name `:nested`. Ensure every route in the router table has a distinct `:name` (e.g. `:home` for `"/"` and `:nested` for `"/nested"`), preventing named route conflicts while preserving smooth navigation and active tab highlighting.

### Scope
- **In Scope**:
  - Assign distinct `:name` identifiers to all routes in `examples.main/routes` (`:home` for `"/"`, `:nested` for `"/nested"`, `:counter` for `"/counter"`, `:http` for `"/http"`, `:navigation` for `"/navigation"`).
  - Update `examples.main` view and active tab indicator logic to handle `:home` and `:nested` consistently for the Nested Components tab.
  - Update fallback rendering logic in `examples.main/view` to cover `:home` alongside other route names.
  - Verify ClojureScript build compilation and navigation behavior across all routes.
- **Out of Scope**:
  - Altering core `relm` engine routing abstractions or other example namespaces.
  - Introducing server-side redirects.

### User Stories
- As a developer navigating Relm examples, I want the Reitit route table to have unique, unambiguous route names so that route resolution and any named route queries behave deterministically.
- As a user loading `"/"` or `"/nested"`, I want the Nested Components view to render and the Nested Components tab to appear active.

### Functional Requirements
- **Unique Route Names**: Every entry in `examples.main/routes` must have a distinct `:name` keyword.
- **Home Route Identification**: `"/"` is named `:home` (or `:root`) and renders `NestedExample`.
- **Nested Route Identification**: `"/nested"` is named `:nested` and renders `NestedExample`.
- **Active Tab Styling**: The "Nested Components" tab highlights as active when `current-route` is `:nested` or `:home`.
- **View Fallback**: View dispatch gracefully handles `:home` as well as `:nested`.

# Technical Design

### Current Implementation
In `examples/src/examples/main.cljs`:
```clojure
(def routes
  [["/" {:name :nested
         :path "/"
         :view (fn [] (NestedExample {}))}]
   ["/nested" {:name :nested
               :path "/nested"
               :view (fn [] (NestedExample {}))}]
   ...])
```
Both `"/"` and `"/nested"` define `:name :nested`. In Reitit, duplicate route names cause conflicts during router initialization or named route resolution (`match-by-name`).

### Proposed Changes

#### 1. Assign Unique Route Name to Root Path
Update the route definitions in `examples.main`:
```clojure
(def routes
  [["/" {:name :home
         :path "/"
         :view (fn [] (NestedExample {}))}]
   ["/nested" {:name :nested
               :path "/nested"
               :view (fn [] (NestedExample {}))}]
   ["/counter" {:name :counter
                :path "/counter"
                :view (fn [] (Counter {:init-count 0}))}]
   ["/http" {:name :http
             :path "/http"
             :view (fn [] (HttpExample {}))}]
   ["/navigation" {:name :navigation
                   :path "/navigation"
                   :view (fn [] (NavigationExample {}))}]])
```

#### 2. Update Active Tab & View Logic in `examples.main`
- Update the active tab condition for Nested Components:
  ```clojure
  :background-color (if (#{:nested :home} current-route) "#4f46e5" "#f3f4f6")
  :color (if (#{:nested :home} current-route) "#ffffff" "#111827")
  :font-weight (if (#{:nested :home} current-route) "600" "normal")
  ```
- Ensure fallback `case` statement in `view` supports `:home`:
  ```clojure
  (case current-route
    (:nested :home) (NestedExample {})
    :counter (Counter {:init-count 0})
    :http (HttpExample {})
    :navigation (NavigationExample {})
    (NestedExample {}))
  ```

### Affected Files
- `examples/src/examples/main.cljs`: Update `routes`, active tab check, and view fallback cases.

# Testing

### Validation Approach
Verify router setup and compilation using `shadow-cljs compile examples` and check route resolution for all defined routes.

### Key Scenarios
1. **Root URL Resolution**: Navigating to `"/"` matches route `:home`, renders `NestedExample`, and highlights the "Nested Components" tab.
2. **Explicit Nested URL Resolution**: Navigating to `"/nested"` matches route `:nested`, renders `NestedExample`, and highlights the "Nested Components" tab.
3. **Other Routes**: Navigating to `"/counter"`, `"/http"`, and `"/navigation"` resolves their respective unique route names and renders corresponding views.
4. **Clean Compilation**: `npx shadow-cljs compile examples` completes with zero errors and zero warnings.

# Delivery Steps

### ✓ Step 1: Disambiguate route names and update route handling in examples.main
All routes in Reitit router have unique `:name` identifiers and UI components correctly handle `:home` and `:nested`.

- Update `"/"` route definition in `examples.main/routes` to use `:name :home`.
- Keep `"/nested"` route definition as `:name :nested`.
- Update Nested Components tab button styling in `examples.main/view` to check for `#{:nested :home}`.
- Update fallback `case` in `examples.main/view` to handle `:home` alongside `:nested`.

### ✓ Step 2: Verify compilation and routing behavior
The ClojureScript example build compiles cleanly and routes resolve without conflicts.

- Run `shadow-cljs compile examples` to verify ClojureScript compilation.
- Validate that all route paths (`/`, `/nested`, `/counter`, `/http`, `/navigation`) map to unique route names.