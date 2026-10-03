# Real-turn identity captures (letta-mobile-bglj6.1.15, input to epic letta-mobile-4vtng)

Raw frames from real App Server turns over the deployed Iroh wrapper (main `7f81cdac1`,
node `330415cc…`, App Server letta-code 0.32.17), captured 2026-10-03. Use them to check
the identity stamper (letta-mobile-jdcoj) and ledger (letta-mobile-r1xkl) against real
data instead of hand-made frames.

## What was captured

Each model directory holds two turns, each in a new conversation. The first four ran on the
`probe` agent. The `openrouter-*` ones ran on `identity-capture`, a test agent with a short
system prompt, through OpenRouter routes on the LiteLLM gateway that have reasoning enabled.
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
| `openrouter-gpt-6.1-sol` | `lmstudio/or-gpt-6.1-sol` | openai/gpt-6.1-sol via OpenRouter (**reasoning**) |
| `openrouter-gemini-3.8-flash` | `lmstudio/or-gemini-3.8-flash` | google/gemini-3.8-flash (**reasoning**, one non-streamed frame) |
| `openrouter-deepseek-v4.1-flash` | `lmstudio/or-deepseek-v4.1-flash` | deepseek/deepseek-v4.1-flash (**reasoning**) |
| `openrouter-qwen3.8-flash` | `lmstudio/or-qwen3.8-flash` | qwen/qwen3.8-flash (**reasoning**) |
| `openrouter-glm-5.3-flash` | `lmstudio/or-glm-5.3-flash` | z-ai/glm-5.3-flash (**reasoning**) |
| `openrouter-grok-4.7` | `lmstudio/or-grok-4.7` | x-ai/grok-4.7 (**reasoning**, and **two assistant messages per turn**) |

That makes 8 model families and 20 turns. Claude Sonnet 5.5 through OpenRouter produced no
reasoning and is left out. The findings below hold for every turn. No secrets: the frames contain only the prompts, `date`/`uptime` output, the
model replies, and the App Server's own device-info `system-reminder`.

## Findings

### Text: cumulative, never rewritten

- **The wire to a viewer carries cumulative snapshots.** Every `assistant_message` and
  `reasoning_message` frame holds the full text so far. Across 2,655 assistant frames and
  256 reasoning frames, each frame after the first is a strict superset of the one before
  (`new.startsWith(old)`). There are **zero non-superset rewrites** and zero pure-increment
  frames. Gemini sends its reasoning as one complete frame.
- Both clients' mapped `ServerFrame`s keep the same cumulative shape, frame for frame.
- The final streamed text **equals** the stored `message.list` text for all 35 messages
  (8 + 27 OpenRouter assistant and reasoning messages).
- The stacked-copies symptom did **not** appear at the frame level, on either client or
  in either turn, including after the mid-turn re-subscribe. Each client's assistant frame
  count matches the wire one-for-one (no duplicated or replayed text frames).

### Which id fields are stable

| entity | wire (viewer) | desktop ServerFrame (sender) | phone ServerFrame (observer) | message.list | stream→stored join |
|---|---|---|---|---|---|
| assistant message | `id = ui-msg-N`, or **`ui-msg-N:assistant:1` when the same message has a reasoning part first**; stable on every frame. **No `otid`.** `seq_id` +1 per frame | `id` same; `otid = iroh-assistant-<turnId>` (client-minted, **per turn**) | `id` same; `otid = iroh-assistant-iroh-observer-turn-<conversationId>` (client-minted, **per conversation**) | `id = ui-msg-N`, no otid or run_id | **`id`** (exact) |
| reasoning | `id = ui-msg-N:reasoning:0` (a **part id**: message N, part 0), stable on every frame. No `otid` | **wire id dropped.** Minted `id = iroh-reasoning_message-<runId>-<turnId>`, `otid = null` | minted `iroh-reasoning_message-<runId>-iroh-observer-turn-<conversationId>` | `id = ui-msg-N:reasoning:0` | **wire `id`**. The clients' minted id never matches the stored row |
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
   **The same collision happens on the sender within one turn**, and `openrouter-grok-4.7`
   captures it. Grok writes **two assistant messages per turn**: a short preamble, then
   the real reply after the tool, with different upstream ids (`ui-msg-9184646` and
   `ui-msg-9184648:assistant:1`). On the desktop both carry one `otid`
   (`iroh-assistant-iroh-turn-18aee1…`), because the minted otid is per turn, not per
   message. On the phone all four messages across both turns carry one `otid`. That is the
   "two messages, one otid" shape behind stacked copies, as a real fixture.
