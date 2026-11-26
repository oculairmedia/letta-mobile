# FFI v1: the combined library and its exported surface (core-ffi)

Status: normative for `core-ffi` (`core/crates/core-ffi`) and the Kotlin adapter in sharedLogic
(`src/jvmAndAndroid/kotlin/com/letta/mobile/data/node/core`). Builds on node-v1: everything behind the boundary is
core-node's. The runtime and binding model is core-architecture's §(c): one tokio runtime per node, listener plus
`TaskHandle` streams, two coarse foreign traits.

## 1. The library

- F-1 One shared library per platform ABI, `baken_core` (`libbaken_core.so`, `baken_core.dll`, `libbaken_core.dylib`),
  built from `core-ffi`, which links every core crate (the combined library, Mozilla's megazord). std, the allocator,
  tokio, iroh, automerge, samod and SQLite exist once per process. It replaces `iroh_ffi` and `automerge_jni` once the
  Kotlin callers move over; until then both kinds load side by side.
- F-2 Bindings are UniFFI, proc-macro style, at the exact version the workspace pins (`uniffi = "=0.33.0"`). The
  scaffolding is compiled into the library; the Kotlin is generated from the built library by the crate's own
  `uniffi-bindgen` binary (`--features bindgen`), so the generator and the scaffolding never disagree.
- F-3 Shipped builds use the `ffi-release` profile: `opt-level = "z"`, fat LTO, one codegen unit, symbols stripped,
  `panic = "unwind"` (a Rust panic in an exported call becomes an error in the caller instead of aborting the app).
- F-4 Targets: `x86_64-pc-windows-msvc` (desktop), and the four Android ABIs through cargo-ndk: `arm64-v8a`
  (`aarch64-linux-android`), `armeabi-v7a` (`armv7-linux-androideabi`), `x86` (`i686-linux-android`), `x86_64`
  (`x86_64-linux-android`), at Android API 26. CI builds and size-reports all of them (§8).
- F-5 Loading is JNA's, the same way `iroh_ffi` loads: on Android from `lib/<abi>/libbaken_core.so` in the APK (the
  library goes into the app's `jniLibs`), on desktop JVMs from `jna.library.path` or the classpath resource
  `<jna-os-arch>/baken_core.dll` (for example `win32-x86-64/`). Packaging the library into the APK and the desktop
  distribution is a later step, decided per platform together with wiring the node into the app.

## 2. Generated Kotlin

- F-6 Package `com.letta.mobile.core.ffi`, in sharedLogic's `jvmAndAndroid` source set under
  `src/jvmAndAndroid/generated/uniffi` (a generated tree: architecture scans, detekt and the KDoc gate skip it). It is
  committed and regenerated with:

  ```
  cd core
  cargo build -p core-ffi --lib --profile ffi-release
  cargo run -p core-ffi --features bindgen --bin uniffi-bindgen -- generate \
    --library target/ffi-release/<lib> --language kotlin --no-format \
    --out-dir ../android-compose/sharedLogic/src/jvmAndAndroid/generated/uniffi
  ```

  CI regenerates it from the Linux library and fails when the committed file differs (§8).
- F-7 Nothing outside the adapter package uses the generated types directly. The adapter exposes `CoreNodeHandle` (an
  interface), `callbackFlow` streams and `suspend` calls, and the Kotlin implementations of the foreign traits.

## 3. Exported surface

The surface is deliberately narrow: one object for the node, a handful of async methods, listener streams, and the
membership bootstrap helpers. Ids cross as lowercase hex strings, agent ids as their text.

| Rust | Kotlin | What |
|---|---|---|
| `CoreNode::new(options, transport, host, hooks)` | constructor | validates the options; nothing runs |
| `async start() -> node id` | `suspend start()` | key from `PlatformHooks`, SQLite store `data_dir/node.sqlite`, `Node::start`, transport bind, platform attach |
| `async stop()` | `suspend stop()` | detach, transport close, `Node::shutdown`; idempotent; a stopped node may start again |
| `is_running()`, `node_id()`, `address()` | | `address` is the id plus, on iroh, the endpoint's direct addresses and relay |
| `async connect(peer)` | `suspend` | `Node::connect`: a log session and a catalog sync kept with redial (node-v1 N-4) |
| `info()`, `runtime_parity()` | | node.info v1 (node-v1 §5), the I6 parity string |
| `membership()` | | the membership view snapshot |
| `async catalog_documents()` | `suspend` | the catalog registry |
| `async catalog_view_json(url)` | `suspend` | the typed view of one document (catalog-v1 §3.7) as JSON, against this node's membership |
| `async placement(agent_id)` | `suspend` | `PlacementStatus` from this node's copy of the log plus its input gate, or none |
| `subscribe_membership(listener)` | `callbackFlow` | current view at once, then one per change |
| `subscribe_catalog_events(listener)` | `callbackFlow` | `Violation`, `LinkClosed`, `Lagged` |
| `subscribe_node_events(listener)` | `callbackFlow` | `Stopping`, `ForegroundChanged`, `NetworkChanged` |
| `InProcessNetwork::new()` | constructor | nodes in one process sharing a network (tests, multi-node processes) |
| `node_id_of_secret_key`, `sign_genesis_op`, `sign_add_member_op` | functions | membership bootstrap: core-membership's ops, filled in and signed |

- F-8 `Transport` is `Iroh { bind_addr, relays }` (one endpoint per node; port 0 picks one; no relays means direct
  addresses only) or `InProcess { network }`.
- F-9 Catalog views cross as JSON on purpose: the typed views are wide and still growing, and a JSON string keeps the
  boundary from churning with every additive field. The registry, membership, placement and node.info cross as records.
- F-10 The membership view crosses as a whole snapshot on every change. Membership is small (a handful of devices);
  diffs are for collections that grow, which the catalog and the feed streams will be when they are exported.

## 4. Threading

- F-11 Every node owns its tokio runtime (node-v1 N-1); there is no global runtime and no `async_runtime` attribute.
  Every exported `async` call either awaits a core-node method that already runs on the node's runtime (`connect`,
  `start`, `stop`) or spawns its body onto the node's runtime and awaits the join handle (`hop`). The future UniFFI
  hands to Kotlin is therefore executor-agnostic: Kotlin's dispatcher polls it, tokio runs it.
