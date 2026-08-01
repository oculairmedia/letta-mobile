# lgns8 epic: status, achievable scope, and the shim-retirement ceiling

Date: 2026-07-23 · **rewritten 2026-07-31 for `letta-mobile-lgns8.9`** (admin-REST
ceiling recomputed), with the shim-only-surfaces disposition and the
train/channels addendum retained below.

Beads: `letta-mobile-lgns8` (epic) and children .9/.10/.11/.16/.17/.23/.25, plus
`letta-mobile-q2b9z` (upstream channel-registry blocker)

## Why this document

The lgns8 epic is "Supersede lettashim with a Kotlin-controlled Letta App
Server v2." This note records what "retire lettashim" can actually mean, so the
remaining issues can be closed honestly rather than left implying a retirement
the architecture cannot deliver.

**It has been rewritten because the ceiling moved.** The earlier version said
the 39 `admin_rest_service` admin_rpc methods "have no native backend to move
to" and must keep the shim as their network target. That conclusion was drawn
from the *shape* of the App Server protocol (no REST admin API) without
auditing what admin-shim's `/v1` handlers actually **did**. The audit — reading
every route in `admin-shim/server.ts` on the pinned `@letta-ai/letta-code`
0.29.12 host — found that almost none of them proxy anything:

| What the handler really is | Count | Consequence |
|---|---|---|
| a read of the on-disk `lc-local-backend` store | 6 | the controller can read the same files |
| a hard-coded constant (tool catalog, provider, embedding model) | 4 | the constant moves into the controller |
| `stubList` — literally `json(res, 200, [])` | 6 | answer natively; there is no data to lose |
| a thin translation over cron tasks | 4 | native `cron_*` commands already expose those tasks |
| **a route admin-shim never registered (404 today)** | **16** | nothing works there now; denying is parity |

Sixteen of the thirty-six were already dead. The remaining ceiling is therefore
much smaller than previously recorded, and it is a *write* ceiling.

## The corrected finding: lettashim is a file-store reader, not the admin backend

The real Letta App Server (`letta app-server --listen`) exposes only the runtime
WebSocket v2 protocol and has no REST admin API — that part stands. But
lettashim (`admin-shim/server.ts`, `:8291`) is **not** a backend either: it is a
thin emulation over the on-disk store at `/root/.letta/lc-local-backend`
(`lib/store.ts`, `lib/runs.ts`, `lib/crons.ts`). Whatever it could serve, it
served by reading that directory.

So the Kotlin controller has exactly three legitimate non-shim owners, and
lgns8.9 assigned all thirty-six methods to one of them (or to an explicit
denial):

1. **`app_server_v2`** — a native command exists. This is the only owner allowed
   to perform admin WRITES, because the App Server is the local backend's single
   writer. (Epic constraint: *do not run multiple writers against one
   local-backend root*.)
2. **`local_backend_store`** — a **read-only** reader over the same directory
   lettashim read, for reads with no native command.
3. **`controller_native`** — constants and empty-by-contract lists that never
   touched a datastore in the first place.

Everything else is `capability_gated_unsupported`: a typed, documented,
fail-closed denial.

## Per-method disposition (all 36 former `admin_rest_service` methods)

`authorization_class` is preserved verbatim from the #1008 capability gates.

### `app_server_v2` — native command (5; **3 of them writes**)

| Method | Native contract | Auth class |
|---|---|---|
| `schedule.list` | `cron_list` | `admin_read` |
| `schedule.get` | `cron_get` | `admin_read` |
| `schedule.create` | `cron_add` | `admin_write` |
| `schedule.delete` | `cron_delete` | `admin_write` |
| `block.update_agent` | `write_memory_file` | `admin_write` |

`schedule.*` was never its own store: admin-shim's `/v1/agents/{id}/schedule`
routes are `cronTaskToScheduledMessage` / `scheduleCreateParamsToCronBody` over
`lib/crons.ts`. Only that translation moves into the controller.
`block.update_agent` carries `agent_id` + `label`, and a core-memory block *is*
the memfs file `memory/system/<label>.md`, so it maps 1:1 onto
`write_memory_file` — a route admin-shim never even had.

### `local_backend_store` — read-only on-disk reads (6)

