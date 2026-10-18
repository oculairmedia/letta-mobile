# Mobile embedded bridge and desktop runtime hosting

**Status:** Current repository hosting comparison; source-audited 2026-10-18. Earlier Electron reverse-engineering notes were about the separate upstream desktop product, not Meridian's Compose Desktop host.

| Concern | Android | Meridian Compose Desktop / JVM |
|---|---|---|
| Shared lifecycle | `LocalAppServerHost` | `LocalAppServerHost` |
| Launcher | `LibnodeAppServerLauncher`, in-process libnode | `JvmLocalAppServerLauncher`, subprocess tree |
| Restart | Single node start per app process; ended runtime requires app restart | Host policy can supervise subprocesses; `ownedLocalAppServerHost` used by service-managed CLI stops after first exit so outer supervisor owns restart |
| Client channel | Shared session channel, direct loopback App Server dial for local runtime | Shared App Server/channel contracts with JVM host bindings |
| Host networking | Token-authenticated loopback Android fetch/DNS bridge | JVM subprocess networking |
| Device tools | `DeviceActionExternalTool` (`device_action`) handled in app process | Host external tools registered through shared controller/registry |

The shared [host contract](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/runtime/host/LocalAppServerHost.kt) launches `server --backend local --listen ws://127.0.0.1:0 --ws-auth capability-token`, observes the listen URL and verifies `/readyz` before leasing it. Android asset/provider preparation is [AndroidLocalAppServerLaunchSource](../../android-compose/app/src/main/java/com/letta/mobile/runtime/local/AndroidLocalAppServerLaunchSource.kt); JVM launch/policy factory is [JvmLocalAppServerLauncher](../../android-compose/sharedLogic/src/jvmMain/kotlin/com/letta/mobile/data/runtime/host/JvmLocalAppServerLauncher.kt).

## Android network bridge

[LocalAndroidNetworkBridge](../../android-compose/app/src/main/java/com/letta/mobile/runtime/local/AndroidNetworkBridge.kt) binds `127.0.0.1` on a random port, creates a random session token and requires bearer auth for `POST /fetch` and `POST /dns/lookup`. `android-network-polyfill.cjs` uses it for Android-backed TLS/fetch/DNS. The launch passes its endpoint/token to the runtime. The App Server socket has a separate capability token.

Device tools are not HTTP bridge routes. [DeviceActionExternalTool](../../android-compose/app/src/main/java/com/letta/mobile/runtime/actions/DeviceActionExternalTool.kt) calls the Android command runner, and the shared runtime/controller advertises external tools on `runtime_start`. Debug ADB activities are separate debug-only entrypoints.

No desktop control/stream/status socket split is required for local Android parity: current App Server is a single `/ws` socket. Protocol inventory and host integrations, rather than the obsolete Electron endpoint comparison, determine missing capabilities. See [embedded integration](embed-letta-code-integration.md) and [deployment guide](app-server-controller-deploy.md).
