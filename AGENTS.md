# Agent Instructions

This project uses **bd** (beads) for issue tracking. Run `bd prime` for full workflow context.

**Letta behavior questions:** don't guess — look them up. `docs.letta.com` publishes
a machine-readable index at `https://docs.letta.com/llms.txt`, and every page has
canonical markdown at `<page>/index.md`. See
[docs/reference/letta-docs.md](docs/reference/letta-docs.md) for the page map
(App Server protocol, MemFS, memory blocks, skills, schedules, channels). Docs are
fetched on demand, not vendored — they'd go stale in-tree.

## Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work atomically
bd close <id>         # Complete work
bd dolt push          # Push beads data to remote
```

## Repository Workflow

**`main` is protected — direct pushes are rejected by both local hooks and a GitHub branch-protection rule.** Full reference: [CONTRIBUTING.md](CONTRIBUTING.md).

Every change goes branch → PR → CI passes → squash-merge. The loop:

```bash
git checkout main && git pull               # start from fresh main
git checkout -b feat/your-change            # branch first, ALWAYS
# ...edit, commit normally...
git push -u origin feat/your-change         # push the branch (NOT main)
gh pr create                                # open PR with the template

# If main moved while you worked:
git fetch && git rebase origin/main         # REBASE, never merge
git push --force-with-lease                 # safe force-push to your branch

