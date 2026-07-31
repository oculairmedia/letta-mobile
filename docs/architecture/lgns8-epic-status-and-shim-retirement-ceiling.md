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
