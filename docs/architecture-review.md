# letta-mobile Architecture Review

Updated 2026-05-25 after the SessionGraph/session-scoped repository work,
external transport naming, the first timeline gateway slice, and the
ConversationStateHolder shadow fold.

## Executive Read

The current architecture is no longer best described as "Client Mode plus the
normal Letta API." The active shape is:

```text
UI / feature modules
  -> session-scoped repository interfaces
  -> SessionManager.currentGraph
  -> per-session repositories and transports

chat send / stream transports
  -> TimelineExternalTransportWriter or MessageApi
  -> TimelineRepository
  -> TimelineSyncLoop
  -> TimelineGatewayEvent queue and reducer paths
  -> Timeline StateFlow consumed by UI
```

That is a better foundation than the previous state. Backend switches now have
one process-level owner, chat has a narrower external transport surface, and
the timeline has started moving toward a serialized event gateway. The main
remaining risk is that the next multiplatform/local-runtime work could bypass
these new boundaries and accidentally create a second runtime architecture.

The Kotlin multiplatform and Koog direction should build on this shape:

- `SessionGraph` is the current runtime boundary.
- `TimelineGatewayEvent` is the in-memory ancestor of the proposed durable
  `RuntimeEvent` stream.
- `ConversationStateHolder` is the first Flow projection candidate for making
  timeline state a pure fold.
- `TimelineExternalTransportWriter` is the current adapter seam for WS-style
  external transports.
- A future `LocalLettaBackend` should plug into the same session and timeline
  path, not write directly around it.
- KMP migration should follow the new Kotlin default: pure shared library
  modules plus separate runnable app modules.

## Current Module Shape

The included Gradle modules are now the practical module map:

```text
android-compose/
  app/                 application wiring, DI, navigation, platform services
  core/                API clients, repositories, session graph, timeline
  core/domain/         small domain module, still underused
  designsystem/        reusable Material 3 UI foundations
  feature-chat/        chat feature state and WS send coordinator
  feature-editagent/   edit-agent feature
  feature-mcp/         present on disk, not currently included
  cli/                 developer tooling
  macrobenchmark/
  baselineprofile/
  native/
```

There is still a `bot/` directory on disk, but it is not included in
`settings.gradle.kts`. Current chat transport work is centered on
`feature-chat`, `core/data/transport`, and `core/data/timeline`, not an active
`:bot` module.

## KMP Structure Constraint

Kotlin's current default project structure changes how we should describe the
multiplatform destination. The goal is not to turn the existing Android app
module into a giant KMP module. The goal is to extract shared code into one or
more pure KMP library modules, then keep runnable apps in separate platform app
modules.

Recommended target shape for this project:

```text
root
  sharedLogic/          KMP library: data contracts, RuntimeEvent, MemFS,
                        LettaBackend, serializers, validation, reducers
  sharedUI/             optional KMP Compose library if UI is shared
  app/
    androidApp/         Android application plugin, manifest, DI, services,
                        Android-only packaging, signing, versioning
    iosApp/             Xcode app consuming sharedLogic or sharedUI
    desktopApp/         optional Compose/JVM desktop entry point
    webApp/             optional JS/Wasm entry point
  server/               optional future server runtime
  core/                 optional root shared client/server contracts if a
                        server module exists
```

Naming can vary, but responsibilities should not. Shared modules are libraries.
App modules are runnable packages.

For letta-mobile specifically:

- `android-compose/app` is already the Android runnable app boundary. It should
  stay Android-specific and eventually become the analog of `androidApp`, not
  the shared KMP substrate.
- `core` currently mixes repository implementations, API clients, timeline
  code, and a small `core/domain` module. The KMP extraction should split
  portable contracts and reducers out of `core` instead of making all of `core`
  multiplatform at once.
- `feature-chat` and `designsystem` should only move toward `sharedUI` if we
  commit to Compose Multiplatform UI sharing. If iOS uses SwiftUI or another
  native UI, it should depend on `sharedLogic` and avoid Compose dependencies.
- If a future server/runtime target appears, introduce a root `core`-style
  shared contract module for code that is used by both server and clients. Do
  not overload the Android-era `core` module name without a migration plan.

AGP 9 makes this more than style. KMP modules that target Android should use
the Android-KMP library plugin, and the Android application entry point must
live in a separate Android application module. That aligns with the structure
above and argues against any migration plan that keeps Android packaging inside
the shared KMP library.

## What Is Working

1. **Session ownership is explicit**

   `SessionManager` owns `currentGraph` and rebuilds the graph when the active
   config changes. Session-scoped repositories observe or delegate through that
   graph, so most app-facing repository interfaces no longer bind directly to
   process-wide remote repository instances.

   This is the right direction for future local/remote backend selection. It
   gives the app one place to decide what "the current backend runtime" means.

2. **Repository DI now points at session-scoped facades**

   `AppModule` binds most `I*Repository` interfaces to `SessionScoped*`
   implementations. This gives existing screens a stable interface while the
   backing graph can be replaced underneath them.

   This is exactly the kind of boundary a Kotlin multiplatform substrate needs:
   callers should depend on capabilities and repositories, not on whether the
   backing runtime is remote Letta, local Kotlin/Koog, or a future compatible
   runtime.

