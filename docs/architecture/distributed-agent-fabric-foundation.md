# Distributed agent fabric foundation

**Status:** Accepted for foundation work; production packaging remains gated on
the Automerge Java license-file issue.

## Decision

Build Meridian's replicated agent state on **Automerge Java 0.0.9 and its
embedded Samod repository runtime**, transported over Meridian's existing
`computer.iroh` endpoint. Do not add PEAT as a runtime dependency and do not
create another Iroh endpoint for replication.

The integration boundary is Automerge Java's message-oriented
`org.automerge.repo.Transport`. A Meridian adapter frames those messages on a
dedicated Iroh ALPN and maps them to one bidirectional stream. The host retains
endpoint identity, discovery, authorization, and lifecycle ownership.

This decision deliberately stops before defining Home authority, execution
leases, or agent placement. Those policies belong above a convergent replicated
store and must not be baked into the transport.

## Why not PEAT

PEAT demonstrates the right architecture, but its published artifacts are the
wrong integration boundary for Meridian.

`peat-mesh` 0.9.0-rc.64 can accept an externally created Rust `iroh::Endpoint`
through `AutomergeBackend::with_iroh_parts`. The published Android
`com.defenseunicorns:peat-ffi:0.1.7` API cannot accept Meridian's opaque
`computer.iroh.Endpoint`, however. It creates a second native Iroh runtime and
endpoint inside `libpeat_ffi.so`.

Direct adoption would also add:

- a 38.2 MB compressed AAR;
- 20.5-35.6 MB of native code per ABI, including a second Iroh/Tokio/QUIC stack;
- BLE, lite-bridge, tactical hierarchy, QoS, routing, and security code that
  Meridian does not need;
- no x86 Android ABI;
- another native packaging and 16 KiB page-alignment surface; and
- an FFI surface that exposes node creation, not the reusable Automerge store
  and sync APIs Meridian needs.

PEAT remains a reference implementation. Reuse its patterns for bounded sync,
backoff, tombstones, JSON conversion, and store observability where useful;
preserve Apache-2.0 attribution for any copied code.

## Why Automerge Java

Version 0.0.9 is substantially more complete than earlier releases. In addition
to `Document`, `Transaction`, and `SyncState`, it ships a Samod-backed repository:

- `Repo.create` and `Repo.find`;
- `DocHandle.withDocument` and change listeners;
- `Storage`, `FileSystemStorage`, and `InMemoryStorage`;
- `Dialer`, `AcceptorHandle`, reconnect/backoff, and peer announcements; and
- a generic `Transport(Sender, closer)` that accepts arbitrary message bytes.

That removes the need to implement document actors, persistence scheduling,
peer handshakes, repository discovery, and per-document sync state in Kotlin.
Meridian only needs the domain projection, storage adapter, Iroh framing, and
endpoint routing.

Published coordinates and native payload:

| Surface | Artifact | Measured size |
| --- | --- | ---: |
| JVM API and native binaries | `org.automerge:automerge:0.0.9` | 17,234,831 bytes |
| Android native AAR | `org.automerge:androidnative:0.0.9` | 8,639,751 bytes compressed |
| Android JNI, all ABIs | `libautomerge_jni_0_2_0.so` | 24,438,224 bytes uncompressed |
| Android arm64 JNI | same | 6,408,560 bytes |

The Android AAR supports `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`, has
`minSdk 26`, and is built with a 16 KiB maximum page-size linker setting. That
matches Meridian's minimum SDK and ABI set. Its native library names do not
collide with Iroh's `libiroh_ffi.so`.

The Java artifact is Java 8 bytecode and works with Meridian's JVM 17 targets
and JDK 21/26 runtimes. The implementation must remain in `jvmAndAndroid`; it
cannot enter `commonMain` or the host-native target.

## Code-verified interoperability

`AutomergeIrohRepoInteropTest` performs the integration rather than inferring
it from APIs:

1. Load Automerge JNI and bind two `computer.iroh:iroh:1.1.0` endpoints in the
   same JVM.
2. Create two Automerge `Repo` instances with independent filesystem stores.
3. Adapt one Iroh bidirectional stream on each side to Samod `Transport`.
4. Create and mutate a document on one repo.
5. Wait for the second repo to receive the document and its field values.
6. Close the second repo, reopen it from disk, and verify the replicated value.

The probe passes on Windows x86-64 with JDK 21:

```text
./gradlew :sharedLogic:jvmTest \
  --tests com.letta.mobile.data.transport.iroh.AutomergeIrohRepoInteropTest \
  -DrunIrohLiveE2E=true

BUILD SUCCESSFUL
```

