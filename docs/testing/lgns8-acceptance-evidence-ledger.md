# lgns8.10 acceptance evidence ledger

Date: **2026-08-01** · Bead: `letta-mobile-lgns8.10` (shim-off parity,
failure-injection, and multi-client acceptance gate) · Consumers:
`letta-mobile-lgns8.11` (production cutover), `letta-mobile-eaczz.10` (device
acceptance checklist)

## Why this document

`lgns8.10` is the release gate for running with lettashim stopped. Its evidence
has been accumulating as bead comments, PR bodies, probe artifacts and live host
logs across two weeks. That is not a gate — it is a pile. This ledger is the
single readable answer to "what is actually proven, by what, and how strongly",
so `lgns8.11` can be scheduled against facts rather than recollection.

It is a **ledger, not a protocol**. The protocol for the interactive rows is
`docs/testing/lgns8-e2e-device-protocol.md`; the operational steps for the
PENDING-OPS rows are in
`docs/architecture/lettashim-retirement-deployment-runbook.md`.

## Status vocabulary

| Status | Meaning |
|---|---|
| **PROVEN-LIVE** | Observed against production (live wrapper / appserver / store), with a recorded artifact. The strongest class. |
| **PROVEN-HARNESS** | Proven by an automated test or an executed probe, in CI or against an isolated throwaway backend. Deterministic and fail-on-revert, but not production. |
| **PENDING-INTERACTIVE** | Requires a human at a device. Protocol written, run not yet performed. |
| **PENDING-OPS** | Requires a production maintenance window. Runbook written, cutover not yet performed. |

A row is only PROVEN-LIVE if something in production actually did the thing.
"The code is merged and CI is green" is PROVEN-HARNESS, never PROVEN-LIVE — the
distinction is the whole point of this ledger.

---

## 1. Concurrent conversations do not block each other

**The epic's headline defect**: two conversations froze each other on-device;
one conversation's terminal was misattributed to another's scope (`or40x`), and
the turn-engine lease was keyed too coarsely (`8xxzv`).

| Evidence | Status |
|---|---|
| **Production E2E, 2026-08-01 00:58.** `app-server-smoke` in concurrent mode against the LIVE appserver (`ws://127.0.0.1:4500`, agent `agent-ca46df7f`, two real store conversations): `activeTurn.concurrentLeases count=2`; both turns streamed genuine model deltas; `terminal.scope_rejected` correctly fenced conversation 1's terminal from conversation 2's scope — the exact `or40x` misattribution class, observed being rejected; both reached `[lifecycle] Completed`; harness verdict "both concurrent turns reached a terminal". | **PROVEN-LIVE** |
| PR #1055 — Iroh turn state keyed by `conversationId` (`or40x` 1/2); PR #1056 — coordinator turn identity keyed by conversation (`or40x` 2/2); PR #1065 — turn-engine lease keyed per `{agent, conversation}` (`8xxzv`). Each carries fail-on-revert unit coverage. | PROVEN-HARNESS |
| On-device confirmation of the *UI* half (no cross-conversation thinking indicator, both settled conversations visible) — protocol step 1. | **PENDING-INTERACTIVE** |

The runtime defect is proven fixed end to end on production. What remains is
confirming the UI projection of it on a real device.

## 2. Multi-client convergence and self-recovery

| Evidence | Status |
|---|---|
| **Production incident + recovery, `letta-mobile-wxy4s` (CLOSED).** The 2026-07-31 ~11:00–11:40 outage: a host reboot restarted the wrapper, both clients reconnected, then their QUIC connections died silently and neither app noticed — ~40 minutes of perceived outage while every service was healthy. Root cause recorded in full: the 15s **unacked** keepalive datagram both failed to detect death and reset the local QUIC idle timer, actively masking it. Fixed by PR #1079 (application-level liveness probe over a fresh QUIC bidi stream → existing supervisor redial path). Post-fix dual-client self-recovery measured at **41s**, inside the designed ~45s worst-case envelope. | **PROVEN-LIVE** |
| PR #1079 fail-on-revert coverage: probe arming/disarming pinned to a handle generation; loss reports carry handle attribution (the `r3i1z` regression class — an unattributed loss report destroys a healthy redialed handle). | PROVEN-HARNESS |
| Two-device convergence *on devices* (Pixel + Desktop, protocol step 4 second half): recovery without manual redial after `systemctl restart meridian-iroh-wrapper`. | **PENDING-INTERACTIVE** |