| Method | Store path | Auth class |
|---|---|---|
| `run.list` | `runs/<id>/run.json` (live root; never `_archive`) | `admin_read` |
| `run.get` | `runs/<id>/run.json`, archive-resolving | `admin_read` |
| `step.list` | `runs/<id>/steps.jsonl` | `admin_read` |
| `agent.context` | agent record + `system-prompt.json` + transcript | `admin_read` |
| `block.list` | the agent's `memfs` system memory files, unioned | `admin_read` |
| `block.get` | the same files, by synthesised id | `admin_read` |

Gated on `LETTA_LOCAL_BACKEND_DIR`; unset means a typed capability error, never
an HTTP fallback.

### `controller_native` — constants and empty-by-contract (10)

| Method | Why | Auth class |
|---|---|---|
| `tool.list`, `tool.get` | port of the hard-coded `BUILTIN_TOOL_DEFINITIONS` catalog | `admin_read` |
| `provider.list` | port of the single synthesised `lmstudio-local` provider | `admin_read` |
| `model.list.embedding` | port of the hard-coded embedding descriptor | `admin_read` |
| `archive.list`, `folder.list`, `group.list`, `identity.list`, `job.list`, `mcp.list` | admin-shim `stubList` → `[]`; the entity does not exist here | `admin_read` |

### `capability_gated_unsupported` — fail-closed denials (15)

Every one of these is a route **admin-shim does not implement** (it 404s), so
the denial is parity with production today, not a regression.

| Method | Auth class | Why denied |
|---|---|---|
| `tool.create`, `tool.update`, `tool.delete`, `tool.attach`, `tool.detach` | `admin_write` | letta-code tools are code-defined, not records; `update_toolset` is the live-session toolset, not admin CRUD |
| `block.create`, `block.update`, `block.delete`, `block.attach`, `block.detach` | `admin_write` | no globally addressable block entity; the global id is shim-synthesised; attach/detach is meaningless for per-agent memfs files |
| `passage.create`, `passage.delete` | `admin_write` | no archival-memory store exists |
| `passage.list` | `admin_read` | same — nothing to read |
| `identity.get` | `admin_read` | no identity entity (the list is a stub) |
| `job.get` | `admin_read` | no job entity (the list is a stub) |

### `message.search` — decided: permanent denial

`message.search` has **no admin_rpc method** and therefore no matrix row; the
decision lives in `unrouted_domains.search`. The pinned 0.29.12 inventory has no
message-search command (`grep_in_files` / `search_files` are workspace *file*
search, not transcript search), and admin-shim implements it over its own
`lib/search.ts` index which the controller does not own. It must never be added
as an ad hoc proxy. Clients search locally over `message.list` pages.

## THE REMAINING UPSTREAM CEILING

This is now the whole of it — **12 write methods and 3 read methods**, all
already non-functional, each waiting on one of five upstream App Server
commands:

| Upstream ask | Would lift |
|---|---|
| a **tool-library** command (admin CRUD distinct from `update_toolset`) | `tool.create`, `tool.update`, `tool.delete`, `tool.attach`, `tool.detach` |
| a **global block** command (addressing a block by a stable global id) | `block.create`, `block.update`, `block.delete`, `block.attach`, `block.detach` |
| an **archival-memory / passage** command | `passage.list`, `passage.create`, `passage.delete` |
| an **identity** command (or a decision that the entity does not exist) | `identity.get` |
| a **job** command (or the same decision) | `job.get` |
| a **message/conversation search** command | `message.search` (no method today) |

Nothing on that list is served by lettashim either. **The wrapper no longer
needs lettashim's REST admin surface at all**: the ownership matrix declares
zero `admin_rest_service` rows, `LETTA_IROH_ADMIN_REST_BASE_URL` is no longer
consumed, and `AdminRestServiceInjectionTest` fails if either is reintroduced.

## What this means per issue

