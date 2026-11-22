# Conversation feed v1: the host's durable, resumable record of every turn

Status: **v1, host journal, `feed.subscribe` and the live `feed_delta` push implemented; the shared client (`FeedTimelineSource`) renders from them behind the capabilities. Turn commands and removing the old client path are later stages.**
Epic: letta-mobile-p3hlw (host owns turns and the canonical timeline; clients become views).

Fixtures: `android-compose/sharedLogic/src/commonTest/resources/contracts/feed.subscribe/feed-v1.json`, with a short
spec in [feed.subscribe/feed-v1.md](feed.subscribe/feed-v1.md), and `.../contracts/feed-delta/v1.json` for the live push
(see Live push).
Wire names, limits and codes: `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/feed/wire/`.
Host journal, store, route and push: `android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/feed/`;
the turn-side interface: `TurnJournal` in `.../node/iroh/`; the producer: `NodeTurnHost`.
Client: `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/feed/client/` (`FeedTimelineSource`,
`FeedFollower`) and `.../data/feed/channel/` (the channel transport binding).
Contract tests: `FeedFixtureTest` (commonTest) decodes every case through the client types; `FeedRoutesTest` and
`FeedJournalStoreTest` (jvmTest) replay them through the real route and the real store; `WireContractsTest` (jvmTest)
runs every case through the full host router; `FeedDeltaFixtureTest` (jvmTest) writes the `feed-delta` cases through the real
journal, registry and pusher.

## What this is, and why

Today a client builds a conversation's timeline from two feeds, the live stream and refetched pages (`message.list`), and
merges two shapes of the same rows. That is the two-writer bug behind blank tool returns, a call listed twice and settle
flicker. The remedy is one writer: the **host** records every turn event in order, per conversation, and clients read that
one ordered record, resuming from the last position they applied.

The App Server's `event_seq` cannot be that position. It counts per listener connection and restarts when the host's
connection to the App Server is re-made (`EventSeqGapDetector`). The feed has its own numbering, owned by the host.

This version ships the host half: the record is **durable** (a wrapper restart keeps finished turns) and **readable from a
cursor** (`feed.subscribe`). It replaces the in-memory ring (`ParkedTerminalStore`, 256 turns by 256 frames, lost on restart)
that parked a detached turn for its initiator's redial, and a reconnecting initiator is now served from the same record.
Nothing a client does today changes: the live stream and `message.list` are untouched.

## Decisions this contract fixes

1. **One host per conversation.** A conversation is served by exactly one host and a client is bound to one host. A cursor is
   meaningful only to the host that issued it. Multi-host is out of scope.
2. **The host never invents a terminal.** It records what it observed. A turn it stopped observing without a real terminal
   (the wrapper died, the App Server connection was lost) is closed with a `turn_closed` entry whose `terminal_observed` is
   `false`. That is a fact about the host, not a `stop_reason`, an `error_message` or a `loop_error`, and no client may treat it
   as the turn's end state. A turn ends only through the App Server's own terminal, or by an abort the host sends to the App
   Server (`abort_message`); the App Server's reply to the abort is then the terminal, and it is recorded like any other.
3. **The journal is the sole row source for a conversation on the feed.** A later stage retires `message.list` reconciliation
   for such a conversation; it is never run beside the feed. Paging older history stays a separate, read-only path.
4. **Epoch beside seq.** Every position is `{epoch, seq}`. `epoch` identifies one host start and only grows (persisted, never
   below the previous one even if the clock steps back); `seq` is the position in one conversation's journal and keeps growing
   across restarts. A cursor whose epoch is not the host's is not an accidental gap but a defined resync.
5. **Large payloads are pointers.** No journal entry or page may approach the 1 MiB Iroh frame cap (see Chunking).
6. **Retention is bounded** by entries and bytes per conversation and by a directory ceiling (see Retention).

## Capability and discovery

The host advertises the auth capability **`conversation_feed_v1`** (through `AdminRpcRouter.featureCapabilities`) when it
serves `feed.subscribe`. Without it a client has no feed: it keeps today's path and never guesses. The method is classified
`chat.read` (the tier of `message.list`); a pairing without it is refused by the transport. A host that predates the contract
answers `Unknown method: feed.subscribe`.

## Positions and entries

