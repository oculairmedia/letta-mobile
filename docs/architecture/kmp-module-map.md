# KMP module map

**Status:** Current source map; audited 2026-10-18 against Gradle declarations and isolation tests.

The product Gradle root is `android-compose/`. Its [settings](../../android-compose/settings.gradle.kts) declare the included projects; `poc/chat-cli/` is a separate experimental build. The unused root REST-era `cli/` build has been removed; the supported developer CLI is `:cli` in this graph.

| Project | Current responsibility / targets |
|---|---|
| `:sharedLogic` | Portable transport/domain/repository/timeline logic; Android, JVM, host-native and wasmJs. JVM/Android integrations live in `jvmAndAndroid`. Compose runtime models are allowed; Compose UI toolkit/plugins are excluded. |
| `:sharedUI` | Shared pages, chat, composer, A2UI, canvas, theme and resources; Android, JVM and wasmJs. Skia actuals share `skikoMain`; Android actuals own Android rendering hooks. |
| `:core:ids`, `:core:runtime` | KMP identity and runtime contracts. |
| `:core:android-data` | Android Room/DataStore/DI/session bindings and remaining Android repository adapters. |
| `:app`, `:feature-chat` | Android activity/navigation/DI/platform services and chat ViewModel/host bindings. Product pages are sharedUI. |
| `:desktop`, `:web` | JVM desktop shell and wasmJs browser shell consuming the shared product UI. |
| `:designsystem` | Remaining Android Compose wrappers/components; depends on sharedUI. |
| `:avatar:core`, `:avatar:renderer-rive` | Shared mascot contracts and Rive rendering support. |
| `:drawbox` | Vendored canvas editor consumed by sharedUI. |
| `:plugin-api`, `:plugin-api-testkit`, `:plugin-view` | Kotlin plugin SPI, conformance kit and platform plugin-page containers. |
| `:cli`, `:appserver-cli`, `:iroh-wrapper-cli` | Developer probes, local App Server supervisor/smoke CLI, production Iroh wrapper. These distributions have different commands. |
| `:sharedUI-devfixtures`, `:core:testutil` | Shared UI fixtures and Android test support. |
| `:architecture-tests`, `:quality:detekt-rules`, `:baselineprofile`, `:macrobenchmark` | Architecture/quality verification and Android performance tooling. |

The [sharedLogic build](../../android-compose/sharedLogic/build.gradle.kts) and [sharedUI build](../../android-compose/sharedUI/build.gradle.kts) define exact source-set dependency edges.

`SharedLogicIsolationTest` prevents sharedLogic from depending on platform/UI modules. `SharedUiIsolationTest` requires sharedLogic and forbids platform apps, Android data/features/designsystem and plugin-view. It does **not** restrict sharedUI to only one project dependency: avatar/core, drawbox and the Skia renderer support are current dependencies; test fixtures are a test dependency. Both tests live in [architecture-tests](../../android-compose/architecture-tests/src/test/kotlin/com/letta/mobile/architecture/).

The [migration epic](kmp-structure-migration-epic.md) is a historical sequence, not the current graph. wasm sharedUI is implemented. The [entrypoint layout decision](kmp-phase-6-entrypoints.md) retains `app/`, `desktop/`, and `web/`; the historical `apps/*` proposal is not pending implementation.