3. **Reasoning loses its identity on the client.** The wire gives each reasoning part a
   stable id equal to the stored row id (`ui-msg-N:reasoning:0`). Both clients throw it away
   and mint `iroh-reasoning_message-<runId>-<turnId>`, which never matches `message.list`.
   After the turn settles, the live reasoning row can only be re-joined by position or
   content, which is the same class of re-matching 4vtng deletes. Because the minted id
   is keyed by run, two reasoning parts in one run would collide.
4. **Tool rows have three ids, and only `tool_call_id` joins them.** The `tc-<callId>`/`tr-<callId>`
   rule in jdcoj removes the 3-ids-per-return and 2-copies-per-call duplication seen here.
5. **`run_id` is not a turn key.** It rotates inside a turn, and the two clients disagree
   on `turn_done.run_id`. Keying anything on `run_id` (`CumulativeStreamText`, run-keyed
   settle) splits one turn into two runs.
6. **Append-always is safe for the viewer wire**, but only because the host already
   accumulates (`CumulativeStreamText`). The viewer wire has no increments left to append.
   jdcoj's `RecordedTurnsStampDeterministicallyTest` should treat these files as
   `CumulativeSnapshot` input.

### Raw upstream (pre-fanout) deltas are pure increments

Every capture above was taken after the wrapper's accumulator (`CumulativeStreamText`) had
already run, so it could not show what the App Server itself sends. `raw-upstream/` closes
that gap. Each file is one turn sent **directly on the App Server WebSocket**
(`ws://127.0.0.1:4500`, the same upstream the Iroh wrapper consumes), on the
`identity-capture` agent, recording every raw frame (`{recv_ms, channel, wire}`), plus the
`message.list` read after it.

| file | route | text messages | raw text frames |
|---|---|---|---|
| `MiniMax-M3` | lmstudio | 2 assistant (preamble + reply) | 110 |
| `claude-sonnet-5-5` | lmstudio | 1 assistant | 79 |
| `or-glm-5.3-flash` | OpenRouter | 2 reasoning + 1 assistant | 188 |
| `or-gpt-6.1-sol` | OpenRouter | 1 assistant | 103 |
| `or-grok-4.7` | OpenRouter | 1 reasoning + 1 assistant | 112 |
| `or-qwen3.8-flash` | OpenRouter | 2 reasoning + 1 assistant | 89 |

- **Every raw assistant and reasoning delta is a pure increment**: the next chunk only
  (`"The"`, `" date"`, `" command"`…). There are 0 snapshot frames in 681.
- **Concatenating the increments for each message id reproduces the stored `message.list`
  text exactly for 12 of 12 messages**, reasoning included.
- So for the App Server delta source, **append-always is correct**, and the host's
  `CumulativeStreamText` is what turns increments into the snapshots seen downstream.
  Snapshot text into append-always would stack copies, but this source does not produce it.
- Not covered: other producers that may emit cumulative text (jdcoj's
  `StreamTextFrameSource.CumulativeSnapshot`, e.g. external-transport frames). These
  captures cover the App Server `stream_delta` path only.

## Gaps

- **No Anthropic reasoning.** Claude via the `lmstudio` and `anthropic` routes, and via
  OpenRouter, returned no thinking. Reasoning comes from the 6 OpenRouter models above.
  The original thinking routes (qwen3.8-max, KAT-Coder) were out of quota and silently
  fell back to MiniMax-M3.
- **First-call prompt is about 29k tokens** whatever the system prompt, because about 30
  tool schemas are advertised (see letta-mobile-y44ux and letta-mobile-7rigo).
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
