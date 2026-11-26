# Node carrier v1: Rust core notes

Status: **implemented by `core/crates/core-carrier` (SPI, in-process carrier, contract suite) and
`core/crates/core-carrier-iroh` (the Iroh adapter). Both pass the node-carrier v1 fixture.** The normative contract is
[node-carrier-v1.md](node-carrier-v1.md); this file records how the Rust core maps onto it and where it adds to it. It
changes no wire behaviour.

Fixture: `android-compose/sharedLogic/src/commonTest/resources/contracts/node-carrier/v1.json`, the same file the Kotlin
`NodeCarrierContractSuite` reads (override with `BAKEN_NODE_CARRIER_FIXTURE`).

## SPI mapping

| Kotlin | Rust (`core_carrier`) | Notes |
| --- | --- | --- |
| `NodeCarrier.dial` | `Carrier::dial(&NodeAddr, alpn) -> DialedLink` | the ALPN is explicit: one carrier serves every protocol of a node |
| `NodeLink` | `Link` + `DialedLink::inbound` (`InboundSource`) | inbound frames come with their `Lane` |
| `NodePeerLink` | `PeerLink` + `AcceptedLink::{control, calls}` | |
| `NodeCallLane` | `CallLane { sink: FrameSink, source: FrameSource }` | both ends hold the same shape |
| `NodePeerAcceptor` | `ProtocolHandler::serve(AcceptedLink)` | registered per ALPN in `Protocols` |
| (none) | `AccessHook`, `MembershipQuery`, `MembershipAccess` | admission before any handler (below) |
| `NodePeerIdentity` | `VerifiedPeer::{Node(NodeId), LocalShell}` | |
| `NodeLinkClosure`, `LOST` | `LinkClosure::{App(AppClose), Lost}` | `From<core_proto::closure::Closure>` |
| `NodeCarrierException` | `CarrierError::{Unreachable, Closed, Frame, Finished, NoProtocol}` | |

Rule 5 (single collectors) holds by construction: every receiver is an owned value returned beside its link, so it can
only be read by whoever holds it.

## Additions to v1

