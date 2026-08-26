# Stream-frame eviction instead of session teardown

Design spec for `letta-mobile-aggeh` option (b), scoped down to the specific
failure observed on 2026-08-23. **Not implemented — this is for review.**

## The failure

`KtorAppServerWebSocketTransport` demultiplexes one WS connection into a control
queue and a stream queue, both `Channel(DELIVERY_QUEUE_CAPACITY)`. When either
fills, `enqueueOrFail` throws `AppServerDeliveryOverflowException`, which fails
the whole generation — control included.

Observed in production, wrapper boot of 2026-08-23 17:58:

```text
App Server connection lost: App Server stream delivery queue exceeded its
bounded capacity of 1024 frames          x26 in one boot
```

The later tally in this document uses 28 overflows for that boot. The raw log and
counting query are not checked in, so reconcile 26 versus 28 before using either
as a denominator.

Each overflow fails the shared connection generation in code: pending control
requests can fail with `transport disconnected`, and active turns can be
released without a terminal. The counts below show that not every overflow had a
matching `activeTurn.releasedWithoutTerminal` event. The preceding
`fanout.broadcast conversationId=local-conv-190 viewerCount=2` lines and absence
of `fanout.observer_dropped` establish correlation only. Initiator writes have no
observer timeout or observer-drop telemetry, so those signals do not rule out a
slow or stalled initiator.

`DELIVERY_QUEUE_CAPACITY` was already raised 256 → 1024 in #1293, whose own commit
message says it "mitigates but does not eliminate". That prediction held: the same
failure now occurs at the higher ceiling. **Raising it again is not a fix**, it
just moves the threshold.

## Scale: the assumption underneath all of this is wrong

Measured from `lastTerminalSeq` at turn completion (n=21, wrapper boot 2026-08-23):

| | events per turn |
| --- | --- |
| min | 180 |
| **median** | **5,529** |
| p90 | 19,622 |
| max | 25,120 |
| **turns whose total event count exceeds 1024** | **19 of 21 (90%)** |

Observed rates: 42–349 events/sec, over turns lasting 35s to **566s** (9.4 minutes).

A delivery queue absorbs the difference between producer and consumer progress;
it does not need to hold a whole turn. Comparing total turn volume with capacity
therefore does **not** show that the queue is undersized or that a median turn
"overruns" it. At the observed peak arrival rate, 1024 slots represent about 2.9
seconds of *zero drain*, but actual required capacity depends on measured drain
rate and stall duration.

Longer runs do increase work elsewhere, and two observations are documented in
the repository's operational notes:

- the 203MB / 47,949-row transcript that costs ≥2.7s of blocked event loop to
  load (`readJsonlFile`, unbounded, retained in `localMessagesByConversationKey`
  until `deleteAgent`), and
- the App Server growing to 2.7GB RSS. (Per the audit note in
  `scripts/deploy/README-stall-diagnostics.md`: RSS is *not* V8 heap occupancy,
  so this is retention growth as an observation, not a demonstrated GC-ceiling
  diagnosis. It still scales with run length, which is the only property this
  section relies on.)

These observations justify auditing work that scales with run length, but they
do not establish one shared root cause: RSS, transcript loading, and transient
queue lag have different bounds and consumers.

### What this changes about the proposal below

The Phase 1 design assumes eviction is **exceptional**. The observed overflows
show that assumption needs measurement, but total events per turn cannot predict
eviction frequency. Measure queue occupancy, arrival/drain rates, and stall
length before deciding whether evict-and-resync would be an edge case or steady
state. That distinction materially changes the proposal's user-visible trade.

It also sharpens the real question, which is **not** what the queue capacity
should be:

- If the consumer can sustain ~350 events/sec on average, the queue only ever
  needs to cover transient drain lag, and no capacity increase is warranted —
  the bug is whatever causes multi-second drain stalls, and that is what should
  be investigated.
- If the consumer *cannot* sustain that rate, **no queue size fixes anything**;
  it only changes how long it takes to fail. The fix would then have to be on the
  drain side — batching or coalescing frames before Iroh fanout, so that cost
  stops scaling linearly with event count × viewer count.