A **cursor** is `{"epoch": <int >= 0>, "seq": <int >= 0>}`: the last entry a client applied. A **delta** is one journal entry:

```json
{"epoch": 1700000000000, "seq": 42, "kind": "stream_delta", "turn": "<turn id>", "payload": { ... }}
```

| Field | Meaning |
| --- | --- |
| `epoch` | The host start that wrote the entry. To resume, use a page's `cursor`, never an entry's epoch. |
| `seq` | Strictly increasing within the conversation. Not contiguous: a cumulative text snapshot replaces its predecessor and the replaced `seq` is a hole. A hole is never a loss signal; the page's `mode` is. |
| `kind` | `turn_open`, `stream_delta` or `turn_closed`. A client ignores kinds it does not know. |
| `turn` | The turn: the client message id of its prompt, else a host-minted `turn-<uuid>`. |
| `payload` | `stream_delta`: one App Server stream delta body, verbatim (the object that is `stream_delta.delta` on the live stream, already stamped with `logical_message_id`/`turn_id`). `turn_closed`: `{"terminal_observed": bool, "host_restarted"?: true}`. `turn_open`: `{}`. |
| `pointer` | Set instead of `payload` for a payload over the inline cap. Exactly one of the two is present. |

`feed_delta` is the frame type of the live push (see Live push). A client that does not know it ignores it.

## `feed.subscribe`

`feed.subscribe` is an `admin_rpc` method. Despite the name, it is a **read from a cursor**: ask again to see later entries,
which is what a reconnecting client does. A host that also advertises `conversation_feed_push_v1` pushes later entries as
`feed_delta` frames (see Live push), and the read is how a client catches up and recovers from anything the push cannot carry.

Params:

| Param | Meaning |
| --- | --- |
| `conversation_id` | Required. The conversation, including the default conversation under either spelling (`default`, or `conv-default-<agent>`). |
| `agent_id` | The agent owning the conversation. Required with the bare `default` (without it the request is `invalid_request`); otherwise optional and checked against the recorded owner. |
| `from` | `{epoch, seq}`: the caller's cursor, or the previous page's `cursor`. Absent asks for a snapshot. |
| `limit` | Most entries in the page, 1 to 500; default 200. |

Result (`FeedPage`):

```json
{"mode": "replay", "resync_reason": null, "cursor": {"epoch": 1700000000000, "seq": 44}, "base_seq": 0, "head_seq": 44,
 "has_more": false, "entries": [ ... ]}
```