## 3. Restart replay, turn identity, and ambiguous-write durability

| Evidence | Status |
|---|---|
| **`AppServerRestartReplayProbe` re-executed against letta-code 0.29.9 AND 0.29.12** on Node v24.18.0, each against an isolated throwaway `LETTA_LOCAL_BACKEND_DIR` (live `/root/.letta/lc-local-backend` untouched). No behavioural delta between versions: agent + conversation survive process restart with `created` flags false, the committed transcript survives, a replayed `client_message_id` still duplicates (otid count 2 — no server-side dedup), and the `durability` / `identity_scopes` / `ambiguity` / `reconciliation_rules` blocks are unchanged. Evidence artifact repinned to **0.29.12** (`source.version` + `PINNED_LETTA_CODE_VERSION`), so version drift now hard-fails instead of silently skipping. Bead `letta-mobile-lgns8.21.1.1` (CLOSED), PRs #1044 / #1059. | PROVEN-HARNESS |
| Two probe defects found and fixed while regenerating — both would have corrupted a future regen: (1) the probe passed green with **zero** assistant messages committed, because an errored turn still commits the user message and still emits a terminal `stop_reason` — a misconfigured provider was a vacuous pass, now a hard failure; (2) with a local backend, `letta connect` persists provider auth under the *backend* dir, not `HOME`, so a fresh throwaway backend had no provider at all (the cause of (1)) — `LETTA_CODE_PROBE_PROVIDER_AUTH` and `LETTA_CODE_PROBE_HOME` added. Regeneration recipe documented in `appserver-cli/README.md`. | PROVEN-HARNESS |
| Mid-conversation `systemctl restart meridian-appserver` from a device, with the next send working without an app restart — protocol step 4 first half. | **PENDING-INTERACTIVE** |

The vacuous-pass finding is the load-bearing one: this row was previously
*believed* proven on evidence that could not have failed.

## 4. Transport correctness under a real production engine

| Evidence | Status |
|---|---|
| **`AppServerProductionEngineConnectTest`** (`sharedLogic` jvmTest, `letta-mobile-vnp3q`): an embedded Ktor WS server plus a **real connect and frame exchange** using the exact engine + `WebSockets` config each production client constructs — the iroh-wrapper live and stub controllers, `createDesktopLettaHttpClient`, `DesktopWsChannelTransport.defaultWsClient`, and the appserver-cli restart-replay probe. Plus a negative control that reproduces the incident class on demand: `HttpClient(OkHttp) + applyAppServerFrameLimits()` must fail the connect with `"Max frame size switch is not supported in OkHttp engine"`. | PROVEN-HARNESS |
| **The incident it closes.** PR #1064 added a non-default `maxFrameSize` to every App Server `/ws` call site; Ktor's OkHttp engine rejects any non-default `maxFrameSize` at connect. Nothing caught it — existing coverage asserted the *constant*, or asserted the plugin *carried* the value on an already-CIO client, or connected with a hand-rolled test client that was not a production config. The production wrapper's App Server link died on 2026-07-31 (reconnect exhausted → `GaveUp` → every native admin route dead) and was fixed live by PR #1078. **Post-fix production verification: `generation.ready attempt=0`, `agent.list` / `message.list` `outcome=success` native, both devices reconnected and authed.** | **PROVEN-LIVE** (the fix) |

The structural lesson is recorded in the test itself: no amount of unit coverage
over a config value substitutes for one real connect with the real engine.

## 5. Shim-off admin parity (no hidden HTTP fallback)

