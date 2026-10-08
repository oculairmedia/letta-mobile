# On-demand agent tools via the Meridian CLI

Status: design, not implemented. Epic: `letta-mobile-jna0o`. Supersedes the plan-only decision bead
`letta-mobile-lg3vt`. Measured on `origin/main` at `c050a1da7` (2026-10-07).

## Problem

The client and the Iroh host register their custom tools through `runtime_start.external_tools`.
letta-code turns that registry into `client_tools` on **every** `conversations/messages` request
(`getExternalToolsAsClientTools()` feeds `buildRequestBodyFromPreparedMessages`). The Letta server
puts them in the model's tool list for every LLM step of the turn: the initial call, and again after
each tool return. The live-tools spike (`docs/architecture/live-external-tools.md`) logged the full
tool list on every model request, step after step.

The canvas tools are most of that payload, and nearly all of it is reference text: the scene format,
the op grammar and the compose schema. An agent needs that text only on the turns where it draws.

## 1. What is injected today

| Where it runs | Tools advertised | Wiring |
|---|---|---|
| Iroh host (`meridian-iroh-wrapper app-server-serve-iroh`, production) | the 8 `canvas_*` tools in `CanvasToolContract.all`, plus `canvas_render_preview` when a renderer is attached (`withPreview`) | `HostCanvasTools.all(...)` → `buildProductionExternalToolRegistryForTesting(hostTools=…)` (`AppServerServeIrohCommand.kt`) |
| Iroh host, when `--meridian-binary` / `LETTA_MERIDIAN_BINARY` is set | `agent_message_send`, plus `agent_discover` when `--local-backend-dir` is set | `CustomIrohMessagingTool`, `AgentDiscoveryTool` |
| Desktop, direct (non-Iroh) App Server | the same 8 `canvas_*` tools | `CanvasExternalTools.all(...)` (`DesktopAppServerChatGatewayBuilder.desktopCanvasToolRegistry`). Nothing in Iroh mode, where the host serves canvas. |
| Android app | `device_action` | `AppModule.provideAndroidExternalToolRegistry` |
| Plugins (LCP) | none yet. Every agent-visible plugin action would become its own tool (`PluginActionTools`, `<idShort>_<action>`). | Mapping only. Host wiring is `letta-mobile-s416w.29` (open). |
| `ImageHydration`/`Goals`/`Schedules`/… stubs | none (gated off by `RemoteCapabilities.FACTORY_DEFAULT`) | `ExternalToolRegistry.standard` |

**The client injects no system-prompt or memory-block text.** I grepped `sharedLogic/commonMain`,
`iroh-wrapper-cli`, `desktop` and `app` for system-prompt, reminder and `client_skills` writes and
found none. The `<system-reminder>` handling in `TimelineEventToUiMessage` only strips the
reminders Lettabot adds. So everything the client adds to the context is the tool definitions.

## 2. Measured cost

Method: a throwaway Java harness loaded the compiled `sharedLogic` JVM classes (sources identical
to `origin/main`). It serialized each definition in the exact wire shape letta-code forwards,
`{name, description, parameters}`, as compact JSON. I counted tokens with `tiktoken` o200k_base and
cross-checked with chars/3.5. No Anthropic key was available, so these are approximations. Claude's
tokenizer usually lands within about ±15% of o200k on JSON-heavy text.

| Tool | description chars | schema chars | wire chars | ~tokens (chars/3.5) | o200k tokens |
|---|---:|---:|---:|---:|---:|
| `canvas_compose` | 671 | 6,789 | 7,516 | 2,147 | **2,018** |
| `canvas_apply_ops` | 5,850 | 694 | 6,816 | 1,947 | **1,792** |
| `canvas_replace_scene` | 3,551 | 709 | 4,526 | 1,293 | **1,209** |
| `canvas_get_layout` | 794 | 486 | 1,345 | 384 | 326 |
| `canvas_get_scene` | 285 | 267 | 610 | 174 | 129 |
| `canvas_list` | 250 | 97 | 400 | 114 | 82 |
| `canvas_create` | 170 | 123 | 348 | 99 | 70 |
| `canvas_compose_guide` | 192 | 62 | 316 | 90 | 69 |
| **canvas, as offered (8 tools)** | | | **21,877** | **6,251** | **5,695** |
| `canvas_render_preview` (when a renderer is attached) | 404 | 763 | 1,230 | 351 | 264 |
| `agent_message_send` | 280 | 318 | 658 | 188 | 134 |
| `agent_discover` | 137 | 194 | 387 | 111 | 92 |
| `device_action` (Android) | | | 355 | 101 | 70 |
| **Iroh host total, all advertised** | | | **24,152** | **6,901** | **6,185** |

