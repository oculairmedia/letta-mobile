# Embed Letta Code as on-device runtime - integration design

**Status:** Implemented local App Server reference with historical spike sections; source-audited 2026-10-18.

Current code is [LocalAppServerHost](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/runtime/host/LocalAppServerHost.kt), [LibnodeAppServerLauncher](../../android-compose/app/src/main/java/com/letta/mobile/runtime/local/LibnodeAppServerLauncher.kt) and [AndroidLocalAppServerLaunchSource](../../android-compose/app/src/main/java/com/letta/mobile/runtime/local/AndroidLocalAppServerLaunchSource.kt). [Runtime pins](../../android-compose/letta-code-runtime/letta-code-runtime.properties), the [embedded CI workflow](../../.github/workflows/embedded-runtime-node18.yml) and [release configuration](../../.github/workflows/release.yml) define packaging and validation. The Decision/Spike bead statuses and TL;DR compatibility story below are May/June history, not unresolved present-day blockers.

**Decision bead:** `lcp-59c` (P1, open)
**Spike bead:** `lcp-59c.1` (P1, open)
**Filed by:** Emmanuel + Meridian, 2026-05-26 01:30am EDT.

This doc records the original project-side design for the
embed-Letta-Code-via-nodejs-mobile pivot. It is grounded in a source-tree review
of `sepivip/SeekerClaw`, a shipping production app using the same pattern, and
a live Day 0 spike confirming Node-version compatibility.

## Original May/June spike summary

- Letta Code is TS/Node end-to-end and already has `letta --backend local`.
- Instead of reimplementing the local runtime in Kotlin, embed nodejs-mobile in
  the Android app and ship actual `letta.js` as the on-device runtime.
- Day 0 result: `letta.js` from `@letta-ai/letta-code` 0.26.1 boots on Node
  18.20.8. Newer 0.26.x releases require Node `>=22.19.0` and must not be
  bundled with the nodejs-mobile v18 runtime.
- App Server gate, 2026-06-24: embedded `@letta-ai/letta-code` 0.26.1 does not
  expose `letta app-server`. Host 0.27.15 exposes App Server and was smoke-tested
  on Node v24.13.1, but requires Node `>=22.19.0`, so Android loopback App Server is blocked until either
  nodejs-mobile is upgraded past Node 22.19 or Letta Code ships a Node-18
  App Server-compatible build.
- Use nodejs-mobile v18.20.4 prebuilt, following the SeekerClaw bridge pattern.
- The local runtime slot must remain pluggable so LettaCode can coexist with
  future engines such as Koog.

## Shape (letta-mobile-by4vm.110.6)

Android hosts the same thing every client hosts: a stock `letta.js server --backend local --listen
ws://127.0.0.1:0 --ws-auth capability-token` on nodejs-mobile, supervised by the shared
`LocalAppServerHost` (`sharedLogic/.../data/runtime/host/`) and launched in process by
`LibnodeAppServerLauncher`. A LOCAL_RUNTIME session's channel (`IrohChannelTransport` with an
`AndroidLocalRuntimeDialer`) takes a lease, dials `ws://127.0.0.1:<port>` with the lease's token
through the shared `openAppServerWebSocket`, and answers `admin_rpc` in process with the router an
Iroh host runs (`AdminRpcRegistry.buildRouter` over the dial's client). Chat, timeline, agents,
conversations, fork, dream and device tools (`runtime_start.register_external_tools`) are the
same code paths as an Iroh host. The headless stream-json protocol, its Kotlin mapper and the
stdin half of the JNI bridge are gone.

```text
Compose UI
  -> session channel (IrohChannelTransport, direct dial)
  -> ws://127.0.0.1:<port> + capability token
  -> letta.js server --backend local   (libnode, in process)
```

The Android implementation follows these constraints:

- Node can only be started once per OS process: a stopped runtime asks for an app restart
  (`LocalAppServerHost.RESTART_THE_APP`; Settings shows the runtime's health, there is no Stop).
  The host starts node at most once: the one start runs in the host's scope and every dial waits
  on it, so a cancelled or timed-out dial never abandons a starting node. Once that launch is over
  (node exited, or ended before it was ready) every dial fails with
  `LocalAppServerTerminatedException`, a `TerminalConnectionFailure`: the channel supervisor stops
  redialling and chat, the agent list and the model picker show "On-device runtime stopped.
  Restart the app." (letta-mobile-by4vm.121.11).
- The runtime's lifecycle (launch, asset time, listen line, readyz, exits, refusals, node's
  stdout/stderr) is logged at INFO under `LocalAppServer` and kept in
  `files/embedded-lettacode/runtime-lifecycle.log` (128 KiB, one rotated `.1`), so the first failure
  outlives logcat: `adb shell run-as <package> cat files/embedded-lettacode/runtime-lifecycle.log`,
  or Settings > Developer > Copy runtime log on a debug build.
- `node::Start()` blocks forever and runs on a dedicated background thread; stdout/stderr are
  pumped back over JNI (the listen line), stdin is a pipe nobody writes.
- Assets are extracted from APK assets into `filesDir` before Node starts (the first start after
  an install takes up to a minute; dials wait up to three minutes).
- `HOME` and `LETTA_LOCAL_BACKEND_DIR` point into app-private storage; the model comes from the
  runtime's own `providers/auth.json` (`lc-lmstudio` at the on-device LiteRT bridge or the
  configured model server), written before every dial.

## Android Implementation Notes

- Default app builds keep native/assets packaging disabled so CI does not need
  a vendored `libnode.so` or a network npm install.
- Build with `-PembedLettaCodeNative=true -PembedLettaCodeAssets=true` after
  adding nodejs-mobile `libnode` under `android-compose/app/libnode`.
