# Real-turn identity captures (letta-mobile-bglj6.1.15, input to epic letta-mobile-4vtng)

Raw frames from real App Server turns over the deployed Iroh wrapper (main `7f81cdac1`,
node `330415cc…`, App Server letta-code 0.32.17), captured 2026-10-03. Use them to check
the identity stamper (letta-mobile-jdcoj) and ledger (letta-mobile-r1xkl) against real
data instead of hand-made frames.

## What was captured

Each model directory holds two turns run on the `probe` agent, each in a new conversation.
Every turn has one tool call (a `Bash` command, `date` or `uptime`) and a streamed reply
of two paragraphs. Three clients were connected to the same conversation at the same time:

| file | client | layer |
|---|---|---|
| `turnN.wire-viewer.jsonl` | low-level `DefaultAppServerClient` that sent `runtime_start` for the conversation | **raw wire** frames (`AppServerReceivedFrame.raw`), envelope included: `event_seq`, `idempotency_key`, `emitted_at`, `delta` |
| `turnN.desktop-serverframes.jsonl` | `IrohChannelTransport` that **sent** the turn (the desktop role) | `ServerFrame`s after the production mapper, which is what the timeline consumes |
| `turnN.phone-serverframes.jsonl` | a second `IrohChannelTransport` that only **observed** the turn (the phone role) | `ServerFrame`s from the observer ingestion path |
| `turnN.message-list.json` | `message.list` (`order=desc`, newest first) read after the turn settled | the stored rows. The turn2 list also includes turn1's rows |
| `ids.md` | generated | every id field per `message_type` per layer, with the text-frame classification |

Each line is `{recv_ms, …, frame|wire}`, in arrival order. Turn 2 also re-subscribes the
phone client mid-stream: `message.list` runs about 4 s after the send, as if the phone opened
the chat while the desktop turn was still running.

Models, named by what the LiteLLM proxy actually **served** (from its
`x-litellm-model-group` / `attempted-fallbacks` headers). Some providers were out of
quota, so the proxy silently failed requests over to MiniMax-M3:

| directory | requested | served |
|---|---|---|
| `minimax-m3` | `lmstudio/MiniMax-M3` | MiniMax-M3 |
| `minimax-m3-fallback-from-qwen3.8-max` | `lmstudio/qwen3.8-max` | MiniMax-M3 (fallback) |
| `minimax-m3-fallback-from-kat-coder` | `lmstudio/KAT-Coder-Pro-V2.5` | MiniMax-M3 (fallback) |
| `claude-sonnet-5-5` | `lmstudio/claude-sonnet-5-5` | Claude Sonnet 5.5 |

So these are two real model families, not four. The findings below hold for all 8 turns. No secrets: the frames contain only the prompts, `date`/`uptime` output, the
model replies, and the App Server's own device-info `system-reminder`.

## Findings

### Text: cumulative, never rewritten

- **The wire to a viewer carries cumulative snapshots.** Every `assistant_message` frame
  holds the full text so far. Across 1,107 assistant frames in 8 messages, each frame after
  the first is a strict superset of the one before (`new.startsWith(old)`). There are
  **zero non-superset rewrites** and zero pure-increment frames.
- Both clients' mapped `ServerFrame`s keep the same cumulative shape, frame for frame.
- The final streamed text **equals** the stored `message.list` text for all 8 assistant
  messages.
- The stacked-copies symptom did **not** appear at the frame level, on either client or
  in either turn, including after the mid-turn re-subscribe. Each client's assistant frame
  count matches the wire one-for-one (no duplicated or replayed text frames).

### Which id fields are stable

| entity | wire (viewer) | desktop ServerFrame (sender) | phone ServerFrame (observer) | message.list | stream→stored join |
|---|---|---|---|---|---|
| assistant message | `id = ui-msg-N`, stable on every frame. **No `otid`.** `seq_id` +1 per frame | `id` same; `otid = iroh-assistant-<turnId>` (client-minted, **per turn**) | `id` same; `otid = iroh-assistant-iroh-observer-turn-<conversationId>` (client-minted, **per conversation**) | `id = ui-msg-N`, no otid or run_id | **`id`** (exact) |
| user message | `id = cm-user-<clientOtid>`, `otid = clientOtid` | same | same | `id = ui-msg-N`, `otid = clientOtid` | **`otid`** only. The first stored user message has a `<system-reminder>` prepended (1311 vs 276 chars), so content matching fails |
| tool call | no `id`, no `otid`. Only `run_id` and `tool_call.tool_call_id` | `id = toolcall-<callId>` (minted), **emitted twice** (second copy has no `seq`) | same, twice | `approval_request_message`, `id = ui-msg-N:tool:<callId>:request` | **`tool_call_id`** |
| tool return | **2 frames, 2 different ids** for one call: `synthetic-tool-return-stream-<callId>` and `synthetic-tool-return-<uuid>` | 3 frames: the 2 above + minted `toolreturn-<callId>` | same 3 | `id = ui-msg-N`, `tool_call_id` | **`tool_call_id`** only |
| run | `run_id = local-run-N`. **Rotates inside one turn**: the tool step is run N, the reply is run N+1 | adds a client `iroh-run-<uuid>` (first `turn_started`); **`turn_done.run_id` = the first run (N)** | user echo `run_id = iroh-observer-run-<conversationId>`; **`turn_done.run_id` = the last run (N+1)** | none | — |
| turn | none on the wire | `turn_id = iroh-turn-<uuid>` (minted per send) | `turn_id = iroh-observer-turn-<conversationId>`, **the same for every turn in the conversation** | none | — |
| ordering | `event_seq` and `idempotency_key` per frame | `seq` restamped per connection (e.g. 20000001…) | `seq` restamped (e.g. 21000001…) | none | — |