- `mode: "replay"`: `entries` are exactly the entries after `from`, in order. The caller appends them.
- `mode: "snapshot"`: the caller's position is unusable; `entries` start the retained journal over and the caller **replaces**
  whatever it holds for the conversation, then continues in `replay` from the returned `cursor` while `has_more`.
  `resync_reason` is one of:
  `no_cursor` (the caller named none), `epoch_changed` (the host restarted), `behind_horizon` (the caller's `seq` is below
  `base_seq`: retention compacted past it), `ahead_of_head` (the caller's `seq` is above `head_seq`: the host lost journal data).
- `cursor` is where to continue: the last returned entry's position when `has_more`, otherwise the head, so a caller that is
  fully caught up gets the same cursor back and an **idle conversation emits zero deltas**.
- `base_seq` is the compaction horizon (entries at or below it may be gone); `head_seq` is the newest `seq` ever issued.
- A page never holds more than 768 KiB of entries (each counted as its body, its turn id and a fixed envelope allowance) plus one entry; `has_more` says to ask again with the returned `cursor`.

### Authorization (per viewer)

Authorization is evaluated on every request, not once per connection:

1. **Conversation scope**: the same rule `message.list` applies. A peer bound to one conversation reads only that one. A peer bound to conversations (not unrestricted) may not read the bare `default`, even when `default` is in its scope: the bare id names no agent, so which journal it selects would come from the request, not from the peer's binding. Such a peer reads its `conv-default-<agent>` alias, whose owner is part of the id.
2. **Agent scope**: the journal is keyed by `{agent, conversation}`. For an agent-scoped peer the owning agent must resolve
   (the alias names it; else the request's `agent_id`; else the owner the journal recorded) and be in the peer's scope, and a
   named owner that differs from the recorded owner is refused. Naming another agent's conversation under your own agent
   therefore reads nothing. An owner that cannot be resolved is a **refusal**, never an empty page: an empty answer would
   tell the peer the conversation belongs to someone else. The bare `default` has no recorded owner, so an agent-scoped peer
   that is not bound to conversations must name its agent.
3. The router refuses a request that names two different agents (top level and under `runtime`) before the handler runs.

Unscoped peers read any conversation. (A conversation id whose owner the journal never recorded answers an empty snapshot.)

### Errors

| `error` | When |
| --- | --- |
| `invalid_request` | Missing or blank `conversation_id`, a non-string id, a bare `default` with no `agent_id`, a malformed or negative `from`, a `limit` outside 1 to 500. |
| `forbidden: agent not in this device's scope` | An agent-scoped peer and an owner that is out of scope, unknown, or not the named one. |
| `forbidden: conversation out of authorized scope` | A peer bound to a different conversation. |
| `forbidden` | The transport's capability gate (no `chat.read`). |
| `unavailable: ...` | The host could not persist its feed epoch for this start, so it does not serve the feed (a cursor from this start could otherwise match a later start). |
| `Unknown method: feed.subscribe` | A host that predates the contract. |

Clients map unknown codes to an "unknown" error; they must not fail on a new code.

## Live push: `feed_delta`

Status: additive to v1; a host announces it with the capability **`conversation_feed_push_v1`**, advertised beside
`conversation_feed_v1` (a host without it never sends the frame, and a client then reads `feed.subscribe` on its own schedule).

### Who is pushed to

A connection is pushed `feed_delta` frames for a conversation when all of these hold, **evaluated per frame at send time**,
exactly as `pins_updated` and `shell_state_updated` are gated per viewer:

1. It is a registered viewer of the conversation: it has called `feed.subscribe` (or `message.list`) for it. The viewer set is the
   host's one registry of conversation viewers; a connection views one conversation at a time, and a rejected read leaves it
   unregistered, so a refused viewer is never pushed to. A frame for a conversation nobody views is never built: an idle host
   does no push work and writes no frame.
2. It has called `feed.subscribe` on this connection (a client that never reads the feed never receives one).
3. Its granted capabilities allow `feed.subscribe` (`chat.read`).
4. It may read the conversation under the name it views it by, by the rule `feed.subscribe` applies: a peer bound to
   conversations never receives the bare `default`'s frames (it names no agent) but receives its `conv-default-<agent>` alias's;
   a peer bound to conversations receives only the one it views.
5. The journal's owning agent is in its agent scope; an agent-scoped peer receives no frame of another agent's journal.

A peer that fails any check receives nothing and is not told why. Telemetry carries counts only, never ids.

### The frame

```json
{"type": "feed_delta", "conversation": "conv-1", "agent_id": "agent-a", "epoch": 1700000000000,
 "seq": 44, "after_seq": 41, "entries": [ ... ]}
```

| Field | Meaning |
| --- | --- |
| `conversation` | The journal's key: the bare `default` for an agent's default conversation (whichever spelling the viewer used), else the conversation id. |
| `agent_id` | The agent owning the journal. A client matches a frame to its conversation by this pair, never by the spelling it asked with. |
| `epoch` | The host start that wrote the entries. |
| `seq` | The seq of the newest entry the frame carries, or the head when it carries none. |
| `after_seq` | The seq this frame continues from: the head before its first entry was written. |
| `entries` | The entries after `after_seq` up to `seq`, oldest first, in the shape `feed.subscribe` returns them. Empty in a resync marker. |

**Coalescing.** The host writes at most one frame per conversation per frame budget (about 32 ms): the entries written within it
travel in one frame, and a cumulative snapshot superseded inside it is not sent, so a streamed message is a few frames, not one
per chunk. A frame carries at most 128 entries; a burst past it, or one retention compacted past, is sent as a **resync marker**:
no entries, `seq` and `after_seq` both the head.

**Bounded.** A connection already four frames behind drops the frame instead of queuing it, and a write that does not finish in
five seconds is abandoned. Dropping loses nothing: the next frame's `after_seq` is above the client's cursor, which tells it.

### What a client does

A client holds one cursor per conversation. On a frame it:

1. Ignores a frame whose `conversation` and `agent_id` are not the conversation it follows.
2. **Resyncs** when `epoch` differs from its cursor's epoch, when `after_seq` is above its cursor's `seq` (it missed entries), or
   when the frame is a marker above its cursor. A resync is a `feed.subscribe` read from its cursor, repeated while `has_more`;
   the client never applies a frame it cannot follow and never guesses what a hole held.
3. Otherwise applies the entries above its cursor in order and moves the cursor to `seq`. A frame at or below the cursor is a
   repeat and changes nothing.
4. On every new connection reads from its cursor before trusting any push: frames sent while it was away are gone.

A seq hole is never a loss signal, in a frame as in a page. A `snapshot` page replaces what the client holds; a `replay` page
appends only what is above the cursor.

Fixture: `.../contracts/feed-delta/v1.json`. Each case registers viewers on the real registry, writes turns through the real
journal, and lists the exact frames each viewer received (and that the others received none). Tests: `FeedDeltaFixtureTest`,
`FeedPusherTest` (coalescing, `after_seq` continuity, marker, zero work when unviewed), `FeedTimelineSourceTest` and
`FeedFollowerTest` (the client rules).

## Client

`FeedTimelineSource` renders one conversation from the feed alone, behind both capabilities (`FeedTimelineSource.isAvailable`);
against any other host the old path stays in place. It reads `feed.subscribe` pages and applies `feed_delta` frames by the rules
above, folds each entry with the live reducer and projection the app already uses (so a row has the id, run and key it has
everywhere), and publishes one ordered list. A cumulative snapshot replaces its row where it stands: a row keeps its key and
its instance unless its own content changed, so the list under it redraws only that row. `FeedTimelineMetrics` counts list
replacements (rows removed or reordered, or a host snapshot), rows redrawn, pages read, resyncs and frames applied, and the
source reports `generation.replaced` for a replacement, the name the old path uses, so one device capture compares both.

The host journals the user's prompt echo (`user_message`) beside the turn, so a viewer's list holds the prompt; the
initiator's redial replay still skips it.

## Chunking and large payloads

Iroh frames are capped at 1 MiB (`IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES`); a peer that advertised `frame_part` may receive
a logical frame split into parts, but a journal must not depend on that.

- A payload over **256 KiB** (`MAX_INLINE_PAYLOAD_BYTES`) is **not stored**: the entry carries a `pointer` instead (except a terminal delta: it is never a pointer, because a replay would lose it; an oversized terminal keeps its type, stop reason and status and has its long text fields shortened):
  `{"reason": "too_large", "byte_len": <int>, "message_type": "...", "message_id": "..."}`. The pointer names what was
  omitted; it does not carry the data. A tool return is fetched on demand with the existing `tool_return.get` (its row id is
  the pointer's `message_id`); an attachment is fetched through the existing attachment path. There is no new fetch path in v1.
- A page carries at most 768 KiB of entries plus one entry, counting each entry's turn id (repeated on every entry) as well as its body, so a page is always a single frame under the cap. A turn id longer than 128 characters is replaced in the journal by `sha256:<hex>` of it (the same raw id always names the same turn), so a long id cannot inflate a page.
- A client renders a pointer entry as a row that is present but not yet loaded, and never as an error.
- The journal never stores base64 attachment bodies beyond the inline cap; the row's own identity still arrives, so the row
  appears and its bytes load by pointer.

## Retention and disk

- Per conversation: at most 1000 entries and 4 MiB (defaults; the host may configure them). Over either, the host drops
  **finished turns oldest first**, then, only for a single enormous running turn, its oldest entries, down to three quarters
  of the bound, and raises `base_seq`. A turn that is running here is never dropped whole.
- Cumulative text snapshots of one message are coalesced: only the newest is kept (the first snapshot is written at once, then every 16th replacement and
  when the message ends, so a host crash loses at most the newest 15 snapshots of the message being streamed), so a streamed message costs one entry, not one per chunk.
- Directory: at most 64 MiB of journal files. Over it, the host deletes the **least recently used** conversations' files
  (never one with a running turn) down to three quarters of the ceiling. A deleted conversation reads as an empty snapshot and
  its viewers resync. A conversation whose journal is created again after eviction starts after the newest seq the evicted one
  issued (the host keeps that floor in memory for the epoch), so a seq is never issued twice in an epoch and a cursor from the
  evicted generation is answered with a `snapshot` (`behind_horizon`), never a silent skip.
- At most 32 conversations are loaded in memory; idle ones are written out and reloaded on demand.
- **Corrupt or unreadable file**: the host logs a warning, deletes that conversation's file and starts it over. It never
  crashes and never serves a partially decoded journal. A final line cut off by a crash is dropped and the rest is kept.
- **No state directory** (or one that cannot be written): the journal lives in memory only, logged; turns are unaffected.

## Reconnect and restart behaviour

- **Initiator reconnect.** An initiator whose connection dropped mid-turn re-sends the same `client_message_id`. The host
  recognises the turn from the journal (also after a restart), does not run it again, and replays the turn's journaled deltas
  to the new connection. When the journal holds no real terminal for that turn, the existing redial path still appends its
  legacy `error_message` stub for old clients; the stub is never written to the journal.
- **Any viewer.** Reads the journal with `feed.subscribe` from its cursor.
- **Wrapper restart.** The journal survives. The next start issues a greater `epoch`, so every cursor from before is answered
  with a `snapshot` (`epoch_changed`). A turn that was running at the restart is closed with `terminal_observed` true only if a
  real terminal had already been journaled, and `host_restarted: true`.
- **Idle conversation.** Zero deltas, zero pushes, a stable cursor.

## Invariants, named as tests

| Invariant | Test |
| --- | --- |
| No gap and no duplicate across reconnect | `FeedJournalStoreTest.reconnectingFromACursorYieldsExactlyTheMissedDeltasWithNoGapAndNoDuplicate`, `pagingByLimitNeverSkipsOrRepeatsAnEntry` |
| A restart is a new epoch and a defined resync | `FeedJournalStoreTest.aCursorFromBeforeARestartForcesAResyncWithTheRetainedJournal`, `FeedJournalStoreTest.theEpochOnlyGrowsEvenWhenTheClockGoesBackwards` |
| Finished turns survive a wrapper restart | `FeedJournalStoreTest.aFinishedTurnSurvivesAWrapperRestartUnderANewEpoch`, `NodeTurnHostTest.aFinishedDetachedTurnIsStillOwedToItsInitiatorAfterASimulatedWrapperRestart` |
| An idle conversation emits zero deltas | `FeedJournalStoreTest.anIdleConversationEmitsZeroDeltas` |
| The host never synthesizes a terminal | `FeedJournalStoreTest.theHostNeverSynthesizesATerminalForATurnItNeverSawEnd`, `NodeTurnHostTest.aTurnThatEndsWithoutARealTerminalIsJournaledAsNotObservedAndNoTerminalIsInvented` |
| A scoped viewer cannot read another agent's journal | `FeedRoutesTest.aScopedViewerCannotReadAnotherAgentsJournalByNamingItsConversationUnderItsOwnAgent` and the alias and owner-unknown cases beside it |
| Retention and the disk ceiling hold | `FeedJournalStoreTest.retentionBoundsEntriesAndDropsFinishedTurnsOldestFirstButNeverTheRunningOne`, `theDiskCeilingDeletesLeastRecentlyUsedConversationsAndKeepsOneWithARunningTurn` |
| A corrupt file is a reset, not a crash | `FeedJournalStoreTest.aCorruptJournalFileIsLoggedAndResetNeverACrash`, `aTornFinalLineFromADiedWriteIsDroppedAndEverythingBeforeItIsKept` |
| Fixtures decode on both sides | `FeedFixtureTest` (client types) and `FeedRoutesTest` / `WireContractsTest` (real route) |

Invariants for later stages, named here so they are not forgotten: a client restart mid-turn rejoins the same turn; Stop from
device A stops the turn on B; settle produces exactly one in-place update.

## Compatibility

- **Old client, new host**: it never sends `feed.subscribe` and sees no change; the live stream and `message.list` are
  untouched. A redial of an old client is served from the journal by the same path as before (same frames, plus the legacy stub
  when no real terminal was journaled).
- **New client, old host**: no `conversation_feed_v1` capability, so it keeps today's path.
- **Unknown fields and kinds** are ignored on both sides.

## Out of scope for v1

Commands (send, stop, approve with idempotency keys), a host-to-client channel for
device-owned tools, local mode (the in-process host restarts with the app; resume semantics are open), and any change to
letta.js's own cancel when the wrapper's connection drops.