3. **TimelineRepository remains the chat source of truth**

   `TimelineRepository` still owns the process registry of `TimelineSyncLoop`
   instances and exposes `StateFlow<Timeline>` per conversation. It also clears
   loops on session changes. That prevents stale loop reuse after backend
   switches and keeps chat state centralized.

4. **External transport is now named as transport, not product mode**

   `TimelineExternalTransportWriter` gives the WS/admin-shim path a narrow
   surface for optimistic local append, ingest, mark sent/failed, reconcile,
   and turn completion. This is cleaner than carrying "Client Mode" into core
   timeline vocabulary.

5. **TimelineSyncLoop has the first linearizing gateway**

   `TimelineSyncLoop` now has a `TimelineGatewayEvent` queue and a single
   `processEventQueue()` worker for incoming timeline mutations. That is the
   most important recent architectural improvement. It is the local, in-memory
   version of the RuntimeEvent/outbox pattern we want for multiplatform.

6. **ConversationStateHolder is the right projection direction**

   `ConversationStateHolder` folds `Flow<LettaMessage>` with `scan` and the
   pure `reduceStreamFrame` reducer. It is shadow-only today, but it proves the
   direction: timeline state should become a deterministic projection over an
   ordered event/frame stream.

## Current Risks

1. **Local runtime work could bypass SessionGraph**

   The KMP/Koog runtime should not be wired as a side path directly into UI or
   timeline internals. It should present as a backend/session implementation
   that satisfies the same app-facing contracts.

   Recommended framing:

   ```text
   RemoteLettaBackend -> SessionGraph -> existing remote repositories/transports
   LocalLettaBackend  -> SessionGraph -> local repositories/Koog/MemFS/outbox
   ```

   If `LocalLettaBackend` is not a session graph implementation, the app will
   regain the same cross-backend leakage class the current session work is
   trying to eliminate.

2. **Current table clearing conflicts with durable replay**

   `SessionGraphFactory.create()` currently clears agent and conversation DAO
   state on graph creation. That is a pragmatic fix for cross-backend cache
   contamination, but it is not compatible with a durable local runtime store
   unless the local store is separately scoped.

   Before adding a local RuntimeEvent outbox or MemFS metadata tables, decide
   whether persistence is keyed by:

   - active config id / backend id
   - session graph id
   - local runtime id
   - agent id plus backend/runtime id

   Durable local state must not live in tables that are cleared as a side effect
   of remote session rebuilds.

3. **The docs still contain stale Client Mode contracts**

   `docs/architecture/client-mode-timeline-rules.md` and
   `docs/architecture/chat-boundary-regression-harness.md` still describe
   `ClientModeTimelineStreamReducer`, `CLIENT_MODE_HARNESS`, and older
   Client Mode send paths. Production code has moved toward
   `TimelineExternalTransportWriter` and `WsChatSendCoordinator`.

   Those docs should be rewritten as external transport contracts before they
   are used as references for new KMP/runtime beads.

4. **Message source semantics were narrowed**

   Production `MessageSource` currently only has `LETTA_SERVER`, while some
   tests and docs still expect `CLIENT_MODE_HARNESS`. That may be intentional
   after external transport renaming, but the architecture needs an explicit
   replacement for "where did this event originate?"

   The clean solution is likely not to re-expand UI `MessageSource`. Put origin
   on the future RuntimeEvent envelope:

   ```text
   source = remote_sse | remote_rest_snapshot | external_ws | local_koog |
            local_memfs | sync_repair | import
   ```

   Then keep `Timeline` focused on renderable state.

5. **TimelineSyncLoop still has mixed mutation paths**

   The event queue serializes a meaningful slice, but comments note that locally
   initiated send streams remain direct for now. That is acceptable during the
   migration, but the local runtime must not add a third mutation path.

   The migration target should be:

   ```text
   all remote frames, local sends, WS/external frames, reconciliation snapshots,
   and future Koog turns enter one ordered gateway
   ```

6. **ConversationStateHolder is not authoritative yet**

   The holder is currently parity/shadow infrastructure. Any architecture doc
   that says "RuntimeEvent directly feeds UI" is premature. The realistic path
   is:

   ```text
   RuntimeEvent/outbox
     -> gateway event or reducer input
     -> ConversationStateHolder / Timeline projection
     -> Timeline StateFlow
     -> UI
   ```

## Revised RuntimeEvent Direction

The RuntimeEvent plan is still the correct foundation for Kotlin
multiplatform, but it should be revised in one important way:

```text
RuntimeEvent is not a new parallel abstraction.
RuntimeEvent is the durable, portable form of the timeline gateway.
```

Initial RuntimeEvent fixtures should be derived from current gateway cases:

- stream message frame
- local send append
- external transport local append
- reconcile-after-send snapshot
- recent-messages snapshot
- retry / mark sent / mark failed
- turn completion / external transport active-clear
- tool return before tool call
- approval response
- hydration seed / rebase event

