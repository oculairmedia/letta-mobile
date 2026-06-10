# Contributing

The active Android app lives in `android-compose/`.

## Workflow

All changes go through a pull request. `main` is protected — direct pushes are rejected.

```bash
git checkout main && git pull               # start from a fresh main
git checkout -b feat/your-change            # branch first, always
# ...edit, commit normally...
git push -u origin feat/your-change         # push the branch
gh pr create                                # open a PR

# if main moved while you were working:
git fetch && git rebase origin/main         # rebase, don't merge
git push --force-with-lease                 # safe force-push of your branch

# when CI is green:
# squash-merge via the GitHub UI -> branch auto-deletes
```

**Do not merge `main` into your feature branch.** Use `git rebase origin/main` instead. Merging produces phantom-conflict commit chains (two commits with the same content but different SHAs) that break the next merge into `main`.

### What's enforced

| Layer | What it does | Bypass |
|-------|--------------|--------|
| `.githooks/pre-commit` | Refuses commits on `main`/`master` | `git commit --no-verify` |
| `.githooks/pre-push`   | Refuses pushes targeting `refs/heads/main` or `refs/heads/master` on any remote, runs `compileRootDebugKotlin` | `git push --no-verify` |
| GitHub branch protection | Blocks direct pushes, requires `test` + `build-apk` + `shared-multiplatform` to pass, linear history only | Admin override in the UI |

The local hooks are nudges — they save a round-trip to CI. The branch-protection rule is the wall.

### First-time setup in a fresh clone

```bash
./scripts/install-hooks.sh        # activates .githooks/ via core.hooksPath
```

The repo admin runs this once to apply / re-apply branch protection on the remote:

```bash
./scripts/bootstrap-branch-protection.sh
```

## Local Android setup

1. Create `android-compose/local.properties` with your Android SDK path.
2. Point `JAVA_HOME` at a full JDK 26 for parity with CI. Gradle 9.4.1 can still
   run on JDK 17-26, so Android Studio's bundled JBR remains acceptable for
   local builds when it falls in that supported range.

Example on Linux:

```bash
cat > android-compose/local.properties <<'EOF'
sdk.dir=/usr/lib/android-sdk
EOF

export JAVA_HOME="/usr/lib/jvm/jdk-26"
```

Example on Windows Git Bash:

```bash
cat > android-compose/local.properties <<'EOF'
sdk.dir=C:\Users\<you>\AppData\Local\Android\Sdk
EOF

export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-26"
```

### Optional Kotlin LSP setup

The OpenCode/OMP LSP config in `.omp/lsp.json` expects the official JetBrains
Kotlin language server executable to be available as `kotlin-lsp` on `PATH` and
started with `--stdio`. Use the `Kotlin/kotlin-lsp` release binary rather than
the deprecated `fwcd/kotlin-language-server` project.

Install/update by downloading the latest `kotlin-lsp` archive for your platform
from `https://github.com/Kotlin/kotlin-lsp/releases`, extracting it outside the
repo, and adding the extracted `bin/` directory to `PATH`. Verify the executable
is discoverable before opening the workspace:

```bash
kotlin-lsp --version
```

The configured root markers are `android-compose/settings.gradle.kts` and
`android-compose/build.gradle.kts` because the Kotlin/Gradle workspace lives in
the `android-compose/` subproject; the repository root intentionally does not
contain Gradle marker files.

## Recommended build checks

Run these from `android-compose/` before pushing (the pre-push hook covers the first one automatically):

```bash
./gradlew :app:compileRootDebugKotlin
./gradlew :app:testRootDebugUnitTest
```

If you touched `sharedLogic/` (KMP common code), also run the all-target gate locally —
commonMain/commonTest must stay platform-neutral (no JVM-only APIs) and this is what
the required `shared-multiplatform` CI check runs:

```bash
./gradlew :sharedLogic:allTests :desktop:test
```

Device install:

```bash
./gradlew installDebug
```

## Releases

Versioning is **tag-driven**. The git tag is the single source of truth for both `versionName` and `versionCode` — no more hand-editing `build.gradle.kts`.

### How it works

| Build context | `versionName` source | Example |
|---------------|----------------------|---------|
| Tag push (`v*`) — CI release workflow | `GITHUB_REF_NAME` minus the `v` | `v0.1.0` → `0.1.0` |
| Explicit override | `-PversionNameOverride=<value>` | `0.1.0-hotfix` |
| Any other build (local, PR, untagged main) | `git describe --tags --match 'v[0-9]*' --dirty` | `0.1.0-3-gab12cd-dirty` |
| No `v*` tag exists at all | hardcoded fallback | `0.0.0-dev` |

`versionCode` is **derived from `versionName`** via `MAJOR * 10000 + MINOR * 100 + PATCH`:

| `versionName` | `versionCode` |
|---------------|---------------|
| `0.1.0` | `100` |
| `1.2.3` | `10203` |
| `2.0.0-rc.1` (suffix stripped) | `20000` |
| `10.0.0` | `100000` |

Suffixes (`-rc.1`, `-3-gab12cd`, `-dirty`, `-sideload`) are stripped before the math. Per-flavor `versionNameSuffix` values (`-sideload`, `-root`, `-benchmark`) still append to the displayed name but don't change the integer.

### Cutting a release

```bash
git checkout main && git pull
git tag -a v0.1.0 -m "release: 0.1.0"
git push origin v0.1.0
```

The `.github/workflows/release.yml` workflow fires on the tag push, builds the signed `play-release` APK, creates a GitHub Release with auto-generated notes diffed against the previous tag, and attaches `letta-mobile-v0.1.0.apk` as a release asset.

**Pre-releases**: append a hyphen suffix to the tag — `v0.2.0-rc.1`, `v1.0.0-beta` — and `softprops/action-gh-release` auto-marks the GitHub Release as a pre-release. `versionCode` ignores the suffix, so a `v0.2.0-rc.1` build has the same `versionCode` as `v0.2.0`. Tag the GA release `v0.2.0` separately to bump `versionCode` to the next tier.

**Mistake recovery**: delete the tag and the release before anyone downloads.

```bash
git push --delete origin v0.1.0
gh release delete v0.1.0 --cleanup-tag
```

### Why this and not manual bumps

Before this change, every release required editing two lines in `build.gradle.kts` (`versionCode` and `versionName`) plus tagging. The two could drift, and the tag history shows examples of inconsistency (`v0.1.3` was a `1.2.1` release). The tag-driven approach removes the manual step and the drift window entirely.

## Issue tracking

This repo uses **bd (beads)** for issue tracking. Run `bd prime` for the full reference. Don't use markdown TODO lists or in-repo task files for tracking work — those fragment across machines.
