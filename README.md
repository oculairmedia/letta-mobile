# Letta Mobile

Android + Compose Desktop client for [Letta AI](https://github.com/letta-ai/letta) — manage agents,
chat with them, review their work, and sync across your devices. Built as a Kotlin Multiplatform
project. One shared domain module powers both apps; platform layers are thin bindings.

<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/oculairmedia/letta-mobile/releases" target="_blank"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="48"></a>

## Features

- **Agent management** — create, edit, clone, import, delete, and organize agents (Android + desktop)
- **Rich chat** — tool outputs, code blocks, reasoning display, mermaid diagrams (native renderer), and a real-time assistant work timeline
- **Interactive tools** — `AskUserQuestion` and `ExitPlanMode` surface to the user with full submit/approve wiring
- **Memory administration** — core memory, archival passages, block library, archive attachment flows
- **Admin tooling** — models, providers, tools, MCP servers, identities, folders, groups, schedules, jobs, runs
- **Live sync** — multi-client conversation sync between mobile and desktop, paired-device authz, agent-to-agent messaging

## Module layout

Everything under [`android-compose/`](android-compose/).

| Path | Purpose |
|---|---|
| `app/` | Android app: screens, navigation, ViewModels, Hilt DI |
| `feature-chat/` | Chat screens, presenters, request shaping |
| `feature-editagent/` | Agent editor |
| `core/data/` | Repositories, Room database, data sources |
| `core/domain/` | Domain models, repository interfaces, business rules |
| `core/runtime/` | Turn engine, runtime event fanout |
| `core/ids/` `core/schemas/` | Shared identifiers and wire schemas |
| `designsystem/` | Reusable Compose UI, theming, A2UI renderer |
| `sharedLogic/` | **Platform-neutral KMP module.** Domain logic, transport, timeline, IPC — consumed by every host |
| `desktop/` | Compose Desktop entry point, OS lock, installer |
| `cli/` `appserver-cli/` | JVM command-line tooling, contract probes |
| `architecture-tests/` | Module-boundary and parity gates |
| `native/` `perf/` `macrobenchmark/` `baselineprofile/` | Native libs (mermaid renderer), perf infra |

The cardinal rule: **feature logic lives in `sharedLogic/commonMain`; platform modules only bind.**
Any behavior duplicated across `app/` and `desktop/` is drift — the `shared-multiplatform` required
CI check (`sharedLogic:allTests` + `desktop:test`) backstops this.

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
- `shared-multiplatform` — `:sharedLogic:allTests` + `:desktop:test`
- `perf-gate` — perf-budget benchmarks
- `codecov` — coverage upload

**Advisory only** (cannot block merge): `detekt`, `qodana`, CodeScene Code Health Review, `Advisory AGENTS.md policy`.

## Releases

Versioning is **tag-driven**. `vX.Y.Z` is the single source of truth for `versionName` and
`versionCode`. CI builds the signed `play-release` APK on tag push and creates a GitHub
Release. Pre-release suffix: `v0.2.0-rc.1`. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for
the full release flow.

## Development notes

- Issue tracking is **bd** (beads) — `bd ready`, `bd show <id>`, `bd update <id> --claim`.
  Never commit `.beads` artifacts.
- The pre-push hook compiles `:app:compileRootDebugKotlin` from `android-compose/`.
- Anything touching `sharedLogic` transport code requires a matched wrapper + APK deploy.
