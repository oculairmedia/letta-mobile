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

On Android, the trusted broker is the app process:

- `AndroidLettaCodeRuntimeController` prepares and starts the embedded Letta Code runtime.
- `LocalAndroidNetworkBridge` starts a localhost HTTP bridge on `127.0.0.1:<random>`.
- The bridge exposes Android-backed capabilities to embedded Letta Code.
- `AndroidDeviceSensorSnapshotProvider` and related services own no-permission device context.
- `DeviceActionCommandRunner` is the shared command facade for device actions.
- The embedded preload registers a compact model-facing tool surface and routes calls to the bridge.

## Route model

Current Android bridge routes include:

| Route | Purpose |
| --- | --- |
| `POST /device/actions/command` | Unified command facade used by `device_action`. |
| `POST /device/sensors/read` | Direct `read_sensors` compatibility route. |
| `POST /device/mobile-actions/intent` | User-mediated Android intent dry-run/launch route. |
| `GET /device/mobile-actions/capabilities` | Mobile action capability matrix. |
| `POST /device/mobile-actions/execute` | Generic mobile action registry execution route. |
| `POST /device/hardware/*` | Hardware capability/control routes. |
| `POST /fetch` | Android-backed fetch/curl route. |
| `POST /dns/lookup` | Android-backed DNS route. |

The long-term preferred model-facing route is `device_action` → `/device/actions/command`, because it keeps the model-visible tool list small while preserving structured commands.

## Auth model

The Android local bridge is both loopback-bound and bearer-token protected:

- `LocalAndroidNetworkBridge.start()` generates a random per-session token with `SecureRandom`.
- The token is URL-safe Base64 without padding.
- The token is not persisted and is not tied to user OAuth.
- The token rotates whenever the bridge/runtime restarts.
- Every HTTP bridge route requires `Authorization: Bearer <token>`.
- `AndroidLettaCodeRuntimeController` injects the token as `LETTA_ANDROID_NETWORK_BRIDGE_TOKEN`.
- The embedded preload and native curl bridge automatically attach the bearer token.

Debug-only ADB command activity does not use HTTP bridge auth because it calls `DeviceActionCommandRunner` directly in the app process and is only packaged in debug builds.

## Model-facing tool surface

The embedded runtime currently exposes a compact tool surface:

- `read_sensors` for direct no-permission device context reads.
- `device_action` for command-facade access to sensors, hardware, intent dry-runs, and capability metadata.

`device_action` supports `device.catalog` so the agent can discover supported commands on demand rather than carrying every command schema in the prompt.

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