- **lgns8.9** — complete at its real scope. `project.*` went to VibeSync (#977);
  the 36 admin methods now have real owners or documented denials; the read-only
  store tier from #998 is re-wired and extended to runs/steps/context/blocks.
- **lgns8.10** (shim-off parity gate) — can now prove the admin surface too, not
  just the runtime. `ShimOffParityGateTest` derives its expectations from the
  matrix and covers four buckets: shim-free native, capability-gated,
  bounded-service (VibeSync only), and local-backend-store.
- **lgns8.11** (production cutover) — the admin role of the shim is retired.
  What still blocks full retirement is **channels**, not admin (see the addendum
  below), so `lgns8.11` should be marked blocked on `lgns8.23`/`q2b9z`.

## Shim-only surfaces: disposition (lgns8.25, 2026-07-31)

Four surfaces exist **only** in lettashim: they have no admin_rpc method, no
ownership row, and no reference anywhere in this repo. Left undecided they are
exactly the hole lgns8.10.3 ("fail closed where no approved owner exists") is
meant to close, and the shim-off gate (lgns8.10) cannot assert "nothing calls
this" without knowing who does. Each now carries a row in
`sharedLogic/src/jvmTest/resources/appserver/iroh-admin-ownership-matrix.json`
under `unrouted_domains`, enforced by
`IrohAdminOwnershipMatrixTest.everyShimOnlySurfaceCarriesADispositionAndCallerEvidence`.

### How the evidence was gathered

- **Repo:** `git grep` over `origin/main` for each path and its camel/snake
  spellings — zero hits for all four. (The only `usageSummary` symbols are the
  Dashboard's *client-side* calculation, not a call to the shim endpoint.)
- **Live traffic:** lettashim logs *every* request to `/tmp/admin-shim.log`
  (`StandardOutput=append:` in the unit; note the journal is empty, so
  `journalctl -u lettashim` is **not** a valid source here). The window
  analysed is **2026-07-23T14:40Z → 2026-07-31T05:48Z, 70,569 logged
  requests**. The log is credible-complete: the 60s healthcheck's `GET /`
  accounts for 10,925 rows, matching the expected ~11.5k for the window.
- **Other hosts on this box:** source-file grep across `/opt/stacks/*`, plus
  `crontab -l`, `systemctl list-timers`, and the running services' process
  environments.

### Disposition table

| Surface | Disposition | Evidence | Successor |
|---|---|---|---|
| `POST /v1/work-activity`, `PATCH /v1/work-activity/{id}` | **ALIVE** — keep on shim until successor | `vibesync.service` (running) boots `WorkActivityReporter` whenever `VIBESYNC_LETTA_CODE_SHIM_URL` is set; the running process env has `…=http://127.0.0.1:8291` and no `VIBESYNC_WORK_ACTIVITY_REPORT=0`. Dormant, not dead: 0 POSTs in the window because no `dispatcher/step.*` molecule events fired. | controller-native bounded ingest (proposed `subagent.ingest_external`) — `letta-mobile-lgns8.25.1` |
| `POST /v1/worker-events` | **DEAD** — deny fail-closed | Sole poster is the shim's own `scripts/letta-cli-sdk-wrapper.mjs`, and only when `LETTA_TASK_ID` is set (prod does not set it). 0 requests in the window. | none — the controller supervises its own sessions |
| `GET /shim/v1/usage/summary` | **DEAD** — deny fail-closed | 0 requests in the window; 0 repo references; mobile usage analytics are computed client-side from step records. Aggregates the shim's local run store, which is not the accounting authority. | none — LiteLLM gateway (`:4000`) is the authority if the need returns |
| `GET/PUT /shim/v1/permissions/global`, `GET /shim/v1/permissions/preview` | **DEAD** as an external surface — deny fail-closed | 0 requests in the window; 0 repo references. The evaluator still runs in-process for shim-served turns, but nothing drives the HTTP config/preview surface. | lgns8.12 Kotlin fail-closed policy layer already owns evaluation |
| `GET /shim/pool` | **DEAD route, successor required** | 0 requests in the window; 0 repo references; the only referencing consumer is the shim's own `scripts/prune-transient-agents.mjs`, which is manual and scheduled by neither cron nor a timer. | **sensing parity gap** — proposed read-only `health.diagnostics` — `letta-mobile-lgns8.25.2` |

### Two decisions worth restating

1. **`/v1/work-activity` was the triage's biggest miss.** A path-grep of this
   repo returns nothing, which reads as "dead"; the caller is a *different*
   service on the same host, configured and enabled but idle. "No traffic in
   the window" and "no caller" are different findings, and only the second
   justifies a denial. The shim-off gate must treat this route as a known
   ALIVE exception until lgns8.25.1 lands.
2. **`/shim/pool` may not simply be deleted.** It is the only window into
   pooled session state; controller-native introspection today reports just
   `health.check {status, controller_state, native}`. Retiring the route
   without the `health.diagnostics` successor trades a live diagnostic for a
   blind spot — the sensing-parity rule in the epic guardrail.

None of these become runtime domains, and none is exposed as a generic
admin_rpc passthrough: the one ALIVE surface, when ported, must be an
explicitly authorized `admin_write` with a source allow-list, and the pool
successor must be strictly read-only.

## Also outstanding

- **lgns8.17** (guarantee matched tool_call responses): real production bug
  (proven by the 2026-07-23 conversation corruption). The shim's turn-settlement
  + conversation-healer + approval-durability machinery
  (`admin-shim/lib/turn-settlement.ts`, `lib/conversation-healer.ts`,
  `lib/pending-approval.ts`) is the reference. Porting it to the Kotlin
  controller needs one design decision resolved first: the controller does not
  own the message store (the App Server does), so it cannot inject synthetic
  `tool_result`s directly the way the shim does — it must either drive the App
  Server to settle, or own settlement through the admin adapter path.
- **lgns8.16** (sleeptime/reflection parity): P1.

## Addendum — 2026-07-31

Three things moved on the same day; all three change what "retire the shim"
means in the near term.

### 1. Train #1077 landed

The batch merge of the lgns8 queue (`#1062`, `#1066`–`#1073`, `#1075`, `#1076`,
merged as `#1077`) is on `main`, including the list-theme work. That clears the
queue that had been blocking further lgns8 landings; the remaining epic
blockers below are dependency problems, not merge-order problems.

### 2. Channels: a hard UPSTREAM dependency, not a design choice

`letta-mobile-q2b9z` (P1) records the blocking finding for
`letta-mobile-lgns8.23`. Verified against the pinned `@letta-ai/letta-code`
`0.29.12`: `letta.js` ships the channel loader
(`discoverUserChannelRegistrations`, `startChannelAccount`), but
`initializeChannels()` is **unreachable** from `runAppServerSubcommand` — there
is no `--channels` flag on `app-server`, and `--channels` is explicitly rejected
when `--listen` is present. What actually imports `~/.letta/channels/*/plugin.mjs`
today is lettashim with `SHIM_CHANNELS_ENABLED=1`.

The consequence is blunt: **retiring lettashim right now takes Matrix and the
mobile channel host down with it** (~130 per-agent Matrix identities, 180
`routing.yaml` routes). With the admin ceiling now closed by lgns8.9, **channels
is the only remaining structural blocker to shim retirement**, and it sits
upstream of this repository.

### 3. lgns8.23 re-scope options

Given q2b9z, option (a) from lgns8.23 — channels as a capability of the
Kotlin-supervised App Server process — is **blocked on upstream** and cannot be
scheduled here. The realistic options are:

- **(a) Wait for upstream** to make `initializeChannels()` reachable under
  `--listen`. Cleanest end state, zero new processes, but the schedule is not
  ours. Track via q2b9z.
- **(b) Kotlin-supervised standalone channel host.** Run a second, dedicated
  `letta` process for channels under the same supervision the App Server already
  gets (`OwnedAppServerProcess`). Unblocks shim retirement without waiting on
  upstream, at the cost of one more supervised process and its lifecycle tests.
  This is the only option that retires the shim on our own schedule.
- **(c) Bounded service adapter.** Keep the shim process alive strictly as a
  channel host with every other route denied fail-closed. Smallest change, but
  it does not retire the shim — it renames the problem, and it keeps a Node
  service in the critical path for Matrix.

Recommendation: treat (b) as the plan of record and (a) as the preferred
long-run convergence, with (c) available only as a bridge if the retirement date
is forced. Either way, `lgns8.11` (full shim retirement) cannot close until one
of these lands — it should be marked blocked on `lgns8.23`/`q2b9z` rather than
carried as merely outstanding.

> **Superseded on 2026-08-01.** Options (a)/(b)/(c) above were resolved by
> measurement, not by choosing: see "3. Channels, resolved" in the final section
> below. Read the addendum for the reasoning, the final section for the outcome.

---

# Final status — 2026-08-01

Every **code** bead in the lgns8 epic has landed. What remains is not
implementation: it is three device runs and three operational steps. This
section is the epic's closing record.

The evidence behind every claim here is catalogued in
**`docs/testing/lgns8-acceptance-evidence-ledger.md`**, which grades each
acceptance dimension PROVEN-LIVE / PROVEN-HARNESS / PENDING-INTERACTIVE /
PENDING-OPS. This section states the conclusions; the ledger holds the proof.

## 1. The code is done

The final day merged nine PRs on top of the train:

| PR | Bead | What landed |
|---|---|---|
| #1077 | — | Train: batch merge of the lgns8 queue (#1062, #1066–#1073, #1075, #1076) |
| #1078 | — | CIO engine for the wrapper — OkHttp rejects the WS frame-size limit |
| #1079 | `wxy4s` | Application-level liveness probe for dead QUIC connections |
| #1080 | `lgns8.22.5` | `ApprovalRegistry` + `ExternalToolDispatcher` extracted |
| #1081 | `lgns8.10.4.1` | Legacy mobile WS shim connectors retired from the Iroh path |
| #1082 | `lgns8.9` | Admin REST adapter retired with real per-method owners |
| #1083 | `lgns8.23` | Controller-native channel restore behind `--channels-host` |

Preceded on the same day by #1055/#1056 (`or40x`), #1059 (`lgns8.21.1.1`), #1060
(`lgns8.25`), #1061 (`lgns8.22.4.1`), #1063 (`lgns8.21`), #1064 (`lgns8.21.7`),
#1065 (`8xxzv`), #1073 (`zsgad`) and #1074 (`jr5tx`).

**Production runs `dist 93908d8ec`** — the packaged wrapper distribution
carrying the full epic — from `/opt/meridian/iroh-wrapper/releases/` behind the
`current` symlink, with a wrapper-only environment file, `StateDirectory=meridian`,
and the NodeId and pairing store verified unchanged across the cutover.

That deployment was not a quiet one, and it is better evidence for it: the
packaged dist was deployed, took a production outage from the OkHttp frame-limit
regression (#1064 → #1077), had a rollback snapshot available, and was recovered
by rolling **forward** to `releases/hotfix-1078` with live verification
(`generation.ready attempt=0`, native admin routes succeeding, both devices
reconnected). Deploy, incident, rollback option, and verified redeploy inside one
day — see ledger §6.

## 2. The ceiling is exactly where .9 left it

`lgns8.9` closed the admin-REST question and nothing since has moved it. The
remaining upstream ceiling is unchanged from the table above:

- **15 methods, all writes or reads with no store behind them** — 12 write
  methods (`tool.*` CRUD/attach, `block.*` CRUD/attach, `passage.create`,
  `passage.delete`) and 3 reads (`passage.list`, `identity.get`, `job.get`),
  plus `message.search` which has no admin_rpc method at all. Every one is
  already non-functional today: lettashim 404s them. **Denying them is parity,
  not regression.** Each waits on an upstream App Server command (tool library,
  global block, archival memory, identity, job, search).
- **`letta-mobile-q2b9z` is now a nicety, not a blocker.** It was re-scoped on
  2026-07-31 from "upstream must make `initializeChannels()` reachable under
  `--listen`" (a hard blocker) to "boot-time restore would be nice upstream"
  (P3). The `channel_start` / `channel_account_start` / `channel_set_config`
  frames are reachable over the app-server WS under bare `--listen`, each
  routing `ensureChannelRegistry().startChannelAccount()` then
  `wireChannelIngress`. The only genuine gap was boot-time restore, and #1083
  closed it **controller-side** — the Kotlin controller re-issues
  `channel_start` for enabled accounts after each connect and reattach.

Nothing on the ceiling list is served by lettashim either. The wrapper does not
need the shim's REST admin surface at all.

## 3. Channels, resolved — and lettashim retirement de-risked

The single most consequential finding of the closing window is a negative one.

**The shim never hosted Matrix in production.** `/tmp/admin-shim.log` contains
**zero** `[matrix` lines across the analysed window — only `[mobile:default]`.
Live Matrix traffic runs through a separate legacy python client
(`python -m src.matrix.client`, the `matrix-tuwunel-deploy` stack). The
addendum's blunt claim above — "retiring lettashim right now takes Matrix and
the mobile channel host down with it (~130 per-agent Matrix identities, 180
`routing.yaml` routes)" — was **wrong on the Matrix half**. It was inferred from
`SHIM_CHANNELS_ENABLED=1` and the presence of the plugin loader, without
checking whether the shim's log ever showed it loading.

Three consequences:

1. **Only the `mobile` channel is shim-hosted**, and #1083 gives it a
   controller-native host behind `--channels-host`. That is the whole cutover.
2. **Matrix consolidation becomes a separate, unforced migration** — moving the
   python client onto the plugin path, on its own schedule, not on the shim's.
3. The patched `plugin.mjs` could be staged into `/root/.letta/channels/matrix/`
   with zero production risk, because nothing live imports it.

The (a)/(b)/(c) re-scope options are therefore moot: the answer is neither
"wait upstream" nor "run a second supervised process" nor "keep the shim as a
bounded channel host". It is **controller-native restore over the existing WS**,
which #1083 implemented and 14 tests guard.

One caution carries forward into the cutover: the double-host guard in #1083 is
an **announcement, not a detection**. Nothing checks whether lettashim is
concurrently hosting the same account. The stop-before-start ordering in the
runbook is mandatory — `SHIM_CHANNELS_ENABLED=0` and restart lettashim *first*,
verify, *then* start the wrapper with `--channels-host`. Never the reverse,
never both in one window.

## 4. A regression class closed, and why it was open

The `#1064 → #1077` frame-limit incident deserves recording as an epic-level
lesson rather than a footnote. #1064 applied a non-default `maxFrameSize` to
every App Server `/ws` client. Ktor's OkHttp engine rejects any non-default
`maxFrameSize` at connect time. The wrapper went dark in production; only #1078
fixed it, live.

Three tests existed over that exact code and none could have caught it: one
asserted the *constant*, one asserted the plugin *carried* the value on an
already-CIO client, and the lifecycle test connected with a hand-rolled client
that was not any production config. **No test performed a real connect with a
real production engine.** `letta-mobile-vnp3q` closes that structurally:
`AppServerProductionEngineConnectTest` drives a genuine connect and frame
exchange against an embedded Ktor WS server using each production client's exact
engine and `WebSockets` configuration, with an OkHttp negative control that
reproduces the incident on demand — so the positive cases are proven capable of
failing, not merely green.

## 5. What `lgns8.11` still needs

> **Superseded by "lgns8.11 close-out — 2026-08-01" below.** Items 2, 3 and 4 of
> this table were all executed in production during the night of 2026-08-01;
> item 1 is the only row that survives, and it needs a human at a device rather
> than a maintenance window. The table is kept unedited because it is the
> pre-cutover record, and rewriting it would erase what the cutover was actually
> gated on.

Cutover is gated on four items, all of them runs rather than code. They are the
PENDING rows of the evidence ledger:

| # | Item | Bead | Kind |
|---|---|---|---|
| 1 | Device protocol steps 1–3: concurrent conversations (UI half), Stop button (incl. first real Desktop abort), image pipeline | `lgns8.19`, `iej8j`, `eaczz.10` | PENDING-INTERACTIVE |
| 2 | Cron/scheduler execution handover — schedules must still fire with lettashim stopped, across a controller restart, with a logged missed-tick policy | `lgns8.24` (P0) | PENDING-OPS |
| 3 | Channels-host live cutover — flag on, `SHIM_CHANNELS_ENABLED=0`, in that order | `d7uls` (P1) | PENDING-OPS |
| 4 | Inbound channel delivery after a reconnect with no re-issued `channel_start` | `lgns8.23.1` | PENDING-OPS (folded into `d7uls`) |

Item 2 is the one most likely to be under-weighted. letta-code 0.29.12 ships
cron natively *and executes it* (`startScheduler()` under the listen path), and
the shim shares `crons.json` with the bundled CLI byte-for-byte — so this reads
like a solved problem. It is not: **CRUD coverage is not execution coverage.**
`PM-letta-mobile` runs on a `*/30` schedule and a silently stopped scheduler is a
silent failure. Prove a 1-minute test schedule firing across a controller
restart before the shim goes down.

---

# lgns8.11 close-out — 2026-08-01

`letta-mobile-lgns8.11` ("production cutover and lettashim retirement with
rollback") is **CLOSED** as of this section. This is the record it is closed
against.

The objective was never "kill a process". It was: **remove lettashim from every
role it held in the production path, with a rollback for each, and prove it
against production rather than against CI.** That objective is met to the
documented ceiling. What remains of lettashim is a single dormant ingest
endpoint with a named successor bead, and stopping the service is that
successor's final step — see "Why the service is still running" below.

## Role-by-role retirement record

| Role lettashim held | Owner now | Status | Evidence |
|---|---|---|---|
| **Runtime** — chat, turns, streaming, tools, approvals | App Server v2 under the Kotlin controller | **RETIRED** (long since) | The whole epic; ledger §1–§4. Concurrent-conversation E2E on the live appserver 2026-08-01 00:58 (`concurrentLeases count=2`, `terminal.scope_rejected`, both `Completed`). |
| **Admin REST** — the 36 `admin_rest_service` methods | `app_server_v2` (5) / `local_backend_store` (6) / `controller_native` (10) / fail-closed denial (15) | **RETIRED** | `lgns8.9`, PR #1082. The wrapper declares **zero** `admin_rest_service` rows and no longer consumes `LETTA_IROH_ADMIN_REST_BASE_URL`; `AdminRestServiceInjectionTest` fails if either returns. Ceiling §1–§2 above. |
| **Client transport** — the mobile app dialing `:8291` | Iroh QUIC → controller → App Server | **RETIRED**, and now measured on hardware | `lgns8.10.4.1` / PR #1081 made routing key on `BackendKind`; the device proof it explicitly deferred was taken 2026-08-01: **zero established `:8291` connections from the shipping client on the Pixel.** The earlier non-zero readings were a mis-scoped measurement against a six-week-old sibling package, not a failing fix. Ledger §5. |
| **Cron / scheduler execution** | The App Server process itself, lease-owned | **RETIRED**, sensed | Handover executed 2026-08-01 01:05 in a user-authorized window: lettashim **and its watchdog stopped**, `meridian-appserver` claimed the lease on its first WS connect (`crons.json scheduler_owner.pid` == the unit's `MainPID`), a `* * * * *` probe fired and **executed** against a production agent (`CRONFIRE-PROD-PROOF` in the transcript at `01:05:46` — the transcript, because the run log is inert), and the lease **stayed App-Server-owned after lettashim was restored**. `PM-30m` now fires natively. Ledger §8, `lgns8.24`. |
| **Channels host** — `mobile`, and the `matrix` plugin | The Kotlin controller (`--channels-host` / `LETTA_CHANNELS_HOST`) | **RETIRED** | Cutover executed 2026-08-01 ~01:5x on release `5311f99cd`, in the mandatory stop-before-start order: `SHIM_CHANNELS_ENABLED=0` + shim restart first, then the flag + wrapper restart. Shim log shows **zero** adapter starts; controller reports `restore_complete channels=2 started=2 failed=0`, `attempt=0`. The patched identity `plugin.mjs` runs under the upstream host as a fresh import. Ledger §7, `d7uls`. |
| **Work-activity ingest** — `POST /v1/work-activity` | still lettashim | **NOT retired** — successor filed | Found ALIVE by the 70,569-request live-traffic disposition (`lgns8.25`, PR #1060): `vibesync.service` calls it from the same host, configured and enabled but idle. Successor: `letta-mobile-lgns8.25.1`. |

Cron execution and channels hosting were the two roles that could have taken
production down silently if the shim had simply been stopped. Both were found by
the 2026-07-30 shim-route cross-audit **because CRUD coverage is not execution
coverage**, and both are now transferred with live proof rather than inference.

## Why the service is still running, and whose step it is to stop it

lettashim is `RUNNING` on the Meridian host and its port is bound. That is not
an unfinished cutover; it is the documented ceiling. **Every role in the table
above except work-activity ingest is retired**, and the host-side evidence
matches: `:8291` is `LISTEN` but carries no device traffic, only a loopback
lease ping.

The service-stop is deliberately **not** `lgns8.11`'s final step. Stopping it
today would take `vibesync.service`'s ingest with it — a live caller with no
successor yet — which is precisely the class of silent breakage this epic spent
its last week hunting. The stop belongs to `letta-mobile-lgns8.25.1`
(controller-native external work ingest), as that bead's last action, once the
successor endpoint exists and `vibesync.service` points at it.
`letta-mobile-lgns8.25.2` (controller-native pool/session diagnostics, replacing
`GET /shim/pool`) removes the remaining sensing reason to keep it.

Closing `.11` on a running-but-roleless shim is the honest grade. Holding it
open until a process disappears would have made the bead track an operational
chore rather than the retirement it actually recorded.

## Rollback inventory

Every role transferred above has a rollback, and none of them requires a rebuild.

| What | How to roll back | Notes |
|---|---|---|
| **Wrapper release** | Re-point `/opt/meridian/iroh-wrapper/current` at a prior `releases/<sha>` directory and restart the unit. Current release: `5311f99cd`. | NodeId and pairing state live **outside** the release dir (`/etc/meridian/iroh-secret.key`, pairing store), so paired peers survive a roll in either direction. Proven in anger on 2026-07-31 (§4 / ledger §6). |
| **Pre-train launcher snapshot** | `/root/meridian-rollback-pretrain` plus the captured-classpath launcher `/etc/meridian/run-iroh-cli.sh` + `iroh-wrapper-classpath.txt`, built from the `meridian-deploy` worktree at `1606b4d2d`. | Last-resort path only. Do **not** delete the `meridian-deploy` worktree — it is the only artifact predating the frame-ceiling regression class. |
| **Channels host** | `LETTA_CHANNELS_HOST=0` in `/etc/meridian/iroh-wrapper.env` + wrapper restart; then `SHIM_CHANNELS_ENABLED=1` in the shim's `channels.conf` + shim restart. | **Inverse order, and never both hosts live.** The double-host guard is an announcement, not a detection. A rollback-and-retry must re-honour stop-before-start. |
| **Channel plugin** | The pre-patch `plugin.mjs` backup under `/root/.letta/channels/matrix/`. | The patched copy is the one that passes per-agent sender identity; reverting it collapses every Matrix reply to the account default. |
| **Cron lease re-claim** | Order matters: stop `meridian-appserver` (a clean SIGTERM releases the lease), **then** start lettashim so it claims. Never start a second claimant against a live holder. | The lease is claimed at **first WS connect**, not at boot — "the app server is up" does not mean "cron is running". A restart always costs ≥1 tick (the startup tick never fires), so treat only **two** consecutive missing occurrences as a failure. |
| **Cron sensing** | `systemctl disable --now meridian-cron-sensing.timer`. | Read-only sensor; disabling it removes detection, never function. |

## Surviving tracked debt

Nothing below blocks the retirement; all of it is filed, prioritised and owned.

**Internal, P2 — the TurnEngine convergence program.**
`letta-mobile-lgns8.22.7` (converge all hosts on the shared coordinator and
reduce `TurnEngine` to orchestration) and `letta-mobile-o97nk.1` (extract the
turn lifecycle into an explicit state machine). This is the architectural
follow-through of the epic, not a defect backlog.

**Upstream asks — filed with measured evidence and a concrete request.**

| Bead | Ask |
|---|---|
| `letta-mobile-mocf1` | Recurring cron ticks missed across a restart are skipped silently — no catch-up, no `missed_count`, plus the startup-tick gap. Asks for replay from a persisted `last_tick_at` or explicit missed accounting. |
| `letta-mobile-xsxwd` | The cron scheduler lease loser gives up permanently after 3×30s with only a `console.error`, its budget spent from first-WS-connect. Asks for backoff retry and/or a structured, queryable give-up signal. |
| `letta-mobile-yoa82` | An inbound channel event enqueued against a dead socket runs no turn and emits no failure signal (`o5bqk`'s upstream half). |
| `letta-mobile-q2b9z` | Let `app-server --listen` initialize the channel registry at boot. **P3 nicety** since #1083 solved boot-restore controller-side. |
| `letta-mobile-z0bi7` | A `--stdio` app-server mode, which would unblock `lgns8.18` Path B (true no-socket IPC). |

**Successors.** `letta-mobile-lgns8.25.1` (work-activity ingest — owns the
lettashim service-stop), `letta-mobile-lgns8.25.2` (pool/session diagnostics),
`letta-mobile-7dm1q` (host skill-root enumerator, unblocks `skill.list`).

**Still open above `.11`, deliberately.** `letta-mobile-lgns8.10` (the
acceptance gate) and the `letta-mobile-lgns8` epic remain open on the
interactive device rows — `lgns8.19` (Stop button), `iej8j` (image pipeline) and
the concurrent-conversation UI presence check. Those need a human holding the
Pixel, and no amount of production evidence substitutes for them.
