# h30cy — the shadow-holder dual-reducer failure mode

## Summary

Every `TimelineSyncLoop` secretly ran a **second, parallel reducer** — the
"experimental" `ConversationStateHolder` (bead `letta-mobile-bfqgi`, "shadow
holder"). It executed the SAME `reduceStreamFrame` on its own frame flow, in
parallel with the authoritative dispatcher, and **diverged** — never matching the
authoritative timeline and lagging behind it. This dual-reducer race is the reason
the Iroh "dropped character → duplicate row" symptom was intermittent and
resistant to every fix aimed at the authoritative path (dedup, replay, subscribe
gate): those fixes never touched the shadow reducer, which kept ingesting the same
frames under an untagged (`"unknown"`) source and diverging.

## The evidence (live 3-response capture, build 0.14.0-52-g27d31a511)

Telemetry ratios across three live replies:

| Event | Count | Meaning |
|---|---|---|
| `gate1.emitBoth` / `gate1.emit` | 155 / 153 | transport emits per turn set |
| `gate2.bridgeEvent` | 465 (= **3×** emits; 459 `MessageDelta`) | WsChatBridge fans each frame 3× |
| `gate.reduceIngest.coordinator.loop…` | 69 | authoritative dispatcher reduce path |
| `gate.reduceIngest.unknown` | 70 | **second, untagged reduce path (the shadow holder)** |
| reduceIngest total / emit | 139 / 153 = **0.91** | NOT a clean 2× — the two paths SPLIT frames, hence intermittent |
| `streamSubscriber.foldedViaHolder` | 70, ALL `emitted=true` `matched=FALSE` `shadowHolderLag=32` | shadow reducer 32 events BEHIND, never reconciles |

The `matched=false` on every fold with a steady `shadowHolderLag=32` is the number
that was hard to quantify by eye: the shadow timeline is a fixed 32 events behind
the authoritative one and never catches up.

## Why it diverges

- `TimelineSyncLoop` (line ~62) constructs `ConversationStateHolder`, fed via
  `holderFramesIn` — a `MutableSharedFlow(extraBufferCapacity = 64)` with
  **replay = 0**.
- `TimelineStreamDispatcher` (line ~52) forwards each frame to the shadow with
  `holderFramesIn.tryEmit(message)`. `tryEmit` **DROPS** when the buffer is full or
  no active collector is attached — so the shadow reducer misses frames exactly
  like the authoritative path's first-fragment drop, but independently. Its fold
  therefore lands on a DIFFERENT (shorter, lagging) timeline.
- The shadow's `reduceStreamFrame` runs with the default `source = "unknown"`
  (`TimelineStreamDispatcher.dispatch` / `TimelineSyncIngest`), which is why it
  shows up as the `reduceIngest.unknown` ingest path.

## Why it is safe to remove

The shadow holder is explicitly **non-authoritative** (its own doc: "not UI and
not authoritative … parity fold"):

- Authoritative state is `TimelineSyncLoop._state` (a `MutableStateFlow<Timeline>`)
  exposed via `state`. The dispatcher writes `_state`; the UI observes `state`.
- `holder.state` is read in exactly ONE place — `getHolderEventCount = {
  holder.state.value.events.size }` — used only to compute the `shadowHolderLag`
  parity metric. It is never projected to UI, never merged into `_state`.

So the shadow reducer contributes NOTHING the user sees. Its only live effects are
(1) a second parallel `reduceStreamFrame` that pollutes the reduce/ingest/dedup
diagnostic surface with an untagged path, and (2) an always-diverging lag metric.

## The fix

Strip the shadow holder's live wiring so exactly ONE authoritative reducer runs:
remove the `holder` / `holderFramesIn` / `holderHydrationSeed` construction and the
dispatcher's `holderFramesIn.tryEmit` fan-out + `getHolderEventCount` /
`foldedViaHolder` parity plumbing. Keep the authoritative `_state` path untouched.

If removing the shadow reducer resolves the on-device symptom, make it permanent
(delete the experimental holder path entirely rather than flag-gating it).
