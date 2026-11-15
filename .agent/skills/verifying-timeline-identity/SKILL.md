---
name: verifying-timeline-identity
description: Use when a change touches how the shared timeline keys rows or hands them between the live overlay and settled pages - TimelinePageProjection (aggregatePreparedRuns, claimRun, containsComplete, runEnvelope), MessageGrouping (groupMessagesForRender, runBlockKey), TimelineRowAssembly / TimelineRowKeyGuard, SettledLiveAdoptions / SettledRunKeys, CanonicalTimelinePresentation (settledRows, live projection, withSettledKey, onResidentRows), CanonicalTimelineSessionPaging / TimelineLedgerPagingSource, or the store read positions - and when implementing or reviewing the letta-mobile-o1hju flicker fixes (S2 / D1). Runs a TLA+ model (TLC) that checks no key is listed twice, no drawn row vanishes, and keys stay stable, under every interleaving of live frames, durable writes, generation replacement, memory trims, scrolling and Paging loads.
license: Method from pstack create-formal-verification-skill (MIT, Lauren Tan, https://github.com/cursor/plugins)
metadata:
  bead: letta-mobile-o1hju
  sync-commit: 624012ec297e65003062991f5a07a2a5945f316c
---

# Verifying timeline row identity

The shared timeline (Android, desktop, web) lists one live overlay and the resident settled
pages as one LazyColumn. It promises:

- **I1 NoDuplicateKey**: no key is listed twice. If one is, `TimelineRowKeyGuard` blanks a row
  every frame (`timeline.duplicateKeyDropped`), so a row vanishes.
- **I5 NoVanishedRow**: a row the list draws stays drawn while the reader stays put. If not, rows
  flash out and back in.
- **I7 StableKeys**: the same record set (same page, same ledger) always gets the same key. If not,
  a row is remounted with nothing changed.
- **StableKeysAcrossRecut**: I7 strengthened. The key also survives a refresh that moves the page edges.
  This is informational: the plan's R1 assumes it, and per-page grouping cannot meet it (see Results).

The model is `verification/timeline-identity/TimelineIdentity.tla`. Every action names the Kotlin
function and file:line it models, and one indivisible code step is one model step.

## Does my change touch it?

Covered code at the sync commit. Paths are under
`android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/` unless stated:

| Code | Model |
|---|---|
| `timeline/TimelinePageProjection.kt` `aggregatePreparedRuns` :118, `runClaim` :153, `claimRun` :163, `spansVisibleGap` :191, `containsComplete` :209, `runEnvelope` :222 | `PageRowSet`, `BlockRows`, `ContainsComplete`, `Boundary` |
| `chat/projection/MessageGrouping.kt` `groupMessagesForRender` :182 (runIdCounts :194, unique Single :266), `runBlockKey` :541 | `Blocks`, `GroupKey` |
| `timeline/TimelineLivePublication.kt` per-record key `segment-<identity>` :117-124, `overlayEvents` :245 | `SegRow`, live drain |
| `timeline/CanonicalTimelinePresentation.kt` `settledRows` :113, `projectLiveItems` :180, `filterUnsettledEvents` :210, `onResidentRows` :245, `withSettledKey` :320, `project` :353 | `Load`, `LiveItems`, `ResidentRows` |
| `timeline/SettledLiveAdoptions.kt` `adoptLocked` :26; `timeline/SettledRunKeys.kt` `livePresentationFor` :43, `sharesShapeWith` :69 | `AdoptOne`, `AdoptAll` |
| `chat/projection/TimelineRowAssembly.kt` `assemble` :34, `displayedLive` :43; `chat/projection/TimelineRowKeyGuard.kt` `duplicateRows` :20 | `ShownLive`, `ListKeys`, `Blanked`, `Drawn` |
| `timeline/CanonicalTimelineSessionPaging.kt` `observePublications` :65 (trims :75, revisions :82) | `Invalidate`, `Trim` |
| `timeline/TimelineLedgerPagingSource.kt` `invalidateIfBehind` :25, `getRefreshKey` :31, `load` :41; `timeline/CanonicalTimelineEngine.kt` `preparePage` :579, `reconcilePageDetailed` :502, `shouldDeferLiveReconcile` :556 | `Refresh`, `LoadOlder`, `LoadNewer`, `DurableWrite` |
| `android-compose/core/android-data/.../RoomTimelineBoundedStore.kt` read positions :181-196 | `AroundRange`, page edges |

To see what changed since the model was last confirmed:

```sh
git diff --stat 624012ec297e65003062991f5a07a2a5945f316c -- \
  android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/TimelinePageProjection.kt \
  android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/chat/projection/MessageGrouping.kt \
  android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/chat/projection/TimelineRowAssembly.kt \
  android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/chat/projection/TimelineRowKeyGuard.kt \
  android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/SettledLiveAdoptions.kt \
  android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/SettledRunKeys.kt \
  android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/CanonicalTimelinePresentation.kt \
  android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/CanonicalTimelineSessionPaging.kt \
  android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/TimelineLedgerPagingSource.kt \
  android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/TimelineLivePublication.kt \
  android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/CanonicalTimelineEngine.kt \
  android-compose/core/android-data/src/main/java/com/letta/mobile/data/local/RoomTimelineBoundedStore.kt
```

Also check for code that moved, and for new code that writes the same state: a new path that
sets a settled row's key, a new live-drain rule, or a new Paging invalidation.

## Run it

Needs TLC 1.7.4 (`tla2tools-1.7.4.jar`, sha256
`936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88`) and any JDK 11+. On the
build host both are installed: `/opt/tools/tla/tla2tools-1.7.4.jar` and `/usr/lib/jvm/jdk-26`.
Elsewhere, download that jar from the TLA+ GitHub release v1.7.4, check the sha256, and set
`TLA2TOOLS=/path/to/jar`.

```sh
verification/timeline-identity/check.sh          # all configs x all properties, about 1 minute
verification/timeline-identity/able-to-fail.sh   # each passing property fails on a broken variant
verification/timeline-identity/trace.py verification/timeline-identity/out/<config>.<Property>.out
```

`check.sh` runs each property in its own TLC run, so one failure never hides another. It keeps
only failing traces in `out/` (git-ignored). `trace.py` prints a trace one step per line: the
action, the durable prefix `n`, the pages as `lo..hi@n=<ledger read>:[key{records}]`, and the
live rows. Read every TLC result with the pstack `/statechart` skill if you have it: it tells
each violation as its shortest path in plain words and charts the state graph when a check
passes.

Configs (`<fix>-<mode>-<scenario>.cfg`). The fix is `main` (code as on main) or `d1` (plan D1).
The mode is `settled` (history already durable, no overlay, the S1 fixture) or `live` (the
turn streams while it is written). The scenarios are in `MC.tla`:
- `a`: one multi-step run with opaque usage/stop rows (prompt, R, R, U, R, R, U);
- `b`: the #2460 shape, a run-less follow-up prompt inside the run (prompt, R, R, prompt, R, R, U);
- `c`: control, two ordinary single-step turns (prompt, r, r, U, prompt, s, s).

## Expected result at the sync commit

FAIL is a known finding, not a regression. Anything that differs from this table needs a look.

| config | I1 NoDuplicateKey | I5 NoVanishedRow | I7 StableKeys | AcrossRecut |
|---|---|---|---|---|
| main-settled-a | FAIL (S1 H1, cut at usage row) | FAIL (blanked row) | pass (vacuous*) | pass |
| main-settled-b | FAIL (S1 H1, cut next to the follow-up) | FAIL | pass (vacuous*) | FAIL |
| main-settled-c | pass | pass | pass (vacuous*) | FAIL |
| main-live-a/b | FAIL (adopted live key) | FAIL | FAIL | FAIL |
| d1-settled-a | pass | pass | pass (vacuous*) | pass |
| d1-settled-b | pass | pass | pass (vacuous*) | FAIL |
| d1-settled-c | pass | pass | pass (vacuous*) | FAIL |
| d1-live-a/b | FAIL (adopted live key) | FAIL (partial page hides live run) | FAIL | FAIL |

\* With no live overlay, a settled page's keys are a pure function of (page bounds, ledger), so
I7 cannot fail there. It is only meaningful in `live` configs, where adoption state exists.

## Real bug or modelling error?

Replay the trace against the real engine, one model step per code step, before you believe it.
`android-compose/sharedLogic/src/jvmTest/.../timeline/TimelineIdentityModelReplayTest.kt` shows how:
- `TimelineFrameRecorder` drives the real coordinator, writer, Paging and presentation:
  `send`, `stream(..., finished = false)`, `settle`, `reconcile`, `catchUp` (the mediator's
  mid-turn write), `readAllSettledPages`.
- `later(PAGE_ROWS - k)` moves the newest page edge to a chosen row (page = 64 rows).
- Wrap the store in `RoomEdgeKeys`. The shared `InMemoryTimelineStore` reports a page's
  older/newer key as the NEIGHBOUR row, while Room and the desktop store report the page's own
  edge row, which `TimelineLedgerPagingSource` passes to `Before`/`After`. Without the wrapper,
  every Paging append skips the row just before the edge. That hides real collisions (S1 saw the
  follow-up cut green because of it) and makes rows look as if they vanish when the product
  would not drop them.
- Wait for Paging to present before reading a frame (poll `settledKeys()`). A frame read right
  after a write can still be the old generation.

If a step cannot happen in the code, the model is wrong. Fix the model, not the property, and say
what was wrong. If every step happens and the property breaks, it is a bug. Name the commit
that introduced it and keep the replay test.

## Updating the model for an intended change

1. Read the diff of the covered code. Decide, per change: no effect, the model must change, or the
   promise changed on purpose (cite the evidence: doc comment, test, plan decision).
2. Change the matching operator or action from the code, before you rerun. Keep one code step as
   one action, and keep the `file:line` comments true.
3. Never weaken a property, or the model, to make it pass. Change a property only when the promise
   changed on purpose, and record the old wording, the new wording and the evidence.
4. Rerun `check.sh`. For every pass, show it can still fail (`able-to-fail.sh`, and add a matrix
   line when you add a property or config).
5. For a new counterexample, add a replay test (above) that fails on the code it describes.
6. Move `sync-commit` (here and in the `TimelineIdentity.tla` header) only after you have
   confirmed the model describes the code at the new commit. Update the expected-result table.

To model D1 as S2 actually implements it, replace `D1Key` with the implemented rule. The current
`D1Key` is the plan's rule ("the page holds every record of the run"), and it reads the whole
ledger. A real page sees only one row past each edge, so S2 needs more evidence than
`runEnvelope` gives.

## Bounds and assumptions

- A seven-row ledger, PageSize 3 (Room's Around: 1 older row, the anchor, then newer rows), up to 6
  durable revisions, one live turn per run of the model. Fewer than 2,000 distinct states per
  config.
- Record identity is the ledger index. serverId, otid and stream aliases collapse into it.
  Durable rows carry their run id (as the S1 fixture stores them); App Server rows that carry
  none get `turn-<prompt>` from `withPromptOwnedRunIds`, which the model does not reproduce.
- The live run id equals the durable one. The live key is `run-<id>` like the settled one.
- Paging page dropping (maxSize = 3 pages) is never reached at these bounds. Placeholders are off.
- One load step = `preparePage` + `project` (adoption). Splitting it adds no behaviour: the load
  is checked against the revision afterwards, and `project` reads only live state, which can
  change before or after the step anyway.

Not covered: tool-return folding, deferred (preview) bodies, Monitor folding, echo compaction,
skill envelopes, suppression of abandoned fragments, pending/optimistic sends in history,
desktop's different Around split, more than one live publication at a time, I2 and I3 (revision
counting), and render cost.

## Results at the sync commit (for context)

Replayed on the real engine by `TimelineIdentityModelReplayTest`. All three are red on main.
- **Adopted live key comes back on two rows (I1).** A run that took its live key at handover
  (`SettledLiveAdoptions.byIdentity`, remembered under every member) is cut by a later page edge.
  Each part finds a member in `byIdentity` and takes the same key, on a path that skips the
  holders check. The measured key `run-local-run-125` is a live run id. D1 does not change this.
- **Follow-up cut (I1).** A page edge right before a run-less follow-up prompt: both pages see
  `Ends` and both claim `run-<id>`.
- **Partial durable page hides live rows (I5).** A mid-turn catch-up makes only the first rows of
  a run durable. That settled row adopts the live block's key, and `displayedLive` then hides the
  whole live block, including the rows that are not durable yet.
- **D1 and keys across re-cuts.** D1's `run-<id>-<firstMessageId>` changes when a refresh around
  the reader moves the page edge into a run (`d1-settled-b/c`, StableKeysAcrossRecut). The
  plan's R1 ("I7 guarantees stability afterwards") does not hold under re-cuts.

## Credits

Method: pstack `create-formal-verification-skill` / `maintain-formal-verification-skill` by Lauren
Tan (MIT), https://github.com/cursor/plugins. For later drift, run pstack
`/maintain-formal-verification-skill` against this skill.
