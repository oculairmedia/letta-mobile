# App Server Controller Deployment Guide

**Status:** Current repository command/reference guide; audited 2026-10-18. Live host health and deployed artifact identity must be checked separately.

## Two artifacts with different jobs

| Artifact | Module | Entry point | Responsibility |
|---|---|---|---|
| `meridian-app-server` | `:appserver-cli` | `app-server-serve`, `app-server-smoke` | Launch a local App Server through the shared host or drive smoke turns. |
| `meridian-iroh-wrapper` | `:iroh-wrapper-cli` | `app-server-serve-iroh` | Serve the authenticated Iroh endpoint, controller, admin RPC, pairing and host integrations. |

Build distributions from `android-compose/`, with Java/Android paths configured per [CONTRIBUTING](../../CONTRIBUTING.md):

```bash
./gradlew :appserver-cli:installDist :iroh-wrapper-cli:installDist
```

The module build files define distribution names. Do not deploy `meridian-app-server` as a replacement for the Iroh wrapper.

The launch examples below run from the repository root after `installDist`; use
the `.bat` launcher on Windows.

## Launch a local App Server

```bash
android-compose/appserver-cli/build/install/meridian-app-server/bin/meridian-app-server app-server-serve --backend-dir /absolute/path/to/backend --letta-command letta
```

[AppServerServeCommand](../../android-compose/appserver-cli/src/main/kotlin/com/letta/mobile/appservercli/AppServerServeCommand.kt) requires `--backend-dir` (or `LETTA_LOCAL_BACKEND_DIR`) and fences exclusive ownership before launch. `--letta-arg` is repeatable for arguments before `server`. `--unit` records the restart authority.

The shared [LocalAppServerHost](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/runtime/host/LocalAppServerHost.kt) launches `server --backend local` on an ephemeral loopback port with a fresh capability token. The command prints readiness and the token-file location once `/readyz` responds, waits for exit and does not restart it. The caller/service manager owns restart. Earlier examples using this command's `--listen`, `--ws-auth`, `--ws-token-file` or a fixed old npm pin are obsolete; those are not options of the current serve command.

## Smoke an existing server

```bash
android-compose/appserver-cli/build/install/meridian-app-server/bin/meridian-app-server app-server-smoke --url ws://127.0.0.1:<port> --token <capability-token> --agent <agent-id> --conversation <conversation-id> --message hello
```

[AppServerSmokeCommand](../../android-compose/appserver-cli/src/main/kotlin/com/letta/mobile/appservercli/AppServerSmokeCommand.kt) requires URL, agent, conversation and message (the first three also have `APP_SERVER_TEST_*` environment forms). The token is optional only when the target does not require auth. `--second-conversation` exercises concurrent turns; `--image` is repeatable. This sends a real turn to the selected agent.

## Serve Iroh

Use the [wrapper module](../../android-compose/iroh-wrapper-cli/), [deployment scripts](../../scripts/deploy/) and checked-in service templates for current deployment options. `AppServerServeIrohCommand` owns the Iroh identity/port/pairing and either wraps `--app-server-url` / `LETTA_APP_SERVER_URL` or uses its configured local-host path. Persist identity and pairing outside release directories.

The [shim-retirement runbook](lettashim-retirement-deployment-runbook.md) preserves the July/August migration evidence and rollback procedure. Its old host SHAs and removed flags must not be copied into a new deployment.

## Capability boundaries

[CapabilityNegotiator](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/controller/capability/CapabilityNegotiator.kt) and `FactoryVsExtendedDeploymentTest` cover injected baseline/extended capability sets and safe defaults. They are not proof that a stock server implements a custom advertisement endpoint, or that every product screen works directly against stock WS. Production wrapper tooling is wired in [AppServerServeIrohCommand](../../android-compose/iroh-wrapper-cli/src/main/kotlin/com/letta/mobile/cli/commands/AppServerServeIrohCommand.kt); exact adopted admin methods and denials are in the [ownership matrix](../../android-compose/sharedLogic/src/jvmTest/resources/appserver/iroh-admin-ownership-matrix.json).

The single source for the bundled/contract version is [letta-code-runtime.properties](../../android-compose/letta-code-runtime/letta-code-runtime.properties); the captured protocol and hashes are in [app-server-v2-contract-matrix.json](../../android-compose/sharedLogic/src/jvmTest/resources/appserver/app-server-v2-contract-matrix.json). A fixture proves its recorded package behavior, not the health or version of an arbitrary remote endpoint.
