# lgns8 epic: status, achievable scope, and the shim-retirement ceiling

Date: 2026-07-23 (shim-only surfaces disposition + train/channels addendum appended 2026-07-31)

Beads: `letta-mobile-lgns8` (epic) and children .9/.10/.11/.16/.17/.23/.25, plus
`letta-mobile-q2b9z` (upstream channel-registry blocker)

## Why this document

The lgns8 epic is "Supersede lettashim with a Kotlin-controlled Letta App
Server v2." Implementation of the runtime-native path (.3–.8, .12–.15) and the
d6e8g security epic is merged. This note records a load-bearing architectural
finding that bounds what "supersede/retire lettashim" can mean today, so the
remaining issues (.9/.10/.11) can be scoped and closed honestly rather than
left implying a retirement that the architecture cannot deliver.

## The finding: lettashim IS the admin backend

The real Letta App Server (`letta app-server --listen`) exposes **only** the
runtime WebSocket v2 protocol (`runtime_start`, `input`, `sync`,
`abort_message`, approvals, native `agent_*`/`conversation_*`/`list_models`/
`skill_*`/`cron_*` commands). It has **no REST admin API**.

lettashim (`admin-shim/server.ts`, `:8291`) is the **only** implementation of
the `/v1/*` admin REST surface — a local, file-backed emulation
(`backend: "letta-code-local"`), not a proxy in front of a real App Server. For
`/api/*` (projects) it reverse-proxies to VibeSync (`:3099`).

Consequence: the 39 `admin_rest_service` admin_rpc methods (runs/steps,
archives/folders/passages/groups, identities, models/providers, schedules/jobs,
tools, blocks, mcp, goals, slash-commands) have **no native backend to move
to**. The lgns8.13 ownership matrix already encodes this — every one of those
rows is `fallback: shim_until_cutover`.

## What this means per issue

- **lgns8.9** (replace admin proxies with injected services): achievable part
  landed in PR #977 — `project.*` now calls VibeSync `:3099` directly (off the
  shim splice), plus the `CapabilityUnavailable` degradation pattern. The 39
  admin methods can only be *structurally* refactored (injected/testable
  wrappers around the same shim calls); their network target must remain the
  shim because there is no alternative. 8 of them (`folder.list`, `group.list`,
  `identity.list`/`.get`, `mcp.list`, `job.list`/`.get`, `step.list`,
  `archive.list`) are shim **stubs that return `[]`** — deprecating them to
  capability-unavailable would regress the mobile admin screens for no gain, so
  they stay. `message.search` has no Iroh surface → permanent denial.

- **lgns8.10** (shim-off parity gate): can prove the **runtime** path works with
  the shim's runtime role off (native turns + native admin ops succeed, bounded
  admin degrades to capability-unavailable without failing chat). It **cannot**
  prove the admin surface works shim-off, because the shim is the admin backend.

- **lgns8.11** (production cutover / lettashim retirement): **inherently
  partial**. The runtime role can be retired (turns/agents/conversations go
  native). The admin role **cannot** be retired until the upstream App Server
  gains a REST admin API — outside this repo. `lettashim retired` cannot be
  made true here.

## Recommended scope resolution

Close lgns8.9/.10/.11 at their **achievable** scope (runtime path native and
off-shim; admin surface bounded, injected, and gracefully degrading), and split
the admin-surface-off-shim work into a new issue explicitly **blocked on
upstream App Server REST admin**. That keeps the epic honest: the Kotlin
controller is the runtime authority and the bounded admin adapter owner; the
shim remains only as the admin datastore until upstream closes the gap.

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
`routing.yaml` routes). This is not the ceiling described earlier in this
document — that ceiling was about admin surfaces the App Server does not expose.
This is a second, independent ceiling on the channels surface, and it sits
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
