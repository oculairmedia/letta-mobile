# Letta Mobile — Kotlin Multiplatform

## Project Overview

Letta Mobile is a Kotlin Multiplatform project that ships **two clients** from one `sharedLogic` codebase:

- **Android app** (`com.letta.mobile` / `.dev`) — Compose UI, Hilt, Room, Material 3, A2UI renderer.
- **Compose Desktop** — same `sharedLogic`, Skiko rendering, packaged CLI.

The primary production transport is **Iroh QUIC** (`iroh://<nodeId>@…`). The WebSocket App Server (`@letta-ai/letta-code@0.29.12`) is reached over a single bidirectional `/ws` connection.

**Location:** `/opt/stacks/letta-mobile`
**Build root:** `android-compose/` (the Gradle workspace)
**GitHub:** `oculairmedia/letta-mobile`

**Tech stack:**
- Kotlin / Jetpack Compose / Compose Desktop
- Kotlin Multiplatform — `sharedLogic` consumed by Android, JVM/desktop, host-native
- Hilt (Android DI), Room (Android persistence), Ktor (HTTP + WebSocket transports)
- Iroh QUIC (`computer.iroh:iroh:1.0.0`) for P2P transport
- Material 3 + Lucide icons + A2UI renderer
- Gradle 9.4.1, JDK 17/21/26 (CI uses JDK 26; detekt requires JDK 21)

---

## How to Build

```bash
cd android-compose
cp -f local.properties.example local.properties
export JAVA_HOME="/usr/lib/jvm/jdk-26"      # CI parity
./gradlew :app:assembleDebug                # Android APK
./gradlew :desktop:run                      # Compose Desktop (needs a display; on a headless VM use DISPLAY=:1 and SOFTWARE rendering)
```

For App Server contract work, also provision Node `v24.18.0` and `@letta-ai/letta-code@0.29.12` (see the App Server section of `AGENTS.md`).

---

## Module map

| Module | Purpose |
|---|---|
| `app/` | Android app — screens, navigation, ViewModels, Hilt DI |
| `feature-chat/` `feature-editagent/` | Compose feature modules |
| `core/data/` | Repositories, Room DB, transport-bound data sources |
| `core/domain/` | Domain models, repository interfaces, business rules |
| `core/runtime/` | App Server runtime contracts, turn lifecycle interfaces |
| `core/ids/` `core/schemas/` | Shared identifiers and wire-shape schemas |
| `designsystem/` | Reusable Compose UI, theming, A2UI renderer, LettaIcons |
| `sharedLogic/` | **Platform-neutral KMP module** — domain, turn engine, fanout, transport, timeline, IPC |
| `desktop/` | Compose Desktop — windowing, Ktor engine, OS lock, installer |
| `cli/` `appserver-cli/` | JVM tooling for probes, restart-replay evidence, shim-off parity gates |

**Important directories:**
- `docs/reference/letta-docs.md` — **how to look up Letta's own docs** (index at `docs.letta.com/llms.txt`, canonical markdown at any page + `/index.md`, plus a page map). Read Letta docs instead of guessing at agent/memory/App-Server semantics; they are fetched on demand, not vendored.
- `designsystem/src/main/java/com/letta/mobile/ui/a2ui/README.md` — A2UI renderer and catalog authoring guide (read before touching A2UI payloads, catalog IDs, or renderer dispatch)
- `app/src/main/java/com/letta/mobile/ui/screens/` — Android screen composables
- `sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/transport/` — Iroh + App Server transports (platform-neutral)
- `sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/` — App Server turn engine (`AppServerTurnEngine.kt`), `RuntimeEventFanout`, and transports
- `appserver-cli/` — JVM probe toolchain for App Server contract verification

**The cardinal rule:** feature logic goes in `sharedLogic/commonMain`. Platform modules (`app/`, `desktop/`) only bind. Duplicating repository logic across `app/` and `desktop/` is the documented anti-pattern — the `shared-multiplatform` required CI gate backstops this.

---

## Recommended Build Checks

Run before pushing. The pre-push hook covers the first command; the rest is on you.

```bash
export JAVA_HOME=/usr/lib/jvm/jdk-26
cd android-compose
./gradlew --no-daemon :app:compileRootDebugKotlin
./gradlew --no-daemon :app:testRootDebugUnitTest

# Required if you touched sharedLogic/ (KMP common code) — also enforced by CI:
./gradlew --no-daemon :sharedLogic:allTests :desktop:test

# Mechanical-debt preflight (advisory; do not lower thresholds):
./gradlew --no-daemon :app:detekt    # must run on JDK 21
```

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