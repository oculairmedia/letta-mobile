# lgns8 epic: status, achievable scope, and the shim-retirement ceiling

Date: 2026-07-23

Beads: `letta-mobile-lgns8` (epic) and children .9/.10/.11/.16/.17

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