The first useful proof is not Koog. The first useful proof is:

```text
record current gateway inputs as RuntimeEvent fixtures
replay them through the reducer/projection
assert the same final Timeline as the live path
```

Once that works, Koog is just another producer of ordered RuntimeEvents.

## KMP And Koog Placement

Koog belongs inside the local backend runtime, not in app UI, not in repository
facades, and not in the public Letta-shaped app contract.

Recommended layering:

```text
app/androidApp or other platform app
  -> IConversationRepository / TimelineRepository / backend capabilities
  -> SessionGraph
  -> LocalLettaBackend
  -> sharedLogic runtime services
       - Koog TurnEngine adapter
       - MemFS repository and prompt compiler
       - tool approval and execution gate
       - RuntimeEvent outbox
       - AgentFile import/export
```

The entity should not know whether the turn came from remote Letta or local
Koog. The observable contract is Letta-shaped events, messages, approvals,
tool calls, MemFS changes, and run state.

The practical extraction order should be conservative:

1. Move pure contracts and DTO-like models into `sharedLogic`.
2. Move reducers and RuntimeEvent fixtures into `sharedLogic`.
3. Add KMP storage abstractions for MemFS and RuntimeEvent without moving Room.
4. Add Android actual implementations from `androidApp`/Android-specific
   modules.
5. Add Koog-backed local runtime behind the same `SessionGraph` boundary.
6. Consider `sharedUI` only after runtime/data contracts are stable.

## MemFS Placement

MemFS should be the memory source of truth for the local runtime. Legacy memory
blocks should be compatibility projections, not the substrate.

RuntimeEvents should describe MemFS mutations and commits, but the full file
tree should not be encoded into every event. The likely split is:

```text
MemFS store
  - files
  - revisions
  - commits
  - metadata

RuntimeEvent outbox
  - MemFsCommitCreated
  - MemFsFileChanged
  - PromptContextInvalidated
  - AgentFileImported / AgentFileExported
```

That keeps replay small while preserving durable synchronization points.

## Recommended Next Work

1. **Rewrite stale Client Mode docs as external transport docs**

   Rename or supersede `client-mode-timeline-rules.md` with a contract centered
   on `TimelineExternalTransportWriter`, `WsChatSendCoordinator`, external WS
   turn lifecycle, and SSE suppression.

2. **Document SessionGraph as the backend runtime boundary**

   Add an ADR that states whether `LocalLettaBackend` will materialize through
   `SessionGraph`. The answer should be yes unless a concrete blocker appears.

3. **Add a KMP module-structure ADR**

   Record the target structure as shared library modules plus separate platform
   app modules. Decide whether the first extraction is `sharedLogic` only or a
   combined `shared` module. Given native Android UI and uncertain iOS UI,
   `sharedLogic` first is the safer choice.

4. **Define RuntimeEvent by mapping current gateway events first**

   Do not start with a greenfield event taxonomy. Start with the events the app
   already needs to serialize safely, then add MemFS/run/tool events.

5. **Decide durable scope before adding tables**

   The outbox cannot safely land until backend/session/local-runtime scoping is
   explicit. Current delete-on-session-create behavior makes this mandatory.

6. **Finish converging timeline mutations into the gateway**

   Local sends, external WS sends, SSE frames, REST snapshots, retries, and
   reconciliation should all become gateway inputs. This prepares the code for a
   durable outbox without changing UI behavior first.

7. **Promote ConversationStateHolder only after replay parity**

   Keep the holder shadowed until RuntimeEvent/gateway replay can prove byte- or
   state-equivalent timelines across live and replay paths.

8. **Add local runtime behind a feature flag**

   The first local runtime vertical slice should create/import a local agent,
   initialize MemFS, run one Koog-backed turn, append RuntimeEvents, project to
   the existing timeline, and survive app restart.

## External References

- JetBrains: [A New Default Project Structure for Kotlin Multiplatform](https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/)
- Kotlin documentation: [Recommended Kotlin Multiplatform project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)
- Android Developers: [Set up the Android Gradle Library Plugin for KMP](https://developer.android.com/kotlin/multiplatform/plugin)

## What To Stop Saying

These phrases are now stale or too imprecise for architecture docs:

- "Client Mode is the architecture"
- "bot module owns the active gateway"
- "RuntimeEvent feeds UI directly"
- "memory blocks are the memory substrate"
- "local runtime writes timeline events"

Prefer:

- "external transport"
- "session graph"
- "timeline gateway"
- "RuntimeEvent outbox"
- "MemFS source of truth"
- "timeline projection"

## Overall

The architecture has improved. The most important current shape is the
combination of `SessionManager`/`SessionGraph` for backend lifetime and
`TimelineSyncLoop`/`ConversationStateHolder` for ordered chat state.

The KMP/Koog plan should now be revised around those facts. Build the local
runtime as another session-backed Letta-shaped runtime, make RuntimeEvent the
durable form of the existing gateway, use MemFS as the memory substrate, and
extract shared code into pure KMP library modules consumed by separate platform
apps. Keep the UI consuming the same timeline projection until replay parity
proves the new substrate can take over.