### What this means for 4vtng

1. **The observer's `otid` and `turn_id` are per conversation, not per message or per turn.**
   Every assistant message the phone sees in a conversation, across turns, carries the
   same `otid` (`iroh-assistant-iroh-observer-turn-local-conv-575` for both
   `ui-msg-9184506` and `ui-msg-9184510`). Any client matcher keyed on `otid` or `turn_id`
   will treat two different replies as one row. That is the per-conversation observer
   candidate named in bglj6.1.15 (`IrohObserverIngestor`, mapper `turnId` fallback).
   The frames alone don't prove that it is the writer behind the screenshot. A reducer
   replay of the phone files with `gate.reduceIngest`/`mergeBranch` logging is the next
   step.
2. **The upstream message `id` is the only stable assistant identity.** It is stable for
   the whole stream and equals the stored row id. That supports minting `logical_message_id`
   from `message_id`/`id` first, as jdcoj's precedence rule says.
   **The same collision happens on the sender within one turn.** A later capture on a
   working agent (not committed here, since it's a real agent's conversation) wrote **two
   assistant messages per turn**: a short preamble, then the real reply, with different
   upstream `id`s. On the desktop they shared one `otid` (`iroh-assistant-<turnId>` is per
   turn, not per message). On the phone all four messages across both turns shared one
   `otid`. That is the "two messages, one otid" shape behind stacked copies. A fixture
   for it should be built synthetically, or recaptured on a test agent that splits its
   reply.
3. **Tool rows have three ids, and only `tool_call_id` joins them.** The `tc-<callId>`/`tr-<callId>`
   rule in jdcoj removes the 3-ids-per-return and 2-copies-per-call duplication seen here.
4. **`run_id` is not a turn key.** It rotates inside a turn, and the two clients disagree
   on `turn_done.run_id`. Keying anything on `run_id` (`CumulativeStreamText`, run-keyed
   settle) splits one turn into two runs.
5. **Append-always is safe for the viewer wire**, but only because the host already
   accumulates (`CumulativeStreamText`). The viewer wire has no increments left to append.
   jdcoj's `RecordedTurnsStampDeterministicallyTest` should treat these files as
   `CumulativeSnapshot` input.

## Gaps

- **No reasoning part.** None of the models served produced reasoning. The thinking
  models (qwen3.8-max, KAT-Coder) were out of quota and the proxy fell back to MiniMax-M3,
  which streams no reasoning. Called directly, the proxy returns only `content` deltas.
  `anthropic/claude-sonnet-5` with `thinking.enabled` is served for real, but the proxy
  returns only a text block, never a thinking block. The last stored thinking on this host
  is from 2026-09-30 (qwen3.8-max). `usage.reasoning_tokens` was 0 throughout. A reasoning
  capture needs a route that actually serves a thinking model.
- The "desktop" role is a headless `IrohChannelTransport`, not the Compose Desktop UI. The
  in-process `chatHotPathDebug`/`frameFlowDiag` flags were on (`gate1.emit`/`FrameFlowDiag`
  gates logged), but no timeline reducer ran, so these captures contain no
  `gate.reduceIngest`/`mergeBranch` lines.
- The raw wire is the viewer connection's. The sending client's own raw wire was not
  tapped; its mapped `ServerFrame`s are in `desktop-serverframes`.

## How it was captured

A throwaway JVM program, not committed, compiled against the deployed wrapper's
`lib/*.jar`, opened the three connections above in one process. It ran
`agent.update` (model) and `conversation.create` over admin_rpc, then sent each turn with
`IrohChannelTransport.send`. `ids.md` was generated by a small Python classifier: for
each text frame it checks whether the frame equals, extends (`startsWith`), or diverges
from the previous frame with the same key.
