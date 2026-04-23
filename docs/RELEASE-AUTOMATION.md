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

After that launch, the app should route through the authenticated path because
`SettingsRepository.activeConfig` is now populated before Compose navigation
chooses its start destination.

## Safety model

- Debug only: the importer implementation exists only under
  `android-compose/app/src/debug`.
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