| Evidence | Status |
|---|---|
| **The admin ceiling is closed.** The `admin-shim/server.ts` route audit on the 0.29.12 pin found that 16 of 36 `admin_rest_service` methods were routes admin-shim never registered (404 today), and the rest were on-disk store reads, hard-coded constants, `stubList` empties, or thin cron translations. All 36 now have a native owner (`app_server_v2` / `local_backend_store` / `controller_native`) or a typed fail-closed denial. The wrapper declares **zero** `admin_rest_service` rows; `LETTA_IROH_ADMIN_REST_BASE_URL` is no longer consumed. PR #1082 (`lgns8.9`). | PROVEN-HARNESS |
| `AdminRestServiceInjectionTest` fails if the REST adapter or its env var is reintroduced; `ShimOffParityGateTest` derives its expectations from `iroh-admin-ownership-matrix.json` across four buckets (shim-free native, capability-gated, bounded-service, local-backend-store); `IrohAdminOwnershipMatrixTest` requires a disposition + caller evidence for every shim-only surface. | PROVEN-HARNESS |
| **Shim-only surface disposition on live traffic** (`lgns8.25`, PR #1060): 70,569 logged requests over 2026-07-23T14:40Z → 2026-07-31T05:48Z, credible-complete (the 60s healthcheck's 10,925 `GET /` rows match the expected ~11.5k). One surface found **ALIVE** and nearly missed — `/v1/work-activity`, called by `vibesync.service` on the same host, configured and enabled but idle. "No traffic in the window" and "no caller" are different findings. | **PROVEN-LIVE** |
| PR #1063 — no-HTTP gate attributed to the wrapper with parity assertions derived from the matrix; PR #1081 — legacy mobile WS shim connectors removed from the Iroh path (`lgns8.10.4.1`). | PROVEN-HARNESS |
| Running the full gate with lettashim actually **stopped** in production. | **PENDING-OPS** |

## 6. Deployment, ownership fencing, and rollback

| Evidence | Status |
|---|---|
| **Live cutover to the packaged dist, 2026-07-31** (`letta-mobile-r6221`, CLOSED; code in PR #1073 / `zsgad`). Release installed at `/opt/meridian/iroh-wrapper/releases/d689d7c66` behind a `current` symlink; unit from the repo template plus `Environment=JAVA_HOME=java-21`; wrapper-only env at `/etc/meridian/iroh-wrapper.env` (**provider keys are no longer visible to the wrapper** — the appserver.env inheritance is gone); `StateDirectory=meridian` with the subagent registry at `/var/lib/meridian/subagent-registry.json` (`subagent_registry_v1: true`). **Verified: NodeId unchanged `330415cc…`, pairing store intact, 89 handlers, fresh authed dial `connect.ok direct`.** | **PROVEN-LIVE** |
| **Rollback path exercised in anger, same day.** The OkHttp incident (§4) hit the freshly-deployed wrapper. Rollback snapshot retained at `/root/meridian-rollback-pretrain`; recovery was performed by rolling forward instead — dist rebuilt from the fix branch (merged as #1078 with green CI), installed at `releases/hotfix-1078`, `current` re-pointed. **Redeploy verified live at 19:5x**: `generation.ready attempt=0`, native admin routes succeeding, both devices reconnected, user-confirmed mobile connectivity. The release-directory layout did what it was designed to do — keys and pairing state live outside the release dir, so both roll-back and roll-forward preserve paired peers. | **PROVEN-LIVE** |
| Template bug found by the deploy and fixed in-flight: the unit template omits the `JAVA_HOME` pin, and the dist targets class-file 65 while the system java is 17. | **PROVEN-LIVE** |
| PR #1074 — the iroh probe declares wrapper-scan mode instead of assuming systemd (`jr5tx`), so the gate reports honestly off-host. | PROVEN-HARNESS |

This row is stronger than a green deploy: a deploy, a production incident, a
rollback option, and a verified redeploy inside one day.

## 7. Channels host (Matrix + mobile plugins)

| Evidence | Status |
|---|---|
| **Empirical probe executed 2026-07-31** (`letta-mobile-lgns8.23.1`), against a bare `letta --listen` on a throwaway HOME / backend / ports 47731–47733, with live store, channels and ports 4500/4501/8291 untouched and mtimes verified unchanged afterwards. **(a) Plugin loads under upstream — YES**: a bare WS connection with *no handshake at all* answers `channels_list` with both our `matrix` and `mobile` plugins discovered from `channel.json`; a `channel_account_start` against a deliberately unreachable homeserver returned `plugin.mjs:296` verbatim, proving `ensureChannelRegistry()` → `loadChannelPlugin` → `createAdapter` all ran. **(b) A real account reaches running — YES**: `channel_start` returned `running: true` with `channels_updated`, and server stdout showed the dedicated sync identity distinct from the send/echo identity. Boot-restore confirmed **absent** under bare `--listen`, and confirmed restorable by client-issued `channel_start`. | PROVEN-HARNESS |
| **Production discovery, 2026-07-31 maintenance window: the shim does NOT host Matrix.** `/tmp/admin-shim.log` has **zero** `[matrix` lines (only `[mobile:default]`); live Matrix traffic runs through the separate legacy python client (`python -m src.matrix.client`, `matrix-tuwunel-deploy` stack). This materially de-risks lettashim retirement: only the **mobile** channel is shim-hosted, and Matrix consolidation onto the plugin path becomes a separate, unforced migration. It also meant the patched `plugin.mjs` could be staged to `/root/.letta/channels/matrix/` with zero production risk. | **PROVEN-LIVE** |
| PR #1083 — controller-native channel restore behind `--channels-host` / `LETTA_CHANNELS_HOST`, **default OFF**. 14 new tests (`ChannelRestoreCoordinatorTest`, `ChannelFrameFanoutIsolationTest`) with fail-on-revert verified by temporarily reverting each guard in turn (skip-if-running → reconnect re-issue test failed; re-assert-only-between-attempts → landmine test failed; broadcast unscoped frames → fanout isolation test failed; all reverts restored). CI green on all 21 checks. | PROVEN-HARNESS |
| **Inbound round trip — PROVEN, executed 2026-08-01** (probe #4, second throwaway env, live untouched). A REAL Matrix event posted by the account's dedicated sync identity was picked up by the adapter, reached `wireChannelIngress`, resolved its route, enqueued on the routed runtime, **executed the turn**, and replied back through the channel via the `MessageChannel` tool — the reply event was then fetched back from the homeserver to confirm. So inbound → ingress → turn → outbound works under bare `--listen` with no shim. **Routes are provisionable over the WS**: `channel_route_update` writes `~/.letta/channels/<id>/routing.yaml` (JSON despite the name) and the on-disk file matched the accepted frame exactly — no file surgery needed by the controller. Reply identity landed as the shared account, consistent with the (c) plugin-side identity regression already recorded (corroborating, not an independent proof — the throwaway agent had no seeded identity). | PROVEN-HARNESS |
| **The reconnect question is answered, and the answer was a new defect: `letta-mobile-o5bqk`.** On a fresh socket that had issued only `runtime_start`, inbound *did* still fire from the OLD socket-bound ingress closure — event received, typing indicator posted to the real room, route resolved, item **enqueued** (visible verbatim in `update_queue`) — but **the turn never ran** for the ~100s the client stayed attached, with no error and no channel-side failure notice; it drained ~2 min later on the NEXT connect. `scheduleQueuePump` bails on the dead captured socket: the same enqueue-but-never-drain shape as the cron defect in §8 (3c/3d). PR #1083's re-issue on generation-ready is confirmed load-bearing (with it, inbound replied in 5s), but it does not cover the window between socket-open and that re-issue. | PROVEN-HARNESS (defect) |
| **This PR** — client-side mitigation for the window. The restore now issues `channel_start` for every account the previous enumeration found enabled **before** `channels_list`, from a process-lifetime `ChannelAccountCache` that outlives the socket, shrinking the window from `1 + 1 + <channel count>` round trips to one. Five new tests, four of which fail when the fast path is removed and a fifth when the "don't start twice in one pass" dedup is removed. **Residual, stated honestly:** the window is shortened, not closed — the enqueue-against-a-dead-socket silent drop lives inside upstream's ingress closure and cannot be fixed from the client. An inbound event landing inside the surviving window still shows typing-then-silence and drains on a later connect. Upstream ask filed with the probe transcript. Second residual: an account disabled between generations is started once from the cache before the enumeration contradicts it; there is no `channel_stop` on this client, so that case emits a WARN (`fast_path_started_stale_account`) rather than being silently resurrected. | PROVEN-HARNESS |
| Live cutover: quiesce → stop wrapper → `SHIM_CHANNELS_ENABLED=0` + restart lettashim → `LETTA_CHANNELS_HOST=true` + start wrapper → verify `[matrix:*] started` appears **exactly once** per account → inbound/outbound identity checks → force a reconnect and repeat. `letta-mobile-d7uls` (P1). **The double-host guard is an announcement, not a detection** — nothing checks whether lettashim is concurrently hosting, so the stop-before-start ordering is mandatory and must never be reversed or combined into one window. | **PENDING-OPS** |

## 8. Cron / scheduler execution ownership

| Evidence | Status |
|---|---|
| **Scope shrank on inspection.** letta-code 0.29.12 ships cron natively and first-class: the full `Cron{List,Add,Get,Runs,Trigger,Update,Delete,DeleteAll}Command` protocol plus `CronsUpdatedMessage`, and *real execution* — `startConnectedListenerRuntime()` calls `startScheduler()` when `shouldStartCronScheduler`, and the app-server listen path passes `startCronScheduler: true` (kill switch `LETTA_DISABLE_CRON_SCHEDULER=1`, set nowhere in this repo or the shim). `cron` is a first-class `QueueItemSource`, so a fired cron enters the same turn queue as user or channel input. The shim does **not** own a separate cron model — `admin-shim/lib/cron-scheduler.ts` says so explicitly: it shares `crons.json` with the bundled letta cron CLI, byte-for-byte. | PROVEN-HARNESS (source-verified on the pin) |
| **Execution PROVEN on the harness, 2026-08-01** (`lgns8.24` probe, third throwaway env: no shim anywhere, own HOME/backend/port, live `crons.json` and the `*/30` PM schedule untouched). **(1) The lease is claimed by the app-server process itself — at FIRST WS CONNECT, not at boot.** `crons.json` did not exist after 12s of idle listening; `scheduler_owner` appeared only once a client attached (`attachOpenListenerSocket({startCronScheduler:true})`), carrying the `--listen` PID. Confirmed again across a restart with the new PID. **(2)** `cron_add` takes a real cron expression; `* * * * *` is the floor (`TICK_INTERVAL_MS` = 60000) and was accepted over a bare WS. **(3) Three consecutive 1-minute fires really executed** — proven by the committed transcript (`messages.jsonl`), not the run log: three `user` fire-prompts at exactly 60s spacing, each answered by the agent against a real provider. `cron_delete` takes effect immediately with no leaked timer. | PROVEN-HARNESS |
| **Four defects the same probe measured, all of which shape the cutover.** **(3b) The run log cannot discriminate.** Every entry — executed and dark alike — reads `status:"ok", outcome:"queued", reason:"scheduled_time_matched"`; there is no terminal executed/succeeded outcome written at all. **Any gate or alert built on cron run-log outcomes is inert; only the conversation transcript or an external heartbeat can tell a live scheduler from a dead one.** **(3c) Zero-client ticks are dark** (settles `lgns8.24.1` empirically): with the client disconnected the scheduler keeps ticking and enqueueing, but `scheduleQueuePump` bails on the closed transport and no turn runs. **(3d) The drain on reconnect is LOSSY — this corrects the bead's own "everything drains at once" note.** Three buffered dark fires produced exactly ONE turn on reconnect, carrying the OLDEST occurrence's (3-minute-stale) timestamp; the other two were silently discarded with no error, no `failed_count`, and run-log entries still reading `ok`. For a `*/30` heartbeat: hours dark → one late, mislabelled run → no signal that the rest were lost. **(4) Missed-tick policy on restart is silent skip, with no accounting.** A clean SIGTERM releases the lease; the minutes the server was down produced no run-log entry, no turn, and `missed_count` stayed 0 (`shouldFireTask()` is a pure `cronMatchesTime` check; missed accounting exists only for one-shot tasks). **(4b) The startup tick never fires** (2/2 reproducible): the first fire is always exactly +60s after the lease, so a restart costs at least one tick — the real skip window is downtime *plus* the remainder of the restart minute. | PROVEN-HARNESS (defects) |
| **THE PRODUCTION HANDOVER, EXECUTED 2026-08-01 01:05** (user-authorized maintenance window). lettashim and its watchdog stopped; `meridian-appserver` restarted and **claimed the scheduler lease on its first WS connect** — `/root/.letta/crons.json` `scheduler_owner.pid = 2796361`, identical to the unit's `MainPID`. A `* * * * *` probe schedule added over the App Server WS **fired and executed against the production agent**: `CRONFIRE-PROD-PROOF` is in the production conversation transcript at `01:05:46` (the transcript is the sensor; the run log is inert per 3b). `cron_delete` clean. lettashim was then restored and **the lease stayed App-Server-owned** (still pid `2796361` with lettashim running) — the ownership transfer is durable across a shim restart, and cron execution is permanently retired from the shim. `letta-mobile-lgns8.24`. | **PROVEN-LIVE** |
| **The sensing the defects force is INSTALLED, not merely specified** (2026-08-01). `scripts/deploy/cron-sensing-check.sh` plus `meridian-cron-sensing.{service,timer}` run every 15 minutes on the Meridian host, logging to `/var/log/meridian-cron-sensing.log`. Two halves, both required: (i) the lease is held by a live process that really is the process it claims to be — non-null `scheduler_owner`, pid alive, `/proc` start-ticks equal to the recorded `process_start_ticks` (a recycled pid fails), `boot_id` equal to the running one (a previous-boot lease fails); (ii) every enabled recurring task has a `Scheduled task "<name>" is firing` prompt in its **conversation transcript** newer than `2 x cadence + 5min`, else FAIL. An un-parseable cadence is reported UNSENSED, never a silent pass. First live run under systemd exited 0 against production: lease `pid 2796361` verified on all four identity checks, `letta-mobile-pm-30m` last fired `2026-08-01T05:01:04.999Z`, 796s old against a 3900s budget. This is detection, not recovery — the two defects it senses are upstream. | **PROVEN-LIVE** |
| **The two client-unfixable defects are filed upstream with the measured evidence.** (4)/(4b) — silent missed ticks, no catch-up, no `missed_count`, and a startup-tick gap that costs one tick on every restart — is `letta-mobile-mocf1`, asking for either replay from a persisted `last_tick_at` or an explicit missed-count. The lease loser giving up permanently after 3x30s with only a `console.error`, its budget spent from first-WS-connect rather than boot, is `letta-mobile-xsxwd`, asking for backoff retry and/or a structured, queryable give-up signal. Both are scheduler internals inside letta.js; neither is reachable from the client. `lgns8.24.2` and `lgns8.24.3` close as **measured + mitigated + upstream-referenced**. | PROVEN-HARNESS (defects, upstream-blocked) |

Execution is no longer the unknown — a bare `letta app-server --listen` with no
shim claims the scheduler lease itself and really runs the fired turn end to
end, and since 2026-08-01 the production App Server does exactly that. What
replaced that unknown is worse-shaped and now measured: cron failure is
**invisible from the cron surfaces themselves**. A silent scheduler was always
the risk; (3b) proves the run log will report `ok` throughout one. That is why
this row is only PROVEN-LIVE *with* the external sensor: the handover alone
would have moved the scheduler onto a surface where its own death is
unobservable. The sensor reads the conversation transcript because nothing
inside cron discriminates, and it fails closed — nonzero exit, no self-healing,
a human decides. Recovery from the two sensed defects requires upstream
(`letta-mobile-mocf1`, `letta-mobile-xsxwd`); detection does not, and is
installed.

---

## Fail-on-revert evidence catalog, PRs #1055–#1083

Every merged PR in the epic's final train, one line each. "Fail-on-revert" means
the PR shipped a test that was demonstrated to fail without the fix.

| PR | Bead | What it proves |
|---|---|---|
| #1055 | `or40x` (1/2) | Iroh turn state keyed by `conversationId` — a second conversation can no longer evict the first's turn state. |
| #1056 | `or40x` (2/2) | Coordinator turn identity keyed by conversation — terminals stop being misattributed across scopes. |
| #1059 | `lgns8.21.1.1` | Restart-replay evidence regenerated and repinned to 0.29.12; the vacuous-pass hole (zero assistant messages) now hard-fails. |
| #1060 | `lgns8.25` | Four shim-only surfaces dispositioned against 70,569 live requests; matrix test requires caller evidence for each. |
| #1061 | `lgns8.22.4.1` | Inbound control registry review follow-ups. |
| #1063 | `lgns8.21` | No-HTTP gate attributed to the wrapper; parity assertions derived from the ownership matrix rather than hand-listed. |
| #1064 | `lgns8.21.7` | Byte-bound context preflight inspection — the inbound frame ceiling. *(Also the origin of the OkHttp incident; see #1078.)* |
| #1065 | `8xxzv` | Turn-engine lease keyed per `{agent, conversation}` — concurrent leases become legal. |
| #1073 | `zsgad` | Installable wrapper distribution replacing the captured-classpath launcher. |
| #1074 | `jr5tx` | Iroh probe declares wrapper-scan mode instead of assuming systemd. |
| #1077 | — | Train: batch merge of the lgns8 queue (#1062, #1066–#1073, #1075, #1076). |
| #1078 | — | CIO engine for the wrapper: OkHttp rejects the WS frame-size limit. The live fix for the 2026-07-31 outage. |
| #1079 | `wxy4s` | Application-level liveness probe detects black-holed QUIC connections; handle-attributed loss reports (the `r3i1z` class). |
| #1080 | `lgns8.22.5` | `ApprovalRegistry` and `ExternalToolDispatcher` extracted from the runtime. |
| #1081 | `lgns8.10.4.1` | Legacy mobile WS shim connectors retired from the Iroh path. |
| #1082 | `lgns8.9` | Admin REST adapter retired with real per-method owners; reintroduction fails a test. |
| #1083 | `lgns8.23` | Controller-native channel restore behind `--channels-host`; three guards each verified fail-on-revert. |
| #1084 | `vnp3q` | Real-engine WS connect regression test + OkHttp negative control — closes the gap that let #1064→#1077 ship. |
| #1085 | `o5bqk` | Channel ingress re-wired in the first round trip after a reconnect: `channel_start` for cached accounts precedes `channels_list`. Removing the fast path fails four tests; removing the start-once-per-pass dedup fails a fifth. |
| *(this PR)* | `lgns8.24.2`, `lgns8.24.3` | Cron sensing installed on the production host: transcript-based check plus a 15-minute systemd timer. Docs-and-scripts only — the defects it senses are upstream (`mocf1`, `xsxwd`). |

---

## What is still open

Two rows, and only two, stand between this ledger and a closed `lgns8.10`. Of
the four that stood here on 2026-08-01, `lgns8.23.1`'s inbound probe was
executed (now a PROVEN-HARNESS row in §7, having produced `o5bqk` on the way)
and the `lgns8.24` cron handover was executed in production (now a PROVEN-LIVE
row in §8, with its sensing installed):

1. **PENDING-INTERACTIVE — device protocol steps 1–3.** Concurrent conversations
   (UI half), Stop button (`lgns8.19` device evidence, including the first-ever
   real Desktop abort), and the image pipeline (`iej8j` device half). Protocol:
   `docs/testing/lgns8-e2e-device-protocol.md`.
2. **PENDING-OPS — `d7uls` channels cutover.** The maintenance-window flag flip,
   in the mandatory stop-before-start order, now also carrying the cron sensing
   step and a post-restart inbound re-check.

Everything else in this document is proven, and §1, §2, §4, §5, §6, §7 and §8
each carry at least one PROVEN-LIVE row.