The cost is mostly reference text. `canvas_apply_ops` and `canvas_replace_scene` end in the same
3,200 chars (943 tokens): `CanvasSceneSchema.description` plus the dry-run hint, so it is sent
twice. The
compose schema is 90% of `canvas_compose`. Content that is already served on demand:
`canvas_compose_guide` returns 7,991 chars (2,198 o200k tokens) only when called.

**Multiplier.** The cost applies per LLM request, not per turn:

- `tool_tokens × steps_per_turn`. A canvas turn with 4 tool calls is 5 requests, so about
  28k–31k input tokens go to canvas schemas. That holds even on turns that never touch the canvas.
- **Caching softens the price, not the footprint.** Tools sit at the head of the prompt (tools →
  system → messages), so a provider with prompt caching can serve them as cached reads. Three
  things still hold:
  1. They occupy context-window room on every step, which brings compaction earlier
     (`AppServerContextWindowPreflight` sees the total).
  2. Any re-advertisement changes the tool list and invalidates the entire cached prefix.
     Plugins make re-advertisement routine (`live-external-tools.md`).
  3. OpenAI-compatible routes through llmux may not cache at all.

  I could not see from here whether the Letta server sets cache breakpoints for these models;
  that is open question Q1.

## 3. What "the Meridian CLI" is

There is an existing CLI, but it has no canvas commands.

| Artifact | What it is | Agent-reachable today? |
|---|---|---|
| `android-compose/cli` (`LettaMobileCli`, `CliktCommand(name = "meridian")`) | Developer JVM CLI: `rest`, `agents`, `conversations`, `tools`, `blocks`, `profile`, probes, `agent-message send`, `peer …`, `app-server-serve-iroh`. Android-library module, run via `./gradlew :cli:run`. | No. It has no installable distribution. |
| `android-compose/iroh-wrapper-cli` → `bin/meridian-iroh-wrapper` | The packaged production host (systemd `meridian-iroh-wrapper.service`): `app-server-serve-iroh`, `pair`, `agent-message send`. Kept deliberately narrow. | Only indirectly: the host shells out to it for `agent_message_send` via `--meridian-binary`. |
| `appserver-cli` → `bin/meridian-app-server` | App Server launcher/probe tooling | No |

**Topology that makes the CLI viable.** On the production box,
`meridian-appserver.service` (letta-code, `ws://127.0.0.1:4500`) and
`meridian-iroh-wrapper.service` run side by side. The wrapper owns the canvas relay, the op log and
`HostCanvasTools`. The agent's Bash tool runs inside the App Server's process tree on the same
host, and letta-code exports `LETTA_AGENT_ID` and `LETTA_CONVERSATION_ID` into the shell env
(`getShellEnv`). A local IPC hop from the agent's shell to the wrapper is all that is missing.
There is no such endpoint today: the wrapper listens only on Iroh, and on 127.0.0.1:3099 as a
Vibesync client.

**Prior art.**

- `device_action` already uses the meta-tool shape: a 70-token tool whose catalog is fetched with
  `device.catalog`.
- letta-code ships an **on-demand skills** mechanism: `~/.letta/skills`, `.agents/skills`, and the
  agent memory `skills/` dir. Each skill costs one line in the system prompt's
  `<available_skills>` block (`compileAvailableSkillsBlock`, first description line only), and its
  body is loaded only when used.
- The host already deploys a skills root (`--skills-dir /opt/skills`).

## 4. Design

### Shape: one command surface, two front doors

```
agent Bash ── meridian canvas compose ──► meridian shim ─UDS─┐
                                                              ├─► MeridianCommandRouter (sharedLogic)
agent tool ── meridian {command, input} (fallback arm) ───────┘        │
                                                                        └─► ExternalToolRegistry.invoke(…)
                                                                            (HostCanvasTools / CanvasExternalTools, unchanged)
```

