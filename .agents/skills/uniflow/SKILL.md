---
name: uniflow
description: 'ARCHITECTURE SKILL - Uniflow event-loop pattern for ClojureScript/Squint apps with Replicant. USE FOR: implementing or refactoring unidirectional action/effect systems, enforcing single access point state handling, event enrichment for pure actions, gather-then-decide async workflows, auditing state access patterns, AX/FX dispatch loops. DO NOT USE FOR: pure UI styling, build/release pipeline, general Clojure refactoring unrelated to state or event flow. IMPORTANT: Also load this skill when PLANNING or DISCUSSING state/event architecture — not only at the moment of refactoring.'
---

# Uniflow - Unidirectional Event Loop Architecture

Uniflow is a unidirectional event loop for Clojure, ClojureScript, SCI, Squint, any Clojure dialect, applications. Actions decide. Effects execute. The event loop is the single access point for state.

[Replicant TodoMVC](https://github.com/anteoas/replicant-todomvc) is where Uniflow was invented, on the [TodoMVC](https://todomvc.com) specification: views, actions, effects, enrichment, and routing for that app. Later conventions are not all in place there. Actions return `:new-state` and `:effects`. The keys in this skill are `:uf/db`, `:uf/fxs`, and `:uf/dxs`. The minimal loop with those keys is `templates/uniflow-starter.cljs`.

## When to Use This Skill

- Implementing a new ClojureScript/Squint app with Replicant
- Refactoring scattered `swap!`/`reset!` calls into unidirectional flow
- Adding async effects (HTTP, storage, timers) to an existing Uniflow app
- Auditing code for state access violations
- Designing action/effect boundaries for a new feature
- Debugging data flow through the dispatch loop

## Prerequisites

- **Replicant** for declarative DOM rendering and event dispatch
- **ClojureScript runtime**: Squint, SCI/Scittle, Shadow-CLJS, or standard CLJS
- Familiarity with atoms, `swap!`/`reset!`, and `clojure.walk`

## Mental Model

```
View (declarative event vectors with placeholders)
  |
  v
Enrichment (resolve :event/*, :dom/*, [:db/get ...] to plain data)
  |
  v
Action handler (pure: state + enriched-action -> {:uf/db :uf/fxs :uf/dxs})
  |
  +--> :uf/db  --> reset! state atom (only here)
  +--> :uf/dxs --> recursive dispatch (more actions)
  +--> :uf/fxs --> effect execution (side effects)
```

Three return keys from actions:
- `:uf/db` - next state value (committed by the loop via `reset!`)
- `:uf/fxs` - ordered effect vectors to execute
- `:uf/dxs` - deferred actions dispatched after effects

## Non-Negotiable Invariants

1. **Single access point**: Only the dispatch loop may `deref` or `reset!` the state atom. No exceptions at runtime.
2. **Action purity**: Actions receive state as a plain map. They return data. They never touch the atom. They never perform side effects. Helpers called by actions inherit this rule.
3. **Effect isolation**: Effects receive data from action-declared params only. They never read `@!state`. Helpers called by effects inherit this rule.
4. **Entry point discipline**: Message handlers, event listeners, and callbacks dispatch actions. They do not read state or mutate it.
5. **Guard function purity**: Utility and guard functions receive data as parameters, never deref the atom.
6. **Data flows down**: State-derived values needed by effects must be extracted in actions and passed via `:uf/fxs` params.
7. **Gather-then-decide**: Effects gather external data. Actions make decisions. Never mix.

## Anti-Patterns and Corrections

| Anti-pattern | Correction |
|---|---|
| Effect reads `@!state` | Action extracts value, passes via effect args |
| Helper called by effect reads `@!state` | Refactor helper to accept data as parameter |
| Direct `swap!`/`reset!` outside event loop | Create action, return `:uf/db`, dispatch |
| Guard function derefs atom | Make guard pure with explicit data inputs |
| Message handler reads state then acts | Dispatch action; action extracts from state |
| Effect gathers state then dispatches decision | Gather-then-decide: gather in effect, decide in deferred action |
| Callback reads atom for "current" value | Action passes value through effect params at dispatch time |

## Enrichment System

Enrichment is the boundary layer that keeps actions pure. Views declare placeholder keywords for runtime data. The dispatch pipeline resolves these to plain Clojure values before actions execute.

### Two enrichment phases

1. **Replicant data enrichment** - resolves DOM event properties (`:event/*`), node references (`:dom/node`), and element lookups (`[:dom/element-by-id "id"]`) from the Replicant dispatch data.
2. **State enrichment** - resolves `[:db/get :key]` vectors to values from the current state map.

See the Reference Implementation below for the complete enrichment code.

### Standard placeholders

| Placeholder | Resolves to |
|---|---|
| `:event/event` | Shallow map of JS event |
| `:event/target.value` | Input value |
| `:event/clientX` | Event property by dot-path |
| `:dom/node` | Source DOM node |
| `[:db/get :key]` | State lookup: `(get state :key)` |
| `[:dom/element-by-id "id"]` | `document.getElementById` |

### Before and after

View declaration:
```clojure
{:on {:input [[:db/ax.assoc :ui/draft :event/target.value]]}}
```

Enriched action passed to handler:
```clojure
[:db/ax.assoc :ui/draft "user-typed-value"]
```

The action handler receives pure data. No DOM objects. No event objects. Trivially testable.

## Reference Implementation

See `templates/uniflow-starter.cljs` for a complete, minimal Uniflow + Replicant app. Copy into your project and adapt. It includes enrichment, actions, effects, async helpers, the dispatch loop, a Replicant view, and initialization.

### `await` vs `js-await` Across Runtimes

The reference implementation above uses `js-await` (Squint). The await form differs by runtime:

| Runtime | Await form | Async marker | Notes |
|---------|-----------|--------------|-------|
| **Squint** | `(js-await expr)` | `^:async` on `defn`/`fn` | Compiles to native JS `await` |
| **SCI/Scittle** | `(await expr)` | `^:async` on `defn`/`fn` | `js-await` is unresolved in SCI |
| **Shadow-CLJS** | `(js-await [x promise] body)` | None (macro) | `(:require [shadow.cljs.modern :refer (js-await)])`. Macro that expands to `.then` chains, not native async/await. Binding form: `(js-await [result expr] (use result) (catch e (handle e)))` |
| **ClojureScript (future)** | `(await expr)` | `^:async` on `defn`/`fn` | CLJS-3470 (merged 2026-03-08, not yet released). Will require `(:require [cljs.core :refer [await]])` |
| **Any CLJS** | `(<p! promise)` | Inside `(go ...)` block | `(:require [cljs.core.async.interop :refer-macros [<p!]])`. Works everywhere, returns channel |

Squint and SCI both have native async/await but use different symbols. Shadow-CLJS provides `shadow.cljs.modern/js-await` - a macro that expands to `.then` chains (not native async/await). Note the different binding syntax: `(js-await [result expr] body)` vs Squint's `(js-await expr)`. Standard ClojureScript will gain native `await` via CLJS-3470, but this is not yet released. For any ClojureScript environment, `core.async/<p!` also works. When adapting the reference implementation, choose the await mechanism matching your runtime.

## Testing Actions

Actions are pure functions. Test them with plain data, no mocks needed.

```clojure
(deftest submit-action-adds-item
  (let [state {:ui/draft "Buy milk" :todo/items []}
        uf-data {:uf/replicant-data {}}
        result (handle-action state uf-data
                              [:todo/ax.submit "Buy milk"])]
    (is (= "" (get-in result [:uf/db :ui/draft])))
    (is (= 1 (count (get-in result [:uf/db :todo/items]))))
    (is (= "Buy milk" (:item/text (first (get-in result [:uf/db :todo/items])))))))

(deftest submit-on-enter-dispatches-submit
  (let [result (handle-action {} {:uf/replicant-data {}}
                              [:todo/ax.submit-on-enter {:key "Enter"} "Buy milk"])]
    (is (= [[:todo/ax.submit "Buy milk"]] (:uf/dxs result)))))

(deftest submit-on-other-key-is-noop
  (let [result (handle-action {} {:uf/replicant-data {}}
                              [:todo/ax.submit-on-enter {:key "a"} "Buy milk"])]
    (is (nil? result))))

(deftest save-action-declares-async-recipe
  (let [items [{:item/id "1" :item/text "Buy milk"}]
        result (handle-action {} {:uf/replicant-data {}}
                              [:todo/ax.save items])]
    ;; Verify the effect recipe and deferred action are declared correctly
    (is (= [:uf/await :http/fx.post "/api/todos" items]
           (first (:uf/fxs result))))
    (is (= [:log/fx.log :info "Saved" :uf/prev-result]
           (second (:uf/fxs result))))
    (is (= [[:todo/ax.handle-save-result :uf/prev-result]]
           (:uf/dxs result)))))
```

Enrichment makes this possible: action tests use the same data shapes that enrichment produces. No DOM, no events, just maps and vectors. Async actions are tested identically - verify the declared recipe, not the execution.

## Testing Views

A view is a pure function of state. It returns hiccup, and the event handlers in that hiccup are action vectors. A view test calls the view with a state map, selects the actions bound to an event, runs those actions through the action handler, and asserts the next state and the declared effects.

The view tests in [Replicant TodoMVC](https://github.com/anteoas/replicant-todomvc) show this. They live in `test/todomvc/*_view_test.cljc`, with selection helpers in `test/test_util.cljc`. They use [lookup](https://github.com/cjohansen/lookup) to read attributes and event actions out of the hiccup. The files are `.cljc`. In that repo, `bb run-tests-jvm` runs them with JVM `clojure.test`, and the same tests run in the REPL. No browser runner, and no Node toolchain.

## Async Patterns in Practice

See `references/async-patterns.md` for the full catalog of proven async patterns including:
- **Await-and-Respond**: Single awaited effect with result forwarding
- **Gather-then-Decide**: Effect gathers context, deferred action makes pure decision
- **Multi-Step Recipe**: Sequential awaited effects in strict order
- **Shared Initialization Gate**: Deduplicating concurrent initialization via stored promise

### `:uf/prev-result` Threading Semantics

- **In `:uf/fxs`**: Replaced inline as effects execute sequentially. Each awaited effect's return value becomes the next `:uf/prev-result`.
- **In `:uf/dxs`**: Replaced with the **final** `:uf/prev-result` after all effects have completed. Deferred actions see the last effect's result.

## Naming Conventions

- Actions: `:namespace/ax.verb-noun` (e.g., `:todo/ax.submit`, `:nav/ax.handle-navigation`)
- Effects: `:namespace/fx.verb-noun` (e.g., `:http/fx.fetch`, `:dom/fx.blur`)
- Generic action: `:db/ax.assoc` for simple state updates
- Unhandled sentinels: `:uf/unhandled-ax`, `:uf/unhandled-fx`

## Code Organization

**Avoid forward declares.** In Clojure, definition order matters. Define functions before they are used. `declare` is almost always a sign of poor structure. The one legitimate exception is mutual references between the dispatch function and the event handler entry point - the reference implementation uses `(declare dispatch!)` for this reason. When splitting across namespaces, the circular reference dissolves naturally.

**Extract the dispatch loop to its own namespace.** The event handler, enrichment, async helpers, and dispatch loop belong in a dedicated namespace (e.g., `my.event-handler`). Action handlers and effect handlers live in separate namespaces. The reference implementation above shows everything in one namespace for brevity - real applications split them:

```
my.event-handler  ;; dispatch!, execute-effects!, enrichment
my.actions        ;; handle-action, domain-specific actions
my.effects        ;; perform-effect!, domain-specific effects
my.db             ;; !state atom, init
my.views          ;; Replicant components
```

## Agent Checklist

Before completing Uniflow work:
- [ ] No `@!state` reads outside the dispatch loop
- [ ] No `swap!`/`reset!` outside the dispatch loop
- [ ] No `declare` - define functions before use, extract to separate namespaces if needed
- [ ] All effects receive state-derived data via action params
- [ ] Actions return only `:uf/db`, `:uf/fxs`, `:uf/dxs`
- [ ] Entry points (message handlers, listeners) dispatch actions only
- [ ] Guard/utility functions are pure (data in, data out)
- [ ] Actions never call non-deterministic functions (`random-uuid`, `Date.`, etc.) - use `uf-data` instead
- [ ] Action tests use plain data, no mocks
- [ ] View interaction tests call the view with state, select actions from the hiccup, and run the action handler

## Error Handling Strategy

**Awaited effect rejection**: If an awaited effect's promise rejects, the `.then` chain in `dispatch!` will reject. Wrap `execute-effects!` in a try/catch at the dispatch level, or use a global error handler.

**Effect result envelopes**: Use `{:success true/false :error "msg"}` as a convention. Actions receiving `:uf/prev-result` should check `:success` before proceeding:

```clojure
:my/ax.handle-result
(let [[result] args]
  (if (:success result)
    {:uf/db (assoc state :my/data (:data result))}
    {:uf/fxs [[:log/fx.log :error "Failed:" (:error result)]]}))
```

**Unhandled actions/effects**: The generic handlers log warnings via `js/console.warn`. In development, consider making unhandled dispatches throw to catch typos early.

## Troubleshooting

| Issue | Cause | Fix |
|-------|-------|-----|
| State not updating | Action returns nil instead of `{:uf/db ...}` | Check `case` clauses return maps; `when` returns nil on falsy |
| Effect reads stale state | Effect derefs `@!state` directly | Extract value in action, pass via effect args |
| `:uf/prev-result` is nil | Previous effect not marked `:uf/await` | Add `:uf/await` prefix to the effect vector |
| Action seems impure | Calls `random-uuid`, `js/Date.`, etc. | Move to `uf-data` enrichment in dispatch loop |
| Circular dependency between namespaces | Actions and dispatch reference each other | Pass `dispatch` as a parameter to effects; keep actions pure (no dispatch calls) |