**That question must be answered before implementing Phase 1.** Measuring actual
drain throughput is a prerequisite, not a follow-up. Notably, the 28 overflows in
this boot produced only 6 `releasedWithoutTerminal` events, so most overflows tore
down the session *without* losing a turn — which is itself unexplained and worth
understanding before committing to a design.

## Why the current behaviour is the way it is

Two invariants are deliberately encoded in tests and must survive any change:

- `streamBackpressureCannotBlockControlDeliveryOnTheSharedSocket` — a full stream
  queue must not stall control delivery. This is why the queues cannot simply
  `send()` (suspend): `receiveAndDemuxFrames` reads both off **one** sequential
  loop against the shared socket, so suspending on a full stream queue would
  stall control right behind it.
- `deliveryQueueOverflowFailsTheGenerationInsteadOfGrowingWithoutBound` — the
  bound is intentional; an unbounded queue trades a clear, recoverable failure
  for a memory leak under a genuinely stuck consumer.

The original design also rejected silent eviction, on the grounds that frame loss
can corrupt client state. **That objection is correct for control frames and, as
written, was applied to both queues.** The proposal below is that it does not
hold uniformly for the stream channel.

## The key observation

The stream channel carries exactly five frame types
(`AppServerProtocol.STREAM_CHANNEL_MESSAGE_TYPES`), and they fall into two
classes with different recovery properties:

| Type | Payload | Class | Safe to drop? |
| --- | --- | --- | --- |
| `stream_delta` | `delta: JsonElement` | **incremental** | Only with a resync |
| `update_loop_status` | `loopStatus` | **absolute state** | Yes, if a newer one survives |
| `update_device_status` | `deviceStatus: JsonObject` | **absolute state** | Yes, if a newer one survives |
| `update_queue` | `queue: List<JsonObject>` (full list) | **absolute state** | Yes, if a newer one survives |
| `update_subagent_state` | full state | **absolute state** | Yes, if a newer one survives |

Every one of them carries `runtime: AppServerRuntimeScope` (agent + conversation)
and a monotonic `eventSeq: Long`.

Four of the five are **last-write-wins state replacements**. Dropping an
intermediate `update_queue` is not data loss at all, provided a later one arrives —
the newer frame already contains everything the older one said.

Only `stream_delta` is genuinely incremental, and it is precisely the frame class
`TimelineRecentMessagesReconciler` exists to repair: a snapshot fetch returns the
authoritative message list, which is a superset of any deltas that were dropped.

Control frames are untouched by this proposal and keep failing fast.

## Proposal

### Phase 1 — stop stream overflow from killing the session

Split the two queues' overflow policy. Control keeps `enqueueOrFail`. Stream
becomes lossy-with-notification:

1. Stream queue becomes `Channel(DELIVERY_QUEUE_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)`.
   `trySend` then never fails, so the generation is never torn down by stream rate.
2. The transport tracks enqueued-vs-delivered counts to detect that a drop
   occurred (a `Channel` cannot be queried for size, and `DROP_OLDEST` is silent).
3. On detecting drops, the transport emits the affected `AppServerRuntimeScope`
   on a new, conflated resync signal.

New surface on the transport interface:

```kotlin
/** Emits the runtime scope whose stream frames were evicted under backpressure.
 *  Conflated: a burst that drops 900 frames yields one signal, not 900. */
val streamResyncRequests: Flow<AppServerRuntimeScope>
```

### Phase 1 — routing the resync

The transport is conversation-agnostic; `TimelineRecentMessagesReconciler` is
constructed **per conversation**. So the transport must not call the reconciler
directly. The per-conversation timeline layer collects `streamResyncRequests`,
filters for its own `conversationId`, and calls the existing path:

```kotlin
reconcileRecentMessages(
    reason = "streamFramesEvicted",
    forceRefresh = true,          // required: bypasses the streamSubscriberActive skip
    connectionGeneration = <current generation>,
)
```

`forceRefresh = true` is load-bearing — eviction happens *while the stream is
active*, which is exactly the case the default skip suppresses.

This path is already coalesced (`inFlightRecentReconcile`) and debounced
(`minForcedReconcileIntervalMs`, per `connectionGeneration`), so a sustained
overflow cannot turn into a reconcile storm. That existing debounce is why this
proposal does not need its own rate limiting — but the spec depends on it, so
`connectionGeneration` **must** be threaded through correctly rather than left at
`DEFAULT_CONNECTION_GENERATION`.

