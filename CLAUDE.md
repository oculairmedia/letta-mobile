# Letta Mobile — Kotlin Multiplatform

## Project Overview

Letta Mobile is a Kotlin Multiplatform project that ships **three clients** from one `sharedLogic` codebase:

- **Android app** (`com.letta.mobile` / `.dev`) — Compose UI, Hilt, Room, Material 3, A2UI renderer.
- **Compose Desktop** — same `sharedLogic`, Skiko rendering, packaged CLI.
- **Browser WASM** — shared domain and Compose UI, with browser host bindings.

The primary production transport is **Iroh QUIC** (`iroh://<nodeId>@…`). The WebSocket App Server is reached over a single bidirectional `/ws` connection. There is ONE letta-code pin, `@letta-ai/letta-code@0.34.14`, in `android-compose/letta-code-runtime/letta-code-runtime.properties` (= wire contract = desktop bundled runtime = Android embedded runtime = `appserver-contract-baseline` CI job); the embedded runtime ships in the `full` release APK (nodejs-mobile Node 18, arm64-v8a only, a loopback App Server the app dials over ws:// through the same channel as an Iroh host, letta-mobile-by4vm.110.6; the `lite` APK has no runtime, see `docs/RELEASE.md`); the Linux host still runs 0.33.6 until redeployed; restart-replay evidence was re-captured at 0.34.14 on 2026-10-17. All pins are tabulated in `sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/transport/appserver/README.md`.

**Location:** `/opt/stacks/letta-mobile`
**Build root:** `android-compose/` (the Gradle workspace)
**GitHub:** `oculairmedia/letta-mobile`

**Tech stack:**
- Kotlin / Jetpack Compose / Compose Desktop
- Kotlin Multiplatform — `sharedLogic` consumed by Android, JVM/desktop, host-native
- Hilt (Android DI), Room (Android persistence), Ktor (HTTP + WebSocket transports)
- Iroh QUIC (`computer.iroh:iroh:1.1.0`, pinned in `gradle/libs.versions.toml`) for P2P transport
- Material 3 + Lucide icons + A2UI renderer
- Gradle 9.6.1 (wrapper), JDK 17/21/26 (CI uses JDK 26; detekt requires JDK 21)

---

## How to Build

```bash
cd android-compose
cp -f local.properties.example local.properties
export JAVA_HOME="/usr/lib/jvm/jdk-26"      # CI parity
./gradlew :app:assembleDebug                # Android APK
./gradlew :desktop:run                      # Compose Desktop (needs a display; on a headless VM use DISPLAY=:1 and SOFTWARE rendering)
```

**Build logic** (all under `android-compose/`):
- `gradle/libs.versions.toml` is the only place a dependency or plugin version lives — one version per library for every module (no per-module `-chat`-style forks; when versions disagree, use what `:app` resolves). The root `build.gradle.kts` applies plugins by `alias(libs.plugins.…)`.
- `build-logic/` holds the convention plugins: `com.letta.mobile.android.library` (compileSdk/minSdk from the catalog, Java + Kotlin JVM 17 — modules applying it must not redeclare them), `com.letta.mobile.architecture-graph`, and `com.letta.mobile.embedded-runtime`.
- The embedded letta-code runtime pipeline (npm install, nodejs-mobile libnode, NDK helpers, letta.js patches) is `com.letta.mobile.embedded-runtime`; its pins are `letta-code-runtime/letta-code-runtime.properties` (the one letta-code pin, also read by the desktop build), its letta.js patches `letta-code-runtime/patches/*.mjs` (one per patch, rationale in each header), and its shipped preloads `letta-code-runtime/runtime/` (`node18-compat.cjs` first). It only runs with `-PembedLettaCodeNative`/`-PembedLettaCodeAssets` (or the same keys in `local.properties`), which is also the `full`/`lite` release variant switch. Bump `assetRevision` when a patch or polyfill changes.

To iterate on the phone UI from a desktop (`:desktop:runPhone`, `:desktop:runPhonePlayground`, hot reload), see `docs/development/phone-preview.md`.

For App Server contract work, also provision Node `v24.18.0` and the contract `@letta-ai/letta-code@0.34.14`; restart-replay evidence was re-captured at 0.34.14 on 2026-10-17 (see the App Server section of `AGENTS.md`). The embedded runtime uses the same package pin; platform capability and Node 18 compatibility still require embedded-runtime validation.

---

## Module map

Module and source paths below are relative to `android-compose/` unless prefixed with `docs/`.

| Module | Purpose |
|---|---|
| `app/` | Android host — navigation, ViewModels, Hilt DI and containers around shared pages |
| `feature-chat/` | Android host of the shared chat page — ViewModels, Hilt bindings and platform glue around the `sharedUI` chat surface |
| `core/android-data/` | Repositories, Room DB, transport-bound data sources |
| `core/runtime/` | App Server runtime contracts, turn lifecycle interfaces |
| `core/ids/` | Shared identifiers; wire schemas live in source packages |
| `designsystem/` | Reusable Android Compose UI, theming, and dialogs |
| `sharedLogic/` | **KMP shared module** — platform-neutral domain, transports, timeline, repositories and runtime |
| `sharedUI/` | **KMP Compose UI** — the shared Compose UI for Android, Desktop and web: pages, chat surface, A2UI renderer, LettaIcons, primitives |
| `desktop/` | Compose Desktop — windowing, Ktor engine, OS lock, installer |
| `web/` | Browser WASM host and browser API bindings |
| `cli/` `appserver-cli/` `iroh-wrapper-cli/` | JVM tooling, contract probes and the production Iroh server wrapper |

**Documentation:** start at [docs/README.md](docs/README.md) for ownership, status conventions and historical material.

**Important directories:**
- `docs/reference/letta-docs.md` — **how to look up Letta's own docs** (index at `docs.letta.com/llms.txt`, canonical markdown at any page + `/index.md`, plus a page map). Read Letta docs instead of guessing at agent/memory/App-Server semantics; they are fetched on demand, not vendored.
- `sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/a2ui/README.md` — shared Android/Desktop A2UI renderer and catalog authoring guide (read before touching A2UI payloads, catalog IDs, or renderer dispatch)
- `app/src/main/java/com/letta/mobile/ui/screens/` — Android screen composables
- `sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/transport/` — Iroh + App Server transports (platform-neutral)
- `sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/runtime/AppServerTurnEngine.kt` — App Server turn engine; `RuntimeEventFanout` is in `data/controller/fanout/`
- `appserver-cli/` — JVM probe toolchain for App Server contract verification

**The cardinal rule:** feature logic goes in `sharedLogic/commonMain`. Platform modules (`app/`, `desktop/`) only bind. Duplicating repository logic across `app/` and `desktop/` is the documented anti-pattern — the `shared-multiplatform` required CI gate backstops this.

**All UI is shared (`sharedUI`); platform modules are containers** (hard cut `letta-mobile-ckmub`, gate `letta-mobile-ckmub.7`; advisory now, required from slice S20). `desktop/`, `app/`, `feature-*`, `web/` may contain only window/activity hosts, nav hosts that map routes to `sharedUI` pages, the platform's `LettaHost` container (popover window vs bottom sheet), DI, and platform services (renderers, camera, clipboard, file access, tray, installer). New screens, rows, dialogs, pickers, sheets, headers and settings cards go in `sharedUI/commonMain` and compose the shared primitives (`LettaPageScaffold`, `LettaHost`/`LettaPane`, `ShellHeader`, `RosterList`/`ListRow`, `LettaPicker`, `InspectorPane`, `SettingsSection`, `OverlayHost`, `SharedDocument`); platform rendering differences go in a `sharedUI` expect/actual or a `LettaHost` binding. `scripts/ci/platform-ui-gate.sh` (CI job `platform-ui-gate`) fails a new `@Composable` file under those trees unless it is in `scripts/ci/platform-ui-allowlist.txt` with a `Platform-UI-Reason:` line in the PR body, and fails an allowlisted legacy file that grows past its recorded line count (+5). Copying a surface instead of composing the primitives is a review blocker. Android pages are edge-to-edge: `LettaPageScaffold` owns insets; pages never call `systemBars`/`statusBars`/`navigationBars`/`ime` padding themselves. Full rule text: `AGENTS.md` → Module map.

---

## Recommended Build Checks

Run before pushing when you have the resources. The pre-push hook does **not** compile by default (set `PRE_PUSH_COMPILE=1` to opt in); CI owns the compile gate.

```bash
export JAVA_HOME=/usr/lib/jvm/jdk-26
cd android-compose
./gradlew --no-daemon :app:compileRootDebugKotlin
./gradlew --no-daemon :app:testRootDebugUnitTest

# If you touched sharedLogic/ (KMP common code). What CI actually runs (android.yml):
#   `shared-multiplatform` — :sharedLogic:jvmTest, then only *compiles* hostNative
#     and wasmJs (:sharedLogic:allTests is NOT run: hostNativeTest hangs).
#   `test` — :app:testRootDebugUnitTest, :core:android-data:testDebugUnitTest, and on
#     PRs :sharedUI:jvmTest / :desktop:test only when their own trees changed
#     (scripts/ci/changed-gradle-modules.sh). Every main push runs both anyway
#     (`main module tests`), so a skipped local run shows up red on main.
./gradlew --no-daemon :sharedLogic:jvmTest :sharedUI:jvmTest :desktop:test

# detekt — JDK 21. Same as the CI `detekt` job: findings already in a module's
# detekt-baseline.xml are ignored, anything new fails. Do not lower thresholds.
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew --no-daemon detekt -Parchitecture.strict=true
```

**CI at a glance** (full table: "CI map" in `AGENTS.md`):
- **Required** (branch protection): `test`, `build-apk-pass` (play-release builds on main pushes only), `shared-multiplatform`.
- **Nightly + main, not a PR gate:** `perf-gate` (emulator startup macrobenchmark, one measurement; on a breach the job summary shows the last 5 main runs as a control and a human decides whether to rerun — no automatic retry; a red nightly/main run opens or updates the `perf-gate regression` issue). PRs run only the cheap `perf-scripts` self-test unless they edit the perf gate or use `[perf:rebaseline]`.
- **Not required, but honest** (red = a new problem in your diff): `detekt` (baselined), `Platform UI gate` (`--enforce`: a new platform `@Composable` file fails), `main module tests` (main pushes only: `:sharedUI:jvmTest :desktop:test`), `embedded-runtime-node18` (a letta-code pin bump or staging change must boot the staged tree on Node 18.20.4 headless and as an App Server, within the 28 MiB gzip budget).
- **Advisory** (never fail): `Advisory AGENTS.md policy`, `row goldens (advisory)`, `Export architecture graph`; CodeScene is external and still expected green.
- **Not run in CI:** `:sharedLogic:allTests`, the `app/src/androidTest` device tests. Qodana was removed (its license token had been declined since 2026-09).

Before pushing, also run the **pre-push PR readiness checklist** in `AGENTS.md` — it covers scope audit, sensitive-path grep, stale-base detection, and the concurrent-collection defaults that sharedLogic transport code is expected to apply on first commit.

---

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find next bead to work on
bd show <id>          # View bead details
bd update <id> --claim  # Claim a bead
bd close <id>         # Close a bead
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until the PR is open and CI is running (or merged).

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** — Create beads issues for anything that needs follow-up.
2. **Run quality gates locally** (if code changed):
   ```bash
   cd android-compose
   ./gradlew --no-daemon :app:compileRootDebugKotlin
   ./gradlew --no-daemon :app:testRootDebugUnitTest
   ```
3. **Update issue status** — Close finished work, update in-progress items.
4. **Sync beads**: `bd dolt push`
5. **PUSH THE BRANCH AND OPEN A PR** — never push to `main` directly:
   ```bash
   git fetch && git rebase origin/main
   git push -u origin <your-branch>           # or --force-with-lease if rebased
   gh pr create                               # if no PR exists yet
   gh pr checks <PR#>                         # confirm CI is queued/running
   git status                                 # MUST show "up to date with origin/<your-branch>"
   ```
6. **Clean up** — Clear stashes, delete merged local branches.
7. **Verify** — All changes committed AND pushed to the feature branch AND a PR exists.
8. **Hand off** — Add a `bd note` to the active/next bead with a concise bootstrap handoff.

**CRITICAL RULES:**
- Work is NOT complete until the feature branch is pushed AND a PR exists.
- NEVER `git push origin main` — the pre-push hook will reject it; branch protection on the remote will too.
- NEVER stop before pushing — that leaves work stranded locally.
- NEVER say "ready to push when you are" — YOU must push the branch and open the PR.
- If push fails on a feature branch, resolve (rebase / fix hook failure / set `JAVA_HOME`) and retry until it succeeds.
- Don't merge your own PR until CI is green. Squash-merge only.
<!-- END BEADS INTEGRATION -->
