---
sessionId: session-260829-213129-grt9
---

# Requirements

### Overview & Goals
Provide clear, comprehensive, and scannable module documentation for `com.lambdaseq/relm.query` in `query/README.md`. The documentation follows the format established in `form/README.md` and `reitit/README.md`, adhering to the reader-first principles of `artifact-style`: bottom-line up front, progressive disclosure, concrete code examples matching actual API signatures, and structured reference tables.

### Scope
- **In Scope:**
  - Create `query/README.md` with:
    - Module overview and TanStack Query-style capabilities in Relm's Elm architecture.
    - Installation snippet (`deps.edn`).
    - Architecture data flow diagram (view, update, context cache, HTTP side effects).
    - Vector query keys and automatic REST URL/query-param inference (including Reitit router integration).
    - Query lifecycle and configuration (`::query/update`, `::query/fetch`, caching, `:stale-time`, exponential backoff retries).
    - Mutation lifecycle (`::query/mutate`, optimistic updates, rollback, `:on-mutate`, `:invalidate`).
    - Hierarchical cache invalidation (`::query/invalidate`, prefix matching).
    - View query helper reference table (`data`, `loading?`, `fetching?`, `error`, `status`, `stale?`, `mutation-state`, `mutation-loading?`, `mutation-error`, `mutation-data`).
    - Pure context state reducers reference (`set-query-data`, `set-query-error`, `invalidate-query-keys`, etc.).
    - Complete, runnable end-to-end component example.
  - Update root `README.md`:
    - Add `com.lambdaseq/relm.query` to the Module Catalog table and installation snippet.
- **Out of Scope:**
  - Changes to the underlying implementation of `com.lambdaseq.relm.query`.
  - Non-Markdown documentation formats.

### User Stories
- **As a Relm developer**, I want a dedicated `query/README.md` so that I can quickly learn how to fetch, cache, mutate, and invalidate server state using vector keys.
- **As a Relm developer**, I want concise reference tables for all view queries, message handlers, and options so that I don't have to inspect source code for API signatures.
- **As a Relm developer**, I want a complete working component example so that I can copy a working pattern into my own application.

### Functional Requirements
- **Scannable Structure**: Table of Contents with working anchor links, bold lead-ins for key points, and clean Markdown tables for options and functions.
- **Accurate Code Examples**: All ClojureScript snippets must match the public API of `com.lambdaseq.relm.query` and `com.lambdaseq.relm.core`.
- **Ecosystem Consistency**: Match the voice, style, and structure of `form/README.md` and `reitit/README.md`.

# Technical Design

### Current Implementation
- `query/src/com/lambdaseq/relm/query.cljs` implements all query, mutation, invalidation, key inference, cache reducers, and view query helpers.
- `examples/src/examples/query.cljs` provides a complete working example.
- Existing module READMEs (`core/README.md`, `form/README.md`, `reitit/README.md`) provide the benchmark for styling and structure.
- Root `README.md` contains the ecosystem catalog and installation section.

### Proposed Changes

#### 1. `query/README.md` Structure
- **Title & Overview**: Identity and summary of TanStack Query on Relm.
- **Table of Contents**: Anchor links to all major sections.
- **Installation**: Dependency configuration for `deps.edn`.
- **Architecture Overview**: Text and ASCII/diagram explaining the flow between views, update messages, context `:queries` / `:mutations`, and `::relm.http/fetch` effects.
- **Vector Query Keys & Request Inference**:
  - Explaining key structures (`[:todos]`, `[:users 1 :posts]`, `[:todos {:status "active"}]`).
  - Automatic URL and query param inference.
  - Reitit router name resolution when `:router` is in `context`.
- **Queries (`::update` / `::fetch`)**:
  - Dispatching queries.
  - Caching, `:stale-time`, `:force?`, and background refetching.
  - Exponential backoff retry mechanism (`:retry`, `calculate-retry-delay`).
- **Mutations (`::mutate`)**:
  - HTTP mutation dispatch (`:post`, `:put`, `:patch`, `:delete`).
  - Optimistic updates via `:on-mutate` and rollback context.
  - Mutation lifecycle callbacks (`:on-success`, `:on-error`, `:on-settled`).
- **Hierarchical Invalidation (`::invalidate`)**:
  - Prefix matching behavior and automatic refetching of active queries.
- **View Query Helpers**:
  - Summary table covering `data`, `loading?`, `fetching?`, `error`, `status`, `stale?`, `mutation`, `mutation-loading?`, `mutation-error`, `mutation-data`.
- **Pure Context Cache Reducers**:
  - Summary of `get-query`, `set-query-loading`, `set-query-data`, `set-query-error`, `invalidate-query-keys`, `set-mutation-state`.
- **Complete Working Example**:
  - Realistic component showcasing initial query loading, cached view rendering, optimistic item creation, and automatic cache invalidation.

#### 2. Root `README.md` Updates
- Add `com.lambdaseq/relm.query` to the installation snippet in `README.md`.
- Add **Query** (`com.lambdaseq.relm.query`) to the Module Catalog table in `README.md` with link to `query/README.md`.

### File Structure
- `query/README.md` (New: comprehensive documentation for `relm.query`)
- `README.md` (Modified: register query module in catalog and installation examples)

# Testing

### Validation Approach
- Verify markdown syntax and ensure all internal anchor links resolve correctly.
- Review all code snippets against the signatures in `query/src/com/lambdaseq/relm/query.cljs`.
- Verify the root `README.md` links to `query/README.md`.

### Key Scenarios
1. **API Signature Parity:** Ensure every documented function and option in `query/README.md` matches the actual implementation in `com.lambdaseq.relm.query`.
2. **Cross-Link Validity:** Ensure the root `README.md` catalog points to `query/README.md` and intra-doc anchors in `query/README.md` link properly.

# Delivery Steps

### ✓ Step 1: Create query module README.md documentation
Create comprehensive, scannable documentation in `query/README.md` covering all features, options, view queries, and usage examples.

- Author `query/README.md` following the structure of `form/README.md`.
- Document key inference, queries (`::update`), mutations (`::mutate`), invalidation (`::invalidate`), and exponential backoff.
- Provide tables for view query helpers and options, along with a complete working component example.

### ✓ Step 2: Update root README.md with Query module entry
Register `com.lambdaseq/relm.query` in the root repository documentation.

- Add `com.lambdaseq/relm.query` to the installation snippet in root `README.md`.
- Add the **Query** row to the Module Catalog table linking to `query/README.md`.