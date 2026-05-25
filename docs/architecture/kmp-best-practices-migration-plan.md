# KMP Best-Practices Migration Plan

Status: draft architecture plan
Created: 2026-05-25
Tracking bead: `letta-mobile-45eyc`

## Purpose

This document outlines the required changes to align letta-mobile with current
Kotlin Multiplatform project-structure guidance while preserving the current
Android app behavior.

The key shift is structural:

```text
Do not make the runnable Android app module the shared KMP module.
Do extract portable code into pure KMP library modules.
Do keep each runnable platform app in its own app module.
```

The first migration target should be shared runtime and data logic, not shared
UI. The local-agent substrate, MemFS, RuntimeEvent outbox, and Letta-shaped
backend contracts should move before any attempt to share Compose UI across
platforms.

## Source Guidance

Primary references:

- JetBrains: [A New Default Project Structure for Kotlin Multiplatform](https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/)
- Kotlin docs: [Recommended Kotlin Multiplatform project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)
- Android docs: [Set up the Android Gradle Library Plugin for KMP](https://developer.android.com/kotlin/multiplatform/plugin)
- Kotlin docs: [Compose Multiplatform resources](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources.html)
- Kotlin docs: [Compose Multiplatform resources setup](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-setup.html)
- Android docs: [Stability in Compose](https://developer.android.com/develop/ui/compose/performance/stability)
- Android docs: [Fix stability issues in Compose](https://developer.android.com/develop/ui/compose/performance/stability/fix)
- Android docs: [Strong skipping mode](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping)
- Android docs: [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)
- Kotlin docs: [Compose compiler options DSL](https://kotlinlang.org/docs/compose-compiler-options.html)
- JetBrains: [Cash App KMM case study](https://blog.jetbrains.com/kotlin/2021/03/cash-app-case-study/)
- Cash App: [SQLDelight](https://cashapp.github.io/sqldelight/)
- Cash App: [Molecule](https://github.com/cashapp/molecule)
- Cash App: [Zipline](https://github.com/cashapp/zipline)
- Cash App: [Redwood](https://github.com/cashapp/redwood)
- Kotlin docs: [Kotlin/Native as an Apple framework](https://kotlinlang.org/docs/apple-framework.html)
- Kotlin docs: [Interoperability with Swift/Objective-C](https://kotlinlang.org/docs/native-objc-interop.html)
- Kotlin docs: [C, Objective-C, and Swift library import](https://kotlinlang.org/docs/native-lib-import-stability.html)
- Touchlab: [SKIE](https://skie.touchlab.co/)

The relevant points for this repo:

- Shared code should live in KMP library modules.
- Runnable Android, iOS, desktop, and web apps should live in separate app
  modules that depend on shared libraries.
- If every platform uses shared Compose UI, a single `shared` module can contain
  both logic and UI.
- If any platform uses native UI, split into `sharedLogic` and `sharedUI` so
  native UI apps can depend only on non-Compose logic.
- KMP modules with Android targets should use the Android KMP library plugin
  rather than turning an Android application module into a KMP module.
- Shared Compose resources should live under `composeResources` in the shared
  source set that owns them.
- Compose stability must be treated as a module-boundary concern. Moving logic
  into KMP modules can make UI models look unstable unless the migration
  deliberately preserves stability, immutability, and compiler report coverage.
- Cash App's KMP adoption is the closest strategic reference for this project:
  share business logic and persistence first, preserve native platform
  workflows, roll out behind feature flags, and avoid forcing UI sharing before
  the runtime boundary is proven.
- Cash App's runtime libraries show useful patterns but should not be copied
  wholesale. SQLDelight is a strong persistence precedent, Molecule is a strong
  presenter/state precedent, Zipline is a strong host/guest runtime precedent,
  and Redwood is a heavier shared-presentation option for later evaluation.
- iOS/Native targets should be added only after native dependency packaging,
  exported framework shape, and Swift developer experience are explicit. Linker
  and symbol issues are not a late cleanup item; they are part of the boundary
  design.

## Cash App Lessons For This Project

Cash App is the right reference point for letta-mobile because its KMP adoption
was not a "rewrite the app in shared UI" move. The useful lesson is gradual
platform convergence around shared Kotlin logic while Android and iOS keep their
productive native workflows.

For this repo, that translates to:

```text
sharedLogic first
shared persistence contracts second
runtime event replay third
local backend behind SessionGraph fourth
shared UI only after the runtime works
```

### What To Copy

Copy these Cash App patterns:

- share business logic, persistence contracts, serializers, and pure functions
  before sharing UI
- keep app modules native and comfortable for each platform team
- roll out KMP runtime behavior behind explicit feature flags
- make the shared module useful to Android, iOS, desktop, and server-style JVM
  contributors
- keep PRs small enough that platform specialists can review them without
  becoming Gradle archaeologists
- prefer type-safe generated contracts for persistence and transport where the
  schema is stable
- expose streams as `Flow`/`StateFlow` at boundaries rather than leaking storage
  or UI implementation details

### What Not To Copy Blindly

Do not blindly import every Cash App library.

```text
SQLDelight:
  good candidate for durable multiplatform RuntimeEvent/MemFS storage after the
  schema stabilizes; not a first PR replacement for current Room code.

Molecule:
  useful if we choose Compose-runtime presenters that emit StateFlow display
  models; not a reason to put Compose UI into sharedLogic.

Zipline:
  useful precedent for a portable host/guest runtime, signed code, serialized
  service boundaries, suspend calls, and Flow across a runtime boundary; not a
  default dependency for the embedded Letta-shaped runtime.

Redwood:
  useful proof that shared presentation logic can target Android, iOS, and web
  through platform renderers; too heavy for the first migration slice unless the
  project explicitly chooses schema-driven shared UI.
```

The guiding rule:

```text
Use Cash App as architecture precedent.
Adopt a Cash App library only when it removes concrete project risk.
```

### Cash App Pattern Mapping

| Cash App pattern | letta-mobile application |
|---|---|
| Shared business logic with native UI | `sharedLogic` owns RuntimeEvent, MemFS, Letta-shaped contracts, reducers, and replay; Android UI stays Android-first. |
| Persistence and pure functions as early KMP wins | Move idempotency, replay, path rules, validation, and projection reducers before moving repository implementations. |
| Gradual rollout behind flags | `SessionGraphFactory` selects remote Letta vs local Koog runtime behind config/feature flags. |
| SQLDelight-style portable storage | Evaluate SQLDelight for RuntimeEvent outbox and MemFS commits after schemas stabilize; keep Room as Android adapter during migration. |
| Molecule-style state production | Consider shared presenter/state producers for stable `StateFlow<UiModel>` after reducers are common; do not make UI consume raw runtime DTOs. |
| Zipline-style host/guest boundary | Treat local runtime, future plugin runtime, and AgentFile execution as host/guest boundaries with serialized services and explicit capability exchange. |
| Redwood-style shared presentation | Defer unless iOS/desktop/web commit to shared presentation; prefer stable UI projections first. |

### Implications For The Local Agent Runtime

The local agent should look like a Cash App-style shared runtime, not a web app
embedded inside Android.

Required implications:

- the runtime API must be Kotlin-first and serializable
- platform hosts must provide filesystem, network, model, tool, and secure
  storage services through explicit interfaces
- the runtime emits `RuntimeEvent` streams rather than mutating UI state
- every event is replayable from an offset
- host/runtime boundaries support suspend calls and streaming flows
- feature flags can switch between remote Letta and local Koog without changing
  UI contracts
- storage migration is a schema decision, not a repository rewrite side effect

### Web Target Note

Kotlin web can be productive, but it is not the architectural foundation for
the agent runtime. If a web app is added later, it should be an app module that
consumes `sharedLogic`, not the source of truth for runtime behavior.

## Native Dependency And Swift Experience Gate

The Touchlab/SKIE conversation adds an important constraint: KMP adoption can
fail even when the shared Kotlin code is correct if the native dependency and
Swift-facing API experience is poor.

For this project, that means `iosMain` is not a checkbox to turn on after
creating `commonMain`. Before adding iOS targets to a shared module, we need to
answer:

- what native Apple framework is exported
- which shared modules are visible to Swift
- which dependencies are compiled into that framework
- whether dependencies are static, dynamic, or host-provided
- whether any platform library requires cinterop
- whether symbols and native libraries are resolvable from Xcode
- whether Swift callers get usable APIs for suspend functions, Flow, sealed
  hierarchies, enums, and inline value classes

### Dependency Rules For Native Targets

Do not add native dependencies to `commonMain` unless they are genuinely
multiplatform and already proven on iOS.

Use this split:

```text
commonMain:
  contracts, serializers, reducers, state machines

iosMain:
  Apple filesystem, keychain, networking, native model/tool adapters

jvmMain:
  desktop/server filesystem, process, local runtime adapters

androidMain:
  Android storage, services, permissions, Room/DataStore bridges
```

If a native library is needed, configure it where it is consumed. Do not rely on
transitive native linker behavior. Every cinterop/native dependency should have
a small smoke test or sample build that proves Xcode can link the exported
framework.

### Swift API Rules

The shared API should be designed for Swift as a first-class caller:

- exported names should be stable and intentional
- Swift should not need to know Android concepts
- flows should represent event streams, not mutable storage internals
- suspend functions should map cleanly to Swift async usage
- sealed hierarchies and enums should be reviewed from Swift, not only Kotlin
- inline value classes should be tested at the Swift boundary before becoming
  public API

SKIE should be evaluated before an iOS app consumes runtime APIs. It can improve
Swift ergonomics for Flow, suspend functions, enums, sealed classes, and other
Kotlin constructs, but it should be introduced by ADR because it changes the
published framework experience.

Native target exit criteria:

- Android JVM/shared tests already pass
- exported framework builds from Gradle
- Xcode can import and link the framework
- Swift smoke tests cover one suspend call and one event stream
- SKIE adoption decision is recorded before exposing broad runtime APIs
- no shared module exposes Android-only dependencies through the Apple framework

## Current Repo Shape

Current Gradle root: `android-compose`.

Included modules:

```text
:app
:core
:sharedLogic
:designsystem
:feature-chat
:feature-editagent
:cli
:macrobenchmark
:baselineprofile
```

Current characteristics:

- `:app` is a full Android application module.
- `:sharedLogic` is the first KMP library module and owns portable domain
  identity value classes.
- `:core`, `:designsystem`, `:feature-chat`, and `:feature-editagent` are
  Android library modules.
- Android packaging, signing, flavors, Sentry, baseline profile consumption,
  manifest placeholders, and version derivation live in `:app`.
- Shared runtime concepts are currently still in Android modules:
  - `SessionManager` and `SessionGraph`
  - timeline models and reducers
  - `TimelineExternalTransportWriter`
  - API models and repository interfaces
  - DataStore, Room, Android security, Hilt, OkHttp/Ktor wiring
- Compose UI and Android platform UI dependencies are spread across `:app`,
  `:designsystem`, and `:feature-chat`.

This is a solid Android modular architecture, but it is not yet a Kotlin
Multiplatform structure.

## Target Structure

Recommended long-term shape:

```text
android-compose/
  app/
    androidApp/              Android app packaging, manifest, DI, services
    desktopApp/              optional JVM desktop app entry point
    webApp/                  optional JS/Wasm app entry point
  iosApp/                    Xcode project outside or beside Gradle root

  sharedLogic/               KMP library, no Compose dependency by default
  sharedUI/                  optional KMP Compose UI library
  sharedRuntime/             optional split if runtime grows too large
  sharedPersistence/         optional SQLDelight-backed storage split
  sharedTesting/             optional test fixtures and fake backends

  core/                      temporary Android-era module during migration
  designsystem/              Android-only until sharedUI decision is made
  feature-chat/              Android-only until sharedUI decision is made
```

The exact folder names can change, but the responsibilities should not:

- shared modules are libraries
- app modules are runnable applications
- Android application packaging stays out of shared KMP modules
- native UI platforms depend on `sharedLogic`, not `sharedUI`

## Required Gradle Changes

### 1. Add KMP Plugin Coordinates

The root build should declare, but not immediately apply, KMP plugins:

```kotlin
plugins {
    id("org.jetbrains.kotlin.multiplatform") version "<kotlin-version>" apply false
    id("com.android.kotlin.multiplatform.library") version "<agp-version>" apply false
}
```

The repo currently uses:

```text
Kotlin: 2.3.20
AGP: 8.9.2
Android application/library plugins
```

Before adopting AGP 9 rules broadly, the migration should decide whether to:

- first create JVM-only KMP modules with no Android target
- or upgrade AGP and introduce Android KMP library targets immediately

The safer path is:

```text
sharedLogic v1: KMP with JVM target only or Android target only if plugin support is ready
sharedLogic v2: add iOS targets after the public API stabilizes
sharedLogic v3: add desktop/web only when a real app module needs them
```

### 2. Keep `:app` As Android Application

Do not convert `:app` into a KMP module.

Keep in `:app` or future `:app:androidApp`:

- `com.android.application`
- Android manifest
- app id, signing, versionCode/versionName derivation
- product flavors: `play`, `sideload`, `root`
- Sentry Gradle config
- baseline profile consumption
- Android services, receivers, WorkManager setup
- Hilt Android entry points
- Android resources required for packaging
- Play/sideload/root build-time gates

This module should depend on shared modules, not become one.

### 3. Convert Portable Modules Incrementally

Do not convert `:core` wholesale. It currently contains Android-only
dependencies such as Room, DataStore, Android security, lifecycle process,
Paging runtime, OkHttp, Hilt, KSP, Android tracing, and Material utilities.

Instead create new modules and move code by portability:

```text
:sharedLogic
  commonMain
  commonTest
  androidMain only when Android actuals are needed
  iosMain only when iOS actuals are needed
  jvmMain only when desktop/server actuals are needed

:sharedRuntime
  optional later split for MemFS, RuntimeEvent, Koog adapters

:sharedUI
  optional later split for Compose Multiplatform UI
```

`core` remains Android infrastructure during migration.

### 4. Add Source Sets Deliberately

Initial `sharedLogic` source-set policy:

```text
commonMain:
  pure contracts, serializers, reducers, state machines, validation

commonTest:
  fixtures for RuntimeEvent, timeline replay, MemFS contracts

androidMain:
  Android actuals for clocks, UUIDs, filesystem roots, crypto wrappers,
  platform logging, Room/DataStore adapters if needed

iosMain:
  iOS actuals only after a real iOS app target exists

jvmMain:
  desktop/server actuals for filesystem, process, and local model adapters
```

Avoid creating source sets because they are theoretically useful. Create them
when code actually needs platform APIs.

## Required Module Extraction

### Phase 0: Establish Boundaries

Create ADRs before moving files:

1. KMP module structure ADR
2. SessionGraph as backend runtime boundary ADR
3. RuntimeEvent as durable timeline gateway ADR
4. shared UI decision record: Android-only UI now, `sharedUI` later unless iOS
   commits to Compose Multiplatform

Output:

```text
docs/architecture/kmp-module-structure-adr.md
docs/architecture/session-graph-runtime-boundary.md
docs/architecture/runtime-event-gateway-adr.md
```

### Phase 1: Create `:sharedLogic`

Required Gradle work:

- add `include(":sharedLogic")`
- create `sharedLogic/build.gradle.kts`
- apply `org.jetbrains.kotlin.multiplatform`
- add Android KMP library plugin only when Android target is introduced
- configure Kotlin compiler flags consistently with current modules
- add serialization and coroutines dependencies only in common source sets
- add test dependencies usable from KMP tests

Initial dependencies should be small:

```text
commonMain:
  kotlinx-coroutines-core
  kotlinx-serialization-core/json
  kotlinx-datetime if replacing java.time in common code

commonTest:
  kotlin-test
  kotlinx-coroutines-test
  turbine if Flow tests move into KMP
```

Avoid in `commonMain`:

- AndroidX
- Room
- DataStore
- Hilt/Dagger
- OkHttp-specific APIs
- Android Compose
- Android resources
- java.io/java.nio unless source-set constrained

### Phase 2: Move Pure Contracts

Move types that are already platform-neutral or can be made neutral with small
changes:

- backend capability model
- Letta-shaped identifiers
- DTO-like data contracts
- `RuntimeEvent` envelope and payload hierarchy
- offset and idempotency types
- MemFS path, revision, commit, and metadata contracts
- AgentFile import/export contracts
- tool call / approval / tool result contract types
- run lifecycle contract types

Do not move repository implementations yet. Move interfaces and pure models.

Required code hygiene:

- replace `java.time.Instant` in shared contracts with `kotlinx.datetime.Instant`
- replace Android logging with injected logging/event sinks
- replace generated Android `BuildConfig` reads with platform config providers
- avoid using `Parcelable` in shared contracts
- avoid Android annotations in common source

### Phase 3: Move Pure Reducers And Replay Fixtures

The highest-value shared logic is deterministic event projection.

Candidates:

- timeline immutable model, if made platform-neutral
- `reduceStreamFrame`
- RuntimeEvent-to-timeline projection
- idempotency and dedupe helpers
- replay-from-offset fixtures
- MemFS prompt invalidation rules
- tool return before tool call folding
- approval response folding

Current code to inspect first:

```text
android-compose/core/src/main/java/com/letta/mobile/data/timeline/Timeline.kt
android-compose/core/src/main/java/com/letta/mobile/data/timeline/TimelineStreamReducer.kt
android-compose/core/src/main/java/com/letta/mobile/data/timeline/experimental/ConversationStateHolder.kt
```

The goal is not to move `TimelineSyncLoop` immediately. The goal is to move the
pure state machine it depends on.

Acceptance criteria:

- KMP tests can replay fixtures and produce the same final state as Android
  tests.
- Android code depends on `:sharedLogic` for the pure reducer.
- No Android dependencies leak into `commonMain`.

### Phase 4: Split Runtime Interfaces From Android Implementations

Define shared interfaces in `sharedLogic`:

```kotlin
interface LettaBackend {
    val capabilities: BackendCapabilities
    fun events(afterOffset: RuntimeEventOffset): Flow<RuntimeEventEnvelope>
}

interface MemFsStore {
    suspend fun read(path: MemFsPath): MemFsFile?
    suspend fun write(command: MemFsWriteCommand): MemFsCommit
    fun commits(afterRevision: MemFsRevision): Flow<MemFsCommit>
}

interface RuntimeEventOutbox {
    suspend fun append(event: RuntimeEventDraft): RuntimeEventEnvelope
    fun events(afterOffset: RuntimeEventOffset): Flow<RuntimeEventEnvelope>
}
```

Keep implementations outside common code:

```text
Android remote Letta backend:
  core/app Android code, Ktor/OkHttp, Room/DataStore adapters

Android local Letta backend:
  Android actual storage, Koog adapter, filesystem/SAF adapters

JVM desktop local backend:
  jvmMain filesystem, process/tool adapters

iOS local backend:
  iosMain filesystem/keychain/network actuals
```

### Phase 5: Add Android Actuals And Adapters

After `sharedLogic` contracts exist, adapt current Android code:

- `SessionGraph` chooses a backend implementation.
- current remote repositories become `RemoteLettaBackend` adapters.
- current timeline events are normalized into `RuntimeEvent`.
- `TimelineRepository` remains Android-side process owner during migration.
- Room/DataStore remain Android actual implementations, not common code.

Important rule:

```text
LocalLettaBackend must enter through SessionGraph.
It must not write directly to UI state or bypass TimelineRepository.
```

### Phase 6: Optional `:sharedUI`

Create `sharedUI` only if we choose Compose Multiplatform UI sharing.

Move only UI that is:

- platform-neutral
- free of Android lifecycle APIs
- not tied to Hilt Android
- not dependent on Android-only resources
- useful on at least two platforms

Likely not first-wave candidates:

- Android navigation graph
- Android service-bound screens
- WorkManager/notification UI
- root/sideload system-access UI
- Hilt-bound ViewModels
- screens with Activity Result APIs

Possible later candidates:

- pure chat message rendering components
- timeline item rendering
- static design tokens
- common empty/error/loading states
- MemFS viewer components
- AgentFile import/export previews

## Shared Resources Requirements

### Resource Ownership Rules

Keep in Android app module:

- launcher icons
- Android manifest resources
- notification channels and app labels if Android-specific
- Sentry manifest placeholders
- Play/sideload/root packaging resources
- resources needed by Android-only services or receivers

Move to `sharedUI/src/commonMain/composeResources` only when the resource is
used by shared Compose UI:

```text
sharedUI/src/commonMain/composeResources/
  drawable/
  font/
  values/strings.xml
  values-<locale>/strings.xml
  files/
```

Do not move Android resources into `sharedLogic` just because multiple
platforms need similar data. If non-UI logic needs bundled data, prefer a
small resource abstraction and platform actual loaders.

### Resource Access Rules

Shared Compose UI should use generated Compose resource accessors:

```kotlin
import letta.shared.ui.generated.resources.Res
```

Android-only UI should continue using Android resources.

Native iOS UI should not depend on `sharedUI` merely to get strings. If iOS
uses SwiftUI, localization should either stay native or flow through shared
logic as plain data keys.

### Resource Migration Sequence

1. Inventory current Android `res` usage.
2. Mark each resource as Android packaging, Android UI, or shared UI candidate.
3. Move only shared UI candidates to `composeResources`.
4. Update imports from Android `R` to generated `Res` only inside shared UI.
5. Verify Android packaging still includes required app resources.
6. Add iOS/desktop/web checks only after those app modules exist.

## Compose Performance And Stability Requirements

The KMP migration must not move code in a way that silently makes hot Compose
surfaces less skippable. The reviewed Compose performance guidance changes the
migration rule from "move pure models first" to:

```text
Move pure runtime contracts into sharedLogic.
Project shared runtime contracts into stable UI models before Compose consumes them.
Measure stability and recomposition before optimizing.
```

This matters because `sharedLogic` should remain mostly Compose-free, while
Compose can only infer stability across module boundaries when the relevant
types are known stable, annotated/configured as stable, or compiled with the
Compose compiler. A clean KMP boundary can therefore create a UI performance
regression if raw shared DTOs, mutable data, or ordinary collections flow
directly into chat rows, timelines, agent lists, or MemFS viewers.

### Current Repo Baseline

The Android Compose-bearing modules currently have compiler reports configured:

```text
:app
:designsystem
:feature-chat
:feature-editagent
```

Each uses:

```kotlin
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

That pattern should be preserved for any future Compose module, especially
`:sharedUI`. If the reports are later centralized in a convention plugin, the
result must still emit module-local metrics and reports for release-like builds.

The repo currently uses Kotlin `2.3.20`, which is newer than Kotlin `2.0.20`.
Strong skipping is therefore already the default behavior for Compose compiler
modules. Do not add obsolete `enableStrongSkippingMode` plumbing. Feature flags
should use the Compose compiler DSL only when there is a measured reason, for
example:

```kotlin
composeCompiler {
    featureFlags = setOf(
        ComposeFeatureFlag.OptimizeNonSkippingGroups,
    )
}
```

Use feature flags as experiments guarded by before/after reports and traces,
not as default migration scaffolding. If strong skipping must be disabled for a
diagnostic run, use the current DSL form:

```kotlin
composeCompiler {
    featureFlags = setOf(
        ComposeFeatureFlag.StrongSkipping.disabled(),
    )
}
```

### Stability Policy For Shared Models

Shared runtime models should not be passed blindly into UI. For each model that
crosses from `sharedLogic` into Compose, choose one of these paths:

1. Map to a UI projection in a Compose-bearing module.
2. Use immutable KMP collection types in the UI-facing model.
3. Wrap ordinary collections in a carefully audited stable wrapper.
4. Add `@Immutable` or `@Stable` only when the type actually satisfies the
   Compose stability contract.
5. Use a Compose compiler stability configuration file for external or standard
   library types that the team explicitly accepts as stable.

Preferred first wave:

```text
sharedLogic:
  Letta/RuntimeEvent/MemFS contracts with no Compose dependency.

feature/sharedUI:
  stable UI projection types that adapt sharedLogic contracts for Compose.
```

Do not add Compose runtime annotations to every `sharedLogic` type just to make
compiler reports green. `@Stable` and `@Immutable` are correctness contracts;
wrong annotations can prevent recomposition when the UI should update.

Acceptable uses of annotations:

- small immutable value objects with only `val` properties
- persistent collection wrappers with no mutation escape hatch
- UI projection models owned by a Compose-bearing module
- shared contracts that are demonstrably immutable and worth exposing as stable

Risky uses:

- Room/DataStore entities
- mutable session state
- repository results backed by mutable caches
- models containing `var`
- models containing ordinary `List`, `Map`, or `Set` that can mutate elsewhere
- objects whose equality is expensive enough that skipping costs more than
  recomposition

### Collection Policy

Compose treats ordinary `List`, `Set`, and `Map` parameters as unstable even
when their element type is stable. This is directly relevant to:

- timeline message lists
- tool call and tool result groups
- MemFS file trees
- agent tag lists
- capability lists
- run step collections

For hot UI paths, prefer:

```kotlin
ImmutableList<T>
ImmutableSet<T>
ImmutableMap<K, V>
```

or an audited wrapper:

```kotlin
@Immutable
data class TimelineItems(
    val items: List<TimelineItemUiModel>,
)
```

Only use a wrapper when the list cannot mutate behind the wrapper. If the data
can change, expose a new wrapper instance with a new immutable snapshot.

`sharedLogic` may still use ordinary collections for domain APIs where Compose
does not consume the type directly. The stability requirement applies at the UI
boundary, not to every internal runtime object.

### Multi-Module Stability Gate

Every PR that moves UI-fed types into KMP modules should include this check:

```text
Does a composable receive this type, or a collection of this type?
If yes, is Compose able to infer or trust its stability?
If no, is the recomposition cost irrelevant or measured acceptable?
```

Required migration gates:

- run Compose compiler reports before and after moving UI-fed types
- inspect skippable/restartable changes for touched screens
- keep stable lazy-list item keys based on durable ids
- avoid passing shared DTO collections directly to large lists
- prefer stable UI models for chat/timeline rows
- add comments only when a stability wrapper has a non-obvious invariant

Do not require every composable to become skippable. Optimizing every function
is premature and can add unnecessary generated code, equality work, and review
burden. Focus on hot paths:

- chat timeline
- live streaming rows
- run monitor screens
- agent and tool lists
- MemFS tree/file viewer
- frequently updating status chips and progress surfaces

### State Read Policy

The KMP migration will introduce more Flow-backed state and replayed projections.
Compose screens must keep high-frequency state reads close to the UI element
that actually needs them.

Use `derivedStateOf` when a high-frequency state source maps to a lower-frequency
UI decision:

```kotlin
val showJumpButton by remember {
    derivedStateOf { listState.firstVisibleItemIndex > 0 }
}
```

Good candidates:

- show or hide scroll-to-bottom affordances
- collapse or expand a toolbar from scroll state
- derive "active run visible" from a fast run-event stream
- derive coarse connection status from frequent heartbeat/runtime events

Bad candidates:

- ordinary one-to-one state mapping
- values already distinct in the ViewModel or reducer
- derived values that allocate on every read
- hiding a slow reducer or unstable list model instead of fixing the source

When a value changes every frame or during scroll, prefer lambda-based modifiers
or draw/layout phase reads where possible, such as `Modifier.offset { ... }` or
`drawBehind { ... }`, rather than forcing composition for a layout-only or
draw-only change.

### Movable Content Policy

Use `movableContentOf` for adaptive UI when the same stateful subtree moves
between parents and should keep remembered state.

Good candidates:

- chat composer moving between compact and expanded layouts
- a selected run detail panel moving between side pane and bottom sheet
- MemFS preview moving between split-pane and stacked layouts
- tool approval controls moving between desktop and phone arrangements

Do not use it as a general recomposition fix. It is appropriate when identity
and remembered state must survive a parent/layout change.

### Non-Restartable And Non-Skippable Policy

`@NonRestartableComposable` can be useful for trivial pass-through wrappers or
root functions where a recomposition scope adds overhead without value. It
should be rare and justified by compiler reports or tracing.

`@NonSkippableComposable` is an opt-out for cases where strong skipping is wrong
for the component's semantics. It should be rarer still.

Do not add either annotation during mechanical KMP extraction. Add them only in
a performance PR with evidence.

### Performance Verification

Before moving shared UI or UI-fed contracts:

```text
1. Capture Compose compiler metrics/reports for touched modules.
2. Capture baseline profile or trace evidence for hot screens if behavior risk is high.
3. Move contracts or UI projections.
4. Re-run compiler reports.
5. Confirm skippability/stability did not regress in hot composables.
6. Run existing Android compile/test gates.
```

For docs-only or skeleton module work, compiler report comparison is optional.
For chat/timeline rendering changes, it is required.

Recommended near-term PR:

```text
Add a small script or Gradle convention that gathers compose_compiler reports
from :app, :designsystem, :feature-chat, :feature-editagent, and future
:sharedUI into one reviewable artifact.
```

## Dependency Policy

### Allowed In `sharedLogic/commonMain`

- Kotlin standard library
- kotlinx.coroutines core
- kotlinx.serialization
- kotlinx.datetime
- small multiplatform libraries with active KMP support
- pure domain code
- SQLDelight generated/runtime APIs only after a persistence ADR chooses it for
  RuntimeEvent or MemFS storage

### Avoid In `sharedLogic/commonMain`

- AndroidX runtime APIs
- Compose UI
- Hilt/Dagger
- Room
- DataStore
- OkHttp-specific classes
- Java file/process APIs
- Android `Context`
- `BuildConfig`
- mutable global singletons

### Allowed In `sharedUI/commonMain`

- Compose Multiplatform runtime/UI/foundation/material where needed
- Compose Multiplatform resources
- shared design tokens
- pure shared UI models

### Avoid In `sharedUI/commonMain`

- Android lifecycle owners unless wrapped behind common interfaces
- Activity Result APIs
- Android resources via `R`
- Hilt ViewModels
- Android-only navigation APIs
- WorkManager, notifications, services

## SessionGraph And Backend Boundary Changes

Required changes:

1. Define `BackendKind`:

   ```text
   remoteLetta
   localKoog
   futureCompatibleRuntime
   ```

2. Give each backend a stable runtime id:

   ```text
   backendId / runtimeId / configId
   ```

3. Make `SessionGraphFactory` create a graph from backend kind and config.

4. Stop treating table clearing as the long-term session-boundary mechanism.

5. Before durable local runtime storage lands, add explicit scoping to local
   tables/outbox:

   ```text
   backend_id
   runtime_id
   agent_id
   conversation_id
   offset
   ```

6. Keep current clear-on-switch behavior only as an Android remote cache safety
   mechanism until backend-scoped persistence exists.

7. Treat every backend as a host/runtime contract, not a UI implementation:

   ```text
   host services in app/platform modules
   runtime contracts in sharedLogic
   runtime storage behind explicit persistence interfaces
   events out through Flow<RuntimeEventEnvelope>
   ```

## RuntimeEvent Requirements

`RuntimeEvent` should be the durable, portable version of the current timeline
gateway, not a parallel state system.

Required event envelope:

```kotlin
data class RuntimeEventEnvelope<T : RuntimeEventPayload>(
    val offset: RuntimeEventOffset,
    val eventId: RuntimeEventId,
    val backendId: BackendId,
    val agentId: AgentId?,
    val conversationId: ConversationId?,
    val runId: RunId?,
    val createdAt: Instant,
    val source: RuntimeEventSource,
    val schemaVersion: Int,
    val payload: T,
)
```

Required first payload categories:

- local user append
- remote stream frame
- external WS frame
- REST snapshot reconcile
- send marked sent
- send marked failed
- retry requested
- tool call observed
- tool return observed
- approval requested/resolved
- run started/completed/failed/cancelled
- MemFS commit
- AgentFile import/export

Required replay tests:

- replay produces the same final timeline as live ingestion
- duplicate event id is ignored
- offset resume does not skip live events
- tool return before tool call resolves correctly
- external WS and SSE do not double-render the same turn
- backend switch cannot replay another backend's events

## MemFS Requirements

MemFS belongs in shared runtime logic as the memory substrate.

Required common contracts:

- path model
- file metadata
- revision model
- commit model
- write commands
- prompt invalidation rules
- AgentFile mapping
- legacy memory-block compatibility projection

Required platform actuals:

- Android filesystem or app-private storage adapter
- desktop filesystem adapter
- iOS filesystem adapter
- optional encrypted metadata adapter per platform

Do not put Room entities in common code. Put common contracts in `sharedLogic`
and map to platform storage schemas.

### Portable Persistence Direction

Cash App's SQLDelight precedent matters because RuntimeEvent and MemFS need
durable portable schemas. Room can remain the Android implementation while the
shared contract stabilizes, but the long-term KMP storage candidate should be
SQLDelight or an equivalent type-safe multiplatform persistence layer.

Do not combine these changes in one PR:

```text
RuntimeEvent schema design
Room adapter changes
SQLDelight introduction
MemFS persistence rewrite
```

Recommended sequence:

1. Define common RuntimeEvent and MemFS contracts.
2. Keep Android Room/DataStore adapters working.
3. Freeze first durable schemas with replay tests.
4. Spike SQLDelight for RuntimeEvent outbox only.
5. Add MemFS commits only after outbox replay is proven.
6. Remove Android-only storage assumptions from shared APIs.

## Koog Requirements

Koog should be an implementation detail of `LocalLettaBackend`.

Required boundaries:

```text
sharedLogic:
  TurnEngine interface
  RuntimeEvent contracts
  tool approval contracts
  MemFS prompt context contracts

androidMain/jvmMain:
  KoogTurnEngine implementation
  model/provider adapters
  tool execution adapters
  filesystem/process adapters
```

The public app-facing contract should remain Letta-shaped. UI should not know
whether a run came from Koog or remote Letta.

The Cash App/Zipline lesson for Koog is boundary design:

- pass serializable commands and values across the runtime boundary
- expose long-running work as `Flow<RuntimeEventEnvelope>`
- keep host capabilities explicit
- make every runtime capability discoverable before a turn starts
- avoid giving the runtime direct access to Android services, DI, or UI state
- assume future runtimes may be embedded, remote, dynamically loaded, or
  isolated differently

This does not require Zipline. It requires a Zipline-shaped level of discipline
around host/guest contracts.

## Testing Requirements

New KMP test tiers:

```text
sharedLogic:commonTest
  pure reducers
  RuntimeEvent replay
  MemFS path/commit rules
  AgentFile fixtures
  collection immutability/stability invariants for UI-fed projections

sharedLogic:androidUnitTest
  Android actual adapters without full app UI

sharedUI/commonTest or Android unit tests
  stable UI projection mapping
  snapshot/reducer-to-UI model tests
  adaptive layout state-preservation cases where movableContentOf is used

app/androidApp tests
  SessionGraph integration
  Android DI
  UI behavior
  notification/service behavior
```

Keep existing Android test gates while adding KMP gates:

```text
./gradlew :sharedLogic:allTests
./gradlew :sharedLogic:jvmTest
./gradlew :sharedUI:allTests
./gradlew :app:testRootDebugUnitTest
./gradlew :app:compileRootDebugKotlin
```

Exact task names will depend on selected targets.

## Migration Phases

### Phase A: Documentation And Build Skeleton

Deliverables:

- KMP module-structure ADR
- `:sharedLogic` skeleton
- Gradle plugin setup
- no production behavior change

Exit criteria:

- `:sharedLogic` compiles
- Android app still compiles
- no Android dependencies in `sharedLogic/commonMain`

### Phase B: Common Contracts

Deliverables:

- identifiers
- backend capability contracts
- RuntimeEvent envelope
- MemFS contracts
- AgentFile contracts

Exit criteria:

- contracts compile in common source
- Android code can depend on them
- fixture tests pass

### Phase C: Timeline Replay Core

Deliverables:

- pure reducer moved or mirrored into shared logic
- RuntimeEvent fixture replay tests
- Android timeline adapter still uses existing `TimelineRepository`

Exit criteria:

- live and replay fixtures match
- no UI behavior change
- current timeline tests remain green

### Phase D: Runtime Outbox Interface

Deliverables:

- `RuntimeEventOutbox` common interface
- Android Room/DataStore-backed implementation behind current app
- offset semantics documented
- backend/runtime scoping enforced
- persistence ADR deciding whether SQLDelight is the long-term outbox/MemFS
  storage layer

Exit criteria:

- events can replay after app restart
- backend switch cannot cross-contaminate offsets
- current clear-on-switch behavior no longer threatens durable local events

### Phase E: Local Backend Skeleton

Deliverables:

- `LocalLettaBackend`
- `SessionGraphFactory` can build a local runtime graph behind feature flag
- no Koog turn yet
- MemFS initialization

Exit criteria:

- local agent can be created/listed behind flag
- remote API mode unaffected
- local state persists

### Phase F: Koog Turn Slice

Deliverables:

- Koog turn engine adapter
- prompt compiled from MemFS
- one turn emits RuntimeEvents
- timeline projection renders result

Exit criteria:

- one local turn works end-to-end
- replay after restart produces same timeline
- tool approval path has explicit unsupported/disabled behavior if not complete

### Phase G: Optional Shared UI

Deliverables:

- sharedUI skeleton only if we commit to Compose UI sharing
- shared resources moved only for shared UI surfaces
- platform app modules consume sharedUI
- Compose compiler reports preserved for sharedUI and every app module that
  consumes it
- stable UI projections created for shared runtime models used by hot screens
- explicit decision on Compose Multiplatform vs native UI vs Redwood-style
  schema-driven presentation if web/iOS/desktop requirements demand it

Exit criteria:

- Android rendering parity
- iOS/desktop target decision documented
- no Android resource regression
- no unmeasured stability regression in chat/timeline hot paths

## Required Cleanup Before Implementation

Before major extraction:

- rewrite stale Client Mode docs as external transport docs
- remove or quarantine stale `CLIENT_MODE_HARNESS` references in tests/docs
- decide if `feature-mcp` is intentionally excluded from settings
- decide whether `bot/` is deleted, revived, or treated as historical
- fix current JVM target mismatch before using test results as a migration gate
- decide module naming style: `sharedLogic` vs `shared-logic`
- decide whether new modules live under `android-compose/` or a new root-level
  app folder structure

## Non-Goals

Do not do these in the first migration:

- convert the Android app module to KMP
- move all of `:core` at once
- move all Compose UI to common source
- introduce desktop/web app modules without a target owner
- make Room or Hilt common dependencies
- make Compose runtime a blanket dependency of sharedLogic just to annotate
  everything stable
- adopt SQLDelight, Molecule, Zipline, or Redwood without a narrow ADR and a
  measured reason
- use a Kotlin web framework as the runtime architecture source of truth
- pass raw shared DTO lists directly into hot Compose lists
- bypass `SessionGraph` for local runtime
- make RuntimeEvent a second UI state path beside timeline

## First Concrete Pull Requests

Recommended PR sequence:

1. Add KMP module-structure ADR and update stale docs.
2. Add empty `:sharedLogic` with `commonMain` and `commonTest`.
3. Move identifier/value-object contracts into `:sharedLogic`.
4. Define `RuntimeEvent` envelope and fixtures.
5. Add replay tests for current timeline gateway cases.
6. Move pure timeline reducer pieces only after tests pin behavior.
7. Add MemFS common contracts.
8. Write persistence ADR covering Room bridge now and SQLDelight evaluation
   later.
9. Add Android actual storage abstractions behind feature flag.
10. Wire `SessionGraphFactory` to select remote vs local backend graph.
11. Add Koog-backed local turn slice.
12. Add sharedUI only after stable UI projection and Compose report policy is
    already in place.

## Success Criteria

The migration is on track when:

- Android app behavior remains unchanged while shared modules grow.
- Shared logic has no Android dependencies in `commonMain`.
- RuntimeEvent replay can reproduce current timeline state.
- `SessionGraph` remains the only backend/runtime switch point.
- Local runtime state is scoped and cannot be cleared by remote backend switches.
- Shared resources are only moved when UI is actually shared.
- Compose compiler reports remain available for every Compose-bearing module.
- Hot UI models use stable projections, immutable collections, or documented
  stability contracts.
- Portable persistence has a documented path from Android Room bridge to a KMP
  storage layer if/when the schemas justify it.
- Cash App-style host/runtime boundaries exist before any dynamic or plugin
  runtime is attempted.
- Platform app modules own packaging, entry points, and platform services.

## Final Architecture Target

The end state should look like this:

```text
Android app
  -> Android DI/platform services
  -> SessionGraph
  -> RemoteLettaBackend or LocalLettaBackend
  -> sharedLogic contracts and reducers
  -> platform storage/network/tool adapters

iOS app
  -> SwiftUI or Compose UI decision
  -> sharedLogic contracts and runtime
  -> iOS storage/network/tool adapters

Desktop/web app
  -> optional app modules
  -> sharedLogic
  -> sharedUI only if Compose UI sharing is chosen

sharedLogic
  -> LettaBackend
  -> RuntimeEvent
  -> MemFS
  -> AgentFile
  -> pure reducers/projections
```

This aligns the project with Kotlin's current KMP structure while preserving
the architecture that letta-mobile has already converged on: session-scoped
backend lifetimes, timeline as projection, MemFS as substrate, and Koog as a
local runtime implementation detail.
