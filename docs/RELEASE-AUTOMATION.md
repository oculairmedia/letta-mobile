# Release automation bootstrap

Debug-only bootstrap for hands-off release verification.

The app ships a debug-only importer that reads a one-shot credential payload
from a plaintext staging preference, writes it into the normal encrypted
`SettingsRepository` flow, and immediately clears the staging key. Release
builds reject staged payloads, clear the staging key, and emit telemetry
instead of importing credentials.

## Preconditions

- A connected adb device or emulator.
- A **debug** install of the app (`run-as` must work for `com.letta.mobile`).
- A disposable non-production Letta identity.
- Credentials exported in the shell environment — never in committed files,
  PR bodies, or commit messages.

## Supported inputs

The supported interface is environment variables:

- `AUTOMATION_SERVER_URL` (or fallback `LETTA_SERVER_URL`)
- `AUTOMATION_ACCESS_TOKEN` (or fallback `LETTA_TOKEN`)
- optional `AUTOMATION_CONFIG_ID` (defaults to `automation-auth`)
- optional `AUTOMATION_MODE` (`CLOUD` or `SELF_HOSTED`)
- optional `ANDROID_SERIAL` for multi-device setups
- optional `APP_PACKAGE` / `APP_COMPONENT` if the install target changes

The staged payload is a base64-encoded JSON object with this exact shape:

```json
{
  "serverUrl": "https://api.letta.com",
  "accessToken": "token-value",
  "configId": "automation-auth",
  "mode": "CLOUD"
}
```

- `serverUrl` and `accessToken` are required.
- `configId` is optional and defaults to `automation-auth`.
- `mode` is optional; when omitted, the app derives `CLOUD` for
  `https://api.letta.com` and `SELF_HOSTED` otherwise.

## Usage

Install a debug build, then inject the credentials before running higher-level
release gates:

```bash
cd android-compose
./gradlew :app:installDebug

cd ..
export AUTOMATION_SERVER_URL="https://api.letta.com"
export AUTOMATION_ACCESS_TOKEN="<disposable-token>"
scripts/release/inject-automation-creds.sh
```

What the script does:

1. Verifies adb connectivity and that the debug package is installed.
2. Force-stops the app.
3. Uses `run-as` to write a staging preference at
    `shared_prefs/letta_automation.xml` inside app-private storage.
4. Launches `MainActivity`.
5. On app startup, the debug-only importer writes the payload into the normal
    encrypted `SettingsRepository` config store and deletes the staging key.

The importer emits a log marker that downstream automation can wait on:

```text
Imported automation credentials for <serverUrl>
```

After that launch, the app should route through the authenticated path because
`SettingsRepository.activeConfig` is now populated before Compose navigation
chooses its start destination.

## Device bootstrap gate

Phase 2 adds a higher-level helper that bootstraps a fresh device from APK
install through an authenticated conversation:

```bash
make verify-device-ready \
  DEVICE=<serial> \
  APK=/absolute/path/to/app-debug.apk \
  BASE_URL=http://192.168.50.90:8289 \
  API_KEY=<disposable-token> \
  AGENT=<agent-id> \
  CONV=<conversation-id>
```

The underlying script is `scripts/release/bootstrap-device.sh`.

It performs these steps:

1. uninstall the app if present
2. install the APK
3. launch `MainActivity`
4. inject automation credentials and wait for the auth-import log marker
5. launch Agent List explicitly
6. wait for the Agent List hydration marker
7. launch the target conversation
8. wait for the timeline hydration marker

Exit codes are step-specific so orchestration can classify the failure:

- `10` unexpected uninstall failure
- `11` install failure
- `12` initial app launch failure
- `13` auth injection or auth-import wait failure
- `14` Agent List launch or hydration wait failure
- `15` conversation launch failure
- `16` timeline readiness wait failure

The readiness-log contract is:

```text
AgentList hydrated count=<n>
Timeline ready conv=<conversation-id> count=<n>
```

These strings are part of the automation interface. If they change, update the
script and this document together.

`make verify-release` builds on this gate. When the full device bootstrap
prerequisites are present, the orchestrator runs `verify-device-ready` before
the conversation-dependent release gates and includes the result in
`reports/verify-release-<timestamp>.md`.

Current caveat: the bootstrap handoff reaches "timeline ready" before the
first guaranteed `ChannelHeartbeatSync` processed cursor is persisted. Until
`letta-mobile-r6j3.1` lands, `verify-sync` remains advisory in the
orchestrator even though the standalone gate still enforces 6/6 HEALTHY.

## Safety model

- Debug only: the importer implementation exists only under
  `android-compose/app/src/debug`.
- Debug-only cleartext override: `android-compose/app/src/debug/res/xml/network_security_config.xml`
  allows disposable self-hosted LAN endpoints such as `http://192.168.x.x` for
  automation runs. Release builds still use the main network security config and
  do not inherit this override.
- Release fail-closed: `android-compose/app/src/release` rejects any staged
  payload, clears the staging key, and emits `Telemetry.error` instead of
  importing credentials.
- One-shot import: malformed or successful payloads are cleared from the
  staging preference on first read.
- Credential persistence returns to the existing encrypted storage path after
  import.

## Local helpers

If you keep local shell helper files under `scripts/release/*.env`, they are
gitignored via `scripts/release/.gitignore`. The supported runtime interface is
still exported environment variables.
