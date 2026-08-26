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

## Scale: the assumption underneath all of this is wrong

Measured from `lastTerminalSeq` at turn completion (n=21, wrapper boot 2026-08-23):

| | events per turn |
|---|---|
| min | 180 |
| **median** | **5,529** |
| p90 | 19,622 |
| max | 25,120 |
| **turns exceeding the 1024-frame queue** | **19 of 21 (90%)** |

Observed rates: 42–349 events/sec, over turns lasting 35s to **566s** (9.4 minutes).

**The queue holds 18% of a median turn and 4% of the largest.** At the observed
peak rate, 1024 slots is ~2.9 seconds of drain lag. This is no longer a
burst-absorption buffer in any meaningful sense — it is a small fraction of one
unit of work.

This reframes the whole issue. `DELIVERY_QUEUE_CAPACITY` was chosen for runs of a
few hundred messages. Runs now routinely carry thousands of messages and tool
calls, so **every bound sized against the old workload is now undersized by one
to two orders of magnitude** — and the same phenomenon shows up in at least two
other places already documented in `letta-mobile-jsfrn`:

- the 203MB / 47,949-row transcript that costs ≥2.7s of blocked event loop to
  load (`readJsonlFile`, unbounded, retained in `localMessagesByConversationKey`
  until `deleteAgent`), and
- the App Server growing to 2.7GB RSS. (Per the audit note in
  `scripts/deploy/README-stall-diagnostics.md`: RSS is *not* V8 heap occupancy,
  so this is retention growth as an observation, not a demonstrated GC-ceiling
  diagnosis. It still scales with run length, which is the only property this
  section relies on.)

All three are the same root phenomenon — run length scaled well past what the
bounds assume — not three unrelated bugs.

### What this changes about the proposal below

The Phase 1 design assumes eviction is **exceptional**. At this scale it would be
**routine**: a median turn overruns the queue by 5x, so evict-and-resync becomes
the steady state rather than an edge case, and "live streaming" degrades toward
periodic snapshot refreshes for the duration of a long turn. That may still beat
today's behaviour (whole session torn down), but it is a materially different
trade than the one the rest of this document was written to make.

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

---

# Amendment 2026-08-25 — the drain is the defect, not the queue

Evidence gathered from the wrapper boot of 2026-08-24 11:26 (2d uptime at time
of writing). **This amendment re-sequences the proposal above: Phase 1 is at the
wrong layer to go first.** Nothing here is implemented.

## What the current boot shows

| Signal | Value |
|---|---|
| Overflow teardowns | **41** |
| Connection losses from any *other* cause | **0** (41 of 41 were this overflow) |
| Turns ended `releasedWithoutTerminal` | 22 |
| Recoveries (`reattached runtimes:` 0 / 2 / 3) | 6 / 28 / 12 |
| Conversations sharing the path | 4 (`local-conv-190` 162k broadcasts, `-176` 25k, `conv-8d4b…` 612, `-210` 364) |

The App Server itself was healthy throughout: stall probe 5–8 ms, RSS ~320 MB,
no process restarts, no kernel OOM kills, **3** probe failures in two days
against **41** teardowns. Host swap pressure explains the 3, not the 41.
This failure is wrapper-side.

## The drain chain

```
WS socket ─▶ receiveAndDemuxFrames            one sequential loop, all conversations
                │ trySend, cap 1024            ← overflow THROWS, kills generation
                ▼
             streamDeliveryQueue
                │ for (f in q) streamFrameFlow.emit(f)
                ▼
             MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = SUSPEND)
                │ emit() SUSPENDS on the slowest subscriber
                ▼
             mergedFrames() = channelFlow { control.collect{send}; stream.collect{send} }
                │ ONE channel, default 64, send() suspends
                ▼
             AppServerClient.events ─▶ turn engine ─▶ ConversationTurnFanout
                │   observers → async, fire-and-forget, 5s timeout   OK
                │   initiator → awaitAll(...), NO timeout            BLOCKS
                ▼
             IrohViewerHandle.writeFrame
                └─ streamWriteMutex.withLock { sink.writeAll(...) }  ← QUIC network write
```

**Drain rate is gated by one awaited QUIC write to the initiator viewer, per
frame.**

## This answers the prerequisite question

The section above makes measuring drain throughput a blocking prerequisite, and
asks whether the consumer can sustain ~350 events/sec. **The question is
malformed as posed.** The consumer is not CPU-bound; it is bound to a network
round-trip to an unbounded external party. So the second branch of that question
already applies: *"no queue size fixes anything… the fix would then have to be on
the drain side."* Confirmed. `DELIVERY_QUEUE_CAPACITY` is not the variable.

## There is no isolation anywhere on the inbound path

One drain coroutine serves every conversation and, transitively, every viewer.
The slowest single write *anywhere in the system* stalls the drain for
*everything*. Two independent faults compound:

1. **No isolation** — one sequential drain, N conversations × M viewers.
2. **The overflow policy for that stall is a global teardown** — `enqueueOrFail`
   throws and fails the whole generation, control included.

Either alone is survivable. Together, one slow writer on one conversation
degrades every surface — desktop included, which is how this was reported.

**Scoping note on the evidence.** Per-event `stream.job.done … generation
superseded` names only 0–2 endpoints (11 lines total across 3 distinct
endpoints), because only peers holding an *active stream job* at that instant are
superseded. The all-surface impact is structural — there is exactly one App
Server connection for the entire wrapper, and teardown closes the shared control
command queue — not something that appears in the log as simultaneous per-peer
supersession. Do not expect to see it there.

