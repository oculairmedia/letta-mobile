# Plugin trust model

What the host guarantees before any plugin runs (epic `letta-mobile-cdgpr`, "trust minimum"). Each
guarantee names the test that pins it. If you change the code behind a guarantee, its test must
still pass. If a guarantee changes, update this table in the same PR.

Tests live in `android-compose/sharedLogic/src/jvmTest/kotlin/com/letta/mobile/data/plugin/host/`
unless the path says otherwise. They run against real subprocesses (`FakeLcpPlugin`), the real
`PluginHostRuntime`, the real handlers and the real view bridge.

## Guarantees

| # | Guarantee | Where it is enforced | Pinned by |
|---|---|---|---|
| 1 | A plugin whose recorded consent does not cover the installed version and everything its manifest asks for (for example a hand-edited or stale `plugins.json`) is never spawned. It goes Faulted and its tools are withdrawn. | `PluginDriverHost.launch` (`InstalledPlugin.consented`) and `PluginRegistryReducer` (enable refused without consent) | `PluginTrustTest.aConsentNotCoveringTheInstalledVersionNeverStartsTheDriver`, `PluginHostRuntimeTest.enableWithoutConsentIsRefusedAndPartialConsentIsNotRecorded` |
| 2 | The plugin's environment is `PATH` plus the manifest's declared `env` substitutions. No host variable gets through, including a planted `LETTA_TOKEN`. | `ProcessLaunchPlanner.plan`, `ProcessLaunchPlan.start` (clears the environment) | `PluginTrustTest.theEnvironmentIsPathAndDeclaredSubstitutionsOnlyNeverAHostSecret` (the jvmTest task plants `LETTA_TOKEN`) |
| 3 | The working directory is the package directory. A `runtime.command` or `runtime.cwd` that leaves the package is refused before anything is spawned. | `ProcessLaunchPlanner.inside` | `PluginTrustTest.theWorkingDirectoryIsThePackageAndAPathEscapeIsRefusedBeforeSpawning` |
| 4 | Memory, CPU and task caps are applied: `systemd-run --user --scope -p MemoryMax -p CPUQuota -p TasksMax` when available, otherwise `--max-old-space-size` for a node runtime. | `ProcessLaunchPlanner.scopePrefix` and `ProcessLaunchPlanner.plan` | `PluginTrustTest.memoryCpuAndTaskCapsAreInTheLaunchPlan` |
| 5 | Stopping a plugin sends SIGTERM and then SIGKILL once the grace runs out. The process tree is gone afterwards. | `ProcessPluginDriver.terminate` | `PluginTrustTest.aPluginIgnoringSigtermIsSigkilledOnceTheGraceRunsOut` |
| 6 | Three crashes inside the window fault the plugin. It is not restarted again, and its agent tools are withdrawn. | `CrashLedger`, `ProcessPluginDriver.supervise`, `PluginHostRuntime.onDriverState` | `PluginTrustTest.threeCrashesFaultThePluginAndWithdrawItsTools` |
| 7 | Secrets never reach `host.log` telemetry, stderr telemetry, an action result, or an emitted element's props. Logs and stderr are scrubbed, and an emit or result that carries a secret is refused. | `LcpSecretGuard`, `HostLcpHandlers.log`, `ProcessPluginDriver.onStderr` | `PluginTrustTest.secretsNeverReachHostLogStderrTelemetryResultsOrPlacedElements` |
| 8 | `host.readElements` is refused, both by the session's capability guard and by the handlers, because contract v1 does not grant `canvas:read`. | `LcpCapabilityGuard`, `HostLcpHandlers.readElements` | `PluginTrustTest.readElementsIsRefusedBySessionAndHandlers` |
| 9 | A plugin may place only its own kinds and touch only its own elements. A foreign kind or element refuses the whole emit, and nothing is written. | `PlacementCompiler.checkOwnership` | `PluginTrustTest.aForeignKindOrElementRefusesTheWholeEmit` |
| 10 | Assets are limited to the allowed media types and to the per-plugin byte quota. | `HostLcpHandlers.storeAsset`, `PluginAssetQuota` | `PluginTrustTest.anAssetOfAnotherTypeOrOverTheQuotaIsRefused` |
| 11 | Every view message, view actions included, is rate limited by default. | `ViewBridge` (`ViewRateLimiter`) | `PluginTrustTest.viewMessagesAreRateLimitedByDefault` |
| 12 | A page opens a link only when it declares `ui:openLink`, the owner consented to it, and the link policy allows the link. The defaults are `DenyAll`. | `ViewCallHandler.openLink`, `ViewBridgeServices` defaults | `PluginTrustTest.linksAreDeniedUnlessTheOwnerConsentedToUiOpenLink` |
| 13 | `--plugins=off` constructs no runtime, registers no tool source and leaves the plugins dir untouched. | `startPluginHost` (iroh-wrapper-cli) | `iroh-wrapper-cli/src/test/kotlin/com/letta/mobile/cli/commands/PluginHostWiringTest.pluginsOffConstructsNothingAndPublishesNoTools` |
| 14 | `disableAll()` stops every driver and withdraws every tool. | `PluginHostRuntime.disableAll` | `PluginTrustTest.disableAllStopsEveryDriverAndWithdrawsEveryTool` |

Related guarantees with their own tests: LCP method deadlines and the 4 MiB message cap
(`ProcessPluginDriverTest.aMissedDeadlineFailsTheCallButTheDriverKeepsRunning`,
`ProcessLaunchPlanTest.stdioDropsAnOversizedLineAndForwardsStderr`), emit and log rate limits
(`PluginRateLimitsTest`, `HostLcpHandlersTest`), and the bus variables stripped under a systemd scope
(`ProcessPluginDriverTest.underASystemdScopeTheBusVariablesNeverReachThePlugin`, which skips where
`systemd-run --user` is unavailable).

## Telemetry

Every plugin event is a typed `PluginHostEvent`, reported as `plugin.<wire>` under the `PluginHost`
tag through `PluginHostEvents`. The events are install, update, consent, enable, disable, remove,
active, fault, restart, invoke (with latency and outcome), refused (with reason), asset (bytes),
log, stderr (scrubbed, WARN), view_open, view_fail, kill_switch, registry_unreadable and
quota_store_failed.
`PluginTelemetryTest.everyEventInTheSetIsEmittedBySomePath` fails if any event has no emitter or if
an untyped one appears.

## Known gaps

- The package is hashed at install only. At launch, a modified file inside the unpacked package
  directory is not detected. Consent is checked against the registry's manifest, not the bytes on disk.
- The runtime does not have a secrets store yet (`letta-mobile-cdgpr.14`), so a plugin started
  through `PluginHostRuntime` runs with no secrets. Guarantee 7 is pinned at the driver and handler
  level, which is where secrets will enter.
- The per-client view kill switch is not built yet (`letta-mobile-cdgpr.8`). Only the runtime
  (`disableAll`) and global (`--plugins=off`) kill switches emit `kill_switch`.