- **`MeridianCommandRouter`** (`sharedLogic/commonMain`): maps `canvas.<verb>` onto the existing
  tool implementations through `ExternalToolRegistry.invoke(name, input, caller)`. It adds no
  canvas logic of its own: same validator, same publisher, same ACL, same result JSON.
- **Help and schemas are generated from `CanvasToolContract`.** `--help` shows a tool's description
  and `schema` its `inputSchema`, so help never drifts from what the tools accept. The host serves
  them, so the shim never skews against the host version.
- **Front door A, the CLI (primary).** A tiny `meridian` shim on the App Server's `PATH`. It sends
  argv, stdin and the caller env to the wrapper over a Unix socket and prints the JSON result. It
  can be POSIX `sh` + `curl --unix-socket`, or a Node script, since Node is already on the host.
  **Not** the JVM CLI: JVM start-up would add about 1 s to every call.
- **Front door B, the meta-tool (fallback arm).** A single external tool, `meridian`, with
  `{command, input}` (90 o200k tokens). It runs through the same router. It serves runtimes without
  Bash, and it is the A/B arm for the identity and approval concerns below.

### CLI surface

| Command | Maps to | Notes |
|---|---|---|
| `meridian --help` | — | Top-level tree, about 10 lines |
| `meridian canvas --help` | — | Verbs with one-line descriptions |
| `meridian canvas compose [--dry-run] [--canvas ID] < input.json` | `canvas_compose` | JSON on stdin (`<<'JSON'` heredoc), never argv. This avoids the shell-quoting collapse fixed in bn008. |
| `meridian canvas guide compose` | `canvas_compose_guide` | 2.2k tokens, on demand |
| `meridian canvas layout [--cursor C] [--limit N]` | `canvas_get_layout` | |
| `meridian canvas scene` | `canvas_get_scene` | |
| `meridian canvas apply-ops [--dry-run] < ops.json` | `canvas_apply_ops` | |
| `meridian canvas replace-scene [--dry-run] < scene.json` | `canvas_replace_scene` | |
| `meridian canvas list` / `create [--title T]` | `canvas_list` / `canvas_create` | |
| `meridian canvas preview …` | `canvas_render_preview` | Only when a renderer is attached |
| `meridian canvas schema <verb>` / `meridian canvas ops --help` | `inputSchema` / the scene and op grammar | The reference text that is in every request today |
| `meridian agents find …` / `meridian agent-message send …` | `agent_discover` / `agent_message_send` | `agent-message send` already exists |
| `meridian plugin <id> <action> < input.json` | plugin actions (`s416w.29`) | One command, not N tools |

Contract:

- stdout is the tool's result JSON, byte-identical to today's `ExternalToolResult.Success`.
- Errors go to stdout as `{"error": …}` with a non-zero exit code: 2 for refused input, 3 for
  denied, 4 when the host is unreachable.
- stderr carries only human hints.

### The system-prompt pointer

Ship it as a letta-code skill, `meridian-canvas/SKILL.md`, in the App Server user's
`~/.letta/skills`. The skill body holds the verb table and the compose and ops quick reference.

| Pointer variant | Text | o200k tokens |
|---|---|---:|
| **Skill line (recommended)** | ``- `meridian-canvas`: Read and change this conversation's canvas (notes, checklists, cards, diagrams) with the `meridian canvas` CLI.`` | **33** |
| Plain system-prompt sentence (if skills are off) | "Canvas: this conversation has a shared canvas the user sees in the app. Use the `meridian canvas` CLI from your shell to read and change it (compose notes/checklists/cards, get layout, apply ops). Run `meridian canvas --help` before first use; every command prints JSON." | 61 |
| Meta-tool `meridian` (arm B) | tool definition | 90 |

### Results, receipts and timeline rendering

Today `CanvasArtifactReceipts.isComposeTool` keys on the tool name `canvas_compose` and reads the
request args and return JSON. A CLI call arrives as `Bash {command: "meridian canvas compose <<'JSON' …"}`
with its stdout as the return.

- Add one recognizer in sharedLogic, `MeridianCommandCall.parse(toolCall)`. It parses Bash calls
  whose command begins with `meridian canvas <verb>` and the meta-tool's `{command}` into
  `(verb, inputJson?)`, taking the input from the heredoc body.
