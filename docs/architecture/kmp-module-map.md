# KMP module map

Gradle root: **`android-compose/`**. Open that directory (or link only that Gradle project) for day-to-day KMP work. The git repo root also contains optional extra Gradle trees (`cli/`, `poc/chat-cli/`) that are not part of the LettaMobile multiplatform graph.

`:web` is included in `android-compose/settings.gradle.kts`; re-sync Gradle in the IDE if it is missing from the module list.

## Current graph (after Phase 0–3a)

```text
sharedLogic              KMP library — contracts, transport, timeline, reducers,
                         plus Android/JVM paging APIs (former :core:domain);
                         still hosts composeUi / jvmAndAndroid UI until Phase 3b
sharedUI                 KMP Compose UI (android + jvm) — scaffold empty in 3a;
                         will own shared composables after Phase 3b
core:ids                 KMP identifiers
core:runtime             KMP runtime contracts
core:android-data        Android-only — Room, Hilt, SessionManager, OkHttp
app                      Android application
desktop                  JVM Compose Desktop application
web                      wasmJs application
designsystem             Android Jetpack Compose
feature-chat             Android presentation
feature-editagent        Android presentation
```

**Rule:** `:sharedLogic` must not Gradle-depend on `:app`, `:core:android-data`, `:designsystem`, `:feature-*`, `:desktop`, `:web`, or `:sharedUI`. Enforced by `:architecture-tests:test` (`SharedLogicIsolationTest`).

**Rule:** `:sharedUI` depends on `:sharedLogic` only among project modules (not on app/feature/desktop). Enforced by `SharedUiIsolationTest`.

## Target graph

See [kmp-structure-migration-epic.md](kmp-structure-migration-epic.md). Next: Phase 3b move UI sources from `:sharedLogic` into `:sharedUI`.