Precedent: `ReconnectCoordinator` already triggers reconcile on redial recovery,
so "transport event causes a forced reconcile" is an established pattern here.

### Phase 2 (optional, only if Phase 1 proves insufficient)

Conflate the four absolute-state types instead of evicting them: keep at most one
in-flight frame per `(runtime, type)`, replacing rather than appending. This is
**lossless** for those types and reduces pressure on the buffer so that fewer
`stream_delta` frames need dropping.

It cannot be done inside a `Channel` (no arbitrary element access), so it needs a
small mutex-guarded `ArrayDeque` buffer plus a wakeup signal. That is a real
component with real concurrency risk, which is why it is deliberately **not** in
Phase 1. Phase 1 alone stops the session teardown, which is the actual incident.

## What must not change

- Control frames are never evicted or conflated. A dropped `admin_rpc_response`
  strands a caller forever; there is no reconciler for control.
- The stream buffer stays **bounded**. This proposal changes what happens at the
  bound, not whether there is one.
- `receiveAndDemuxFrames` must not gain a suspending send on either queue.

## Test plan

New:

- Stream overflow evicts and keeps the generation ready (the direct inverse of
  today's `deliveryQueueOverflowFailsTheGenerationInsteadOfGrowingWithoutBound`).
- Stream overflow emits exactly one resync signal per burst, carrying the correct
  runtime scope.
- Control overflow still fails the generation — the existing behaviour, now
  asserted separately so the two policies cannot drift.
- A resync signal reaches the reconciler as `forceRefresh = true` with the live
  connection generation.
- Post-eviction, a reconcile restores the messages that were dropped (end-to-end,
  against the fake transport).

Existing tests that must be revisited rather than assumed:

- `deliveryQueueOverflowFailsTheGenerationInsteadOfGrowingWithoutBound` currently
  asserts the behaviour this change removes for the stream channel. It must be
  **narrowed to control**, not deleted — deleting it would silently drop the
  unbounded-growth guarantee.
- `streamBackpressureCannotBlockControlDeliveryOnTheSharedSocket` must still pass
  unchanged; it is the reason `DROP_OLDEST` is used rather than a suspending send.
- `normalEndOfStreamDrainsAcceptedFramesBeforeTeardown` — drain semantics on a
  `DROP_OLDEST` channel need re-checking; frames accepted then evicted must not
  be reported as delivered.

## Risks and open questions

1. **A dropped `stream_delta` is user-visible until the reconcile lands.** Text
   may briefly appear truncated mid-turn. Needs a product call on whether that is
   preferable to today's behaviour (the whole session dies and the turn ends with
   no terminal). I believe it clearly is, but it is a judgement call, not a
   technical one.
2. **Drop detection by counter comparison is inferential.** If it proves
   unreliable, the fallback is the Phase 2 custom buffer, which knows exactly
   what it evicted. Prototype the counter approach first and validate it against
   a forced-overflow test.
3. **Does anything else consume `streamFrames` and assume completeness?**
   `IrohAppServerTransport` has its own `streamFrameFlow` and the Iroh fanout path
   forwards stream frames to viewers. If a viewer receives an evicted-from stream,
   that viewer needs the same resync signal — otherwise this fixes the local
   timeline and leaves remote viewers subtly stale. **This is the largest open
   question and must be answered before implementation.**
4. **Ordering.** `DROP_OLDEST` preserves relative order of survivors, and
   `eventSeq` is monotonic, so consumers can still detect a gap. Nothing today
   gap-detects on `eventSeq` (`AppServerTurnEngine` reads it for telemetry and
   terminal tracking only) — worth considering as a cheap independent safety net.

## Why not the alternatives

- **Raise the capacity again.** Already tried twice (256 → 1024). Same failure at
  the new ceiling; buys time proportional to the raise, costs memory, fixes nothing.
- **Suspending send.** Directly violates
  `streamBackpressureCannotBlockControlDeliveryOnTheSharedSocket` — one read loop,
  so stream backpressure becomes control backpressure.