- `prepareEmbeddedLettaCodeAssets` installs the pinned `@letta-ai/letta-code`
  (`lettaCodeVersion` in `android-compose/letta-code-runtime/letta-code-runtime.properties`)
  into generated APK assets when asset packaging is enabled.

## App Server Compatibility Gate

**Resolved (2026-10-16, letta-mobile-by4vm.109/.110.2):** the Node >= 22.19 requirement is only
the `engines` field. Stock 0.34.14 runs on nodejs-mobile 18.20.4 with the `node18-compat.cjs`
preload, and that is the embedded pin now (`letta-code-runtime/letta-code-runtime.properties`);
the loopback App Server launch is letta-mobile-by4vm.110. The runtime ships in the `full` release
APK (`docs/RELEASE.md`). The history below is kept for context.

The embedded App Server wedge was version-blocked:

| Target | Node requirement | App Server evidence | Android status |
| --- | --- | --- | --- |
| `@letta-ai/letta-code@0.26.1` | `>=18` | `letta app-server --help` falls back to the normal CLI help; package tarball has no `app-server` or protocol-v2 assets. | Compatible with nodejs-mobile 18.20.4, but no App Server entrypoint. |
| `@letta-ai/letta-code@0.27.15` | `>=22.19.0` | Host CLI exposes `app-server` and protocol-v2 assets; local loopback smoke reached `runtime_start -> input -> stream_delta -> sync -> abort_message` on Node v24.13.1. | App Server available for host adapters, but incompatible with the current Android nodejs-mobile 18.20.4 runtime. |
| `@letta-ai/letta-code@0.34.14` (the pin) | `>=22` declared | Boots on 18.20.4 with `runtime/node18-compat.cjs` as the first preload (the App Server path needs only the `crypto` global; `toWellFormed` and `getBuiltinModule` are the other shims hit). `embedded-runtime-node18` CI proves headless and `server --listen` on every relevant PR. | App Server loopback is implemented via LocalAppServerHost and LibnodeAppServerLauncher. The headless smoke remains CI coverage, not the app protocol. |
| nodejs-mobile Android | 18.20.4 | Current pinned embedded libnode in `android-compose/letta-code-runtime/letta-code-runtime.properties`. | The engine every bump must boot on. |

### CI: `embedded-runtime-node18`

`.github/workflows/embedded-runtime-node18.yml` (not a required check) runs on PRs that touch
`letta-code-runtime/`, the staging task in `build-logic/.../embedded/`, `scripts/embedded-runtime/`,
`appserver-cli/`, the shared `LocalAppServerHost` package or the workflow, plus every main push:

1. `:app:prepareEmbeddedLettaCodeAssets -PembedLettaCodeAssets=true` stages the exact tree the APK
   ships (Node 22, as `release.yml`); `:appserver-cli:installDist` builds the shared Kotlin host and
   client.
2. `scripts/embedded-runtime/node18-smoke.sh` on Node `libnodeVersion` (18.20.4): fails when the
   gzip'd staged tree exceeds **28 MiB** (0.34.14 stages 26.4 MiB gzip, ~123 MiB raw; raw size is not
   budgeted), then runs the tree with the shipped preloads in device order behind
   `android-platform-emulation.cjs` (`process.platform = "android"`, no `Intl`) against
   `fake-llm.cjs`: a CLI stream-json Bash turn (it creates the agent), then `letta server` through
   `LocalAppServerHost` (`app-server-serve`) driven by the shared `AppServerClient`
   (`app-server-smoke`: `runtime_start` -> Bash tool -> `end_turn`).
3. A failure prints the first missing-API line from the runtime's stderr (for example
   `crypto is not defined` with the compat preload removed) as an annotation and, with the stderr
   tail, in the job summary; logs are uploaded as `embedded-runtime-node18-logs`.

Run it locally without touching the host runtime: stage with the Gradle task above, then
`docker run --rm --network none -v "$PWD:/repo:ro" -v <jdk>:/jdk:ro -e JAVA=/jdk/bin/java
node:18.20.4-bookworm bash /repo/scripts/embedded-runtime/node18-smoke.sh
/repo/android-compose/app/build/generated/embedded-lettacode-assets/letta-code/nodejs-project
/repo/android-compose/appserver-cli/build/install/meridian-app-server`.

What it cannot see: V8's ICU-backed `\p{...}` regex support (the desktop Node has it, the device
does not), the JNI bridge, Android's network bridge and device tools. Those stay on the Pixel
sign-off (letta-mobile-by4vm.110.7).

## Native Dependency Risks

| Dependency | Risk | Mitigation |
| --- | --- | --- |
| `node-pty` | Android has no PTY. | Stub or make tool path non-PTY. |
| `sharp` | Native libvips. | Feature flag image features if needed. |
| `@vscode/ripgrep` | Android binary availability. | Fall back to JS search. |
| `ws` | Pure JS. | No action needed. |

## Historical Spike Gates

1. `node::Start()` boots hello.js in a foreground service.
2. Bundled `letta.js --backend local` reaches a steady state.
3. Local backend writes under app-private storage.
4. Kotlin can discover the local runtime endpoint.
5. Compose can send a message over loopback and stream a response.

Hard stop if Node cannot boot, `node-pty` cannot be stubbed, boot time exceeds
30 seconds on a real device, or APK size exceeds 150 MB.

## Historical Roadmap Impact

If this works, `letta-mobile-hua6` is no longer the primary implementation plan
for Android. It should remain available as a future pluggable engine experiment,
not as the only local runtime slot. The Android app should own a stable local
runtime host contract that can load `LocalLettaCode` now and other engines later.
