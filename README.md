# Letta Mobile

Android, Compose Desktop and browser client for [Letta AI](https://github.com/letta-ai/letta) — manage
agents, chat with them, review their work, and sync across your devices. Built as a Kotlin
Multiplatform project: one shared domain module (`sharedLogic`) and one shared Compose UI module
(`sharedUI`) power all three clients, and each platform module is a thin host binding.

<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/oculairmedia/letta-mobile/releases" target="_blank"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="48"></a>

Browser client (Iroh WASM, shipped in #1205): https://oculairmedia.github.io/letta-mobile/


## Features

- **Agent management** — create, edit, clone, import, delete, and organize agents; one shared agent editor and new-agent flow used by every client
- **Rich chat** — tool outputs, code blocks, reasoning display, mermaid diagrams (native renderer), and a real-time assistant work timeline
- **Interactive tools** — `AskUserQuestion` and `ExitPlanMode` surface to the user with full submit/approve wiring
- **Approvals and permission modes** — pending approvals relay to every connected client; permission mode changes push live rather than waiting for a reconnect
- **Subagents** — roster and progress for delegated work, including a running subagent's step detail
- **Memory administration** — core memory blocks and the pan/zoom memory graph on a shared page, plus the Android memory overview and file views
- **Admin tooling** — providers, tools, skills, templates, projects, channels, schedules, runs, telemetry, system access, pairing and config
- **Canvas and notes** — a shared canvas workspace with the vendored DrawBox editor, peer-owned documents synced across collaborators, plus Dreaming reflection settings; agents draw with `canvas_apply_ops` and lay out notes, checklists and cards by meaning with `canvas_compose`
- **Canvas plugins** — new canvas element kinds and agent tools from plugins under a versioned contract: the `plugin-api` Kotlin SPI for JVM plugins, the LCP wire protocol for process/service plugins in any language, and live re-advertisement of plugin tools to the agent
- **Live sync** — multi-client conversation sync between mobile, desktop and browser, paired-device authz, agent-to-agent messaging
- **Shell** — one adaptive shell across clients: unified search, connection state, a live dock mascot, and pull-to-refresh on list surfaces

All of the above are shared Compose UI in `sharedUI` and shared logic in `sharedLogic`; the
platform modules supply navigation hosts, DI, persistence and OS integration.

Since the hard cuts that removed the HTTP/REST backend, the REST-only admin screens — archival
passages, block library, MCP servers, tool editor, model browser and usage — are **gone with no App
Server route behind them**. Their data-layer repositories remain in `sharedLogic` where a shared
surface still uses them. Do not re-add these screens without an App Server route to back them.

## Module layout

App modules live under [`android-compose/`](android-compose/); repository-level tooling and
architecture docs live in [`scripts/`](scripts/) and [`docs/`](docs/). Every Gradle module is
declared in [`android-compose/settings.gradle.kts`](android-compose/settings.gradle.kts).

The structure is shared-first: as of October 2026 the two shared modules hold roughly eight times
as much Kotlin as the Android `app` module, and `app/` is a host rather than the product.

| Path | Purpose |
|---|---|
| `sharedLogic/` | KMP domain, repositories, timeline, and transport logic (`commonMain`, `jvmAndAndroid`, host-native and wasmJs targets) |
| `sharedUI/` | KMP Compose UI shared by Android, desktop and browser: shell, chat, agent editor, memory, providers, schedules, canvas, search, settings, A2UI rendering |
| `sharedUI-devfixtures/` | Deterministic UI fixtures for `sharedUI` previews and tests |
| `app/` | Android host: navigation graph, Hilt DI, ViewModels, thin containers around shared pages |
| `desktop/` | Compose Desktop host: entry point, single-instance/OS lock, bundled JBR runtime, installers |
| `web/` | Browser client (Kotlin/WASM, Compose for Web) |
| `feature-chat/` | Chat feature UI, presentation state and coordination (the `AdminChatViewModel` stack) |
| `designsystem/` | Reusable Compose UI, theming, and dialogs |
| `core/android-data/` | Android data bindings, repositories, Room database, data sources |
| `core/runtime/` | Turn engine, runtime event fanout |
| `core/ids/` | Shared identifiers; wire schemas are source packages under `core/schemas/`, not a `:core:schemas` Gradle module |
| `core/testutil/` | Shared test doubles and harnesses |
| `plugin-api/` `plugin-api-testkit/` `plugin-view/` | The Kotlin SPI a JVM-runtime canvas plugin implements (its DTOs are the LCP wire shapes), the conformance test kit for plugin authors, and the WebView host that renders a plugin's UI |
| `avatar/core/` `avatar/renderer-rive/` | Avatar models and Rive rendering |
| `drawbox/` | Vendored canvas editor used by the shared canvas workspace (`drawbox/VENDORED.md`) |
| `cli/` `appserver-cli/` `iroh-wrapper-cli/` | JVM tooling, App Server contract probes, and the Iroh server wrapper that ships as `meridian-iroh-wrapper` |
| `quality/detekt-rules/` `architecture-tests/` | Static-analysis rules and module-boundary tests |
| `macrobenchmark/` `baselineprofile/` `perf/` `native/` | Performance infrastructure and native libraries |
| `build-logic/` | Convention plugins (`com.letta.mobile.android.library` and friends) |

`core/docs/` is documentation, not a module.

The cardinal rule: **portable feature logic lives in `sharedLogic/commonMain`; shared
Compose UI lives in `sharedUI`; platform modules provide host-specific bindings.** The
required `shared-multiplatform` check runs bounded `:sharedLogic:jvmTest`, native
compilation, wrapper/CLI tasks, and browser tests rather than `:sharedLogic:allTests`
or `:desktop:test`. Desktop tests run in `test` when the desktop module changes.

## Transport

The App Server gateway is the only client transport. The former HTTP/REST Letta backend has been
removed, so a client cannot fall back to REST: an App Server change that alters a frame requires a
matched wrapper and client deploy (see Development notes).

## Runnable entry points

| Platform | Command (from `android-compose/`) |
|---|---|
| Android (dev) | `./gradlew :app:assembleRootDebug` — the `root` distribution flavor; `play` and `sideload` also exist (see [`android-compose/README.md`](android-compose/README.md)) |
| Android (release-shaped) | `./gradlew :app:assemblePlayRelease` |
| Compose Desktop | `./gradlew :desktop:run` |
| Phone UI on desktop | `./gradlew :desktop:runPhone` (live backend) / `:desktop:runPhonePlayground` (fixtures); `…Hot` variants add hot reload — see [`phone-preview.md`](docs/development/phone-preview.md) |
| Browser WASM | `./gradlew :web:wasmJsBrowserDevelopmentRun`; CI runs `:web:wasmJsBrowserDistribution` and `:web:wasmJsBrowserTest` (job `web-wasm`) |
| Iroh wrapper (the deployed server) | `./gradlew :iroh-wrapper-cli:installDist` → `build/install/meridian-iroh-wrapper/bin/meridian-iroh-wrapper app-server-serve-iroh …` |
| CLI / probes | `./gradlew :cli:run -PcliArgs="…"`; App Server contract probes via `:appserver-cli` (see [`kmp-phase-6-entrypoints.md`](docs/architecture/kmp-phase-6-entrypoints.md)) |

`:cli` is an Android library module with a custom `run` task, so it has no `installDist`; the
distributable server binary comes from `:iroh-wrapper-cli`. The `app-server-iroh-probe` and
`app-server-iroh-two-client-probe` commands live in `:cli`.

Full notes: [`docs/architecture/kmp-phase-6-entrypoints.md`](docs/architecture/kmp-phase-6-entrypoints.md).

## Further reading

| Topic | Doc |
|---|---|
| Letta's own docs (agents, memory, App Server) | [`docs/reference/letta-docs.md`](docs/reference/letta-docs.md) |
| A2UI renderer and catalog authoring | [`sharedUI/…/ui/a2ui/README.md`](android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/a2ui/README.md) |
| `canvas_compose` contract | [`docs/reference/canvas-compose-v1.md`](docs/reference/canvas-compose-v1.md) |
| Canvas plugin platform | [plan](docs/design/canvas-plugin-platform-plan.md), [LCP wire v1](docs/reference/canvas-plugin-wire-v1.md), [view bridge v1](docs/reference/plugin-view-bridge-v1.md) |
| Live external tools | [`docs/architecture/live-external-tools.md`](docs/architecture/live-external-tools.md) |
| KMP module map | [`docs/architecture/kmp-module-map.md`](docs/architecture/kmp-module-map.md) |
| Releasing | [`docs/RELEASE.md`](docs/RELEASE.md) |

## Quick start

```bash
git clone https://github.com/oculairmedia/letta-mobile.git
cd letta-mobile
./scripts/install-hooks.sh          # activate .githooks/ via core.hooksPath

cd android-compose
cp -f local.properties.example local.properties
# Set JAVA_HOME using the platform-specific instructions in CONTRIBUTING.md.
./gradlew :app:assembleRootDebug    # Android APK (root flavor)
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

- `test` — module unit tests, policy scripts, additive module tests (`:desktop:test`, `:designsystem`, `:feature-chat`, `:cli` when those trees change)
- `build-apk-pass` — Android APK build (stable aggregator of the `build-apk` matrix)
- `shared-multiplatform` — bounded `:sharedLogic:jvmTest` (12 min), host-native and wasmJs compilation, wrapper/CLI verification, and browser tests; desktop tests are additive in `test` when desktop changes
- `perf-gate` — perf-budget benchmarks

Coverage uploads run on `main` via `codecov.yml`, not as a required PR check.

Everything else is **advisory** (cannot block merge), but several of these catch real breakage and
are worth reading before you merge: `detekt`, `row-parity` (legacy vs shared chat rows),
`platform-ui-gate` (platform-UI ratchet), `iroh-probe-gate` (`probe-unit`, `probe-hermetic`,
`probe-red-path`), `architecture-graph`, `agents-policy`, `web-wasm`, `shared-multiplatform-pass`,
`build-iroh-wrapper-pass`, `desktop-installer-pass`, `appserver-contract-baseline` (scheduled),
`qodana`, and CodeScene Code Health Review.

## Releases

Versioning is **tag-driven**. `vX.Y.Z` is the single source of truth for `versionName` and
`versionCode`. CI builds on tag push and creates a GitHub Release. Each release ships a
signed Android APK (embedded runtime included), Windows desktop installers (`.exe` + `.msi`),
and a Linux Iroh wrapper distribution. Pre-release suffix: `v0.2.0-rc.1`. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the full
release flow.

## Development notes

- Issue tracking is **bd** (beads) — `bd ready`, `bd show <id>`, `bd update <id> --claim`.
  Never commit `.beads` artifacts.
- The docs are checked against the tree in the required `test` job:
  [`scripts/ci/docs-freshness-check.py`](scripts/ci/docs-freshness-check.py) fails when a Gradle
  module is missing from the module tables, a Gradle task path names no module, the required-gate
  list here differs from `scripts/bootstrap-branch-protection.sh`, a relative link breaks, or a
  quoted Gradle version differs from the wrapper. Run it locally before pushing doc changes.
- The pre-push hook blocks pushes to `main`/`master` and checks `JAVA_HOME`. Kotlin compile is **opt-in** (`PRE_PUSH_COMPILE=1`) — off by default so cold Gradle configure cannot OOM the shared host; CI owns the compile gate.
- Anything touching `sharedLogic` **App Server transport** requires a matched wrapper + APK deploy.
  There is no REST fallback to catch a mismatched pair.
- `main` moves by **hard cuts**: a shared page lands and the Android screen it replaces is deleted in
  the same PR, guarded by parity harnesses (`row-parity`) and the `platform-ui-gate` ratchet. When a
  page seems to be missing from `app/`, look for it in `sharedUI/` first.
