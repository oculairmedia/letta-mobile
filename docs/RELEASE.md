# Release checklist

Use the required checks in [AGENTS.md](../AGENTS.md#ci-map-what-runs-where)
and [.github/workflows/android.yml](../.github/workflows/android.yml) for the
current merge gates. From `android-compose/`, focused Android checks are
`:app:compileRootDebugKotlin` and `:app:testRootDebugUnitTest`; shared changes
also need the affected shared/module tests and multiplatform compilation in CI.

For transport acceptance, use the [Iroh two-client probe](iroh-two-client-live-sync-probe.md)
and [device protocol](testing/lgns8-e2e-device-protocol.md). Record the backend,
commit, build variant and observed results rather than interpreting an old
REST/SSE subscriber result as current App Server/Iroh evidence.

Release packaging is defined by [release.yml](../.github/workflows/release.yml).
The retained packaging notes below describe its full/lite Android outputs;
verify the actual workflow run and attached assets for each release.

## Linux x86-64 desktop (Omarchy / Arch)

The desktop artifact is `letta-desktop-<tag>-linux-x64.AppImage`, accompanied by
`letta-desktop-<tag>-linux-x64.AppImage.sha256`. It runs the same shared Compose
UI as Windows. It is separate from `meridian-iroh-wrapper-<tag>-linux-x64.tar`,
which is a server CLI and cannot launch the desktop UI. This is a portable
AppImage, not an Arch/pacman package; there is no validated PKGBUILD.

The AppImage bundles JBR **25.0.4 b508.27** (Jewel requires Java 25+), Node
**24.13.1**, the pinned `@letta-ai/letta-code` runtime and its Linux x64 npm
native dependencies. Neither a system JDK nor a system Node installation is
needed. Mermaid and tablet JNI are built on Linux; Automerge and Iroh's Linux
x64 JNI are carried by their upstream JARs. The Rive desktop renderer currently
requires Windows/D3D11, so Linux uses the existing fallback mascot. Bundling the
tablet JNI does not certify Surface pen, touch, keyboard, suspend or scaling.
JCEF keeps its existing on-demand download/cache behavior; this is not an
offline JCEF bundle.

Build and smoke runners use Ubuntu **22.04 x86-64**, whose official repositories
provide the legacy `libdbusmenu-gtk4` dependency used by electron-builder's
AppImage ([package](https://packages.ubuntu.com/jammy/libdbusmenu-gtk4)).
The intended runtime is a
current, fully updated glibc-based Arch/Omarchy x86-64 installation with X11
libraries, GTK3, NSS, ALSA, fontconfig/fonts, OpenGL/Mesa and libstdc++ available.
The current electron-builder AppImage payload also carries `libappindicator.so.1`,
which requires the GTK2 `libdbusmenu-gtk.so.4` library. The payload smoke fails if
that shipped library cannot resolve its dependencies. A graphical D-Bus session
and a window manager are required for first-window acceptance; Xvfb alone is not
a complete desktop session. The Linux launcher uses a reverse-DNS Nucleus app ID
so its MPRIS D-Bus registration does not inherit spaces from the display name.
This does not promise compatibility with older Linux distributions or musl.
The desktop's AWT backend uses **XWayland** in Omarchy's Wayland session; ensure
`xorg-xwayland` is installed and `DISPLAY` is set. Native Wayland is not enabled
or claimed. No live Intel Surface Pro7/Omarchy verification has been performed.

Download the AppImage and matching checksum from the same verified release:

```bash
sha256sum -c letta-desktop-<tag>-linux-x64.AppImage.sha256
chmod +x letta-desktop-<tag>-linux-x64.AppImage
./letta-desktop-<tag>-linux-x64.AppImage
```

Replace `<tag>` with the release tag in these commands. FUSE-based execution
requires Arch's `fuse2` package. To run without FUSE, extract into a permanent,
user-writable directory, then launch its `AppRun` (keep the whole extracted tree):

```bash
./letta-desktop-<tag>-linux-x64.AppImage --appimage-extract
mkdir -p "$HOME/.local/opt"
# Destination must not already exist.
mv -f squashfs-root "$HOME/.local/opt/letta-desktop"
"$HOME/.local/opt/letta-desktop/AppRun"
```

For menu integration, create `~/.local/share/applications/letta-desktop.desktop`
with the following content, replacing `/home/YOU` with your actual home path.
Use the existing image's icon if desired; the minimal entry needs no icon:

```ini
[Desktop Entry]
Type=Application
Name=Letta Desktop
Exec="/home/YOU/.local/opt/letta-desktop/AppRun" %U
Terminal=false
Categories=Utility;
MimeType=x-scheme-handler/meridian;
```

Quit the running app before replacing an extracted installation. Keep the
Windows updater metadata (`latest.yml` / prerelease channel files) unchanged;
Linux releases are downloaded/replaced manually. Release tagging, publication
and installation are performed by the PM after verified merge.

Packaging implementation and acceptance:

- `:desktop:packageDistributionForCurrentOS` selects AppImage on Linux and
  preserves EXE/MSI on Windows. Nucleus **2.1.5** provides the AppImage backend
  ([upstream source](https://github.com/NucleusFramework/Nucleus/blob/v2.1.5/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/desktop/application/dsl/TargetFormat.kt)).
- The Linux Node archive SHA-256 comes from
  [Node's versioned checksum manifest](https://nodejs.org/dist/v24.13.1/SHASUMS256.txt).
  The Linux JBR SHA-512 comes from
  [JetBrains' matching archive checksum](https://cache-redirector.jetbrains.com/intellij-jbr/jbrsdk-25.0.4-linux-x64-b508.27.tar.gz.checksum).
  Archive hashes are pinned in `desktop/build.gradle.kts`; Windows pins stay intact.
- `desktop.yml` and `release.yml` build on Linux, extract the actual AppImage,
  validate its desktop entry, inspect all loose ELF files and the selected JNI
  members of universal JARs, check dynamic-library resolution, load required JNI
  using the packaged JVM, run the bundled Node/AppServer CLI and require a visible
  app window under Xvfb/software rendering, Openbox and a private D-Bus session
  after relocation to a path with spaces. Startup errors, X errors and new JVM
  crash logs fail acceptance; CI uploads smoke diagnostics even after a failure.
  The package binds the existing explicit runtime properties using `$APPDIR`;
  the smoke verifies those expand to real bundled files, without development paths.
- Run `python3 scripts/tests/test_desktop_packaging.py` for lightweight fixtures.
  After a build slot is authorized, run the Gradle packaging task above, then
  `xvfb-run -a -s "-screen 0 1280x800x24" dbus-run-session -- bash scripts/desktop/linux-package-smoke.sh <AppImage>` from the
  repository root. Preserve Linux and Windows workflow evidence before release;
  passing fixtures alone is not packaged-build acceptance. Failed smoke runs retain
  their extracted tree and logs, printing the evidence directory; set
  `LETTA_KEEP_PACKAGE_SMOKE=1` to retain successful runs too.
- The PR desktop workflow also opens the Windows packaged app with its shipped
  runtime, requires its first window to remain open, and rejects startup errors
  and JVM crash logs. This complements the existing EXE/MSI build and size gates;
  the Windows updater metadata and installer identity remain unchanged.

## Historical REST/SSE checklist (retained record)

**Status:** Historical April-era release procedure. The standalone root `cli/`,
`make verify-sync`, `make verify-stream`, `make verify-all`, `make verify-release`,
`gates.yaml` and `scripts/release/verify-release.sh` were removed on 2026-10-19.
They had no workflow callers and exercised retired REST/SSE endpoints. Commands,
telemetry names, task names, issue states and dashboard recommendations in the
original record below are not current instructions. The [April run ledger](RELEASE-GATE-LEDGER.md)
is preserved as evidence. Debug device bootstrap remains available separately
through `make verify-device-ready` and [its procedure](RELEASE-AUTOMATION.md).

## Original record

## 1. Verify sync health on a real device (advisory until `letta-mobile-r6j3` closes)

The orchestrated version of this gate runs automatically when `verify-release`
has the device/bootstrap prerequisites (`DEVICE`, `APK`, `BASE_URL`, `API_KEY`,
`AGENT`, `CONV`). The standalone target remains useful for focused debugging.

The timeline-sync loop is the heart of the app. A single regression here
makes the product broken, and bugs have historically landed silently. Gate
the release on a live sync-drift check.

```bash
# Prereqs: a device connected via adb, the app installed & logged in, and
# the chat screen for the target conversation currently open.
# For debug-only automated auth bootstrap with a disposable non-production
# identity, see docs/RELEASE-AUTOMATION.md.

make verify-sync \
    AGENT=agent-<id> \
    CONV=conv-<id> \
    ITERATIONS=6 \
    INTERVAL=10
```

The target runs `letta-cli sync-drift --watch` for 6 samples at 10s
intervals (~60s total) and **fails the standalone command if any sample reports
anything other than `HEALTHY`.** In this gate, `HEALTHY` means the device is within a
bounded heartbeat lag window: under 30 seconds of drift and fewer than 5
messages outstanding relative to the persisted `processed::<conversation>`
cursor. `STALE` and `BROKEN` are both rejected.

In `make verify-release`, this gate is currently classified as **advisory**
until `letta-mobile-r6j3` closes because the internal cursor source is still
debug-build-only (`letta-mobile-8f1v`) and the initial post-bootstrap warm-up
window can produce a first-sample flake (`letta-mobile-r6j3.1`).

## 2. Verify resume-stream delivery (advisory until `letta-mobile-r6j3` closes)

`verify-release` auto-triggers this gate by starting a background streaming
conversation request during the watch window when `LETTA_TOKEN` / `API_KEY` is
available. Run the standalone target when you want to inspect the raw SSE
output yourself.

Confirms the server-side push path is intact end-to-end. Independent of
the client: no device needed.

```bash
# In one shell:
make verify-stream CONV=conv-<id> STREAM_TIMEOUT=60

# In another shell, during the STREAM_TIMEOUT window: send a message to
# the target conversation (via the app, Matrix bridge, letta-cli, or any
# other client). Multiple sends are fine.
```

The target asserts that at least one SSE event was received from the
resume-stream endpoint. Zero events fails the build.

In `make verify-release`, this gate is currently **advisory** while
`letta-mobile-50m6` tracks the remaining release-path mismatch work under the
`letta-mobile-r6j3` observability epic.

**Expected behavior:** On self-hosted Letta ≥ 0.16.7 where we have verified
the resume-stream multiplexing semantics, this must pass. If it fails with
"no active runs" while a run is demonstrably active, the server has
regressed — open a blocker against the server repo before release.

## 3. Observability sanity

Open the in-app **Telemetry inspector** (dev menu → Telemetry) and confirm
the `TimelineSync` subsystem is emitting events during normal use:

- `streamSubscriber.opened` appears when a run starts.
- `streamSubscriber.eventReceived` appears per SSE frame.
- `streamSubscriber.ingested` / `streamSubscriber.eventDeduped` track ingestion.
- `streamSubscriber.idle404` appears while idle (roughly at the backoff
  cadence: 2s → 4s → 8s → 16s → 30s).
- `streamSubscriber.closed` appears after each run finishes.

These events belong to the legacy SSE subscriber. Their absence on an Iroh chat
is expected and does not diagnose a release regression; inspect Iroh/controller
telemetry and the canonical ledger instead.

## 4. Build & packaging

Pushing a `v*` tag runs `.github/workflows/release.yml`, which attaches two Android APKs to the
one GitHub release (letta-mobile-by4vm.110.8). Both are `playRelease` builds with the same package
and signing key, so a phone switches variant by installing the other APK over it. One Gradle
property selects the variant:

| Asset | Build | ABIs | Embedded letta-code runtime | LiteRT-LM on-device model | Size |
|---|---|---|---|---|---|
| `letta-mobile-<tag>-full.apk` | `-PembedLettaCodeNative=true -PembedLettaCodeAssets=true` | arm64-v8a | yes (libnode, git/bash/curl/node helpers, npm assets) | yes | ~95-105 MB (estimate from by4vm.107) |
| `letta-mobile-<tag>-lite.apk` | no embed flags (the default) | arm64-v8a, armeabi-v7a, x86_64 | no | no | ~95 MB (measured 2026-10-16: `playBenchmark`, unstripped `.so`) |

`lite` is what CI's `android.yml` builds (`root-debug` on PRs, `play-release` on `main`), so
CI never pays for the runtime packaging; only `release.yml` builds `full`. With the runtime off,
the embedded-runtime plugin also drops LiteRT-LM's JNI (it only serves the runtime's on-device
model) and Settings hides the on-device runtime and its "Use embedded LettaCode" offer.
`release.yml` checks per variant: `full` must be arm64-only and ship `libnode.so` and
`liblitertlm_jni.so`, `lite` must ship neither; both check the Automerge JNI and 16 KiB `LOAD`
alignment on their 64-bit ABIs.

`release.yml` writes each APK's size into the run summary. Native libraries are most of `lite`
(31 MB arm64-v8a, 23 MB armeabi-v7a, 30 MB x86_64), so an arm64-only `lite` would be ~42 MB. To
build one locally: `./gradlew :app:assemblePlayRelease` (lite) or add the two flags (full; needs
Node and the NDK helpers under `app/libs/embedded-*`).

The same workflow also builds Windows EXE/MSI installers and a Linux Iroh wrapper
distribution; the two APKs are only its Android outputs.

## 4a. Historical orchestrated verify-release entrypoint

```bash
make verify-release \
    DEVICE=<serial> \
    APK=/absolute/path/to/app-debug.apk \
    BASE_URL=http://192.168.50.90:8289 \
    API_KEY=<disposable-token> \
    AGENT=agent-<id> \
    CONV=conv-<id>
```

- `make verify-release VERIFY_RELEASE_ARGS=--json ...` prints structured JSON
  to stdout for CI consumers while still writing the markdown report locally.
- The orchestrator keeps the current gate policy and recent runs in
  `docs/RELEASE-GATE-LEDGER.md`.

## 5. Post-release monitoring

If Grafana is wired:

- Watch the **Resume-stream delivery rate** panel for 24h after rollout.
  A flat line at zero across multiple conversations means the subscriber
  is broken for the new build.
- Watch the **Idle 404 / events ratio** panel. A sudden shift toward 100%
  idle indicates runs are happening but aren't being caught.
- Watch the **ANR / crash budget** panel (from the performance epic) — a
  spike within the first 2h of rollout usually means a regression slipped
  past the pre-release gate.
  Import path / source of truth:
  `docs/observability/sentry-anr-crash-budget-dashboard.json`.

Alert rules under the same dashboard will fire warnings if any open chat
screen sees zero events for > 10 minutes (likely subscriber dead).

---

## Related

- `letta-mobile-mge5.5` — verify-sync Makefile target + gate (this doc).
- `letta-mobile-mge5.6` — StreamSubscriber telemetry + Grafana dashboard.
- `letta-mobile-mge5` (parent epic) — resume-stream subscription architecture.
- `letta-mobile-o7ob.3.5` — ANR / crash budget dashboard.
- `letta-mobile-7973` — verify-release orchestrator and reporting.

## Operational notes

- The standalone `verify-stream` target still supports the original
  human-triggered workflow, but it can also self-trigger a background run when
  `STREAM_SEND_TEXT` plus `LETTA_TOKEN` are provided.
- If `make verify-sync` fails because `adb` can't see the device, the
  target exits `2` (environmental error) rather than `1` (gate failure).
  Scripts driving the release pipeline should treat both as blockers but
  can distinguish the two for paging purposes.
- `processed::<conversation>` is written by the background
  `ChannelHeartbeatSync` worker, not by the foreground chat timeline. Small
  outstanding counts immediately after bootstrap are therefore expected; the
  HEALTHY threshold intentionally allows bounded lag instead of requiring a
  literal zero-gap cursor.
