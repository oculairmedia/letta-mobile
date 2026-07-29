# Letta Mobile

Native Android + Compose Desktop client for [Letta](https://github.com/letta-ai/letta), built as a Kotlin Multiplatform project with most logic in a single platform-neutral module that both apps consume.

The primary production transport is **Iroh QUIC** (`iroh://<nodeId>@…`), with a WebSocket App Server (`@letta-ai/letta-code@0.29.9`) reached over a single bidirectional `/ws` connection. Plain HTTPS to `https://…/v1/…` remains supported as a fallback.

<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/oculairmedia/letta-mobile/releases" target="_blank"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="48"></a>

## What this repo ships

| Surface | What it does |
|---|---|
| **Android app** (`com.letta.mobile` / `.dev`) | Compose UI, Material 3, Lucide icons, A2UI renderer, Hilt DI, Room database |
| **Compose Desktop** | Same `sharedLogic` domain, Skiko rendering, OS single-instance lock, packaged CLI |
| **sharedLogic (KMP)** | Platform-neutral domain, transport, timeline, reducers, repositories — Android, JVM/desktop, host-native |
| **Iroh QUIC transport** | Per-agent Ed25519 identity + EndpointAddr resolution, a2a ALPN `/letta/a2a/0`, message envelope + ack |
| **App Server transport** | One-socket WebSocket client to `letta-code@0.29.9` (`/ws`), `RuntimeEventFanout` inbound router |
| **appserver-cli / cli** | JVM tooling for contract probes, restart/replay evidence, message-list paging, shim-off parity gates |

## What the apps include

- **Agent management** — create, edit, clone, import, delete, organize agents (Android + desktop)
- **Rich chat** — tool outputs, code blocks, reasoning display, mermaid (native renderer), shared transitions, real-time assistant work timeline (Aether)
- **Interactive tools** — `AskUserQuestion` and `ExitPlanMode` surface to the user with full submit/approve wiring
- **Memory administration** — core memory, archival passages, block library, archive attachment flows
- **Admin tooling** — models (LLMux catalog + Grok/MiniMax limits), providers, tools, MCP servers, identities, folders, groups, schedules, jobs, runs
- **Iroh live sync** — multi-client conversation sync, paired-device paired-conversation authz, agent-to-agent messaging
- **Sliding-window timeline eviction** — bounded memory for long conversations

## Module layout

The Gradle build lives in [`android-compose/`](android-compose/).

| Path | Purpose |
|---|---|
| `android-compose/app/` | Android app module: screens, navigation, ViewModels, Hilt DI |
| `android-compose/feature-chat/` | Chat-domain feature module — screens, presenters, request shaping |
| `android-compose/feature-editagent/` | Agent editor feature module |
| `android-compose/core/data/` | Repositories, Room database, transport-bound data sources |
| `android-compose/core/domain/` | Domain models, repository interfaces, business rules |
| `android-compose/core/runtime/` | App Server turn engine, runtime event fanout, recovery |
| `android-compose/core/ids/` `core/schemas/` | Shared identifiers and wire-shape schemas |
| `android-compose/designsystem/` | Reusable Compose UI, theming, A2UI renderer, LettaIcons |
| `android-compose/sharedLogic/` | **Platform-neutral KMP module.** Domain logic, transport, timeline reducers, IPC; consumed by every host |
| `android-compose/desktop/` | Compose Desktop entry point, Ktor engine, OS lock, installer |
| `android-compose/cli/` `appserver-cli/` | JVM command-line tooling and contract probes |
| `android-compose/architecture-tests/` | Module-boundary + shim-off parity gates |
| `android-compose/native/` `perf/` `macrobenchmark/` `baselineprofile/` | Native libs (mermaid renderer), perf infra |

The cardinal rule: **feature logic lives in `sharedLogic/commonMain`; platform modules only bind.** Anything duplicated across `app/` and `desktop/` is a drift hazard — the `shared-multiplatform` required CI check (`sharedLogic:allTests` + `desktop:test`) backstops this.

## Quick start

```bash
git clone https://github.com/oculairmedia/letta-mobile.git
cd letta-mobile
./scripts/install-hooks.sh          # activate .githooks/ via core.hooksPath

cd android-compose
cp local.properties.example local.properties
export JAVA_HOME="/usr/lib/jvm/jdk-26"
./gradlew :app:assembleDebug        # Android APK
./gradlew :desktop:run              # Compose Desktop (needs JDK 26 + a display)
```

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the full setup (Linux/macOS/Windows paths, Kotlin LSP, Node + letta-code for App Server contract work).

## Pre-push PR readiness

CI takes ~20 minutes per iteration. The most common reasons PRs loop through 2-4 review iterations are scope/stale-base rollbacks, mechanical Qodana debt, and missing concurrent-collection defaults — all of which are catchable at dev cost in seconds. **Run the checklist in [`AGENTS.md`](AGENTS.md) before opening a PR.** It covers scope audit, sensitive-path grep, stale-base detection, title/scope match, local required gates, mechanical-debt preflight, and the concurrent-collection defaults that sharedLogic transport code is expected to apply on first commit.

## Required CI gates

`main` is protected; PRs must pass all of:

- `test` — module unit tests, policy scripts
- `build-apk-pass` — Android APK build (stable aggregator of the `build-apk` matrix)
- `shared-multiplatform` — `:sharedLogic:allTests` + `:desktop:test`
- `perf-gate` — perf-budget benchmarks
- `codecov` — coverage upload (newly required; repo registered via OIDC)

**Advisory only** (cannot block merge): `qodana`, `detekt`, CodeScene Code Health Review, `Advisory AGENTS.md policy`. See `AGENTS.md` for the rationale.

## Transport at a glance

| Surface | Protocol | Where |
|---|---|---|
| Agent-to-agent messaging | Iroh QUIC, a2a ALPN `/letta/a2a/0` | `sharedLogic` iroh transport |
| Conversation sync (mobile/desktop paired) | Iroh QUIC | `IrohChannelTransport` |
| App Server turn protocol | One-socket WebSocket to `/ws` | `KtorAppServerWebSocketTransport` |
| Legacy HTTP | HTTPS to `<base>/v1/…` | `LettaHttpChatGateway` (fallback) |

The App Server is `@letta-ai/letta-code@0.29.9`. Two-socket `?channel=control|stream` upgrades are rejected with HTTP 426 — only the one-socket path is supported on `main`.

## Releases

Versioning is **tag-driven**. `vX.Y.Z` is the single source of truth for `versionName` and `versionCode`. CI builds the signed `play-release` APK on tag push and creates a GitHub Release with auto-generated notes. Pre-release suffix: `v0.2.0-rc.1`. See `CONTRIBUTING.md` for the full release flow and mistake-recovery steps.

## Development notes

- Issue tracking is **bd** (beads) — `bd ready`, `bd show <id>`, `bd update <id> --claim`. Never commit `.beads` artifacts.
- The pre-push hook compiles `:app:compileRootDebugKotlin` from `android-compose/`.
- For App Server contract work the canonical toolchain is Node `v24.18.0` + `@letta-ai/letta-code@0.29.9`; see the App Server section of `AGENTS.md` for the exact verifier command.
- Anything that touches `sharedLogic` transport code (appserver, iroh) requires a matched wrapper + APK deploy — these are independent processes running different snapshots of the same Kotlin source.