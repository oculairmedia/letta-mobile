# Phase 6a — runnable entry points (docs + aliases deferred)

**Status:** Current entrypoint map; audited 2026-10-18 against included projects and application/build declarations. Physical `apps/*` moves remain a proposal.

Physical `apps/*` moves (Option B) stay deferred so CI paths (`:app`, `:desktop`, `:web`) stay stable; the current settings retain the original project paths.

## One entry per platform

All commands run from `android-compose/` with `JAVA_HOME` set per `CONTRIBUTING.md`.

| Platform | Gradle project | Command | Notes |
|---|---|---|---|
| Android | `:app` | `./gradlew :app:assembleRootDebug` / `:app:installRootDebug` | Debug APK app id `com.letta.mobile.dev` (explicit variant override); release base id `ca.oculair.meridian` |
| Compose Desktop | `:desktop` | `./gradlew :desktop:run` | Needs display + JDK 26; software Skiko on headless: `JAVA_TOOL_OPTIONS=-Dskiko.renderApi=SOFTWARE` |
| Browser (WASM) | `:web` | `./gradlew :web:wasmJsBrowserDevelopmentRun` (or CI `web-wasm` job) | Ship target for Iroh WASM |
| JVM CLI / probes | `:cli`, `:appserver-cli` | `./gradlew :cli:run` / `:appserver-cli:installDist` | Product CLI and App Server supervisor/smoke tools are distinct; see [deployment guide](app-server-controller-deploy.md). |
| Production Iroh wrapper | `:iroh-wrapper-cli` | `./gradlew :iroh-wrapper-cli:installDist` | `meridian-iroh-wrapper app-server-serve-iroh`; separate from the App Server supervisor. |

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