# When CI is green:
# Squash-merge via GitHub UI. Branch auto-deletes.
```

**Hard rules for agents:**

- **Never commit on `main` / `master`.** The pre-commit hook in `.githooks/pre-commit` will refuse. Bypassing with `--no-verify` defeats the purpose — don't.
- **Never push to `origin main`.** The pre-push hook refuses and branch protection on the remote would reject it anyway.
- **Never merge `main` into a feature branch.** Always `git rebase origin/main`. Merging produces phantom-conflict commit chains (same content, different SHAs) that wedge the next merge to `main`. Two `Merge branch 'main' into <branch>` commits in a PR's history is a strong signal that this rule was broken.
- **CI gates merges, not pushes.** Required status checks on `main` (branch protection, asserted by `scripts/tests/ci-policy-scripts-test.sh`): `test`, `build-apk-pass`, and `shared-multiplatform`. All must be green before squash-merge. `perf-gate` (the emulator macrobenchmark) is **not** a PR gate: it runs nightly and on main pushes. (`build-apk` is a matrix job; branch protection requires the stable aggregator `build-apk-pass`.) The CI map below is exactly what runs where.
- **Run the pre-push checklist before opening the PR.** See "Pre-push PR readiness checklist" below. Skipping it is the documented root cause of every multi-iteration review loop in the last wave.

### CI map (what runs where)

**Required** (red blocks merge):

| Check | Workflow | Runs on | What it actually runs |
|---|---|---|---|
| `test` | `android.yml` | PRs + main pushes touching the workflow's paths | CI policy script tests, stateful-mock gate, then one Gradle graph: `:app:compileSideloadDebugKotlin :core:android-data:testDebugUnitTest :app:testRootDebugUnitTest :app:testScreenshot :build-logic:test checkArchitectureBoundaries :architecture-tests:test :architecture-tests:architectureTest :quality:detekt-rules:check` (the last includes `guardrailDetekt`, blocking, on changed `.kt` files). **PRs only**, path-filtered additive tasks from `scripts/ci/changed-gradle-modules.sh` (`:feature-chat`, `:designsystem`, `:sharedUI:jvmTest`, `:desktop:test`, `:cli`, `:plugin-*`, … only when that module's own tree changed). |
| `shared-multiplatform` | `android.yml` | same | `:sharedLogic:jvmTest` (12 min bound), `:appserver-cli:test`, `:iroh-wrapper-cli:test`, then **compiles only** for hostNative and wasmJs (`:sharedLogic`, `:sharedUI`) plus a Node unit test of the karma reporter config (`sharedLogic/karma.config.test.cjs`); browser tests run only in `web-wasm`. Not `:sharedLogic:allTests` (hostNativeTest hangs) and not `:desktop:test`. |
| `build-apk-pass` | `android.yml` | same | `:app:assembleRootDebug`. `:app:assemblePlayRelease` + APK size/R8 validation run on **main pushes only**; on PRs that matrix leg skips its build but still reports success. |

**Not required, but honest** (they go red on a real problem; fix it, don't wave it through):

| Check | Workflow | Runs on | What it runs |
|---|---|---|---|
| `perf-gate` | `android-perf.yml` | **nightly (07:00 UTC) + main pushes** (+ dispatch); on a PR only when it edits `android-perf.yml`, `android-compose/perf/**` or `android-compose/macrobenchmark/**`, or has `[perf:rebaseline]` in its title | **one** cold/warm startup macrobenchmark on the API 33 emulator (~30 min). No automatic rerun: on a breach the job summary shows the last 5 main runs as a control and a human decides (`docs/performance/perf-gate.md`). A red nightly/main run opens or comments on the single `perf-gate regression` issue. |
| `perf-scripts` | `android-perf.yml` | every PR + main pushes + nightly | perf script unit tests and the Baseline Profile freshness check (warns, never fails); ~1 min, no emulator. |
| `detekt` | `android.yml` | after the required jobs | `./gradlew detekt -Parchitecture.strict=true` against each module's `detekt-baseline.xml`: only findings not already in the baseline fail. Covers `app`, `core:android-data`, `designsystem`, `feature-chat`, `quality:detekt-rules`, and every `src/<sourceSet>/kotlin` dir of `sharedLogic`, `sharedUI`, `plugin-view`, `desktop`, `web` (by4vm.65). |
| `Platform UI gate` | `agents-policy.yml` | PRs into `main` | `platform-ui-gate.sh --enforce`: PUI001 (new platform `@Composable` file) and PUI004 (allowlist add/raise without `Platform-UI-Reason:`) fail; PUI002 growth warns. |
| `main module tests (sharedUI jvm + desktop)` | `android.yml` | **main pushes only** (+ dispatch) | `:sharedUI:jvmTest :desktop:test` unconditionally, so main is never silently broken by a PR whose diff skipped them. |
| `embedded-runtime-node18` | `embedded-runtime-node18.yml` | PRs touching `letta-code-runtime/`, the embedded staging task, `scripts/embedded-runtime/`, `appserver-cli/` or the `LocalAppServerHost` package; every main push | stages the APK's letta-code tree with `:app:prepareEmbeddedLettaCodeAssets`, enforces the 28 MiB gzip budget, then boots it on Node 18.20.4 (the device's nodejs-mobile) behind an android-platform emulation preload: a headless Bash turn and a `LocalAppServerHost` + `app-server-smoke` turn against a fake LLM. A red run names the first missing API (`docs/architecture/embed-letta-code-integration.md`). ~3 min (Gradle staging ~1.5 min, the Node 18 run ~10 s). |
| `windows-installer` / `desktop-installer-pass`, `iroh-probe-gate`, `appserver-contract-baseline`, `web-wasm`, `build-iroh-wrapper-pass` | `desktop.yml`, `iroh-probe.yml`, `appserver-contract.yml`, `android.yml` | path-filtered PRs / pushes | their own builds and probes; not in branch protection. |

**Advisory** (findings never fail the job): `Advisory AGENTS.md policy` (`agents-policy-check.sh`, `continue-on-error`), `row goldens (advisory)` (`:feature-chat:rowGoldenReport`, `continue-on-error`), `Export architecture graph` (`continue-on-error`), CodeScene Code Health Review (external; still expected green before merge, see `cs delta`). `codecov` runs on main pushes only.

**Runs nowhere in CI:** `:sharedLogic:allTests` / hostNativeTest, and the 7 files under `app/src/androidTest` (device tests: mascot still, LiteRT-LM engine, embedded-runtime loop, image request capture, live Iroh receive, Automerge JNI). Run those by hand on a device when you touch their area.

## Module map (where work belongs)

| Area | Module path | Put logic here | Host binding only |
| --- | --- | --- | --- |
| Shared domain / transport / timeline | `android-compose/sharedLogic/` | repositories, reducers, mappers, RPC, caching | — |
| Shared Compose UI (Android + Desktop) | `android-compose/sharedUI/` | shared composables, A2UI renderer, markdown/bubbles (after Phase 3b) | — |
| Android UI / navigation | `android-compose/app/`, `feature-*` | Compose screens, ViewModels that call shared APIs | Activity, Hilt, permissions |
| Design system / A2UI | `android-compose/designsystem/`, `android-compose/sharedUI/` | reusable components/theme; shared Android/Desktop A2UI renderer | — |
| Desktop shell | `android-compose/desktop/` | — | windowing, installer, Ktor engine, OS lock |
| CLI / probes | `android-compose/cli/`, `appserver-cli/` | JVM tooling | — |

Local quality: run `bash scripts/tests/ci-policy-scripts-test.sh`, then `bash scripts/ci/agents-policy-check.sh` (full) or `bash scripts/ci/agents-policy-check.sh --diff-base origin/main` (PR-shaped). Run detekt from `android-compose/` with Java 21 as documented below. The policy scripts are Bash-only; their regression runner executes in the required `test` CI job.

- **`sharedLogic` commonMain/commonTest must stay platform-neutral.** No JVM-only APIs (`String.format`, `StringBuilder.delete(start, end)`, `String.toByteArray()`, …) — the code must compile for every configured KMP target (Android, JVM/desktop, host-native for Windows). The `shared-multiplatform` required check (`:sharedLogic:jvmTest` plus hostNative and wasmJs *compiles* — not `allTests`, whose hostNativeTest hangs, and not `:desktop:test`, which runs in `test` only when `desktop/` changes and on every main push in `main module tests`) enforces this; see bead letta-mobile-kx1r3 for the leaks that motivated it.
- **First-time setup in a fresh clone:** run `./scripts/install-hooks.sh` to activate the local hooks via `core.hooksPath`.
  - **Hooks note (letta-mobile-by4vm.34):** `bd init` points `core.hooksPath` at `.beads/hooks/`, which silently disabled `.githooks/` — the main-branch commit/push guards above were not running in clones set up that way. `.beads/hooks/pre-commit` and `pre-push` now run the beads step and then exec `.githooks/<hook>` with the same args (pre-push stdin is buffered so both see the ref list); the `.githooks` exit code is the hook's. Either `core.hooksPath` value now enforces the guards. If `bd hooks install` rewrites those files, re-add the chain block at the end.
- **Feature LOGIC goes in `sharedLogic/commonMain`; platform modules add only the binding.** State, transforms, caching, TTL, retry/cache policy, request/response shaping, mappers, and reducers are platform-neutral — they belong in `sharedLogic`, implemented once, consumed by every host (Android `app`, `desktop`, future iOS). A platform module (`desktop/`, `app/`) should contain ONLY what genuinely cannot be shared:
  - **Legit-platform:** windowing/Compose-host wiring (AWT/Swing on desktop, Activity on Android), OS single-instance lock, file-storage backend, Ktor **engine** creation, installer/`Main`, platform permissions.
  - **Must-be-shared (never duplicate per platform):** repositories, view-state reducers, data mappers, formatters, caching/TTL/error-flow, request shaping.
  - **Anti-pattern to reject in review:** a platform module re-implementing a `sharedLogic` interface (e.g. `IAgentRepository`) with its own logic. If you need a shared interface implemented, implement the platform-neutral core in `commonMain` and inject the platform bit (e.g. the Ktor `HttpClient` engine). Duplicated implementations across `desktop/` and `core/android-data`/`app/` WILL drift — that is the regression this rule prevents (audit 2026-06-13, beads `letta-mobile-mqzkc`/`8h463`).
  - The `shared-multiplatform` required check (`:sharedLogic:jvmTest` + hostNative/wasmJs compiles) backstops this; keep it green.
- **All UI is shared (`sharedUI`); platform modules are containers.** (Hard cut `letta-mobile-ckmub`, gate `letta-mobile-ckmub.7`; enforced for new files since `letta-mobile-by4vm.32`, not yet a required check.) `desktop/`, `app/`, `feature-*`, `web/` may contain only window/activity hosts, nav hosts that map routes to `sharedUI` pages, the platform's container for `LettaHost` (popover window vs bottom sheet), DI, and platform services (renderers, camera, clipboard, file access, tray, installer). A new screen, row, dialog, picker, sheet, header or settings card goes in `sharedUI/commonMain` and composes the shared primitives (`LettaPageScaffold`, `LettaHost`/`LettaPane`, `ShellHeader`, `RosterList`/`ListRow`, `LettaPicker`, `InspectorPane`, `SettingsSection`, `OverlayHost`, `SharedDocument`). Platform rendering differences go in a `sharedUI` expect/actual or a `LettaHost` binding, not a platform-module composable.
  - `scripts/ci/platform-ui-gate.sh` enforces this. **PUI001** (error, never overridable): a new `@Composable` file under those trees fails unless it is listed in `scripts/ci/platform-ui-allowlist.txt`. **PUI004** (error): adding or raising an allowlist line fails unless the PR body carries a `Platform-UI-Reason: <why>` line — that reviewed reason is the only way a new platform file gets in. **PUI002** (warning for now): an allowlisted legacy file grew beyond its recorded line count (+5). Copying an existing surface instead of composing the primitives is a review blocker. Run it locally with `bash scripts/ci/platform-ui-gate.sh --enforce --diff-base origin/main` (set `PLATFORM_UI_REASON=...` to mirror the PR-body reason); `--update` regenerates the allowlist (each cut slice deletes its lines); self-test: `bash scripts/tests/platform-ui-gate-test.sh`. CI job: `Platform UI gate` in `.github/workflows/agents-policy.yml` — runs with `--enforce`, goes red on PUI001/PUI004, but is not a required check.
  - Android pages are edge-to-edge: `LettaPageScaffold` owns insets; no page calls `systemBars`/`statusBars`/`navigationBars`/`ime` padding itself (agents-policy rule `PUI-INSETS`, planned, greps for those calls outside `sharedUI` scaffold).

**When CI fails on your PR:**

1. Read the failure (`gh pr checks <num>` or `gh run view <id> --log-failed`).
2. Fix on the same branch — push the fix; CI re-runs automatically.
3. Don't open a second PR for the fix; iterate on the existing branch.

**When `main` has moved and your PR is now behind:**

```bash
git fetch
git rebase origin/main
# resolve conflicts in editor if any
git push --force-with-lease
```

Do not `git merge origin/main` into your feature branch — see hard rules above.

## Pre-push PR readiness checklist (run these before opening a PR)

The cost of getting this right at dev time is a few seconds. The cost of
getting it wrong is a 20-minute CI round trip plus a code-review thread.
Every failure class below has been observed on at least one merged PR in the
last wave. The checklist below is designed to catch each one *before* it
costs a CI cycle.

```bash
# 0. Fetch and rebase. Do NOT push yet — run all checks first, then push last.
git fetch origin && git rebase origin/main
[[ "$(git rev-parse --abbrev-ref HEAD)" == "main" ]] && echo "STOP: on main" && exit 1

# 1. Scope audit — confirm the diff is the diff. This is the #1 hidden
#    rollback detector (see PR #1013 / commit 30e91c7be which silently
#    rolled back 143 unrelated files).
BASE=$(git merge-base origin/main HEAD)
git diff --stat $BASE..HEAD
# A test-only PR touching one file should have ~1 file in the diff. If
# `git diff --stat` shows dozens of files when you only changed one, STOP
# and figure out where the extra files came from before opening the PR.

# 2. Sensitive-path grep — anything under these paths must be load-bearing
#    for the change, not incidental. Use this to audit yourself:
SENSITIVE='authz|security|capability|[Ii]roh|appserver|supervisor|local-backend|ProcessHandleController|BridgeEgress|AppServerTurnEngine|LocalImageContext'
git diff --name-only $BASE..HEAD | grep -iE "$SENSITIVE"
# Each hit must be intentional. If you find one that isn't, stop and
# investigate — see PR #1013 and PR #1027 for the cost of getting this
# wrong. A hit is fine when the file IS the class under test; a hit is
# not fine when the file is a sibling that got dragged in by a stale base.

# 3. Stale-base detector — branches based on old main look "small" but
#    actually re-apply merged commits as "new". The two signals:
#    Signal 1: total commits between your branch and merge-base.
#    Signal 2: commits between your branch and merge-base, EXCLUDING
#    everything reachable from origin/main (so merged-main commits
#    drop out, leaving only your actual feature commits).
git log --oneline $BASE..HEAD --no-merges | wc -l   # signal 1
git log $BASE..HEAD --not --remotes/origin/main --oneline | wc -l  # signal 2
# If signal 2 is much smaller than signal 1, you likely merged main
# into your branch instead of rebasing. That is the failure mode that
# produced PR #1013's 144-file phantom rollback.

# 4. Title/scope match — manually compare: does the PR title describe
#    the files actually changed?
echo "Files changed:" && git diff --name-only $BASE..HEAD
echo "Latest commit subject:" && git log -1 --format=%s HEAD
# If your PR title says "add X test" but X isn't in the file list, the
# branch and the title disagree. Fix one or the other before pushing.

# 5. Local gate — required CI is :app compile + testRootDebugUnitTest
#    (`test`) and :sharedLogic:jvmTest (`shared-multiplatform`). CI runs
#    :desktop:test / :sharedUI:jvmTest on a PR only when their own trees
#    change, so a sharedLogic change must run them here; main runs them on
#    every push and goes red if you skipped it. Failed gate = red CI.
cd android-compose
SHARED_HITS=$(git diff --name-only $BASE..HEAD | grep -cF 'sharedLogic/' || true)
if [ "$SHARED_HITS" -gt 0 ]; then
  ./gradlew --no-daemon :sharedLogic:jvmTest :sharedUI:jvmTest :desktop:test
fi
./gradlew --no-daemon :app:compileRootDebugKotlin :app:testRootDebugUnitTest

# 6. Mechanical-debt preflight — catches the issues that light up on
#    EVERY PR's first commit: Unused import directive, Unused symbol,
#    Long overload to Duration conversion, Redundant suspend modifier,
#    plus CodeScene "Complex Method" and "Excess Function Arguments".
#    Pre-fix them at dev cost, not after CI flags them.
# detekt on JDK 21; strict = what the CI `detekt` job runs (new findings fail,
# baselined ones don't). Fix new findings; never lower thresholds.
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew --no-daemon detekt -Parchitecture.strict=true
# CodeScene delta — the same analysis the "CodeScene Code Health Review"
# PR check runs, but local. Needs the `cs` CLI + CS_ACCESS_TOKEN (PAT from
# https://codescene.io/users/me/pat); skips silently when absent. The
# pre-push hook runs this automatically in advisory mode; run it by hand
# (or with CS_DELTA_GATE=1 to hard-fail on findings) via:
../scripts/codescene/cs-delta.sh
# Visual scan for: unused imports, `5_000L` that should be `5.seconds`,
# a method with 6+ params that wants a parameter object, a single
# function doing dispatch on type — split it before pushing.

# 7. Concurrent-collection defaults for sharedLogic — every recent PR
#    had to add these on iteration 2+. Bake them in on first commit:
#    - Stamp `connectionGeneration` at frame ingest; reject frames with
#      a stale gen at every cross-component boundary.
#    - Register a frame with a registry / fanout ONLY when a subscriber
#      can observe it (no phantom leases).
#    - `markDispatched` only after a successful send; the claim
#      remains for the sender to retry on failure.
#    - Cleanup under `NonCancellable`; a `try { cancelAndJoin() }` in
#      `finally` is a textbook lost-unlock.
#    - New turn / new run / new connection ALWAYS supersedes the prior;
#      drop prior-gen entries from caches and leases at the moment the
#      new identity is recorded.
#    See PRs #1039, #1040, #1042 for the full checklist.

# 8. Required vs advisory — know which CI checks block merge and which don't.
#    Required (any red = merge blocked):
#      `test`, `build-apk-pass`, `shared-multiplatform`
#    Not a PR gate: `perf-gate` (emulator macrobenchmark) runs nightly +
#    on main; a red run files the `perf-gate regression` issue.
#    Not required but honest (red means a NEW problem in your diff; fix it):
#    - `detekt` — fails only on findings outside detekt-baseline.xml.
#    - `Platform UI gate` — new platform @Composable file / allowlist change.
#    Advisory (findings never fail the job):
#    - `Advisory AGENTS.md policy` — greppable rules.
#    - `row goldens (advisory)` — shared chat row pixel diffs.
#    - `CodeScene Code Health Review` — external; still keep it green.
#    Full table: "CI map" near the top of this file.

# 9. PR description — every PR must state, in one sentence each:
#    - what behavior changes
#    - how the regression test exercises it
#    - which transport / module surface it touches (mobile, desktop,
#      iroh, appserver)
#    - whether it requires a matched wrapper + APK deploy (only when
#      touching `sharedLogic` App Server transport)

# 10. Push — only after all checks above pass.
git push --force-with-lease
```

**Why this checklist exists.** The merge window of 2026-07-26 to 2026-07-29
saw 22 PRs land. The non-trivial ones averaged 3 review iterations and 7 commits
before merge, almost all chasing one of: stale-base phantom rollbacks, mechanical
detekt debt, or missing concurrent-collection defaults. None of these is hard to
fix at dev time. All cost ~20 minutes of CI per iteration when caught after push.

## Development Environment & Platform Paths

When running compilation, test execution, or deployment commands, you must configure the environment paths according to the operating system you are running on:

### Windows Environments (PowerShell)
Explicitly configure the environment variables in your shell session before running Gradle:
```powershell
# Prefer a full JDK 26 for CI parity. Android Studio's bundled JBR is acceptable
# for local builds when it is a supported Gradle runtime (JDK 17-26).
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-26"
$env:ANDROID_HOME="$env:USERPROFILE\AppData\Local\Android\Sdk"
./gradlew :app:compileRootDebugKotlin
./gradlew :app:testRootDebugUnitTest

# NOTE: If you are building on a Windows mapped network drive (e.g. U:), Java's Files.isWritable()
# and canWrite() checks may fail due to remote Samba SID matching issues. Run the following command
# in a Windows command prompt or PowerShell (replacing U: with your mapped drive) to grant recursive
# Full Control permissions:
# icacls U:\letta-mobile\android-compose /grant Everyone:(OI)(CI)F /T
```

### Linux / macOS Environments (Bash)
Use standard Bash syntax to prefix the build command with the required paths if they are not already in your environment:
```bash
# Set if not globally available:
export JAVA_HOME="/usr/lib/jvm/jdk-26"  # Or another supported JDK 17-26 path
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:compileRootDebugKotlin
./gradlew :app:testRootDebugUnitTest
```

## Non-Interactive Shell Commands

**ALWAYS use non-interactive flags** with file operations to avoid hanging on confirmation prompts.

Shell commands like `cp`, `mv`, and `rm` may be aliased to include `-i` (interactive) mode on some systems, causing the agent to hang indefinitely waiting for y/n input.

**Use these forms instead:**

```bash
# Force overwrite without prompting
cp -f source dest           # NOT: cp source dest
mv -f source dest           # NOT: mv source dest
rm -f file                  # NOT: rm file

# For recursive operations
rm -rf directory            # NOT: rm -r directory
cp -rf source dest          # NOT: cp -r source dest
```

**Other commands that may prompt:**

- `scp` - use `-o BatchMode=yes` for non-interactive
- `ssh` - use `-o BatchMode=yes` to fail instead of prompting
- `apt-get` - use `-y` flag
- `brew` - use `HOMEBREW_NO_AUTO_UPDATE=1` env var

## Issue Tracking

This project uses **bd** (beads) for issue tracking — a CLI tool. **Do not use Huly** for this project.

```bash
# Find next bead to work on
bd list

# View bead details
bd show <id>

# Claim a bead
bd update <id> --claim

# Close a bead when done
bd close <id>
```

## Project Reporting

- **bd** is the source of truth for all issue tracking
- Do **not** report letta-mobile work to Meridian
- Use the `bd` CLI for all issue management

## Versioning and Releases

`versionName` and `versionCode` are **derived at build time** — never hand-edit them in `android-compose/app/build.gradle.kts`.

- **Source of truth**: the git tag `vX.Y.Z`.
- **`versionName`** = tag minus the `v` prefix (e.g. `v0.1.0` → `0.1.0`). Local / PR / untagged builds fall back to `git describe --tags --match 'v[0-9]*' --dirty` (e.g. `0.1.0-3-gab12cd-dirty`).
- **`versionCode`** = `MAJOR * 10000 + MINOR * 100 + PATCH`. Examples: `0.1.0 → 100`, `1.2.3 → 10203`, `10.0.0 → 100000`. Suffixes (`-rc.1`, `-3-gab12cd`, `-dirty`) are stripped before the math, so `v0.2.0-rc.1` and `v0.2.0` produce the **same** `versionCode`. Tag the GA release separately to bump.
- **Override** (rare — for one-off builds): `./gradlew assembleRelease -PversionNameOverride=0.1.0-hotfix`.

### Cutting a release

```bash
git checkout main && git pull
git tag -a v0.1.0 -m "release: 0.1.0"
git push origin v0.1.0
```

The `.github/workflows/release.yml` workflow picks up the tag, builds the signed play-release APKs, creates a GitHub Release with auto-generated notes, attaches `letta-mobile-v0.1.0-full.apk` (arm64, embedded runtime + LiteRT-LM) and `letta-mobile-v0.1.0-lite.apk` (arm64-v8a/armeabi-v7a/x86_64, neither; matrix in `docs/RELEASE.md`). Mistakes are reversible with `git push --delete origin <tag>` + `gh release delete`.

### Rules for agents

- **Never** edit `versionCode` or `versionName` in `build.gradle.kts`. Both lines are computed values; manual edits will be overwritten on the next build.
- **Never** push a tag from the command line as part of a feature PR. Tags are cut from `main` after a PR merges, not from the feature branch.
- **Pre-releases**: use a hyphen-suffixed tag (`v0.2.0-rc.1`) — `softprops/action-gh-release` auto-marks the GitHub Release as a pre-release based on the hyphen.

## Material Design System Rules

Use these rules for all new UI work and UI refactors in this repo. The app already uses Material 3 broadly; the goal is to use it **systematically**.

For A2UI renderer or catalog-authoring work, read
[`android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/a2ui/README.md`](android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/a2ui/README.md)
before changing widget payloads, catalog IDs, bindings, or renderer dispatch.

### 1. Stable chrome, expressive content

- Treat app bars, drawers, and other navigation chrome as **stable structure**.
- Put rich emphasis, hero moments, and shared transitions on **content surfaces** like cards, rows, tiles, and detail headers.
- Avoid anchoring critical shared-element transitions inside unstable collapsing chrome unless there is no safer option.

### 2. Fix geometry before tuning motion

- If an animation glitches, first fix layout/state restoration and anchor stability.
- Do **not** try to solve geometry bugs with spring tuning, expressive motion, or shape morphing alone.
- Shared-element transitions must use deterministic source and destination bounds before any motion polish is added.

### 3. Prefer reusable screen shells over ad hoc screen composition

- Search/list/admin screens should converge on a standard shell:
  - `Scaffold`
  - `LargeFlexibleTopAppBar` (or a simpler top bar when hierarchy does not need a flexible header)
  - optional search slot
  - optional selection/filter row
  - optional FAB slot
  - standardized loading / error / empty / content states
- Reuse existing design-system components instead of rebuilding screen structure per feature.
- `AllToolsScreen`, `AgentListScreen`, and `McpScreen` are the current reference implementations for this shell pattern.
- The first dedicated reusable scaffold adopters should be `letta-mobile-bz40.2.2` (project grid home screen) and `letta-mobile-bz40.2.6` (active agents dashboard), then back-port the extracted scaffold to older admin/list screens as they are touched.

#### Compose migration policy

- Prefer Compose and in-repo design-system components for new UI and for old UI that is already being materially changed.
- Do not rewrite stable screens solely because they are old or could be prettier in Compose.
- Favor contained modernization targets first: dialogs, pickers, admin/detail surfaces, form sections, loading states, and contextual actions.
- A migration is worthwhile when it reduces state/layout complexity, improves adaptive behavior, removes bespoke UI, or unlocks reusable design-system behavior.

#### Adaptive layout policy

- Dialogs, picker flows, detail surfaces, and tool/admin screens should reflow intentionally across compact, medium, expanded, landscape, and tablet-sized layouts.
- Avoid layouts that merely stretch columns or clip dense content when more space is available.
- Prefer adaptive structure with stable constraints, responsive grids, panes, or reordered sections before adding motion polish.
- When touching complex UI, verify the result at phone portrait, phone landscape, and tablet/expanded widths when feasible.

### 4. Use Material components by interaction semantics

- Use **segmented buttons** or another mutually-exclusive selector pattern for binary/mode switches when only one option can be active.
- Use **chips** for filters, tags, or multi-select style controls.
- Use **input chips** for user-added items such as editable tags or removable selections.
- Use **assist chips** for read-only metadata or lightweight status labels.
- Use **bottom sheets** / `ActionSheet` for contextual and destructive action menus.
- Use **dialogs** / `ConfirmDialog` for confirmation and blocking decisions.
- Use **switches** for direct on/off settings, not for choosing between multiple modes.
- Use **cards** and **surfaces** to communicate emphasis and hierarchy, not arbitrary decoration.

### 5. Motion policy

- Keep one motion language across the app: low-surprise navigation, stable shared elements, and small expressive accents inside content.
- The app theme already uses `MaterialExpressiveTheme` with `MotionScheme.expressive()`, but navigation should still feel conservative and readable.
- Fix geometry and state restoration before tuning motion. If a transition is glitchy, first verify stable anchors, scroll state, and consistent source/destination layout.

#### Navigation transitions

- Prefer the centralized drill transition pattern in `android-compose/app/src/main/java/com/letta/mobile/ui/navigation/AppNavGraph.kt` for drill-in screens.
- Keep navigation transitions brief, directional, and consistent. Use the shared drill timing/constants instead of inventing per-screen durations or easing.
- Do not add one-off route animations unless the navigation model is materially different and the exception is documented in the owning screen or issue.
- When a screen does not need special treatment, prefer default navigation behavior over custom animation.

#### Shared-element transitions

- Shared elements must go through `optionalSharedElement()` so they degrade safely when no shared scopes are present.
- Only use shared-element motion for stable content-to-content relationships such as list/grid items to detail headers.
- Never anchor critical shared elements inside unstable or collapsing chrome such as `LargeFlexibleTopAppBar`, collapsing search bars, or transient toolbar content.
- Prefer destination anchors in stable content surfaces like cards, rows, tiles, or fixed detail headers.
- Shared-element keys must be deterministic and unique to the item identity.
- If either side cannot provide stable bounds, remove the shared element and fall back to standard navigation motion.

#### Sheets, drawers, and contextual surfaces

- Use Material components such as `ModalBottomSheet`, `ActionSheet`, `ModalNavigationDrawer`, and dialogs for container motion instead of hand-rolled enter/exit animation.
- Let container components own their default motion unless a reusable design-system wrapper needs a documented adjustment.
- Contextual actions should appear from sheets or menus, not from bespoke animated overlays.

### 6. Compose Compiler Metrics & Performance Audits

- Enable compiler metrics and stability reports locally using:
  ```bash
  ./gradlew :app:compileRootDebugKotlin -Pplugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=build/compose_compiler -Pplugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=build/compose_compiler
  ```
- Inspect generated `*_skippable.txt` and `*_unstable.txt` under `build/compose_compiler/` to detect restartable vs skippable composables and unstable class parameters.
- Ensure UI state holders and model parameters used in frequent re-render paths are marked `@Stable` or `@Immutable`, or use `ImmutableList` / primitive parameters.

#### Micro-interactions

- Use expressive motion for local content changes only after layout geometry is correct.
- Preferred patterns are small, reusable helpers like `AnimatedVisibility`, `animateContentSize`, and icon rotation for expand/collapse, banners, and inline affordances.
- Use local motion only when it communicates a concrete state change or preserves user context, such as expand/collapse, progressive loading or streaming, scroll-to-bottom or return-to-context affordances, attachment add/remove, contextual drawer/sheet open and close, or list/detail continuity.
- Keep micro-motion close to the component that owns the state change; avoid animating entire screens for small local updates.
- Avoid stacking multiple animations on the same interaction unless they clearly reinforce hierarchy.
- Do not add motion only for decoration. If the layout jumps, text shifts unexpectedly, or anchors are unstable, fix layout/state first and simplify the animation.

#### Lazy lists, loading, and progressive data

- Do not use motion to hide expensive loading or unstable list geometry.
- For list/detail flows, preserve stable item identity and restore list/grid/top-app-bar state before adding polish.
- Prefer progressive rendering when secondary data arrives later. Show primary content first and add lightweight status affordances, such as the MCP loading banner, instead of blocking the full screen.
- Loading motion should communicate status, not delay access. Use existing shimmer/progress patterns and remove them as soon as content is ready.

#### Decision rules

- If motion clarifies spatial relationship or state change, keep it.
- If motion competes with readability, causes layout jumps, or depends on fragile anchors, simplify or remove it.
- Reuse existing repo patterns before adding a new motion treatment. New reusable motion behavior belongs in `android-compose/designsystem` or shared navigation helpers, not inside a single screen.

### 6. Color role policy

The app theme defines **primary**, **secondary**, **tertiary**, and **error** color roles across all 6 presets. Use each role for its intended semantic purpose.

#### Role assignments

| Color role | Semantic purpose | Typical components |
|---|---|---|
| `primary` / `primaryContainer` | Main brand action, default emphasis | Primary buttons, FABs on main screens, active nav items, progress indicators |
| `secondary` / `secondaryContainer` | Supporting actions and less-prominent selections | Filter chips, secondary buttons, less-prominent toggles |
| `tertiary` / `tertiaryContainer` | Contrasting accent that balances primary/secondary, draws differentiated attention | Accent FABs on secondary screens, "live/active" status indicators, third data-series color in charts, highlight badges, feature-differentiating accents |
| `error` / `errorContainer` | Error and destructive states | Error text, destructive confirmations, failure indicators |
| `surface*` variants | Container hierarchy and elevation | Cards, sheets, scaffolds, dialogs |

#### Tertiary introduction targets

Tertiary is currently underused. When touching these areas, prefer tertiary over inventing one-off accent colors:

- **Status differentiation** — use `tertiaryContainer`/`onTertiaryContainer` for "running", "live", or "active" states that need to contrast with primary success and secondary idle states. `UsageScreen` running chips and `AgentListScreen` already do this — extend the pattern to other status surfaces.
- **Chart accent colors** — use tertiary as the third data-series color alongside primary and secondary in Vico charts and any future data visualization.
- **Accent FABs** — when a screen already has a primary-colored FAB or primary-colored main action, use `tertiaryContainer`/`onTertiaryContainer` for a secondary FAB or competing action to avoid color collision.
- **Highlight badges** — "new", "beta", or feature-differentiating badges that need to stand out from both primary and secondary chip colors.

#### Existing tertiary derivations in `CustomColors`

`deriveCustomColors()` in `Theme.kt` already maps tertiary into:
- `reasoningBubbleBgColor` → `tertiaryContainer` at 45% alpha (reasoning chat bubbles)
- `warningContainerColor` → `tertiaryContainer` (warning state backgrounds)
- `warningTextColor` → `onTertiaryContainer` (warning state text)

Do not duplicate these — use `MaterialTheme.customColors.warningContainerColor` etc. for warning states rather than reaching for `tertiaryContainer` directly.

#### Rules

- Never use raw hex colors when a color role already expresses the intent. If primary, secondary, or tertiary fits, use the role.
- When adding a new accent that does not fit primary or secondary, reach for `tertiary`/`tertiaryContainer` before inventing a custom color.
- If a screen needs more than three accent colors beyond error, add a semantic slot to `CustomColors` and derive it from an existing role rather than hardcoding a hex value.
- Keep `Color.kt` for named reusable constants. If a new tertiary-derived constant is needed app-wide, define it there.

#### Surface and hierarchy

- Follow the repo theme layer and `LettaTopBarDefaults` rather than inventing per-screen app-bar/surface colors.
- Use consistent corner-radius and surface emphasis rules across similar cards and tiles.
- Prefer a clear surface hierarchy over stacking multiple decorative treatments on the same element.

### 7. Typography rules

- Use the existing repo typography tokens and extensions (`MaterialTheme.typography`, `listItemHeadline`, `listItemSupporting`, etc.) as the default.
- Prefer semantic extensions from `TypeHierarchy.kt` such as `sectionTitle`, `chatBubbleSender`, and `statValue` over one-off styling in screens.
- Do not add raw `fontWeight` overrides in feature UI when an existing typography token can express the hierarchy. Add or extend a semantic token first, then reuse it.
- Introduce expressive or emphasized typography only for real hierarchy changes or hero moments, not as ad hoc styling.

### 8. Prefer in-repo patterns first

- Before inventing a new UI pattern, search for an existing implementation in the repo and reuse/adapt it.
- The `android-compose/designsystem` module is the canonical home for reusable UI foundations. Add new shared components there rather than creating per-screen one-offs.
- Current reusable foundations include `ActionSheet`, `ConfirmDialog`, `EmptyState`, `ErrorContent`, themed top-bar defaults, and the shared design-system theme layer.

### 9. Design-system direction issues

- `letta-mobile-q7jt` — Establish Material design system guardrails
- `letta-mobile-iqwq` — Build reusable searchable list screen scaffold
- `letta-mobile-pkzn` — Define motion and navigation transition policy
- `letta-mobile-ns9n` — Standardize selection and emphasis components
- `letta-mobile-k8x0` — Introduce tertiary color role across app UI

### 10. Current design-system migration checklist

- **ActionSheet targets**
  - `FolderAdminScreen`, `ProviderAdminScreen`, `IdentityListScreen`, and other admin list cards with inline edit/delete icon rows should converge on an overflow or long-press `ActionSheet` for contextual actions.
  - Detail screens that currently expose multiple secondary actions inside dialogs should prefer `ActionSheet` when the action set is contextual rather than confirmational.
- **ConfirmDialog / TextInputDialog targets**
  - Use `ConfirmDialog` for dismissible error/info dialogs only when the interaction is a single acknowledge/confirm step; keep destructive confirmations on `ConfirmDialog` rather than raw `AlertDialog`.
  - Use `TextInputDialog` for single-field rename/create flows before reaching for raw `AlertDialog`.
  - Multi-field editor flows such as `FolderAdminScreen`, `ProviderAdminScreen`, `ScheduleListScreen`, `EditAgentScreen`, `McpScreen`, and `AgentListScreen` should converge on reusable dialog wrappers instead of bespoke `AlertDialog` forms over time.
- **FormItem targets**
  - `AgentListScreen` create/import dialogs and `ScheduleListScreen` create dialog should use `FormItem` for label + helper + switch rows.
  - `McpScreen` server form, `ProviderAdminScreen` editor dialog, `FolderAdminScreen` editor dialog, and `EditAgentScreen` block/tag sections are the next targets for `FormItem` adoption.
- **CardGroup targets**
  - `ConfigScreen` and `AgentSettingsScreen` are the reference implementations.
  - Grouped admin/detail surfaces in `FolderAdminScreen`, `ProviderAdminScreen`, `IdentityListScreen`, `ArchiveAdminScreen`, `McpServerToolsScreen`, `RunMonitorScreen`, `JobMonitorScreen`, `MessageBatchMonitorScreen`, and `ToolDetailScreen` should migrate from flat `Column` dialog bodies toward `CardGroup` sections.
- Treat this checklist as the default migration map when touching any of the above screens. Reuse the existing designsystem component before inventing a new layout or dialog pattern.

### 11. Current old-style screen audit

- Most primary screens already use `LettaTopBarDefaults` plus the standardized `Scaffold` / top-bar shell. Treat shell migration as largely complete.
- Remaining modernization work is concentrated in **dialogs, pickers, and admin/detail surfaces**, not the screen chrome itself.
- The highest-value remaining targets are:
  - `ToolPickerDialog`, `CreateToolDialog`, `BlockPickerDialog` — migrate bespoke dialog bodies toward reusable dialog wrappers and semantic typography.
  - `FolderAdminScreen`, `ProviderAdminScreen`, `GroupAdminScreen`, `IdentityListScreen`, `ArchiveAdminScreen`, `McpServerToolsScreen`, `ToolDetailScreen` — replace flat detail columns and raw dialogs with `CardGroup`, `ConfirmDialog`, `TextInputDialog`, and `ActionSheet` where semantics match.
  - `EditAgentScreen`, `ScheduleListScreen`, `McpScreen`, `AgentListScreen` — continue migrating multi-field forms toward `FormItem` and reusable dialog structure.
- When auditing a screen now, prioritize: (1) raw `AlertDialog` replacement, (2) grouped detail surfaces, (3) semantic typography, and only then shell-level chrome changes.

## Cursor Cloud specific instructions

This section covers non-obvious caveats for the Cursor Cloud VM. Standard build/test/run
commands live in `README.md`, `CONTRIBUTING.md`, and `android-compose/README.md` — use those.

### Toolchain layout (already provisioned in the VM snapshot)

- JDK 26 (Temurin, CI parity + desktop runtime): `/usr/lib/jvm/jdk-26`. JDK 21 also exists at
  `/usr/lib/jvm/java-21-openjdk-amd64` (needed by detekt — see below).
- JDK 17 (Temurin): `/usr/lib/jvm/jdk-17`. Some modules request a **Java 17 toolchain** via Gradle
  toolchain resolution; without a discoverable JDK 17 the build fails with "Cannot find a Java
  installation … matching {languageVersion=17}". CI's GitHub runners ship JDK 17 by default; this VM
  needs it installed (Gradle auto-detects it under `/usr/lib/jvm`).
- Android SDK: `$HOME/Android/Sdk` (platform 36, build-tools 36, platform-tools).
- `JAVA_HOME`, `ANDROID_HOME`, and `PATH` are exported for interactive shells from `~/.bashrc`.
  Non-interactive scripts should set `JAVA_HOME=/usr/lib/jvm/jdk-26` explicitly.
- `android-compose/local.properties` (`sdk.dir=…`, gitignored) is auto-created by the startup
  update script; recreate it if missing.

- Build logic: versions only in `android-compose/gradle/libs.versions.toml` (one per library,
  no per-module forks); convention plugins in `android-compose/build-logic/`. The embedded
  letta-code runtime prep is the `com.letta.mobile.embedded-runtime` plugin, with pins
  in `letta-code-runtime/letta-code-runtime.properties` (the one letta-code pin, also read by the
  desktop build) and letta.js patches as real files in `letta-code-runtime/patches/` — edit
  those, not `app/build.gradle.kts`.

### App Server contract / probe toolchain (epic letta-mobile-lgns8)

- For App Server v2 contract and probe work, **Node `v24.18.0`** (via nvm:
  `~/.nvm/versions/node/v24.18.0/bin/node`). There is ONE letta-code pin:
  **`@letta-ai/letta-code@0.34.14`** (`letta-code-runtime/letta-code-runtime.properties`), which
  is the wire contract (`app-server-v2-contract-matrix.json` `baseline.version`), the desktop's
  bundled runtime, the Android embedded runtime and what the `appserver-contract-baseline` CI job
  installs; `LettaCodeVersionPinsTest` fails if they disagree. Not the contract: the Linux host
  still runs **`0.33.6`** until redeployed (the client must keep working against it: never
  require a 0.34-only field, gate 0.34-only commands on an advertised capability); the
  restart-replay evidence is still the older **`0.29.12`** capture (it needs a live server +
  model provider to re-capture). The Android embedded runtime ships in the
  `full` release APK only (nodejs-mobile Node 18 plus the `node18-compat.cjs` preload, arm64-v8a
  only, driven through the headless stream-json protocol until letta-mobile-by4vm.110.6; the
  `lite` APK has none, `docs/RELEASE.md`), so anything "verified against a 0.33/0.34 server" is
  desktop/remote-only until then. One table lists every pin:
  `sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/transport/appserver/README.md`
  ("letta-code version pins"). Install the package under test at
  `~/letta-code-install/node_modules/@letta-ai/letta-code` (older VM snapshots carry 0.29.9 there;
  replace it with the version the task needs).
- The contract verifier pins the exact Node + package versions, so run it with Node 24.18.0:
  `~/.nvm/versions/node/v24.18.0/bin/node scripts/appserver/verify-contract-baseline.mjs --package-root ~/letta-code-install/node_modules/@letta-ai/letta-code`.
- Launch the local App Server for probes with
  `~/.nvm/versions/node/v24.18.0/bin/node <package-root>/letta.js server --listen ws://127.0.0.1:4500` (or the deprecated
  `letta app-server --listen` alias). Clients must use the one-socket `/ws` URL — legacy
  `?channel=control|stream` upgrades are rejected with HTTP 426.

### Running the app on this VM

- Both clients are runnable here: the **Compose Desktop** app (`:desktop:run`) and the **Android**
  `:app` in the emulator (see the Android emulator subsection below). They can run side by side on
  the same VNC display (`:1`).
- The desktop app renders through Skiko, which **cannot create a GL context on the VNC X server**
  (`RenderException: Cannot create Linux GL context`). Force software rendering and target the
  VNC display:
  ```bash
  DISPLAY=:1 JAVA_TOOL_OPTIONS="-Dskiko.renderApi=SOFTWARE" ./gradlew :desktop:run
  ```
- `:desktop:run` needs JDK 25+ at runtime (Jewel ships class-file v69); JDK 26 satisfies this.
- The desktop build compiles a Rust native lib via `:desktop:buildDesktopMermaidNative`
  (`native/mermaid_renderer`), whose `Cargo.toml` requires **`edition2024`** → Rust/Cargo **≥ 1.85**.
  A current stable toolchain is provisioned via rustup (`rustup default stable`); if a fresh VM only
  has an older default (e.g. 1.83), run `rustup toolchain install stable && rustup default stable`.
- To connect to a backend: in the running app click **Settings** → pick a **Mode** (Local runtime /
  Remote host; there is no Letta Cloud mode, letta-mobile-ckmub.9), enter the remote host's
  **Address** (`iroh://…` or `ws://…`) and an **Access token** if the App Server asks for one, then
  **Save**. **Test connection** probes a ws/http address without opening a session; an unreachable
  server shows "Connection refused" / "Couldn't load this view".
- An `iroh://<nodeId>@<host>:<port>` ticket in the Server URL field switches the client to the
  Iroh QUIC P2P transport automatically (scheme-detected — Mode does not matter); admin/agent
  traffic then flows over QUIC instead of HTTP.

### Running the Android app (`:app`) in the emulator

- There is **no KVM** on this VM (`/dev/kvm` absent), so the emulator runs under **software (TCG)
  emulation** — functional but slow. The SDK `emulator`, an `x86_64` system image, and an AVD named
  `letta_test` are provisioned in the snapshot.
- Launch on the VNC display with software GPU + no acceleration:
  ```bash
  DISPLAY=:1 emulator -avd letta_test -accel off -gpu swiftshader_indirect \
    -no-snapshot -no-boot-anim -no-audio -memory 3072 -partition-size 4096 -no-metrics &
  adb wait-for-device
  # boot is slow under TCG (several minutes); poll until it prints 1:
  until [ "$(adb -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 10; done
  ```
- Install + launch the debug build (the app id is `com.letta.mobile.dev`, and debug builds also
  register a LeakCanary launcher — start `MainActivity` explicitly, not via the launcher):
  ```bash
  adb -s emulator-5554 install -r android-compose/app/build/outputs/apk/root/debug/app-root-debug.apk
  adb -s emulator-5554 shell am start -n com.letta.mobile.dev/com.letta.mobile.MainActivity
  ```
- Under TCG the OS is slow enough that Android may show "System/Process isn't responding" ANR
  dialogs during startup — tap **Wait** (or `adb shell input tap`) and give it time; these are
  emulation-speed artifacts, not app bugs. `adb exec-out screencap -p > out.png` is the most
  reliable way to capture the app's screen.

### Beads (`bd`) issue tracker on this VM

- `bd` **v1.1.0** is installed at `/usr/local/bin/bd` (provisioned in the snapshot; matches the
  remote's migrated schema **v53**). If missing on a fresh VM, reinstall from the GitHub release
  (`steveyegge/beads` → `beads_1.1.0_linux_amd64.tar.gz`) into `/usr/local/bin`.
- On a fresh clone/VM, sync the embedded Dolt DB once with `bd bootstrap --yes` (pulls from the git
  remote `git+https://github.com/oculairmedia/letta-mobile.git`; no separate `dolt` binary needed).
  Verify with `bd ping`. `bd dolt pull` / `bd dolt push` work against the DoltHub federation remote
  (`doltremoteapi.dolthub.com/oulair/letta_mobile`) using the existing GitHub/Dolt credentials.
- **Schema migrations**: the designated migrator (Emmanuel) runs `bd migrate`/`bd dolt push`; every
  other clone (Cursor included) only runs `bd bootstrap` to sync — never migrate here (it forks the
  shared schema). If `bd ping` warns the schema is behind and `bd bootstrap --yes` errors with
  "can't create database beads; database exists", move the stale local DB aside
  (`mv .beads/embeddeddolt /tmp/embeddeddolt.bak`) and re-run `bd bootstrap --yes` to re-clone the
  migrated schema. This is safe only when the clone has no unpushed tracker changes.
- Recommended local config (silences warnings): `chmod 700 .beads` and
  `git config beads.role maintainer`. `.beads/issues.jsonl` is gitignored — never edit `.beads`
  files directly; use `bd` commands. Pull before tracker mutations and push afterward.
- The canonical epic is `letta-mobile-lgns8` (paused until explicitly resumed — do not claim/work its
  children until authorized). Use isolated git worktrees for code changes when it resumes.

### Persisting the desktop backend config (without committing the URL)

- On **Save**, the desktop client writes its config to `~/.letta-mobile/desktop-settings.properties`
  (outside the git repo — never commit this or paste backend URLs into tracked files / the PR).
- The backend URL is treated as a **secret**. To auto-restore it on a fresh VM, set the
  `LETTA_DESKTOP_SERVER_URL` secret (its value is the full `iroh://…` or `https://…` URL) in the
  Secrets panel. The startup update script recreates
  `~/.letta-mobile/desktop-settings.properties` from that env var when the file is missing, so the
  URL stays encrypted in Cursor Secrets and out of the repo.

### Lint (detekt)

- Detekt (`1.23.8`) rejects `--jvm-target 26`, so `./gradlew detekt` fails on JDK 26 with
  "Invalid value (26) passed to --jvm-target". Run it on JDK 21 instead:
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew detekt
  ```
  Each detekt module has a `detekt-baseline.xml` holding its pre-existing findings. CI's
  `detekt` job (not required, but not masked) runs with `-Parchitecture.strict=true` and fails
  on any finding outside the baseline; without that property local runs only report. Fix new
  findings; regenerate a baseline (`./gradlew detektBaseline`, JDK 21) only to accept debt
  deliberately, in its own commit. `guardrailDetekt` (`detekt-guardrails.yml`, changed files,
  no baseline) runs inside the required `test` job.

### Flaky unit tests

- Under cold-VM CPU contention a few timing-sensitive tests can spuriously fail
  (`AndroidNetworkBridgeStreamingTest` → `SocketTimeoutException`,
  `LettaDatabaseCharacterizationTest` / `MessageRepositoryE2eTest` → `UncompletedCoroutinesError`).
  They pass on an isolated re-run (`--tests`); treat these specific failures as flakiness, not a
  regression.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->

## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until the PR is open and CI is running (or merged).

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** — Create beads issues for anything that needs follow-up.
2. **Run quality gates** locally (if code changed):
   ```bash
   ./gradlew :app:compileRootDebugKotlin
   ./gradlew :app:testRootDebugUnitTest
   ```
3. **Update issue status** — Close finished work, update in-progress items.
4. **Sync beads**:
   ```bash
   bd dolt push
   ```
5. **PUSH THE BRANCH AND OPEN A PR** — never push to `main` directly:
   ```bash
   # if your feature branch is behind main:
   git fetch && git rebase origin/main
   git push -u origin <your-branch>           # or --force-with-lease if rebased
   gh pr create                               # if no PR exists yet
   gh pr checks <PR#>                         # confirm CI is queued/running
   git status                                 # MUST show "up to date with origin/<your-branch>"
   ```
6. **Clean up** — Clear stashes, delete merged local branches.
7. **Verify** — All changes committed AND pushed to the feature branch AND a PR exists.
8. **Hand off** — Add a `bd note` to the active/next bead with a concise bootstrap handoff. Include the exact retrieval command in your final response:
   ```bash
   bd show <active-or-next-bead-id> && git status --short --branch && gh pr view <PR#>
   ```

**CRITICAL RULES:**

- Work is NOT complete until the feature branch is pushed AND a PR exists.
- NEVER `git push origin main` — the pre-push hook will reject it; even if you bypass it locally, branch protection on the remote will too. This is by design.
- NEVER stop before pushing — that leaves work stranded locally.
- NEVER say "ready to push when you are" — YOU must push the branch and open the PR.
- If push fails on a feature branch, resolve (rebase / fix hook failure / set `JAVA_HOME`) and retry until it succeeds.
- Don't merge your own PR until CI is green. Squash-merge only.
<!-- END BEADS INTEGRATION -->
