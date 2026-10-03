# Phone garble, 2026-10-03 ~17:47-17:49 EDT (Meridian, `local-conv-571`)

What the owner saw on the Pixel 9 Pro (dev APK `0.19.0-59-g5b88530bc`): a streaming reply rendered
stacked. A partial copy of the message was followed by the whole message again, for example
`"…faster than describ" + "Garbled text, meaning…"` and `"sett" + "Garbled text…"`. After the turn
settled, reconciliation showed the stored thread correctly ("reconciliation cleans it up but it
starts badly").

## Deployed build

- `readlink /opt/meridian/iroh-wrapper/current` →
  `/opt/meridian/iroh-wrapper/releases/main-5b88530bc`, active since **17:43:02 EDT**.
- So the garble happened on the **post-#1761** wrapper (TurnStreamIdentity stamping), with the
  matching post-#1761 APK. Neither includes the client mapper change (ys9it).

## Files

| file | what it is |
|---|---|
| `stored-assistant-messages.jsonl` | Stored `message.list` text of the last 3 assistant messages (`ui-msg-9184701` 705 chars, `ui-msg-9184703` 148, `ui-msg-9184707` 909). **Clean**, no duplication. |
| `turn-structure.jsonl` | The window's stored rows: ids, types and times only, no user text. |
| `same-build-capture.wire-viewer.jsonl` | **Not the garbled turn.** Frames the same `5b88530bc` host fanned out to a viewer for a test turn at ~17:44 (agent `identity-capture`). |
| `same-build-capture.initiator-serverframes.jsonl` | Same test turn, as the **sending** client's `IrohChannelTransport` mapped it (the phone's role in the garbled chat). |

## The frames for the garbled reply were not recorded

- **Wrapper log:** it has no frame bodies. Since the deploy it contains zero occurrences of
  `logical_message_id`, `text_seq`, `"content"` or `FrameFlowDiag`. For each frame of
  `local-conv-571` it logs only
  `fanout.broadcast conversationId=local-conv-571 viewerCount=1 hasInitiator=true`.
  `IrohFrameFlowDiagnostics` / `chatHotPathDebug` are in-process flags that are off in the wrapper.
- **Phone logcat:** the app logged three lines in the window. Its telemetry doesn't reach logcat in this build.
- **Device timeline ledger:** it holds settled history, which reconciliation already cleaned.

## What the same-build capture shows

These are host output and client mapping on `5b88530bc`, 3 minutes before the garble:
- Every assistant and reasoning frame carries `logical_message_id`, `turn_id` and `text_seq`
  (310/310). `text_seq` runs 1..n per logical id.
- The text is a **cumulative snapshot** under each logical id, with no non-superset rewrite.
- The initiator's mapped `ServerFrame`s are also cumulative per (id, otid), with no self-repeat.
- Since #1761 the observer's otid is per turn (`iroh-assistant-<client otid>`), not per conversation.

So at the transport/mapper layer this build streams correctly. The stacking is **added
downstream**: in the client timeline reducer, at the live-to-settled join, or on a path the test
turn didn't exercise.

## Leads (inference, not proven)

1. **Two assistant messages in one turn.** The reply that began "That points at the live stream
   path…" is stored as two assistant messages in a single turn: `ui-msg-9184703` (148 chars), then
   tool calls, then `ui-msg-9184707` (909 chars), across runs 830-832. On the sender, both carry the
   per-turn otid `iroh-assistant-<turnId>`. A reducer keyed on otid gets the
   "two messages, one otid" stacking (see `openrouter-grok-4.7/`). This will remain until the
   client reads `logical_message_id` (ys9it).
2. **One message stacked on itself.** `ui-msg-9184701` was one assistant message in its turn,
   yet it rendered as `A[0:k] + A`. That pattern means a cumulative snapshot was appended onto an
   earlier prefix of the same message. Possible causes: a second ingest door replaying the
   message (live frames plus a hydrate/replay at the boundary), or an append path that never sees
   the snapshot's `text_seq`. Note that this turn began 4 minutes after the wrapper restart, so a
   reconnect replay is plausible.

## Next capture

To get the frames for the next garbled reply, attach a raw viewer (low-level client,
`runtime_start` on `local-conv-571`) while the owner sends from the phone, or enable
`log.tag.LettaFrameFlowDiag`/`LettaChatHotPath` on the device
(`adb shell setprop log.tag.LettaChatHotPath VERBOSE`). Then pull the per-frame
`gate.reduceIngest` / `mergeBranch` lines for that window.