- **Separate WS connections for control and stream** (`aggeh` option (a)). The
  cleanest structural fix, but Letta Code ≥ 0.29.7 deprecated per-channel sockets
  in favour of the single `/ws` endpoint, as documented in the transport's class
  doc. That constraint would have to be revisited upstream first.

---

## Amendment 2026-08-25 — the drain is the defect, not the queue

Evidence gathered from an operator-captured wrapper boot starting 2026-08-24
11:26 prompted a code audit. **This amendment re-sequences the proposal above:
Phase 1 is at the wrong layer to go first.** Nothing here is implemented.

The operational counts below are a log tally, not a checked-in fixture. They
show correlation and incident scope; they do not by themselves prove which
consumer caused each overflow.

### What the captured boot shows

| Signal | Observed value |
| --- | --- |
| Stream-queue overflow teardowns | 41 |
| Other reconnect reasons in the reviewed tally | 0 |
| Turns ended `releasedWithoutTerminal` | 22 |
| Recovery lines (`reattached runtimes:` 0 / 2 / 3) | 6 / 28 / 12 (46 total) |
| Conversations present in fanout telemetry | 4 |

The recovery-line total does not account for a claimed population of 47 restore
attempts. Until the source log and counting query are preserved together, this
document does not use the 10-timeout/36-disconnect split as a complete failure
distribution.

### Code-verified backpressure path

```text
single WS receive loop
  -> bounded streamDeliveryQueue (trySend; overflow fails the generation)
  -> streamFrameFlow.emit (suspends for a slow SharedFlow subscriber)
  -> DefaultAppServerClient.events / mergedFrames()
  -> AppServerRuntimeEventRouter (one collector)
  -> RuntimeEventFanout.route
       (bounded per-subscriber channels, but route awaits every send)
  -> AppServerTurnEngine subscriber
  -> ConversationTurnFanout.broadcastDeltaBodyNoPark
       observers: per-viewer queue + 5 s timeout
       initiator: awaited, no timeout
  -> IrohViewerHandle.writeFrame
       streamWriteMutex.withLock { sink.writeAll(...) }
```

The repository establishes each suspension point:

- [`KtorAppServerWebSocketTransport`](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/transport/appserver/KtorAppServerWebSocketTransport.kt)
  reads the shared socket sequentially, uses non-suspending `trySend` into bounded
  delivery queues, and suspends while emitting accepted frames to its shared
  flows.
- [`mergedFrames()`](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/transport/appserver/AppServerTransport.kt)
  launches control and stream collectors that both send into one `channelFlow`.
- [`AppServerRuntimeEventRouter`](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/fanout/AppServerRuntimeEventRouter.kt)
  has one collector, while
  [`RuntimeEventFanout.deliverToChannels`](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/fanout/RuntimeEventFanout.kt)
  sends concurrently but waits for all targeted subscriber sends. A full target
  can therefore hold that router collector even though other sends were launched.
- [`ConversationTurnFanout.broadcastDeltaBodyNoPark`](../../android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/ConversationTurnFanout.kt)
  queues observer writes but directly awaits the initiator write without a
  timeout.
- [`IrohViewerHandle.writeFrame`](../../android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/IrohViewerHandle.kt)
  holds a per-connection mutex while awaiting the Iroh sink write.

Consequently, a slow or stalled initiator write **can** propagate backpressure
through a turn subscriber and the sole runtime-event router to the transport's
stream delivery queue. When that queue reaches 1024 frames, `enqueueOrFail`
fails the shared WebSocket generation, including control. The code proves this
failure path exists; proving that it caused all 41 observed overflows still
requires timing/occupancy telemetry at these boundaries. A sink write may wait
on local buffering or remote flow control, so it must not be described as one
network round trip.

### What this answers — and what it does not

Queue capacity is not the first design variable. Increasing it only extends the
amount of downstream lag tolerated before the existing generation-wide failure.
Before making stream delivery lossy, isolate and measure the downstream path so
an overflow can be attributed to a runtime and viewer.

The earlier open question about other `streamFrames` consumers also remains
important. In controller production wiring, the runtime router is a consumer and
its turn subscribers can forward frames to Iroh viewers. Transport-level
`DROP_OLDEST` would discard a frame before those consumers see it. A local
`TimelineRecentMessagesReconciler` refresh is not evidence that a remote viewer
was repaired, so eviction is unsafe until the resync contract reaches every
affected consumer.

