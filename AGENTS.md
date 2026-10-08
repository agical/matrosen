# Smörrebrödsplaneraren

An event host opens this page to plan a smörrebröd order from Matrosen. The host sets a target count, adjusts dishes, and copies or shares the order. The interface is Swedish. The design context is `.impeccable.md`. PEZ writes `README.md`.

The page is one Scittle program. `index.html` loads Scittle and Replicant from `vendor/scittle/0.8.33/`, then the program in this order:

1. `src/matrosen/photos.cljs` — photo map
2. `src/matrosen/model.cljs` — menu, validation, quantities, share text
3. `src/matrosen/views.cljs` — hiccup; an event carries an action vector
4. `src/matrosen/db.cljs` — the `!state` atom
5. `src/matrosen/actions.cljs` — pure action handlers
6. `src/matrosen/effects.cljs` — DOM, `localStorage`, the URL, the clipboard
7. `src/matrosen/event_handler.cljs` — enrichment and `dispatch!`; the file calls `init!` on load

The namespace for `event_handler.cljs` is `matrosen.event-handler`.

`dispatch!` is the only place that reads or writes `matrosen.db/!state` at runtime. An action returns `:uf/db`, `:uf/fxs`, and `:uf/dxs`. An effect receives the data the action placed in the effect vector.

A plan stored in this browser is JSON in `localStorage` under `matrosen.order.v1`. A shared plan is the URL hash. `model.cljs` reads and validates both.

## Skills

Load the matching skill before changing the layer the skill covers.

### Uniflow, in this repo

Read `.agents/skills/uniflow/SKILL.md` before changing actions, effects, enrichment, `dispatch!`, view event data, or the state shape. Async recipes are in `.agents/skills/uniflow/references/async-patterns.md`.

The loop for this page is `src/matrosen/event_handler.cljs`. `.agents/skills/uniflow/templates/uniflow-starter.cljs` is a minimal loop with the same keys, for reading when the shape is unclear.

### Clojure, Babashka, and Scittle

Clojure, Babashka, and Scittle guidance lives in [awesome-backseat-driver](https://github.com/BetterThanTomorrow/awesome-backseat-driver). When a checkout of that repository is available, the skills are under `plugins/`. On PEZ's machine the checkout is `~/Projects/awesome-backseat-driver`. The `clojure` and `babashka` plugins installed from that repository carry the same skill files. Load the skill from the checkout or from the installed plugin.

- Clojure forms, including this Scittle program: `plugins/clojure/skills/clojure`. Scittle is SCI in the browser. Read `references/sci-dialect.md` and `references/runtime-patterns.md` before writing async code or a ClojureScript-only form. Async code in this page uses `await` on an `^:async` function.
- Babashka, including `scripts/dev_server.clj`: `plugins/babashka/skills/babashka`.
- A change to `bb.edn`: `plugins/babashka/skills/babashka-tasks`, after the Babashka skill.

Edit Clojure forms with structural editing, as the Clojure skill describes.

Epupp, in awesome-backseat-driver, is a userscript on someone else's page. This repository is the page.

## REPL

From the repo root, `bb dev` serves the directory at <http://127.0.0.1:8080/>. `--port` selects another port.

On localhost the page loads `scittle.nrepl.js`. The WebSocket host is `localhost`. The port is the `nrepl` query parameter, or `1340`.

Connect Calva's Scittle websocket, then load a changed file into that session. Load `event_handler.cljs` after a view or action change. The file calls `init!`, which rebinds Replicant's dispatch. When `:initialized?` is already true, `:app/ax.initialize` keeps the current order and the loop renders that order.

Develop the change in the REPL, then put the verified form in the file. The Clojure skill's `references/repl-workflows.md` is the habit. The script order in `index.html` is the order for a full load.

## Interface

Read `.impeccable.md` before changing layout, copy, or CSS. Menu data lives in `model.cljs` and `photos.cljs`. Styles live in `styles.css`. Visible strings stay Swedish.