- **R-1 Access hook.** A node's `Protocols` carry one `AccessHook`. A carrier runs it with the peer's verified identity
  and the ALPN before any `ProtocolHandler` sees the connection; `Reject(close)` closes with that code and reason. The
  Iroh carrier runs it in iroh's `EndpointHooks::after_handshake` for incoming connections only, so a refused peer's
  connection is closed before iroh's router dispatches it. The in-process carrier runs it in `dial` and fails the dial
  with `Closed(close)`. A dialler must accept either outcome: the dial failing `Closed`, or a link that ends with the
  refusal (a QUIC dialler can finish its handshake before the node's hook runs).
- **R-2 Membership.** `MembershipAccess` admits `LocalShell` and members (from a `MembershipQuery`), and refuses other
  NodeIds `4403 peer_not_allowed`. ALPNs marked `open_to_all` admit non-members, so a device redeeming an invite still
  reaches the session's `auth`. N-1.3's per-protocol authorization (`4403 protocol_not_authorized`) stays the
  handler's job.
- **R-3 Carriers serve every ALPN with the same lanes.** The lane mapping (first stream control, second push, later
  calls) applies to whatever ALPN a handler is registered for.
- **R-4 After `finish`**, a call lane's `send` fails `Finished`, not `Closed`.

## Iroh carrier

- iroh pinned `=1.5.0`, default features off, `tls-ring` on. iroh 1.x declares its wire stable across 1.x; the Kotlin
  side is `computer.iroh` 1.2.0.
- One `IrohNode` per node: one `Endpoint` (one key) and one iroh `Router` with every ALPN of the node's `Protocols`.
  `IrohNode::carrier()` dials from that same endpoint, so outgoing links prove the node's own NodeId.
- Dialler: `connect`, then `open_bi` (control), then `open_bi` (push) with its send half finished at once (N-2.1). It
  writes `frame_part`s when a frame exceeds 1 MiB and reassembles up to 32 MiB.
- Node: first `accept_bi` is control, second is push (it reads at most one byte of it), every later one is a call lane.
  It writes plain frames only until `on_peer_capabilities` reports `frame_part`, and its decoders hold the pre-auth
  1 MiB reassembly limit until then (N-5.4, N-5.7). A frame the peer cannot read fails `CarrierError::Frame` and the
  link stays open. A stream that breaks the codec ends that lane.
- Closure: `close` records the closure, then closes the QUIC connection with the code (capped at 2^62 − 1) and the
  reason bytes. A remote application close maps back unchanged; any other connection error is `Lost`. A `Link` that
  is dropped while open closes as `0 closed` (a detach).
- An unserved ALPN fails the handshake; the Iroh carrier reports it as `Unreachable` (the error does not say which
  ALPN was missing), where the in-process carrier reports `NoProtocol`.

## In-process carrier

`InProcessNetwork` holds nodes (by NodeId) and the fault model; `carrier(identity)` dials as that identity. Frames
cross as owned strings. Faults, per unordered pair of endpoints:

| Hook | Effect |
| --- | --- |
| `set_delay(a, b, d)` | every frame arrives `d` after it was sent, in order; the sender does not wait |
| `set_loss(a, b, ppm)` | each frame loses the link with probability `ppm`/10^6 (seeded draw): both ends see `Lost` and the frame is not delivered. Over reliable streams, loss shows up as a lost link, never as a gap in a lane |
| `partition(a, b)` / `heal` | dials fail `Unreachable`; open links between them are lost |
| `sever(a, b)`, `sever_all()` | drop the transport under open links once (`Lost`) |
| `stop(node)` | the node becomes unreachable and its links are lost |

Time is tokio's clock, so tests with a paused clock run delays deterministically.

## Contract suite

`core_carrier::contract` (feature `contract-suite`) runs the six fixture scenarios and the rule tests
(`a_node_close_ends_open_calls_and_refuses_later_writes`, `peer_capabilities_reach_the_carrier`,
`a_transport_loss_is_lost`, `dialing_an_address_where_no_node_answers_is_unreachable`, plus
`a_non_member_is_refused_before_any_handler` for R-1). A carrier plugs in with a `ContractHarness` and
`core_carrier::node_carrier_contract_tests!(harness)`.

There is no Rust `NodeSession` yet, so the scripted shell sends the fixture's frames verbatim (serialized once) and the
scripted node compares received text byte for byte; that checks rule 1 directly. Session behaviour (request ids, the
`has_more` loop) is the session's own contract.

The Iroh suite binds three loopback endpoints (node, shell, outsider) with relays and address lookup off. It skips
`a_transport_loss_is_lost`: a QUIC path over loopback cannot be dropped from a test without an endpoint close, which
sends an application close, and a silent drop only shows after the idle timeout.

## Interop with the Kotlin node

`core-carrier-iroh/tests/kotlin_interop.rs` dials a running Kotlin node, authenticates with a token, checks the
`auth_response` (success, `frame_part`, `event_seq_ordered_v1`) and calls `health.check` on a call lane. It runs only
when `BAKEN_KOTLIN_NODE` and `BAKEN_KOTLIN_TOKEN` are set. To run it by hand:

```sh
# 1. Start the hermetic Kotlin stub node (from android-compose/; prints "Node ID: <hex>").
./gradlew :cli:run -PcliArgs="app-server-serve-iroh-stub --iroh-port 47123 \
  --iroh-secret-key-file /tmp/kstub/iroh.key --auth-token rust-interop-token-0123456789abcdef --admin-port 0"

# 2. In core/, once "Listening on Iroh" is printed:
BAKEN_KOTLIN_NODE="<hex>@127.0.0.1:47123" BAKEN_KOTLIN_TOKEN=rust-interop-token-0123456789abcdef \
  cargo test -p core-carrier-iroh --test kotlin_interop -- --nocapture
```

The key file must not exist beforehand (the stub creates it). Run this way against the stub on Windows, the test
passed: iroh 1.5.0 (Rust) and `computer.iroh` 1.2.0 (Kotlin) interoperate on `/letta/appserver/0`. It is not in CI
yet, because CI would have to build and start the JVM host next to the cargo job.
