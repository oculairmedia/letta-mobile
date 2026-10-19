# Runnable entry points and host layout

**Status:** Current entrypoint map and layout decision, checked 2026-10-19 against included projects and application/build declarations. The historical `apps/*` proposal is not the target layout.

Retain `app/`, `desktop/`, and `web/` under the `android-compose/` Gradle root.
The existing settings, CI paths and packaging consume these locations; physical
host grouping provides no shared-code boundary that the modules do not already
enforce. Revisit only with a concrete tooling or build requirement, including its
path migration cost. This settles the earlier grouping proposal without moving
modules or changing project identities.

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

## Historical Option B (not adopted)

The migration plan proposed:

```text
app/      → apps/android/
desktop/  → apps/desktop/
web/      → apps/web/
```

That option would also require CI / jpackage / README path updates. It is not
pending implementation. Do **not** dual-include the same `projectDir` under two
Gradle names — that breaks the project model.