### Control and stream are re-coupled downstream

`streamBackpressureCannotBlockControlDeliveryOnTheSharedSocket` verifies the
transport's two delivery queues. `mergedFrames()` then sends both flows through
one bounded `channelFlow` for each collector of `DefaultAppServerClient.events`.
A blocked downstream collector can fill that channel and suspend both child
sends. The production invariant must therefore be stated end to end, not only at
the socket demultiplexer.

### Separate observation: `channels_list` did not answer in one deployment

A direct probe recorded the following on the reviewed host:

```text
ws://127.0.0.1:4500/ws
  -> {"type":"app_server_info","request_id":"diag-info-1"}      reply in about 3 ms
  -> {"type":"channels_list",  "request_id":"diag-channels-1"} no reply within 15 s
```

That result is deployment evidence, not a version-wide contract. It conflicts
with the repository's 2026-07-31 letta-code 0.29.12 probe, which records a
successful `channels_list` response in
[`lgns8-acceptance-evidence-ledger.md`](../testing/lgns8-acceptance-evidence-ledger.md).
The installed protocol inventory also contains both `channels_list` and
`channels_list_response`. Diagnose the running package version, launch mode,
configuration, and raw server output before claiming that letta-code 0.29.12
does not implement the command.

Log ordering alone is not a reconnect race: `restoreChannels(client)` runs
inside `ReconnectingClientListener.onRecovered`, before the recovery line is
printed, in
[`AppServerServeIrohCommand`](../../android-compose/iroh-wrapper-cli/src/main/kotlin/com/letta/mobile/cli/commands/AppServerServeIrohCommand.kt).
Also, the current
[`ChannelRestoreCoordinator`](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/channels/ChannelRestoreCoordinator.kt)
tries cached enabled accounts with `channel_start` **before** it calls
`channels_list`. A failed enumeration therefore does not, by itself, prove that
no account is running. Operational rollback or cutover decisions must follow the
verified deployment state and the
[lettashim retirement deployment runbook](lettashim-retirement-deployment-runbook.md),
not this architecture note.

### Re-sequenced plan

1. **Instrument first.** Record queue occupancy/drop scope and elapsed time at
   transport delivery, runtime-router handoff, turn consumption, and viewer
   writes. Preserve the counting query with incident evidence.
2. **Keep control independent end to end.** Remove or bypass the
   `mergedFrames()` re-coupling for production routing before claiming control
   isolation. Add a test where stream consumption is blocked after the transport
   and control still reaches its consumer.
3. **Make runtime dispatch non-blocking across scopes.** A handoff from the
   shared WebSocket must never suspend on a full conversation lane. Use bounded
   per-runtime workers or an equivalent reader-side dispatch, define
   lane-scoped overflow explicitly, and test that a full conversation lane
   blocks neither control nor another conversation.
4. **Isolate viewer writes.** Use one bounded serial writer per viewer so frame
   order and that viewer's `event_seq` remain monotonic. Define initiator overflow
   behavior as a product/transport contract; do not silently weaken the current
   guarantee that the initiator receives every frame. Test slow initiator and
   observer paths separately.
5. **Add eviction only after resync is end to end.** The earlier Phase 1 can be a
   safety net only when an eviction identifies the affected runtime/viewers and
   triggers a viewer-scoped repair or disconnect/reconnect contract. Control
   frames remain non-lossy and every queue remains bounded.
6. **Conflate absolute state at the affected viewer queue.** Key replacement by
   `(runtime, type)` belongs where pressure is isolated. Keep `stream_delta`
   incremental and subject to the explicit resync contract.
7. **Investigate `channels_list` independently.** Re-run the pinned contract
   probe against the deployed executable and reconcile the incomplete restore
   tally. Do not infer a version-wide protocol gap or prescribe a host cutover
   from the current sample.

### Still unexplained — settle before building

The ratio of `releasedWithoutTerminal` to overflow events was 6/28 in the
2026-08-23 tally and 22/41 in the later tally. The code-verified backpressure path
does not explain either ratio or the change. Preserve and reconcile the raw
evidence, then add telemetry or a focused reproduction before selecting an
implementation.