## Open question #3 now has an answer

The question asked whether anything else consumes `streamFrames` and assumes
completeness, flagging the Iroh fanout path, and called this "the largest open
question". **The fanout is not a co-consumer — it *is* the consumer, and it is
the bottleneck.** Transport-level eviction would therefore drop precisely the
frames the slow viewers had not yet taken, resync the local timeline, and leave
remote viewers stale. That is the failure the question anticipated. Isolation has
to land first.

## The control/stream isolation invariant is re-coupled downstream

`streamBackpressureCannotBlockControlDeliveryOnTheSharedSocket` holds *inside*
the transport — two queues, two delivery coroutines. But `mergedFrames()`
(`AppServerTransport.kt:25`) immediately merges both into **one** `channelFlow`
with a default 64-slot buffer, and that is what `AppServerClient.events`
(`AppServerClient.kt:185`) consumes. A blocked stream collector fills that shared
channel and suspends the control `send` behind it.

Control is therefore **not** insulated from stream backpressure in production,
only in the unit test's view of the transport. Phase 1 changes only the stream
queue's overflow policy and leaves this untouched.

## Separate defect: the App Server never answers `channels_list`

**First, a misleading signal to disregard.** In the wrapper log
`list_channels_failed` *always* precedes `App Server connection recovered`, which
reads like the restore is being issued against the dying generation and racing
the reconnect. **It is not.** `restoreChannels` runs *inside*
`ReconnectingClientListener.onRecovered` (`AppServerServeIrohCommand.kt:717`), on
that generation's own client, and the "connection recovered" line is printed at
the *end* of `onRecovered`. The ordering is just where the `println` sits. This
trap is easy to fall into from the log alone — the diagnosis below came from
probing the running App Server instead.

The real cause, confirmed by direct probe against the live App Server rather
than inferred from log ordering:

```
ws://127.0.0.1:4500/ws
  -> {"type":"app_server_info","request_id":"diag-info-1"}      REPLY in ~3 ms
  -> {"type":"channels_list",  "request_id":"diag-channels-1"}  NO REPLY in 15 s
```

Both are logged by the App Server as `Received`; only the first is answered.
**letta-code 0.29.12 accepts `channels_list` and silently never responds.** Same
class of wire gap already documented for skills enumeration on this version
(`letta-mobile-7dm1q`).

That explains the entire failure distribution. Every restore blocks until either
the 120 s request timeout expires (10 of 47 — including `channel-restore-1` on
the very first connect, `attempt=0`, with no teardown anywhere near it) or the
overflow churn tears the generation down under it first (36 of 47, `transport
disconnected`). The two reasons are one bug, not two, and the `transport
disconnected` majority is the *drain* defect masking this one.

**Operational consequence.** `LETTA_CHANNELS_HOST=1` is set in
`/etc/meridian/iroh-wrapper.env` and the ownership banner has printed 6 times, so
the channels-host cutover was performed; lettashim now sits in
`/etc/systemd/system/disabled-units/` with `SHIM_CHANNELS_ENABLED=0`. Since the
wrapper's restore has never once succeeded (47/47, `channels=0 started=0`),
**no process is currently hosting channel accounts.** That is an operator
decision, not a code change, and it is independent of the drain work.

## Re-sequenced plan

- **Layer 0 (independent; needs a decision, not a patch).** The App Server does
  not implement `channels_list`, so `--channels-host` cannot work on letta-code
  0.29.12. Either roll the cutover back (lettashim resumes as channels host) or
  carry the gap upstream. Until then the wrapper should detect the unanswered
  command and degrade loudly rather than burn a 120 s timeout on every
  reconnect.
- **Layer 1 (the real fix).** The inbound drain must not be a single global
  sequential loop. Demux by runtime scope into per-conversation lanes, each with
  its own bounded queue and drain coroutine, feeding per-viewer outbound queues
  with their own writer coroutines. A slow viewer then backs up exactly one
  viewer's queue; a slow conversation backs up one lane; neither reaches control
  or another surface. Per-viewer serial writers preserve the frame ordering and
  `event_seq` monotonicity that the initiator `awaitAll` exists to protect
  (`ConversationTurnFanout.kt:370`) — ordering comes from the queue rather than
  from lock acquisition order. What is lost is implicit end-to-end backpressure
  toward the App Server; the per-viewer queue bound replaces it, and overflow
  there is a *viewer-scoped* resync, which is the correct blast radius.
- **Layer 2.** Phase 1 above (`DROP_OLDEST` + conflated `streamResyncRequests`)
  as the safety net it was designed to be. Once backpressure is contained
  per-lane, eviction is exceptional again — which is the assumption Phase 1 rests
  on and which the "Scale" section correctly observes is false today.
- **Layer 3.** Conflation of the four absolute-state types at the **viewer
  queue**, not the transport. The mutex-guarded `ArrayDeque` that Phase 2 says it
  needs already exists there once Layer 1 lands, so conflation becomes a keyed
  replace on insert — and it applies per slow viewer, which is where the pressure
  is.
- **Layer 4.** Collapse the `mergedFrames()` re-coupling, or the isolation
  invariant stays fictional at the only call site that matters.

## Still unexplained — settle before building

The ratio of `releasedWithoutTerminal` to overflow events was 6/28 in the
2026-08-23 boot and is 22/41 here. The drain mechanism above does not explain
either figure or the change between them. The "Scale" section already flags this
as unexplained; it still is, and it should be settled before committing to an
implementation.
