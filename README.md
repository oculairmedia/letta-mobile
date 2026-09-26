# Letta Mobile

Android + Compose Desktop client for [Letta AI](https://github.com/letta-ai/letta) — manage agents,
chat with them, review their work, and sync across your devices. Built as a Kotlin Multiplatform
project. One shared domain module powers both apps; platform layers are thin bindings.

<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/oculairmedia/letta-mobile/releases" target="_blank"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="48"></a>

Browser client (Iroh WASM, shipped in #1205): https://oculairmedia.github.io/letta-mobile/


## Features

- **Agent management** — create, edit, clone, import, delete, and organize agents (Android + desktop)
- **Rich chat** — tool outputs, code blocks, reasoning display, mermaid diagrams (native renderer), and a real-time assistant work timeline
- **Interactive tools** — `AskUserQuestion` and `ExitPlanMode` surface to the user with full submit/approve wiring
- **Memory administration** — core memory, archival passages, block library, archive attachment flows
- **Admin tooling** — models, providers, tools, MCP servers, identities, folders, groups, schedules, jobs, runs
- **Live sync** — multi-client conversation sync between mobile and desktop, paired-device authz, agent-to-agent messaging

## Module layout

App modules live under [`android-compose/`](android-compose/); repository-level tooling and
architecture docs live in [`scripts/`](scripts/) and [`docs/`](docs/).

| Path | Purpose |
|---|---|
| `app/` | Android app: screens, navigation, ViewModels, Hilt DI |
| `feature-chat/` | Chat screens, presenters |
| `feature-editagent/` | Agent editor |
| `core/android-data/` | Repositories, Room database, data sources |
| `core/runtime/` | Turn engine, runtime event fanout |
| `core/ids/` | Shared identifiers; wire schemas are source packages, not a `:core:schemas` Gradle module |
| `designsystem/` | Reusable Compose UI, theming, and dialogs |
| `sharedLogic/` | KMP domain, repositories, timeline, and transport logic |
| `sharedUI/` | KMP Compose UI shared by Android and desktop, including A2UI rendering |
| `desktop/` | Compose Desktop entry point, OS lock, installer |
| `web/` | Browser WASM client |
| `avatar/core/` `avatar/renderer-rive/` | Avatar models and Rive rendering |
| `cli/` `appserver-cli/` `iroh-wrapper-cli/` | JVM tooling, contract probes, Iroh server wrapper |
| `quality/detekt-rules/` `architecture-tests/` | Static-analysis rules and module-boundary tests |
| `drawbox/` `native/` `perf/` `macrobenchmark/` `baselineprofile/` | Canvas, native libs, and performance infrastructure |

The cardinal rule: **portable feature logic lives in `sharedLogic/commonMain`; shared
Compose UI lives in `sharedUI`; platform modules provide host-specific bindings.** The
required `shared-multiplatform` check runs bounded `:sharedLogic:jvmTest`, native
compilation, wrapper/CLI tasks, and browser tests rather than `:sharedLogic:allTests`
or `:desktop:test`. Desktop tests run in `test` when the desktop module changes.

## Runnable entry points

| Platform | Command (from `android-compose/`) |
|---|---|
| Android | `./gradlew :app:assembleDebug` |
| Compose Desktop | `./gradlew :desktop:run` |
| Browser WASM | `./gradlew :web:wasmJsBrowserDevelopmentRun` (CI job `web-wasm`) |
| CLI / probes | `./gradlew :cli:run`; App Server contract probes via `:appserver-cli` (see [`kmp-phase-6-entrypoints.md`](docs/architecture/kmp-phase-6-entrypoints.md)) |

Full notes: [`docs/architecture/kmp-phase-6-entrypoints.md`](docs/architecture/kmp-phase-6-entrypoints.md).

## Quick start

```bash
git clone https://github.com/oculairmedia/letta-mobile.git
cd letta-mobile
./scripts/install-hooks.sh          # activate .githooks/ via core.hooksPath

cd android-compose
cp -f local.properties.example local.properties
# Set JAVA_HOME using the platform-specific instructions in CONTRIBUTING.md.
./gradlew :app:assembleDebug        # Android APK
./gradlew :desktop:run              # Compose Desktop (needs JDK 26, Rust toolchain, and a display)
```

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for full setup instructions (Linux, macOS, Windows).

## Pre-push PR readiness

CI takes ~20 minutes per iteration. The most common reasons PRs loop through review iterations
are stale-base rollbacks, mechanical detekt debt, and missing concurrent-collection defaults —
all catchable at dev cost in seconds. **Run the checklist in [`AGENTS.md`](AGENTS.md) before
opening a PR.**

## Required CI gates

`main` is protected; PRs must pass all of:

- `test` — module unit tests, policy scripts
- `build-apk-pass` — Android APK build (stable aggregator of the `build-apk` matrix)
- `shared-multiplatform` — bounded `:sharedLogic:jvmTest`, host-native compilation, wrapper/CLI verification, and browser tests; desktop tests are additive in `test` when desktop changes
- `perf-gate` — perf-budget benchmarks

Coverage uploads run on `main` via `codecov.yml`, not as a required PR check.
**Advisory only** (cannot block merge): `detekt`, `qodana`, CodeScene Code Health Review, `Advisory AGENTS.md policy`.

## Releases

Versioning is **tag-driven**. `vX.Y.Z` is the single source of truth for `versionName` and
`versionCode`. CI builds on tag push and creates a GitHub Release. Each release ships a
signed Android APK (embedded runtime included), Windows desktop installers (`.exe` + `.msi`),
and a Linux Iroh wrapper distribution. Pre-release suffix: `v0.2.0-rc.1`. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the full
release flow.

## Development notes

- Issue tracking is **bd** (beads) — `bd ready`, `bd show <id>`, `bd update <id> --claim`.
  Never commit `.beads` artifacts.
- The pre-push hook blocks pushes to `main`/`master` and checks `JAVA_HOME`. Kotlin compile is **opt-in** (`PRE_PUSH_COMPILE=1`) — off by default so cold Gradle configure cannot OOM the shared host; CI owns the compile gate.
- Anything touching `sharedLogic` **App Server transport** requires a matched wrapper + APK deploy.
