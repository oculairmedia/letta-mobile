# KMP Extraction Current State

Last updated: 2026-05-25 on branch `codex/kmp-shared-components`.

## Goal

The migration goal is Kotlin Multiplatform readiness first. The embedded
runtime and Koog integration remain useful later consumers of the shared
substrate, but they are not the primary migration driver right now.

The boundary we should keep optimizing for is:

```text
platform apps
  -> platform UI and storage adapters
  -> sharedLogic contracts, reducers, and runtime/event model
```

Koog stays behind `TurnEngine`. Until the shared substrate is broader, the
Android app should keep the placeholder `TurnEngine` behavior and avoid adding
Koog dependencies or Koog-specific concepts to UI, repositories, or settings.

## Landed

| Area | Current state |
|---|---|
| KMP module | `:sharedLogic` exists as a KMP library and is exposed through `:core` with `api(project(":sharedLogic"))`. |
| Shared identity types | `AgentId`, `ProjectId`, `ToolId`, and `BlockId` live in `sharedLogic/commonMain`. Room converters stay in Android `:core`. |
| Shared configuration value types | Backend selection (`LettaConfig`), backend labeling, theme preference enums, model configuration DTOs (`ModelSettings`, `LlmConfig`, `EmbeddingConfig`), and pure API/resource DTOs (`Agent`, `Block`, `Tool`, `Archive`, `Folder`, `Conversation`, project/work DTOs, MCP DTOs, model/provider/job/run/schedule/group/identity DTOs, message/tool-call DTOs, batch-message DTOs, passages, cron tasks, Vibesync events) live in `sharedLogic/commonMain`. |
| Runtime contracts | `BackendDescriptor`, `RuntimeEvent`, `RuntimeEventOutbox`, MemFS, AgentFile, tool/approval contracts, `TurnCommand`, and `TurnEngine` live in `sharedLogic/commonMain`. |
| Runtime reducer | `RuntimeEventProjector` and common tests cover replay, delivery status, tool return folding, approvals, MemFS commits, and AgentFile import/export projection. |
| Shared model tests | `sharedLogic/commonTest` covers domain ID serialization, backend labels, agent update wire keys, message content-part wire shape, and runtime projector/store behavior. |
| Shared repository contracts | Common-safe repository interfaces for agents, archives, blocks, conversations, cron schedules, identities, jobs, MCP servers, models, passages, projects, providers, runs, schedules, steps, tools, and Vibesync events live in `sharedLogic/commonMain`. Paging, upload/Ktor, settings-selection, inspector, and UI-message contracts remain Android-owned. |
| In-memory shared implementations | `InMemoryRuntimeEventOutbox`, `InMemoryMemFsStore`, and `LocalLettaBackend` are portable and covered by common tests. |
| Android persistence adapters | Android `:core` binds Room-backed `RuntimeEventOutbox` and `MemFsStore` implementations. |
| Local backend selection | `LettaConfig.Mode.LOCAL` is selectable in Android settings, health checks skip HTTP probing for it, and `SessionGraphFactory` can create a `LocalLettaBackend` for local configs. |
| Chat send boundary | Local-mode chat sends route through `LocalRuntimeChatSendCoordinator`, which projects `LocalLettaBackend` events into the existing timeline writer. |
| Beads federation | `.beads/config.yaml` has `federation.remote` for the shared Dolt remote. |

## Deliberately Stubbed

| Area | Decision |
|---|---|
| Koog | Do not implement now. `TurnEngine` remains the seam; Android currently provides a placeholder failed event so local mode fails visibly. |
| Local runtime feature completion | Do not prioritize model execution, tools, approvals, or attachment handling until KMP extraction is further along. |
| Shared UI | Do not create `sharedUI` yet. The current app is Android UI first; native future platforms can consume `sharedLogic` without Compose. |

## Not Complete

| Gap | Impact |
|---|---|
| Official KMP project shape | The repo is not yet split into `shared` or `sharedLogic` plus one app module per platform. Android still owns most application code. |
| Native targets | `:sharedLogic` currently validates JVM/Android paths. iOS/native targets are not yet part of the validated build. |
| Letta DTO extraction | Remaining model files in Android `:core` are Android/UI/JVM helpers: Room converters, UI message models, and app-message timeline projection model. Transport frames still live in Android `:core`. |
| Timeline extraction | The timeline model and reducers still live in Android `:core`; moving them needs a common replacement for JVM-only time/UUID usage. |
| Repository contracts | Repository contracts are partially extracted. Remaining Android-owned contracts depend on Paging, Ktor upload/channel types, UI timeline models, or Android settings-selection helpers. |
| App modules | No `iosApp`, `desktopApp`, or `webApp` module exists. This is expected until shared logic has enough value for another platform. |

## Extraction Pivot

The next work should favor pure, low-risk extractions over local runtime
features:

| Order | Extraction | Rationale |
|---|---|---|
| 1 | Backend/app configuration and user preference value types | Small, pure Kotlin, used by app/session setup, and safe to share across future platform apps. |
| 2 | Letta API DTO clusters with no Android or Compose dependency | Enables common clients/adapters and reduces Android `:core` ownership. |
| 3 | Message and timeline contracts | Required before a non-Android UI can render the same conversations. Needs careful time/ID portability. |
| 4 | Shared reducers and projection fixtures | Lets Android and future platforms prove identical timeline/runtime behavior. |
| 5 | Native target declaration and validation | Add iOS/native targets once common APIs are clean enough to compile without Android/JVM assumptions. |

## Guardrails

- Keep Android-specific persistence in `:core`; expose common storage contracts
  from `:sharedLogic`.
- Keep Compose annotations out of `sharedLogic` unless a specific shared UI
  module is introduced.
- Keep Koog-specific types out of shared contracts. Shared contracts should be
  Letta-shaped and runtime-agnostic.
- Prefer moving entire DTO clusters together when they reference each other.
  Avoid half-moving one model if it forces Android-only imports into common
  code.
- Validate each extraction with `:sharedLogic:jvmTest`,
  `:sharedLogic:compileDebugKotlinAndroid`, and the smallest affected Android
  compile/test gate.
