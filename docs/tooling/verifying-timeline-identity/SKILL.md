---
name: verifying-timeline-identity
description: Use when a change touches how the shared timeline keys rows or hands them between the live overlay and settled pages - TimelinePageProjection (aggregatePreparedRuns, claimRun, containsComplete, runEnvelope), MessageGrouping (groupMessagesForRender, runBlockKey), TimelineRowAssembly / TimelineRowKeyGuard, SettledLiveAdoptions / SettledRunKeys, CanonicalTimelinePresentation (settledRows, live projection, withSettledKey, onResidentRows), CanonicalTimelineSessionPaging / TimelineLedgerPagingSource, or the store read positions - and when implementing or reviewing the letta-mobile-o1hju flicker fixes (S2 / S3). Runs a TLA+ model (TLC) that checks no key is listed twice, no drawn row vanishes, and keys stay stable, under every interleaving of live frames, durable writes, generation replacement, memory trims, scrolling and Paging loads.
license: Method from pstack create-formal-verification-skill (MIT, Lauren Tan, https://github.com/cursor/plugins)
metadata:
  bead: letta-mobile-o1hju
  sync-commit: 3dc2866b71975dd5caccc07066ed72ffeb44fddc
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
- **StableRowKeysAcrossRecut** (binding, owner decision R1, 2026-11-14): while the ledger is
  unchanged, a row with exactly the same records keeps its key across any refresh, trim or page
  load, even when the refresh moves the page edges. The promise is per visible row.
- **StableKeysAcrossRecut** (expected failure, kept on purpose): the same promise per record.
  A re-cut that splits or merges a row gives its records a different row, and so a different
  key; per-page grouping cannot avoid that, and the owner chose the row-level promise instead.
  Old wording (binding before R1): "a settled record keeps its key even when a refresh around
  the reader moves the page edges". New status: informational, listed as FAIL below.

The model is `tools/timeline-identity/TimelineIdentity.tla`. Every action names the Kotlin
function and file:line it models, and one indivisible code step is one model step.

## Does my change touch it?

Covered code at the sync commit. Paths are under
`android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/` unless stated:

| Code | Model |
|---|---|
| `timeline/TimelinePageProjection.kt` `aggregatePreparedRuns` :124, `keyedByOwnRecords` :161, `runClaim` :163, `claimRun` :173, `spansVisibleGap` :201, `containsComplete` :219, `runEnvelope` :232 | `PageRowSet`, `BlockRows`, `OwnRecordKey`, `ContainsComplete`, `Boundary` |
| `chat/projection/MessageGrouping.kt` `groupMessagesForRender` :182 (runIdCounts :194, unique Single :266), `runBlockKey` :541 | `Blocks`, `GroupKey` |
| `timeline/TimelineLivePublication.kt` per-record key `segment-<identity>` :117-124, `overlayEvents` :245 | `SegRow`, live drain |
| `timeline/CanonicalTimelinePresentation.kt` `settledRows` :123, `projectLiveItems` :190 (live keys from own records :219), `filterUnsettledEvents` :223, `onResidentRows` :258, `withSettledKey` :334, `project` :373, `assemble` :422, `Row.holdsAll` :100 | `Load`, `LiveItems`, `ResidentRows`, `ShownLiveOf` |
| `timeline/SettledLiveAdoptions.kt` `keyed` :39, `keyedLocked` :46, `firstPresentation` :60; `timeline/SettledRunKeys.kt` `keyedByOwnRecords` :24, `livePresentationFor` :65, `isOnlyHeldWithin` :78, `sharesShapeWith` :101, `withoutRecordsNamedBy` :128 | `KeyOne`, `KeyAll` (S3); `AdoptOne`, `AdoptAll` (main..OwnKeys) |
| `chat/projection/TimelineRowAssembly.kt` `assemble` :43, `displayedLive` :56; `chat/projection/TimelineRowKeyGuard.kt` `duplicateRows` :20 | `ShownLiveOf`, `ListKeys`, `Blanked`, `Drawn` |
| `timeline/CanonicalTimelineSessionPaging.kt` `observePublications` :65 (trims :75, revisions :82) | `Invalidate`, `Trim` |
| `timeline/TimelineLedgerPagingSource.kt` `invalidateIfBehind` :25, `getRefreshKey` :31, `load` :41; `timeline/CanonicalTimelineEngine.kt` `preparePage` :579, `reconcilePageDetailed` :502, `shouldDeferLiveReconcile` :556 | `Refresh`, `LoadOlder`, `LoadNewer`, `DurableWrite` |
| `android-compose/core/android-data/.../RoomTimelineBoundedStore.kt` read positions :181-196 | `AroundRange`, page edges |

To see what changed since the model was last confirmed:

```sh
git diff --stat 3dc2866b71975dd5caccc07066ed72ffeb44fddc -- \
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
tools/timeline-identity/check.sh s3-settled-a s3-settled-b s3-settled-c   # about 1 minute
WORKERS=4 tools/timeline-identity/check.sh s3-live-a s3-live-b       # see Bounds for run times
tools/timeline-identity/check.sh          # every config x every property (hours, see Bounds)
tools/timeline-identity/able-to-fail.sh [config-prefix]   # each passing property fails on a broken variant
tools/timeline-identity/trace.py tools/timeline-identity/out/<config>.<Property>.out
```

`check.sh` runs each property in its own TLC run, so one failure never hides another. It keeps
only failing traces in `out/` (git-ignored; `OUT=<dir>` writes elsewhere, and concurrent runs
need their own, since each run clears the passing outputs of its directory). `trace.py` prints a trace one step per line: the
action, the durable prefix `n`, the pages as `lo..hi@n=<ledger read>:[key{records}]`, and the
live rows. Read every TLC result with the pstack `/statechart` skill if you have it: it tells
each violation as its shortest path in plain words and charts the state graph when a check
passes.

Configs (`<fix>-<mode>-<scenario>.cfg`). The fix is `main` (code as on main), `s2` (S2's
settled edge rule), `d1` (S2's key rule with the first S3 adoption guards), `ownkeys` (settled
keys from own records with those guards; the held alternative) or `s3` (the shipped correction:
settled and live keys from own records, remembered keys, and the list hides a live row only
behind a settled row that holds all of it). `Break` is a set of guards to break (able-to-fail).
The mode is `settled` (history already durable, no overlay, the S1 fixture) or `live` (the
turn streams while it is written). The scenarios are in `MC.tla`:
- `a`: one multi-step run with opaque usage/stop rows (prompt, R, R, U, R, R, U);
- `b`: the #2460 shape, a run-less follow-up prompt inside the run (prompt, R, R, prompt, R, R, U);
- `c`: control, two ordinary single-step turns (prompt, r, r, U, prompt, s, s).

## Expected result at the sync commit

FAIL is a known finding, not a regression. Anything that differs from this table needs a look.
`s3` is the shipped code and must pass I1, I5, I7 and RowRecut in every scenario; the other
fixes are kept to show what each guard removes. `Recut` (per record) is an expected failure.

| config | I1 NoDuplicateKey | I5 NoVanishedRow | I7 StableKeys | RowRecut | Recut (per record) |
|---|---|---|---|---|---|
| main-settled-a | FAIL (S1 H1, cut at usage row) | FAIL (blanked row) | pass (vacuous*) | pass | pass |
| main-settled-b | FAIL (S1 H1, cut next to the follow-up) | FAIL | pass (vacuous*) | pass | FAIL |
| main-settled-c | pass | pass | pass (vacuous*) | pass | FAIL |
| main-live-a/b | FAIL (adopted live key) | FAIL | FAIL | FAIL | FAIL |
| s2-settled-a/b/c | pass | pass | pass (vacuous*) | pass | a pass, b/c FAIL |
| s2-live-a/b | FAIL | FAIL | FAIL | FAIL | FAIL |
| d1-settled-a/b/c | pass | pass | pass (vacuous*) | pass | a pass, b/c FAIL |
| d1-live-a/b | pass | FAIL (partial page) | FAIL | FAIL | FAIL |
| ownkeys-settled-a/b/c | pass | pass | pass (vacuous*) | pass | a pass, b/c FAIL |
| ownkeys-live-a | pass | pass | FAIL (adoption re-keys a drawn row) | FAIL | FAIL |
| ownkeys-live-b | pass | FAIL | FAIL | FAIL | FAIL |
| s3-settled-a | pass | pass | pass | pass | pass |
| s3-settled-b/c | pass | pass | pass | pass | FAIL (re-cut splits a row) |
| s3-live-a | pass | pass | pass | pass | FAIL (re-cut splits a row) |
| s3-live-b | pass | pass | pass | pass | FAIL (re-cut splits a row) |

\* With no live overlay and no remembered keys, a settled page's keys are a pure function of
(page bounds, ledger), so I7 cannot fail there. In s3 it can (a key depends on what was drawn
before), and able-to-fail shows it does when the guards are broken.

`able-to-fail.sh` must print `caught` for every line; S3's key properties need two guards broken
at once (own-record keys and remembered keys each keep rows stable on their own).

## Real bug or modelling error?

Replay the trace against the real engine, one model step per code step, before you believe it.
`android-compose/sharedLogic/src/jvmTest/.../timeline/TimelineIdentityModelReplayTest.kt` shows how:
- `TimelineFrameRecorder` drives the real coordinator, writer, Paging and presentation:
  `send`, `stream(..., finished = false)`, `settle`, `reconcile`, `catchUp` (the mediator's
  mid-turn write), `readAllSettledPages`.
- `later(PAGE_ROWS - k)` moves the newest page edge to a chosen row (page = 64 rows).
- The shared `InMemoryTimelineStore` reports a page's older/newer key as the page's own edge row,
  as Room and the desktop store do; `TimelineLedgerPagingSource` passes it to `Before`/`After`.
  (It once reported the NEIGHBOUR row, which made every Paging append skip a row and hid real
  collisions.)
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

The model's checking aids (`drawnV`, `seenV`, `rowsV`) are recomputed after every step
(`ObserveNext`) so the step properties compare two values instead of re-deriving the list for
every record; they add no behaviour, and every action is `<Name>Step /\ ObserveNext`.

## Bounds and assumptions

- A seven-row ledger, PageSize 3 (Room's Around: 1 older row, the anchor, then newer rows), up to 6
  durable writes (MaxRev 7), one live turn per run of the model. These constants are the only
  bounds: every s3 run finishes without a state CONSTRAINT. Distinct states: s3-settled 189 to
  4,283; s3-live-a 534,904 (about 1 minute per property); s3-live-b 5,573,348 (about 10 minutes
  per property with WORKERS=4 under a concurrent build).
- Record identity is the ledger index. serverId, otid and stream aliases collapse into it.
  Durable rows carry their run id (as the S1 fixture stores them); App Server rows that carry
  none get `turn-<prompt>` from `withPromptOwnedRunIds`, which the model does not reproduce.
- The live run id equals the durable one, so live and settled keys share one name space (the worst
  case for collisions). main..OwnKeys key a live run `run-<id>` (unique in its snapshot); s3 keys
  it `run-<id>-from-<oldest record>`, as its settled copy, except a one-record block whose run id
  is not unique in the snapshot: the grouper builds a Single without a run key, which keeps its
  `msg-` key (`keyedByOwnRecords` leaves it as built).
- Paging page dropping (maxSize = 3 pages) is never reached at these bounds. Placeholders are off.
- One load step = `preparePage` + `project` (adoption). Splitting it adds no behaviour: the load
  is checked against the revision afterwards, and `project` reads only live state, which can
  change before or after the step anyway.

Not covered: tool-return folding, deferred (preview) bodies, Monitor folding, echo compaction,
skill envelopes, suppression of abandoned fragments, pending/optimistic sends in history,
desktop's different Around split, more than one live publication at a time, I2 and I3 (revision
counting), and render cost.

## Results at the sync commit (for context)

Replayed on the real engine by `TimelineIdentityModelReplayTest`. The first three are red on
main; the fourth is the owner's R4 case.
- **Adopted live key comes back on two rows (I1, N2).** A run that took its live key at handover
  (`SettledLiveAdoptions.byIdentity`, remembered under every member) is cut by a later page edge.
  Each part finds a member and takes the same key. S3 remembers a key under one anchor, the
  record that owns the row, and never gives a key another anchor holds.
- **Follow-up cut (I1).** A page edge right before a run-less follow-up prompt: both pages see
  `Ends` and both claim `run-<id>`. S3 keys every claimed block by its own oldest record.
- **Partial durable page hides live rows (I5, N3).** A mid-turn catch-up makes only the first rows
  of a run durable; that row took the live block's key and `displayedLive` hid the whole live
  block. S3 keys live runs by their own records too; a live row draws only the records no settled
  row draws yet (`withoutRecordsNamedBy`), so no record is drawn twice, and a live row left with
  records under a settled row's key is listed as `<key>~live`.
- **A durable row drawn before its live frame keeps its key (R4).** After a reconnect catch-up
  the settled row is drawn first; the turn's live frames arrive later and the turn settles. S3
  keeps the row's first key for the presentation's life, and hides the live block, whose records
  that row already draws, instead of drawing them twice.
- **Keys across re-cuts.** Per record (StableKeysAcrossRecut) no per-page scheme holds: a re-cut
  that splits `{2,3}` into `{3}` moves record 3 to another row (s3-settled-b/c, s3-live-a/b).
  Per visible row (StableRowKeysAcrossRecut) s3 holds in every scenario.

## Credits

Method: pstack `create-formal-verification-skill` / `maintain-formal-verification-skill` by Lauren
Tan (MIT), https://github.com/cursor/plugins. For later drift, run pstack
`/maintain-formal-verification-skill` against this skill.