- `CanvasArtifactReceipts` and `ChatTimelineProjector` use it to treat such a call as the matching
  canvas tool. The compose receipt card and "Show on canvas" then work unchanged, because stdout
  is the same receipt JSON.
- When the heredoc cannot be parsed, the receipt degrades to the existing `fromRequest` and
  degraded paths, never to a broken card.
- Other verbs render as canvas tool rows ("Read canvas layout", "Applied 3 ops") instead of a
  generic "Ran 1 command".
- No wire or App Server change is needed. The projection is shared by Android and Desktop.

### Transport and authentication

- **Endpoint.** The wrapper serves `meridian/tools/1` on a Unix domain socket at
  `/run/meridian/tools.sock` (systemd `RuntimeDirectory=meridian`, mode `0660`, group shared with
  the App Server user).
  - Use `/run`, not `/tmp`: the wrapper unit has `PrivateTmp=true`.
  - Fallback: loopback TCP with a bearer token file readable only by the App Server user.
- **Caller identity.** This is the one real regression against external tools. Today the App Server
  stamps the runtime scope on the call, and the model cannot choose it. In Bash,
  `LETTA_AGENT_ID` and `LETTA_CONVERSATION_ID` are env vars the agent can override.
  - Phase-1 mitigation: bind each CLI request to a live call. The wrapper relays the runtime's
    stream, so it can require an in-flight `Bash` tool call in that conversation whose command
    contains `meridian`. A forged scope then has to match another agent's call that is running at
    that moment.
  - Spike: whether the wrapper sees tool-call frames for every runtime, including runtimes the app
    started (Q2).
  - Honest baseline: on this host the agent's Bash already runs as the user that owns
    `~/.letta/canvas-relay`, so canvas ACLs are not a hard boundary against a hostile agent today.
    The CLI keeps parity. It does not create a new hole.