- F-12 Synchronous calls (`info`, `membership`, `address`, `node_id`, `is_running`, `runtime_parity`) read in-memory
  state and return at once; the Kotlin adapter still calls them off the main thread.
- F-13 `start` and `stop` are serialised per node. A call made while the node is stopped fails `NotRunning`.
- F-14 Listener methods and foreign trait methods are called on the node's runtime threads. A listener must return
  quickly (the adapter's `trySend` into the flow's channel); a foreign `async` method may suspend as long as it needs
  (it does not hold a runtime thread while suspended).

## 5. Streams and cancellation

- F-15 A stream is `subscribe_x(listener) -> TaskHandle`. The task runs on the node's runtime and calls the listener.
  `TaskHandle.cancel()` aborts it: no listener call starts after `cancel` returns. Dropping the handle (Kotlin `close()`
  or garbage collection) cancels too. Cancelling twice, or after the task ended, does nothing. `is_finished()` reports
  whether it ended.
- F-16 Every stream ends when its node stops (the runtime that runs it shuts down). A broadcast stream whose listener
  fell behind reports `Lagged { missed }` (catalog events) and keeps going.
- F-17 Cancelling a Kotlin coroutine that awaits an exported `suspend` call is best effort: the Kotlin side stops
  waiting, but work already spawned on the node's runtime runs to its end. Long operations get their own handle with
  `cancel()` when they are exported (moves, full replica pulls); short ones are bounded inside the core.
- F-18 The Kotlin adapter wraps each stream as `callbackFlow { val handle = node.subscribeX(listener); awaitClose {
  handle.cancel(); handle.close() } }`, so collecting is subscribing and cancelling the collector cancels the task.

## 6. Foreign traits

- F-19 `RuntimeHost` (supervising letta-code; core-node's `RuntimeHost` over it): `info()`, `async start(spec) -> App
  Server URL`, `async stop(agent)`, `async quiesce(agent, timeout_ms) -> Idle | Busy`, `async checkpoint(agent)`,
  `async abort_turn(agent)`, `endpoint()`. The Kotlin implementation delegates to `LocalAppServerHost`: `start` acquires
  a lease per agent and returns its URL, `stop` closes it; turn-level calls go to a `RuntimeTurnControl` the platform
  supplies.
- F-20 `PlatformHooks`: `async node_secret_key() -> 32 bytes` (load or create in the platform's secure storage; the
  Kotlin implementation delegates to the existing `IrohSecretKeyStore`, so the core node and the Kotlin node share one
  identity), `attach(events)` when the node starts, `detach()` when it stops. While attached the platform calls
  `PlatformEvents.foreground_changed(bool)` and `network_changed()`; a network change tells the iroh endpoint to
  re-probe its paths at once and is reported on the node's event stream.
- F-21 A foreign method fails with `HostError` (`NotRunning`, `Failed { detail }`); anything else the platform throws
  arrives as `Failed`.

## 7. Errors

- F-22 Every fallible export fails with `CoreError` (Kotlin `CoreException.<Variant>`). The variant name is the stable
  code:

| Variant | When |
|---|---|
| `InvalidArgument { detail }` | an id, key, address, URL or op envelope does not parse |
| `NotRunning` | the call needs a running node |
| `AlreadyRunning` | `start` on a running node |
| `Store { detail }` | the node store failed |
| `Log { detail }` | the agent log refused or failed |
| `Projection { detail }` | export or materialize failed |
| `Catalog { detail }` | the catalog failed, or does not hold the document |
| `Transport { detail }` | the endpoint could not bind |
| `RuntimeHost { detail }` | the platform's runtime host failed |
| `Platform { detail }` | the platform hooks failed (the node key) |
| `Refused { reason }` | the node refused the request |
| `MoveAborted { reason }` | a move ended without committing |
| `Stopped` | the node's runtime or an agent actor stopped during the call |

## 8. Size

- F-23 CI builds the library with `ffi-release` for `x86_64-unknown-linux-gnu` and the four Android ABIs and reports
  each stripped size against a budget of 16 MiB per ABI, with a `cargo bloat --crates` breakdown of the Linux build.
  The budget is report-only in v1: it warns, it does not fail. It becomes a gate once the Android build is the shipped
  one.
- F-24 Measured when v1 landed (`ffi-release`, stripped): `x86_64-pc-windows-msvc` 13.0 MiB (13,615,104 bytes),
  `arm64-v8a` 10.5 MiB (10,981,632 bytes). The largest `.text` shares (cargo bloat, Windows): std 1.8 MiB, automerge
  0.5 MiB, rustls 0.4 MiB, tokio 0.3 MiB, iroh 0.3 MiB, noq-proto (QUIC) 0.3 MiB; the core crates together about 0.9
  MiB. cargo-ndk also copies iroh's own `libiroh*.so` build outputs next to the library; only `libbaken_core.so` ships.
