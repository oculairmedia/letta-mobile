# Android Compose App

This directory is the whole Gradle build: the production Letta Mobile Android app **and** its
Compose Desktop and browser (WASM) siblings. The name is historical — the shared modules here
power all three clients, and `app/` is only the Android host.

## Modules

Gradle modules below are declared in `settings.gradle.kts`; the table also names
their supporting source/config directories, which are not separate projects.

| Module | Purpose | Required / additive tests |
| --- | --- | --- |
| `sharedLogic` | Platform-neutral domain, repositories, timeline reducer, and App Server transport | `:sharedLogic:jvmTest` plus host-native and wasmJs main/test compiles (required `shared-multiplatform`) |
| `sharedUI` | Shared Compose UI for Android, desktop and browser: shell, chat, agent editor, memory, providers, schedules, canvas, search, settings, A2UI rendering | wasmJs main/test compiles in `shared-multiplatform` catch JVM-only calls; `:sharedUI:jvmTest` additive in `test` on path change, and on every main push |
| `sharedUI-devfixtures` | Deterministic fixtures for `sharedUI` previews and tests | covered by `sharedUI` builds |
| `app` | Android host: navigation graph, Hilt wiring, ViewModels, thin containers around shared pages, Android entrypoints | `:app:testRootDebugUnitTest` (required `test` job) |
| `desktop` | Compose Desktop host (JVM): entry point, single-instance/OS lock, bundled JBR, installers | `:desktop:test` (additive in `test` when desktop changes) |
| `web` | Browser client (Kotlin/WASM, Compose for Web) | `web-wasm` job (not required): `:web:wasmJsBrowserDistribution :web:wasmJsBrowserTest`; wasmJs compiles are in required `shared-multiplatform` |
| `core/android-data` | Android data bindings, Ktor wiring, Room, mappers | `:core:android-data:testDebugUnitTest` (required `test` job) |
| `core/data` | Shared Android/JVM Room entities, DAO contracts and model conversions | `:core:data:jvmTest` |
| `core/runtime` | Turn engine, runtime event fanout | covered by host builds |
| `core/ids` | Shared identifiers (`core/schemas/` holds wire schemas as source packages, not a module) | covered by host builds |
| `core/testutil` | Shared test doubles and harnesses | test-only |
| `designsystem` | Compose components, theme, and dialogs | `:designsystem:testDebugUnitTest` (additive on path change) |
| `feature-chat` | Chat feature UI, presentation state and coordination (the `AdminChatViewModel` stack) | `:feature-chat:testDebugUnitTest` (additive on path change) |
| `drawbox` | Vendored canvas editor behind the shared canvas workspace (`drawbox/VENDORED.md`) | covered by `sharedUI` builds |
| `plugin-api` / `plugin-api-testkit` / `plugin-view` | The Kotlin SPI a JVM-runtime canvas plugin implements (LCP wire shapes; `:sharedLogic` depends on it), the conformance test kit, and the WebView host that renders a plugin's UI | additive on path change |
| `avatar/core` / `avatar/renderer-rive` | Avatar models and Rive rendering | additive on path change |
| `cli` / `appserver-cli` / `iroh-wrapper-cli` | JVM tooling, App Server contract probes, and the Iroh server wrapper (`meridian-iroh-wrapper`) | wrapper/CLI tasks in `shared-multiplatform`; additive `cli` tests |
| `appserver-testkit/` | Shared Android/JVM smoke-run scenarios; excluded from product API documentation | CLI compilation and Android instrumented-test compilation |
| `architecture-tests` / `quality/detekt-rules` | Module-boundary tests and custom static-analysis rules | `:architecture-tests:test`, `:quality:detekt-rules:check` (required `test`); `detekt` job (not required, but honest: fails only on findings outside each module's `detekt-baseline.xml`) |
| `macrobenchmark` / `baselineprofile` (modules); `perf/` / `native/` (support directories) | Performance infrastructure and native libraries | `perf-scripts` on PRs; emulator `perf-gate` nightly/main and selected performance PRs (not branch-protection required) |
| `build-logic` | Convention plugins: `com.letta.mobile.android.library`, `com.letta.mobile.kmp.library` (`build-logic/src/main/kotlin/com/letta/mobile/kmp/KmpLibraryPlugin.kt`), architecture-graph, embedded-runtime, JNI 16 KB | `:build-logic:test` (required `test`) |

**Ownership rule:** portable feature *logic* lives in `sharedLogic/commonMain`, shared
Compose UI in `sharedUI`, and host-specific integration in `app/`, `desktop/` and `web/`
(see root `AGENTS.md`).

## Chat architecture

Chat is split by responsibility, not by separate per-client implementations:

- `:feature-chat` contains the chat feature UI, presentation state and coordination.
- `:sharedUI` hosts the Compose chat components shared by Android, desktop and browser.
- `:sharedLogic` owns portable conversation/message repositories, the timeline
  reducer, and transport behavior used by every client.
- `:app` wires Android navigation, lifecycle, and dependency injection; `:desktop`
  and `:web` supply their own host bindings.

For chat changes, put portable timeline/state and transport logic in `sharedLogic`,
all new pages and reusable Compose presentation in `sharedUI`, and Android lifecycle,
ViewModel and DI bindings in `feature-chat` or `app`. Surviving feature UI is legacy
covered by the platform UI allowlist, not the destination for new shared surfaces. There is no `:chat` Gradle module or separate
`LettaChatClient` in this tree.

## Deleted Android screens

`main` advances by hard cuts: when a page moves to `sharedUI`, the Android screen it replaces is
deleted in the same PR, guarded by the `row-parity` harness and the `platform-ui-gate` ratchet.
`app/`'s surviving screens are thin containers plus navigation, DI and ViewModels — when a page
seems to be missing from `app/`, look in `sharedUI/` first (for example `memory/MemoryPage`,
`modelcontrol/ProviderSettingsPages`, `agenteditor/`).

Two categories were removed, and they are not the same:

- **Moved to shared UI** — the agent editor (`feature-editagent` is gone), memory, providers and
  model control, templates, runs, projects, schedules, channels and skills. For these, `app/` keeps
  only a thin `*Screen` + `*ViewModel` container around the shared page. `telemetry` and
  `systemaccess` are still Android-only.
- **Deleted outright** — archival passages, block library, MCP servers, tool editor, model browser
  and usage. These were REST-only screens with no App Server route behind them, so they were
  removed when the HTTP/REST backend went away. Their repositories survive in `sharedLogic` only
  where a shared surface still uses them. Do not restore one of these screens without an App
  Server route to back it.

## Prerequisites

- Android Studio with Android SDK Platform 37 and Build-Tools 36+ installed
- A supported full JDK to launch Gradle (the examples use JDK 26).
  `gradle/gradle-daemon-jvm.properties` selects JDK 21 for the build daemon,
  independently of `JAVA_HOME`; see [Java setup](../CONTRIBUTING.md#local-android-setup).
- `local.properties` pointing at your Android SDK

`compileSdk`, `targetSdk`, `minSdk`, kotlinx-coroutines, Ktor, Hilt, AGP, and
Kotlin plugin versions live in `gradle/libs.versions.toml`. Android library
modules apply `com.letta.mobile.android.library` instead of repeating SDK/Java
17 settings, and Kotlin Multiplatform library modules apply `com.letta.mobile.kmp.library`
for their JVM 17 / `compileSdk` / `minSdk` target defaults. Documented exceptions: `:baselineprofile` uses
`minSdkBaselineProfile` (28); `:feature-chat` still pins Hilt 2.60.1 and
`ktor-client-core` 3.5.2.

## Local setup

Create `local.properties` from the example file and set `sdk.dir` for your machine:

```bash
cp local.properties.example local.properties
```

Set `JAVA_HOME` before running Gradle:

```bash
export JAVA_HOME="/path/to/jdk-26"
```

## Common commands

Run Gradle commands from this directory.

```bash
./gradlew :app:compileRootDebugKotlin
./gradlew :app:testRootDebugUnitTest
./gradlew :app:assembleRootDebug
./gradlew :app:installRootDebug
./gradlew detekt
```

## Desktop client

`:desktop` is a full Compose Multiplatform client, not a preview. It depends on both `:sharedLogic`
and `:sharedUI`, so it renders the same shared pages the Android app does, and supplies only its own
host bindings: window and single-instance handling, notifications, launcher integration and a
bundled JBR 25 runtime. Android-only navigation, Hilt wiring, DataStore and foreground-service
bindings stay in Android modules; portable contracts and policies live in `sharedLogic`.

Run it locally:

```bash
export JAVA_HOME="/path/to/jdk-26"
./gradlew :desktop:run
```

On Windows:

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-26"
$env:ANDROID_HOME="$env:USERPROFILE\AppData\Local\Android\Sdk"
.\gradlew.bat :desktop:run
```

Package installers. CI (`desktop.yml`, job `windows-installer` on `windows-latest`) uses the
host-agnostic task:

```bash
./gradlew :desktop:packageDistributionForCurrentOS
```

Windows-specific tasks (`:desktop:packageMsi`, `:desktop:packageExe`) also work from a Windows
host. `:desktop:test` is additive in the required `test` job when desktop files change — it is
deliberately **not** part of `shared-multiplatform`, where it has a history of hanging.

## Distribution flavors

The app is split by a `distribution` product-flavor dimension so Android
system-access features can be kept out of Play artifacts at compile time:

| Flavor | Purpose | Enabled system-access gates |
| --- | --- | --- |
| `play` | Google Play policy-constrained artifact | Framework-safe APIs only; no local shell, Shizuku/Sui, or root tools |
| `sideload` | GitHub/direct APK for power users | Local app-UID shell and Shizuku/Sui integration gates |
| `root` | Full-featured local/debug and rooted-device artifact | Sideload gates plus root-tool gates |

Flavor-specific manifests live under `app/src/play`, `app/src/sideload`, and
`app/src/root`. Keep policy-sensitive permissions and services out of
`app/src/main` unless they are approved for every distribution.

System-access smoke compile:

```bash
./gradlew :app:compileRootDebugKotlin
./gradlew :app:compileSideloadDebugKotlin
./gradlew :app:compilePlayDebugKotlin
```

## Release process

Releases are driven by annotated `vX.Y.Z` tags on `main`; do not hand-edit
`versionName` or `versionCode`. `app/build.gradle.kts` derives `versionName` from
the tag in release CI and computes `versionCode` as `MAJOR * 10000 + MINOR * 100 + PATCH`.
Untagged builds use `git describe`; an explicit `-PversionNameOverride` is available
for one-off builds, not the normal release path.

1. Confirm the chosen tag is unused, `main` is current, and required CI checks
   pass on the commit to release. Keep local uncommitted changes out of the tag.
2. Create and push an annotated tag at that `main` commit (replace the example
   version with the next release version):

   ```bash
   git fetch origin
   git tag -a vX.Y.Z origin/main -m "release: X.Y.Z"
   git push origin vX.Y.Z
   ```

3. Watch the tag-triggered [release workflow](../.github/workflows/release.yml).
   It uses repository signing secrets to build a signed play-release APK with
   the embedded runtime, verifies the runtime is present, builds Windows EXE/MSI
   installers and the Linux Iroh wrapper distribution, then publishes a GitHub
   Release with generated notes and the artifacts. Do not separately upload a
   locally built APK or create a second release.
4. Verify the workflow succeeded and inspect the release and attached assets:

   ```bash
   gh run list --workflow release.yml --limit 5
   gh release view vX.Y.Z --json tagName,url,assets
   ```

For local signing or test builds, keep keystores and credentials out of git;
local APKs are not substitutes for the CI-produced release artifacts.

## Recommended verification flow

For normal application changes:

```bash
./gradlew :app:compileRootDebugKotlin
./gradlew :app:testRootDebugUnitTest
```

For shared model / repository / serialization changes, verify the shared stack in the order CI
does — shared logic first, then the hosts that consume it:

```bash
./gradlew :sharedLogic:jvmTest
./gradlew :sharedLogic:compileKotlinHostNative :sharedLogic:compileKotlinWasmJs
./gradlew :core:android-data:testDebugUnitTest
./gradlew :designsystem:compileDebugKotlin
./gradlew :app:compileRootDebugKotlin
./gradlew :app:testRootDebugUnitTest
```

`:sharedUI:jvmTest` and `:desktop:test` run as additive tasks in the required `test` job when their
trees change. There is no `:core` Gradle module — the modules are `:core:ids`, `:core:runtime`,
`:core:android-data` and `:core:testutil`.

Run those commands sequentially. KSP state can become unreliable if you try to overlap Gradle work.

Do **not** substitute `./gradlew :sharedLogic:allTests`: its `hostNativeTest` phase hangs, which is
why the required `shared-multiplatform` job runs bounded `:sharedLogic:jvmTest` plus the native and
wasmJs *compiles* instead. The compiles are what catch JVM-only API leaks in `commonMain`.

## Troubleshooting

- If Gradle reports missing Android SDK components, install Platform 37 and Build-Tools 36+ in Android Studio (`compileSdk` is 37; see `gradle/libs.versions.toml`).
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
`org.gradle.caching=true` with a narrow main-manifest processing cache opt-out,
because overlapped project work and broad cache-entry packing each caused
reproducible Android verification failures in this codebase. CI reuses the
Gradle daemon within each ephemeral runner so repeated invocations in the same
job do not pay the cold-start tax.
