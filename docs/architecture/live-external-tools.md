# Live external tools and re-advertisement

Bead `letta-mobile-s416w.27`. Plan: `docs/design/canvas-plugin-platform-plan.md` section 6.3, decision R2.

## What changed

`ExternalToolRegistry` used to be immutable: its tools were fixed when the host built it, and they
reached the App Server in the `external_tools` field of every `runtime_start`. Hot-loaded plugins
need tools that come and go while the host runs.

- **`ToolSource`** (`sharedLogic/.../controller/extras/ToolSource.kt`) is a set of tools that can
  change: an `id` and a `StateFlow<List<ExternalTool>>`. `ToolSource.static(id, tools)` is a fixed
  set, and `MutableToolSource` is one its owner republishes. A plugin host will add one source per
  plugin.
- **The registry** keeps its fixed tools as an immutable fast path. The host's canvas tools are part
  of that static set, and dispatch and advertisement for them work as before. Live sources are added
  and removed with `addSource` / `removeSource`. The merged set is computed on each read
  (`DynamicToolSet`, lock-free copy-on-write). Names stay unique: a source cannot shadow a fixed
  tool, and the first source to claim a name keeps it. `toolsChanged` emits the new advertised set
  each time it really changes. It never emits for a registry that only has fixed tools.
- **Re-advertisement**: `DefaultAppServerController` owns an `ExternalToolReadvertiser`. On
  `toolsChanged`, it waits for a 500 ms debounce and then re-issues `runtime_start` for every
  runtime in its cache, which holds both controller-started and engine-started runtimes. The frame
  carries the scope, the client info and the whole current tool set, with `recover_approvals` and
  `force_device_status` off and no `mode` or `cwd`. `toolAdvertisementState`
  (`ToolAdvertisementState(pending)`) is true from the change until every runtime has been sent the
  new set.
- **Vanished tools**: an agent's in-flight turn still lists a tool whose source was just removed
  (see below). A call to it gets the matched `is_error` result `tool '<name>' is no longer
  available`. It never hangs, and the dispatcher's result cache is unaffected: a replay of an
  answered request still reuses its cached result.
- `reRegisterAll` stays a no-op. Re-advertisement rides on `runtime_start`, as it does on reconnect.

## Spike: does the App Server accept re-advertisement? (R2)

**Yes.** A repeated `runtime_start` for a live runtime replaces its external tools, and the agent
sees the change from its next turn. The fallback ("tools change at the next runtime start") was not
needed.

### Source

The source below is letta-code 0.29.12 `letta.js`, the pinned desktop runtime.

- `handleRuntimeStartCommand` calls
  `registerRuntimeExternalTools(runtime, connectionId, runtimeScope, parsed.external_tools ?? [])`.
  That function unregisters the tools previously registered under the key (connection, runtime) and
  then registers the new list. A runtime-start for an existing runtime is accepted: it resolves the
  same agent and conversation and succeeds.
- `prepareListenerTurn` calls `prepareToolExecutionContextForScope`, which calls
  `captureToolExecutionContext`. That copies the global external-tool registry
  (`Symbol.for('@letta/externalTools')`) into a new `Map` **once per turn**, so the model's tool
  list for a turn is a snapshot.
- If the field is omitted, `parsed.external_tools ?? []` applies, so **every tool of that runtime is
  unregistered**. Every `runtime_start` (initial, reconnect or re-advertisement) must therefore carry
  the full current set.
- `applyRuntimeStartState` changes `mode` and `cwd` only when the fields are present. It clears
  `skill_sources` when that field is absent. The controller never sends `skill_sources`, so the
  re-issue keeps them as they were.

### Live run

Setup:

- letta-code 0.29.12: `node letta.js app-server --listen ws://127.0.0.1:4599`, node v24.13.1, with a
  throwaway local backend (`LETTA_LOCAL_BACKEND_EXPERIMENTAL=1`).
- The `lmstudio` provider points at a fake OpenAI-compatible endpoint, which logs the tool names of
  every model request and calls `spike_tool_b` whenever it is offered.
- A `-r` preload logs the contents of the server's external-tool registry.

All runs used one connection, one agent and one conversation, and every `runtime_start` returned
`success: true`.

| Step | Registry after | Model requests saw |
|---|---|---|
| RS1 `[a]` (create) then turn 1 | `[a]` | `[a]` |
| RS2 `[a,b]`, idle, then turn 2 | `[a,b]` | `[a,b]`, `[a,b]`: b was called, and our `external_tool_call_response` was accepted |
| RS3 `[a]`, then turn 3 | `[a]` | `[a]` |
| RS4 `[a,b]`, turn 4; **RS5 `[a]` sent while the b call was pending**, then answered | `[a]` | `[a,b]`, `[a,b]`: the pending call resolved, and the same turn's next step still had b |
| turn 5 | `[a]` | `[a]` |
| RS6 with `external_tools` omitted | `[]` | n/a |

Conclusions:

1. Re-advertising mid-session works.
2. A change reaches the agent at its next turn boundary. A turn in flight keeps its snapshot, so
   the dispatcher's "no longer available" answer is required.
3. Re-issue the whole set, never a delta, and never omit the field by accident.

### Reproducing

The harness is in `android-compose/appserver-cli/spikes/live-external-tools/`
(`registry-preload.cjs`, `fake-llm.cjs`, `client.mjs`). It needs no model provider or API key.

```powershell
$S = "<scratch dir>"; $LC = "<letta-code install>\node_modules"
$env:SPIKE_LLM_LOG = "$S\llm.log"; $env:SPIKE_REGISTRY_LOG = "$S\registry.log"
$env:HOME = "$S\home"; $env:USERPROFILE = "$S\home"
$env:LETTA_LOCAL_BACKEND_EXPERIMENTAL = "1"; $env:LETTA_LOCAL_BACKEND_DIR = "$S\backend"
node "$LC\@letta-ai\letta-code\letta.js" --backend local connect lmstudio --base-url http://127.0.0.1:4610/v1
node fake-llm.cjs                     # terminal 1
node -r .\registry-preload.cjs "$LC\@letta-ai\letta-code\letta.js" app-server --listen ws://127.0.0.1:4599   # terminal 2
$env:SPIKE_NODE_MODULES = $LC; node client.mjs ws://127.0.0.1:4599/ws
```

Re-run it when the pinned letta-code version changes. The source of letta-code 0.32.14 has the same
`registerRuntimeExternalTools` (replace per connection and runtime, `?? []` on an omitted field),
but only 0.29.12 was run live.