The test is opt-in because it binds live loopback QUIC endpoints, matching the
repository's existing Iroh E2E policy. The first version incorrectly treated a
document announcement as content convergence; the corrected probe waits for the
field value, which captures the real Samod lifecycle.

`Endpoint.online()` is intentionally absent from the loopback probe. With
`RelayMode.disabled()` it did not complete on this Windows host, while direct
address QUIC sync worked without it. Production endpoint lifecycle remains the
host owner's responsibility.

## Required architecture

### One endpoint owner per process

The current code has several endpoint owners:

- `IrohChannelTransport` binds client endpoints;
- `IrohNodeEndpoint` owns the server accept loop;
- desktop gateway factories can bind another endpoint; and
- `A2aWiring` deliberately binds a second endpoint with the same key.

Replication must not add another owner. First introduce an injected endpoint
and protocol router that:

- advertises all host ALPNs;
- accepts each incoming connection once;
- reads `Accepting.alpn()` or `Connection.alpn()`;
- dispatches App Server, A2A, and Automerge connections to registered handlers;
- exposes outbound connect/open-stream operations without transferring endpoint
  ownership; and
- owns ordered, idempotent shutdown.

Adding an ALPN to `IrohNodeEndpoint` without routing is incorrect: its current
accept loop sends every connection to `IrohNodeConnection`.

### Source-set boundary

Place only transport-neutral contracts and agent projections in `commonMain`.
Keep `org.automerge`, `computer.iroh`, Java filesystem APIs, Room, and JNI in
`jvmAndAndroid` or platform modules.

Recommended shape:

```text
sharedLogic/commonMain
  ReplicatedAgentStore contract
  agent document/schema projection
  conflict and tombstone projection

sharedLogic/jvmAndAndroid
  AutomergeReplicatedAgentStore
  IrohAutomergeTransport
  protocol framing and lifecycle

core/data (Android)
  Room-backed Automerge Storage adapter, if filesystem storage is insufficient

desktop / iroh-wrapper-cli
  filesystem Storage path and endpoint-owner wiring
```

Do not reuse the existing Room `agents` table. It is a disposable cache and is
cleared during session graph creation. Replicated state needs its own durable
storage namespace.

### Transport semantics

Samod's `Transport.Sender.send` is synchronous while Iroh `writeAll` is
suspending. The spike blocks Samod's dedicated I/O executor with `runBlocking`
and serializes writes with a mutex, preserving send success/failure semantics.
Production may keep that model or use a bounded writer actor, but it must not
turn a failed write into a successful enqueue or permit unbounded buffering.

Messages use unsigned four-byte big-endian length framing with a 4 MiB initial
cap. Production framing must keep partial-prefix handling, payload bounds, and
connection-generation fencing.

## Risks and gates

1. **License text:** Maven metadata and the build declare MIT, but the
   `automerge-java` repository and v0.0.9 tag contain no LICENSE/COPYING/NOTICE
   file. Do not ship it until upstream supplies the license text or confirms the
   distribution terms in a form suitable for Meridian's notices inventory.
2. **Android release packaging:** before production adoption, run dependency
   resolution, native-load instrumentation, 16 KiB ELF verification, R8/minify,
   and APK size measurements on every shipped ABI.
3. **Lifecycle:** test session rebuild, reconnect, process restart, and shutdown
   under active sync. The repository owns worker executors; endpoints remain
   host-owned.
4. **Authorization:** Samod's announce policy controls which documents are
   advertised, not who may author an accepted change. Home authority and signed
   execution leases require a separate application-layer protocol.
5. **Schema evolution:** version the replicated schema independently from the
   transport ALPN and preserve unknown fields during projection.

## Rejected alternatives

- **Direct PEAT AAR:** duplicates Iroh and carries a large, unrelated native
  stack.
- **Direct `peat-mesh` Rust crate:** endpoint sharing works only inside the same
  Rust process; adopting it would require replacing Meridian's current Iroh FFI
  boundary with a custom combined native library.
- **Handwritten Kotlin Automerge sync repository:** unnecessary now that
  Automerge Java exposes Samod `Repo` and generic `Transport`.
- **A second replication endpoint:** creates duplicate identity/address records,
  relay registrations, sockets, and shutdown paths.
- **Replicating the current Room agent cache:** session initialization deletes
  it, and its revision model is not causal or multi-writer safe.

## Next implementation slice

Before Home authority, implement the reusable endpoint/ALPN router and make the
existing App Server listener a registered protocol handler. Then add the
Automerge repository behind a platform-neutral `ReplicatedAgentStore` contract,
with JVM and Android packaging tests. Home election and execution leases begin
only after two hosts can converge and reload replicated agent state through
that path.