- **Capability boundary.** The socket exposes only the router's commands: canvas, agents, plugins.
  No REST passthrough, no admin and no profile commands from the developer CLI.
  - Inputs and results are size-capped as today (compose caps; letta-code's tool-return clamp).
  - Calls are rate-limited per conversation.

### Approvals

Under approve-all, Bash calls run without a prompt (PR #1805 keeps the approval card for requests
that need the user). Under stricter modes, every `meridian` call would raise a `can_use_tool`
prompt, which external tools never do. Mitigations:

- Ship a letta-code permission allow rule (`permissions.allow`, `Bash(meridian canvas:*)` form).
  The exact prefix semantics need a spike.
- Teach `UnleasedApprovalAnswerer` the same allow-list.
- The A/B tracks approval prompts per canvas task as a guard metric.

### Failure modes

| Failure | Effect | Mitigation |
|---|---|---|
| Agent does not know the CLI exists | Never draws | Skill line every turn. Arm B. A/B guard metric: canvas-task success rate. |
| Extra discovery round trip | `--help` (~250 tok) and `guide compose` (2.2k) on first use per conversation; they stay in history until compaction | Paid once per conversation instead of on every step. The skill body carries the compose quick reference, so `guide` is optional. |
| Process spawn latency | ~50–150 ms per call (shim + UDS) | Shim, not JVM. One call per action, as today. |
| Shell quoting mangles JSON | Refused input | stdin heredoc only. `--input-file`. Structured error with a JSON pointer. |
| Host unreachable or wrapper restarting | Exit 4 `{"error":"host_unavailable"}` | Retry hint in stderr. Same outage as today's tools. |
| Shim and host version skew | Wrong flags | Help and schemas come from the host. The shim is version-free. |
| Runtime without Bash or the wrapper (desktop direct mode, Android embedded) | No CLI | Keep native tools there behind the flag. Later, an in-process loopback endpoint (same pattern as `s416w.32`). |
| Approval prompts under strict modes | Card per call | Allow rule. Answerer allow-list. Arm B. |

## 5. Savings estimate

| Arm | Per-request tool and pointer tokens (o200k) | Saving per LLM request | Per 5-step canvas turn |
|---|---:|---:|---:|
| Native (control, Iroh host incl. a2a) | 5,921 (canvas 5,695 + a2a 226) | — | 29.6k |
| **CLI + skill** | ~33 | **~5,890 (99.4%)** | ~0.2k, plus ~0.25–2.4k once per conversation on first use |
| Meta-tool `meridian` | ~90 | ~5,830 (98.5%) | ~0.45k, plus the same one-off |

At an illustrative 30 turns a day × 3 steps per agent, that is about **530k input tokens per agent
per day** no longer sent, whether cached or not. Each step also gets about 5.9k tokens of context
window back.

## 6. Alternatives

| Option | Verdict |
|---|---|
| Trim the schemas in place: dedupe `CanvasSceneSchema.description` across apply_ops and replace_scene, move the compose schema text behind the guide | Cheap first win: the dedupe alone saves ~0.9k tokens, and trimming the compose schema's field descriptions could plausibly bring it to ~2k. Recommended as phase 0 whatever else is chosen. Still O(tools) per request. |
| Letta tool rules or per-turn tool selection | Letta tool rules constrain call order; they do not defer schema loading. A per-turn router guessing which tools a turn needs is fragile. |
| Provider tool search / deferred tools (letta-code `supportsToolSearch` for some OpenAI Responses models) | Not portable across our providers and not exposed for client tools. |
| MCP with lazy loading | Same on-demand idea, but adds an MCP server the agent harness must host. Our tools already live behind the wrapper, so this would be a second dispatch path. |
| Single meta-tool `meridian` | Strong. It keeps App Server-stamped identity, needs no approval prompts and no Bash, and makes UI recognition trivial. About 57 tokens more than the skill line. Kept as arm B and as the fallback. |

## 7. Migration plan

Bead IDs are listed under the epic `letta-mobile-jna0o`.

| # | Phase | Depends on |
|---|---|---|
| 0 | `letta-mobile-jna0o.1` Token-budget harness: CI test printing and asserting the advertised tool budget; capture `usage_statistics` prompt tokens per step | — |
| 1 | `letta-mobile-jna0o.2` Trim in place: dedupe the scene-format text, shorten descriptions | 0 |
| 2 | `letta-mobile-jna0o.3` `MeridianCommandRouter` + help/schema tree generated from `CanvasToolContract` (sharedLogic) | — |
| 3 | `letta-mobile-jna0o.4` Wrapper local endpoint `meridian/tools/1` (UDS + auth + live-call binding spike) | 2 |
| 4 | `letta-mobile-jna0o.5` `meridian` agent shim + `meridian-canvas` skill + deploy (PATH, `~/.letta/skills`, unit `RuntimeDirectory`) | 3 |
| 5 | `letta-mobile-jna0o.6` Timeline: recognise `meridian canvas …` Bash and meta-tool calls as canvas rows and compose receipts | 2 |
| 6 | `letta-mobile-jna0o.7` Approvals: allow rule + `UnleasedApprovalAnswerer` allow-list; verify with no cards (post #1805) | 4 |
| 7 | `letta-mobile-jna0o.8` Meta-tool arm `meridian` on the router | 2 |
| 8 | `letta-mobile-jna0o.9` Feature flag `--agent-tools-mode=native\|cli\|meta` (per host, overridable per agent) + A/B: prompt tokens per step, canvas-task success, steps per task, approval prompts | 0, 4, 5, 6, 7 |
| 9 | `letta-mobile-jna0o.10` Move a2a tools and plugin actions (`s416w.29`) onto the command surface | 2, 8 |
| 10 | `letta-mobile-jna0o.11` Desktop-direct and Android embedded runtimes: in-process loopback endpoint; retire `CanvasExternalTools` advertisement behind the flag | 8 |
| 11 | `letta-mobile-jna0o.12` Flip the default and stop advertising the native canvas tools once the A/B holds; update docs | 8, 9 |

## Open questions

1. **Caching:** does the Letta server set Anthropic cache breakpoints on `client_tools` for our
   models? This decides whether today's cost is mostly price or mostly context-window room.
2. **Identity:** does the wrapper see tool-call frames for every runtime it hosts (live-call
   binding)? If not, is env-scoped soft attribution acceptable on a single-tenant host?
3. **Arm choice:** is a 57-token meta-tool acceptable as the default where Bash approvals or
   identity matter, with the CLI as the scripting surface? Or must it be CLI-only?
4. **On-device runtimes:** should Android embedded and desktop direct runtimes keep native canvas
   tools, since they lack the wrapper?
