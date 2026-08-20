# KMP module map

Gradle root: **`android-compose/`**. Open that directory (or link only that Gradle project) for day-to-day KMP work. The git repo root also contains optional extra Gradle trees (`cli/`, `poc/chat-cli/`) that are not part of the LettaMobile multiplatform graph.

`:web` is included in `android-compose/settings.gradle.kts`; re-sync Gradle in the IDE if it is missing from the module list.

## Current graph (after Phase 0–3c)

```text
sharedLogic              KMP library — contracts, transport, timeline, reducers,
                         paging APIs, chat projection; Compose *runtime* only
                         (@Immutable/@Stable, MutableState) — no UI toolkit
sharedUI                 KMP Compose UI (android + jvm) — A2UI, markdown, icons,
                         bubble/style helpers, CustomColors
core:ids                 KMP identifiers
core:runtime             KMP runtime contracts
core:android-data        Android-only — Room, Hilt, SessionManager, OkHttp
app                      Android application
desktop                  JVM Compose Desktop application
web                      wasmJs application (local theme duplicates until wasm sharedUI)
designsystem             Android Jetpack Compose (api → sharedUI)
feature-chat             Android presentation
feature-editagent        Android presentation
```

**Rule:** `:sharedLogic` must not Gradle-depend on `:app`, `:core:android-data`, `:designsystem`, `:feature-*`, `:desktop`, `:web`, or `:sharedUI`. Enforced by `:architecture-tests:test` (`SharedLogicIsolationTest`).

**Rule:** `:sharedUI` depends on `:sharedLogic` only among project modules (not on app/feature/desktop). Enforced by `SharedUiIsolationTest`.

**Rule:** `:sharedLogic` must not apply Compose UI plugins or toolkit deps (foundation/material3/ui/coil/markdown/lucide). Runtime-only is allowed.

## Target graph

See [kmp-structure-migration-epic.md](kmp-structure-migration-epic.md). Next: Phase 4c desktop stub shrink; Phase 3d/wasm when web adopts `:sharedUI`.
