# Phase 6a — runnable entry points (docs + aliases deferred)

Physical `apps/*` moves (Option B) stay deferred so CI paths (`:app`, `:desktop`, `:web`) stay stable while Phase 5 continues.

## One entry per platform

All commands run from `android-compose/` with `JAVA_HOME` set per `CONTRIBUTING.md`.

| Platform | Gradle project | Command | Notes |
|---|---|---|---|
| Android | `:app` | `./gradlew :app:assembleDebug` / `:app:installRootDebug` | App id `ca.oculair.meridian.dev` (debug) |
| Compose Desktop | `:desktop` | `./gradlew :desktop:run` | Needs display + JDK 26; software Skiko on headless: `JAVA_TOOL_OPTIONS=-Dskiko.renderApi=SOFTWARE` |
| Browser (WASM) | `:web` | `./gradlew :web:wasmJsBrowserDevelopmentRun` (or CI `web-wasm` job) | Ship target for Iroh WASM |
| JVM CLI / probes | `:cli`, `:appserver-cli` | `./gradlew :cli:run` / contract scripts | Prefer `android-compose/cli` over any duplicate root `cli/` |

## Module map (unchanged paths)

See root [README.md](../../README.md) and [kmp-module-map.md](kmp-module-map.md). Shared domain stays in `:sharedLogic`; shared Compose UI in `:sharedUI`.

## Option B (later)

When Phase 5 is further along, consider:

```text
app/      → apps/android/
desktop/  → apps/desktop/
web/      → apps/web/
```

plus CI / jpackage / README path updates. Do **not** dual-include the same `projectDir` under two Gradle names — that breaks the project model.
