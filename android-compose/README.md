# Android Compose App

This directory contains the production Letta Mobile Android app.

## Modules

| Module         | Purpose                                                   |
| -------------- | --------------------------------------------------------- |
| `app`          | Screens, navigation, Hilt wiring, Android entrypoints     |
| `core`         | Data models, Ktor API client, Room, repositories, mappers |
| `designsystem` | Shared Compose components, theme, dialogs, `LettaIcons`   |
| `chat`         | Streaming chat client and chat-domain support             |

## Chat architecture boundary

The codebase intentionally has two chat layers:

- `AdminChatViewModel` in `app/` is the active app-facing chat stack.
  - Route owner: `AgentChatRoute`
  - UI owner: `AgentScaffold` + `ChatScreen`
  - Backend behavior:
    - standard vanilla Letta server flow for normal agent chat
    - embedded bot gateway flow for project-scoped chat when `projectIdentifier` is present
- `LettaChatClient` in `chat/` is a separate LettaBot chat primitive.
  - Status: reusable lower-level client, not wired into app navigation today
  - Current production usage: test-only in this repo

This split is intentional, not accidental duplication. Shared primitives remain below both paths:

- `ConversationRepository`
- `MessageRepository`

When changing chat behavior, decide first whether the change belongs to:

1. the admin app route/UI (`AdminChatViewModel`), or
2. the reusable LettaBot client primitive (`LettaChatClient`)

Do not assume changes to one path automatically belong in the other.

## Prerequisites

- Android Studio with Android SDK Platform 36 and Build-Tools 36 installed
- A full JDK/JBR via Android Studio or Java 17
- `local.properties` pointing at your Android SDK

## Local setup

Create `local.properties` from the example file and set `sdk.dir` for your machine:

```bash
cp local.properties.example local.properties
```

Set `JAVA_HOME` before running Gradle:

```bash
export JAVA_HOME="/path/to/Android Studio/jbr"
```

## Common commands

Run Gradle commands from this directory.

```bash
./gradlew :app:compilePlayDebugKotlin
./gradlew :app:testPlayDebugUnitTest
./gradlew :app:assemblePlayDebug
./gradlew installPlayDebug
./gradlew detekt
```

## Distribution flavors

The app is split by a `distribution` product-flavor dimension so Android
system-access features can be kept out of Play artifacts at compile time:

| Flavor | Purpose | Enabled system-access gates |
| --- | --- | --- |
| `play` | Google Play / conservative local default | Framework-safe APIs only; no local shell, Shizuku/Sui, or root tools |
| `sideload` | GitHub/direct APK for power users | Local app-UID shell and Shizuku/Sui integration gates |
| `root` | Direct APK for rooted devices | Sideload gates plus root-tool gates |

Flavor-specific manifests live under `app/src/play`, `app/src/sideload`, and
`app/src/root`. Keep policy-sensitive permissions and services out of
`app/src/main` unless they are approved for every distribution.

System-access smoke compile:

```bash
./gradlew :app:compilePlayDebugKotlin
./gradlew :app:compileSideloadDebugKotlin
./gradlew :app:compileRootDebugKotlin
```

## Release process

Use this flow when building and publishing an Android APK release.

### 1. Pick the release version

- The public GitHub release line currently uses `v0.1.x` tags.
- Check existing releases before picking a new tag:

```bash
gh release list --limit 20
git tag --list "v0.1.*"
```

- Create a new tag instead of overwriting an existing release.
- Keep the Android app's internal `versionName` / `versionCode` in `app/build.gradle.kts` aligned intentionally; the public GitHub tag and internal Android version do not have to match automatically.

### 2. Prepare signing inputs

Release builds use the `release` signing config in `app/build.gradle.kts`.

Provide signing credentials in one of two ways:

1. `android-compose/keystore.properties` (preferred for local release work)
2. Environment variables (`SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`)

Expected `keystore.properties` shape:

