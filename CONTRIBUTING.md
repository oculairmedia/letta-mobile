# Contributing

The active Android app lives in `android-compose/`.

## Workflow

All changes go through a pull request. `main` is protected — direct pushes are rejected.

```
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
| `.githooks/pre-push`   | Refuses pushes to `origin main`, runs `compileRootDebugKotlin` | `git push --no-verify` |
| GitHub branch protection | Blocks direct pushes, requires `test` + `build-apk` to pass, linear history only | Admin override in the UI |

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
2. Point `JAVA_HOME` at Android Studio's bundled JBR.

Example on Linux:

```bash
cat > android-compose/local.properties <<'EOF'
sdk.dir=/usr/lib/android-sdk
EOF

export JAVA_HOME="/path/to/android-studio/jbr"
```

Example on Windows Git Bash:

```bash
cat > android-compose/local.properties <<'EOF'
sdk.dir=C:\Users\<you>\AppData\Local\Android\Sdk
EOF

export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

## Recommended build checks

Run these from `android-compose/` before pushing (the pre-push hook covers the first one automatically):

```bash
./gradlew :app:compileRootDebugKotlin
./gradlew :app:testDebugUnitTest
```

Device install:

```bash
./gradlew installDebug
```

## Issue tracking

This repo uses **bd (beads)** for issue tracking. Run `bd prime` for the full reference. Don't use markdown TODO lists or in-repo task files for tracking work — those fragment across machines.
