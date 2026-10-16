# Mobile embedded bridge vs. Letta Code desktop

This document maps the Android embedded runtime bridge to the official Letta Code desktop architecture observed from the packaged desktop application. It is a behavioral/architectural comparison, not a source-code port.

## Desktop shape

The desktop client is an Electron wrapper around `letta-code remote`:

- Electron main starts a local proxy on `127.0.0.1:<random>`.
- Electron main launches the bundled Letta Code CLI as a subprocess.
- The CLI connects back over localhost WebSocket endpoints.
- Renderer traffic is brokered by Electron main via IPC, HTTP proxying, and WebSocket fan-out.
- Local bridge traffic is localhost-only and session-token gated.
- Privileged host surfaces such as filesystem, terminal, OAuth, token storage, and update management live in the trusted wrapper process.

The important compatibility lesson is that the UI/model runtime does not directly own every host capability. It talks to a trusted broker with explicit routes, auth, and lifecycle management.

## Android equivalent

On Android, the trusted broker is the app process (letta-mobile-by4vm.110.6):

- `LocalAppServerHost` + `LibnodeAppServerLauncher` start the embedded letta-code App Server in
  process; `AndroidLocalAppServerLaunchSource` prepares the assets, the network bridge and the model
  provider.
- `LocalAndroidNetworkBridge` starts a localhost HTTP bridge on `127.0.0.1:<random>` that letta.js
  fetches and resolves names through (`android-network-polyfill.cjs`), so outbound TLS is Android's.
- Device tools are not bridge routes: the session channel registers them on every
  `runtime_start` (`register_external_tools`), exactly as for an Iroh host, and answers their
  `external_tool_call`s in the app process (`DeviceActionExternalTool` over `DeviceActionCommandRunner`).

## Route model

| Route | Purpose |
| --- | --- |
| `POST /fetch` | Android-backed fetch/curl route. |
| `POST /dns/lookup` | Android-backed DNS route. |

## Auth model

The Android local bridge is both loopback-bound and bearer-token protected:

- `LocalAndroidNetworkBridge.start()` generates a random per-session token with `SecureRandom`.
- The token is URL-safe Base64 without padding, not persisted, not tied to user OAuth.
- Every HTTP bridge route requires `Authorization: Bearer <token>`; the launch passes it as
  `LETTA_ANDROID_NETWORK_BRIDGE_TOKEN` and the preload attaches it.
- The App Server itself requires the per-launch capability token (`--ws-auth capability-token`).

Debug-only ADB command activity does not use HTTP bridge auth because it calls `DeviceActionCommandRunner` directly in the app process and is only packaged in debug builds.

## Model-facing tool surface

- `device_action` for command-facade access to sensors (`sensors.summary|catalog|sample|snapshot`),
  hardware, intent dry-runs, and capability metadata. `device.catalog` lets the agent discover
  supported commands on demand rather than carrying every command schema in the prompt.

## Gaps vs. desktop

Android does not yet implement the full desktop device/status WebSocket protocol. Notable gaps:

- No desktop-compatible `/v1/environments/:id/ws` endpoint on Android.
- No control/stream/status WebSocket channel split.
- No CRDT file-op worker parity.
- No renderer-to-device protocol fixture coverage beyond the Android HTTP bridge.
- No desktop-style status fan-out for `stream_delta`, `run_started`, `run_completed`, or queue/device state.

These gaps are tracked separately so the current bridge can stay small and safe while future work explores protocol compatibility.

## Implementation principles

- Keep Android hardware/system access in app-process services.
- Prefer a shared command facade over many model-visible tools.
- Auth all local HTTP bridge traffic even on loopback.
- Keep debug ADB surfaces in debug source sets only.
- Treat reverse-engineering notes as behavioral reference only; use clean-room code and public/open-source contracts for implementation.