```properties
storeFile=../letta-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Notes:

- `storeFile` is resolved from the `app` module, so paths should be relative to `android-compose/app/`.
- `keystore.properties` and `*.jks` are gitignored; keep them local.
- If you create a temporary keystore for a one-off build, treat that APK as non-production-signing output.

### 3. Build the release APK locally

Run all commands from `android-compose/`.

Recommended sequential flow:

```bash
./gradlew --stop
pkill -f kotlin-daemon 2>/dev/null || true
./gradlew cleanKotlinIC
./gradlew :app:assemblePlayRelease
```

Expected output:

```text
app/build/outputs/apk/play/release/app-play-release.apk
```

Useful checks after the build:

```bash
ls app/build/outputs/apk/play/release
stat app/build/outputs/apk/play/release/app-play-release.apk
```

### 4. CI release build behavior

GitHub Actions already knows how to build a release APK in `.github/workflows/android.yml`:

- decodes `SIGNING_KEYSTORE_BASE64`
- sets `SIGNING_*` env vars
- runs `./gradlew :app:assemblePlayRelease --no-daemon --build-cache`
- uploads `android-compose/app/build/outputs/apk/play/release/*.apk` as an artifact

If local release builds fail, compare your setup with the workflow first.

### 5. Publish the GitHub release

After the APK is built and you have chosen a new tag:

```bash
gh release create v0.1.2 \
  "android-compose/app/build/outputs/apk/play/release/app-play-release.apk#letta-mobile-v0.1.2-play-release.apk" \
  --target main \
  --title "v0.1.2" \
  --notes "## Summary
- short release summary

## Artifact
- letta-mobile-v0.1.2-play-release.apk"
```

Verify the published release:

```bash
gh release view v0.1.2 --json tagName,name,url,assets
```

### 6. Release checklist

Use this checklist every time:

1. Confirm `main` is clean and up to date.
2. Pick a new GitHub release tag; do not reuse an existing one.
3. Confirm signing inputs are present.
4. Run the sequential release build flow.
5. Verify `app/build/outputs/apk/play/release/app-play-release.apk` exists.
6. Publish the GitHub release and upload the APK.
7. Verify the release URL and asset.
8. Document whether the APK was signed with the production key or a temporary local key.

## Recommended verification flow

For normal application changes:

```bash
./gradlew :app:compilePlayDebugKotlin
./gradlew :app:testPlayDebugUnitTest
```

For shared model / repository / serialization changes, it is safer to verify the full stack in order:

```bash
./gradlew clean :core:compileDebugKotlin
./gradlew :designsystem:compileDebugKotlin
./gradlew :app:compilePlayDebugKotlin
./gradlew :app:testPlayDebugUnitTest
```

Run those commands sequentially. KSP state can become unreliable if you try to overlap Gradle work.

## Troubleshooting

- If Gradle reports missing Android SDK components, install Platform 36 and Build-Tools 36 in Android Studio.
- If Gradle reports missing `JAVA_COMPILER`, make sure `JAVA_HOME` points to a full JDK/JBR, not a JRE.
- If Gradle cannot find the SDK, verify `local.properties` contains the correct `sdk.dir` for your machine.
- If Kotlin or KSP behaves inconsistently after dependency or generated-code changes, rerun with `clean` before compiling again.

### Kotlin incremental compilation errors

If you see `.tab` file corruption errors (`source-to-classes.tab already registered`, `lookups.tab`, `class-attributes.tab`, or missing backup file errors), run:

```bash
./gradlew cleanKotlinIC
```

This wipes Kotlin IC caches from all modules without nuking the entire `build/` tree. The next build will recompile from scratch and rebuild clean caches.

If corruption persists, kill stale daemons and do a full clean:

```bash
./gradlew --stop
pkill -f kotlin-daemon 2>/dev/null || true
./gradlew clean
```

See `gradle.properties` for IC hardening settings that reduce these failures.
The repo defaults to `org.gradle.daemon=true` (validated by
`letta-mobile-dbs1`: 60/60 clean runs with no IC corruption and a ~13× warm
speedup) while keeping `org.gradle.parallel=false` and
`org.gradle.caching=false`, because overlapped project work and cache-entry
packing each caused reproducible Android verification failures in this
codebase. CI continues to pass `--no-daemon` explicitly because runners are
ephemeral and the daemon win is zero there.
