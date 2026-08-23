# Stream-frame eviction instead of session teardown

Design spec for `letta-mobile-aggeh` option (b), scoped down to the specific
failure observed on 2026-08-23. **Not implemented — this is for review.**

## The failure

`KtorAppServerWebSocketTransport` demultiplexes one WS connection into a control
queue and a stream queue, both `Channel(DELIVERY_QUEUE_CAPACITY)`. When either
fills, `enqueueOrFail` throws `AppServerDeliveryOverflowException`, which fails
the whole generation — control included.

Observed in production, wrapper boot of 2026-08-23 17:58:

```
App Server connection lost: App Server stream delivery queue exceeded its
bounded capacity of 1024 frames          x26 in one boot
```

Each one tears down the entire session: every in-flight `admin_rpc` dies with
`transport disconnected`, and the live turn ends `activeTurn.releasedWithoutTerminal`.
The logs immediately preceding each overflow are an unbroken wall of
`fanout.broadcast conversationId=local-conv-190 viewerCount=2` — and there are
**zero** `fanout.observer_dropped` events in that boot. So this is not a stuck or
slow viewer: it is raw production rate exceeding drain rate on a bounded queue.

`DELIVERY_QUEUE_CAPACITY` was already raised 256 → 1024 in #1293, whose own commit
message says it "mitigates but does not eliminate". That prediction held: the same
failure now occurs at the higher ceiling. **Raising it again is not a fix**, it
just moves the threshold.

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
|---|---|---|---|
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
