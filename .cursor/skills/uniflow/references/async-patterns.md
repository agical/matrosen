# Async Patterns in Practice

The reference implementation (`templates/uniflow-starter.cljs`) demonstrates Uniflow's async machinery: the `execute-effects!` loop processes effects sequentially, awaiting those marked with `:uf/await` and threading results forward via `:uf/prev-result`. This document catalogs proven patterns from production codebases.

## Pattern 1: Await-and-Respond

The simplest async pattern. Await one effect, send its result back to the caller.

```clojure
;; Action: extract state, delegate to async effect, thread result to response
:msg/ax.connect-tab
(let [[send-response tab-id ws-port] args
      icon-state (get-in state [:icon/states tab-id] :disconnected)]
  {:uf/fxs [[:uf/await :repl/fx.connect-tab tab-id ws-port icon-state]
            [:msg/fx.send-response send-response :uf/prev-result]]})

;; Effect: returns a result map (becomes prev-result)
:repl/fx.connect-tab
(let [[tab-id ws-port icon-state] args]
  (try
    (js-await (connect-tab! dispatch! tab-id ws-port icon-state))
    {:success true}
    (catch :default err
      {:success false :error (.-message err)})))

;; Effect: fire-and-forget, sends the result to caller
:msg/fx.send-response
(let [[send-response response-data] args]
  (send-response (clj->js response-data)))
```

This pattern appears across all message-response handlers: check-status, evaluate-script, storage operations, tab queries.

## Pattern 2: Gather-then-Decide

Effect gathers external context. Deferred action receives the gathered data and makes a pure decision.

```clojure
;; Action 1: kick off gathering, schedule decision
:nav/ax.handle-navigation
(let [[tab-id url] args
      history (:connected-tabs/history state)]
  {:uf/fxs [[:icon/fx.update-icon-disconnected tab-id]
            [:uf/await :nav/fx.gather-auto-connect-context tab-id url history]]
   :uf/dxs [[:nav/ax.decide-connection :uf/prev-result]]})

;; Effect: gathers async context into a plain map
:nav/fx.gather-auto-connect-context
(let [[tab-id url history] args
      {:keys [enabled?]} (js-await (get-auto-connect-settings))
      auto-reconnect? (js-await (get-auto-reconnect-setting))
      saved-port (js-await (get-saved-ws-port tab-id))
      in-history? (tab-in-history? history tab-id)
      history-port (when in-history? (get-history-port history tab-id))]
  {:nav/tab-id tab-id, :nav/url url
   :nav/auto-connect-enabled? (boolean enabled?)
   :nav/auto-reconnect-enabled? (boolean auto-reconnect?)
   :nav/in-history? in-history?
   :nav/history-port history-port
   :nav/saved-port saved-port})

;; Action 2: pure decision based on gathered context
:nav/ax.decide-connection
(let [[context] args
      {:nav/keys [tab-id url]} context
      icon-state (get-in state [:icon/states tab-id] :disconnected)
      {:keys [decision port]} (decide-auto-connection context)
      connect-fxs (when (not= decision "none")
                    [[:uf/await :nav/fx.connect tab-id port icon-state]])]
  {:uf/fxs (vec (concat connect-fxs
                        [[:nav/fx.process-navigation tab-id url icon-state]]))})
```

Key insight: the gather effect returns a **context map** with namespaced keys. The decision action is pure and testable - pass it any context map.

## Pattern 3: Multi-Step Recipe

Sequential awaited effects that must execute in strict order.

```clojure
;; Action: ordered pipeline of inject-bridge -> wait-ready -> inject-libs -> respond
:msg/ax.inject-libs
(let [[send-response tab-id libs] args
      files (when (seq libs)
              (collect-lib-files [{:script/inject libs}]))]
  (if (seq files)
    {:uf/fxs (-> [[:uf/await :msg/fx.inject-bridge tab-id]
                  [:uf/await :msg/fx.wait-bridge-ready tab-id]]
                 (into (mapv (fn [f] [:uf/await :msg/fx.inject-lib-file tab-id f]) files))
                 (conj [:uf/await :msg/fx.send-response send-response {:success true}]))}
    {:uf/fxs [[:msg/fx.send-response send-response {:success true}]]}))
```

Note: each step awaits before the next begins. No `:uf/prev-result` needed here because the ordering itself is what matters, not the intermediate values.

## Pattern 4: Shared Initialization Gate

Deduplicates concurrent initialization by storing a promise in state. The action stays pure - it only decides *whether* to initialize. The effect creates the promise and performs the work.

```clojure
;; Action: pure decision based on state
:init/ax.ensure-initialized
(if (:init/promise state)
  ;; Already initializing/initialized: await existing promise
  {:uf/fxs [[:uf/await :init/fx.await-promise (:init/promise state)]]}
  ;; First call: delegate promise creation + initialization to effect
  {:uf/fxs [[:uf/await :init/fx.create-and-initialize]]
   :uf/dxs [[:init/ax.store-promise :uf/prev-result]]})

;; Action: store the promise returned by the effect
:init/ax.store-promise
(let [[promise] args]
  {:uf/db (assoc state :init/promise promise)})

;; Effect: creates the promise and performs initialization (imperative)
:init/fx.create-and-initialize
(let [resolve-fn (volatile! nil)
      promise (js/Promise. (fn [resolve _reject]
                             (vreset! resolve-fn resolve)))]
  ;; Perform initialization work, then resolve
  (do-init! (fn [] (@resolve-fn true)))
  promise)
```

Second and subsequent callers await the same promise stored in state. No redundant initialization. The action never touches `volatile!` or `js/Promise` - those belong in the effect.

## Effect Return Shapes

Awaited effects return values that become `:uf/prev-result`:
- **Result envelopes**: `{:success true}` or `{:success false :error "msg"}`
- **Context maps**: `{:nav/tab-id 1 :nav/url "..." :nav/auto-connect-enabled? true ...}`
- **Resolved values**: whatever the awaited promise yields
- **nil**: fine when no downstream step needs the result

## `:uf/prev-result` Threading Semantics

- **In `:uf/fxs`**: Replaced inline as effects execute sequentially. Each awaited effect's return value becomes the next `:uf/prev-result`.
- **In `:uf/dxs`**: Replaced with the **final** `:uf/prev-result` after all effects have completed. Deferred actions see the last effect's result.
