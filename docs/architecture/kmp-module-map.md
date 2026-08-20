# KMP module map

Gradle root: **`android-compose/`**. Open that directory (or link only that Gradle project) for day-to-day KMP work. The git repo root also contains optional extra Gradle trees (`cli/`, `poc/chat-cli/`) that are not part of the LettaMobile multiplatform graph.

`:web` is included in `android-compose/settings.gradle.kts`; re-sync Gradle in the IDE if it is missing from the module list.

## Current graph (after Phase 0–1)

```text
sharedLogic          KMP library — contracts, transport, timeline, reducers,
                     plus Android/JVM paging APIs that used to live in :core:domain
core:ids             KMP identifiers
core:runtime         KMP runtime contracts
core:data            Android-only — Room, Hilt, SessionManager, OkHttp (rename in Phase 2)
app                  Android application
desktop              JVM Compose Desktop application
web                  wasmJs application
designsystem         Android Jetpack Compose
feature-chat         Android presentation
feature-editagent    Android presentation
```

**Rule:** `:sharedLogic` must not Gradle-depend on `:app`, `:core:data`, `:designsystem`, `:feature-*`, `:desktop`, or `:web`. Enforced by `:architecture-tests:test` (`SharedLogicIsolationTest`).

## Target graph

See [kmp-structure-migration-epic.md](kmp-structure-migration-epic.md). Next: Phase 2 rename `:core:data` → `:core:android-data`, then extract `:sharedUI`.
