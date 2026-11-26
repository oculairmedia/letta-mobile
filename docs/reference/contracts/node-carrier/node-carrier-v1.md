# Node carrier v1: one client stack over any transport

Status: **v1 frozen. The SPI, the client session, the fixture and the contract suite are implemented and proven against
the reference loopback carrier. The in-process carrier and the Iroh carrier are the next slices; each is done when the
suite passes against it. No shell code uses the carrier yet.**

Code: `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/node/carrier/`.
Fixture: `android-compose/sharedLogic/src/commonTest/resources/contracts/node-carrier/v1.json`.
Suite: `NodeCarrierContractSuite` (commonTest, same package), with `ScriptedContractNode` and the reference
`LoopbackNodeCarrier` beside it.

## Why

Every shell, local or remote, is a client of a node and speaks one protocol to it. Today the desktop holds an App Server
client in process (`DesktopLocalAppServerClientRegistry`) and talks Iroh frames to remote hosts, so local and remote
attach behave differently and the shell holds object references into the node. With a carrier:

1. The shell always speaks the node protocol (the frames `IrohNodeConnection` serves: auth, runtime commands,
   `admin_rpc`, pushes, the feed) through `NodeSession`, whatever the transport.
2. The transport is an SPI with two implementations, in-process and Iroh, and one contract suite runs against both.
3. Nothing but frames crosses, so moving the node to a background process later is a packaging change.

## Layers

```
shell code ──> NodeSession ──> NodeCarrier.dial ──> NodeLink  ═══frames═══  NodePeerLink ──> NodePeerAcceptor (the node)
               (protocol,       (SPI: transport)    (shell end)             (node end)       (auth, gates, router, turns)
                common code)
```

- **Carrier** (`NodeCarrier`, `NodeLink`, `NodeCallLane`, `NodePeerLink`, `NodePeerAcceptor`): moves `NodeFrame`s on
  three lanes and reports the verified identity of the other end. It never authenticates, authorizes, parses or rewrites a
  frame.
- **Session** (`NodeSession`): the protocol on the shell's side. It is the same code over every carrier.
- **Node**: serves a `NodePeerLink`. Auth (`PeerSessionAuth`), capability gates (`IrohPeerCapabilities`), the admin
  router, turns and the feed stay where they are; they read frames from the link instead of from Iroh streams.

## Lanes

| Lane | Direction | Carries | Iroh today |
| --- | --- | --- | --- |
| `CONTROL` | both | `auth`/`auth_response`, `runtime_start`, `input`/`input_accepted`, `sync`, `abort_message`, queue controls, `error`, `app_tool_call` and its response | first bi-stream the client opens |
| `PUSH` | node to shell | the `event_seq` frames of a turn (`stream_delta`, `update_loop_status`, `update_queue` and `turn_finished`, in `event_seq` order, so `turn_finished` follows its deltas), `feed_delta`, agent and conversation events, `pins_updated`, `shell_state_updated`, ... | second bi-stream (the client finishes its send half at once) |
| `CALL` | both, one per call | one `admin_rpc` request, then the node's frames ending in `admin_rpc_response`. A streamed method (`voice.speech`, `voice.transcribe`) sends `admin_rpc_stream` frames before its response. While its send half is open the shell may write `admin_rpc_cancel` carrying the call's `request_id` (the node matches the cancel by it); finishing the shell's send half also cancels a streamed call | every later bi-stream |

Frames on one lane arrive in send order. There is no order across lanes (a control response and a push may arrive in
either order), exactly as between QUIC streams.

## Carrier rules (normative)

Every carrier keeps these; the suite checks them.

1. **Frames, unchanged.** A frame arrives exactly once, byte-for-byte equal as JSON, on the lane it was sent on, in send
   order within its lane. Chunking (Iroh `frame_part`) is internal to a carrier and never visible above it.
2. **Verified identity only.** `NodeLink.node` is the node the carrier reached and `NodePeerLink.peer` the peer that
   dialled, as the transport proved them: the Iroh endpoint id in hex, or `NodePeerIdentity.LOCAL_SHELL` for the
   in-process carrier. Never a value taken from a frame.
3. **Dial sends nothing.** `dial` opens the control and push lanes and returns; the first frame is the session's `auth`.
   An address the carrier does not dial, or a node it cannot reach, throws `NodeCarrierException(Unreachable)`.
4. **One acceptor call per link.** The node side calls `NodePeerAcceptor.serve(link)` once per link and closes the link if
   it is still open when `serve` returns.
5. **Single collectors.** `NodeLink.inbound`, `NodePeerLink.control`, `NodePeerLink.calls` and each `NodeCallLane.incoming`
   are collected once (sequential re-collection continues where the previous one stopped). They complete when the link
   closes (a call lane's also when the other end finishes sending).
6. **Calls.** `openCall(request)` opens a new lane and writes the request as its first frame; the node sees the lane on
   `calls` with the request first on `incoming`. `finishSending` ends one half; the other end's `incoming` completes after
   the frames already sent. Lanes are independent: answering one never waits for another.
7. **Closure, delivered unchanged.** `close(closure)` ends the link once (later closes do nothing). Both ends' `closure`
   becomes the same `NodeLinkClosure(code, reason)`; the closing end's is set before `close` returns. A transport loss with
   no close from either end is `NodeLinkClosure.LOST`. A carrier delivers any code and reason unchanged; it never maps
   or filters them. The codes today's node uses (the list is not exhaustive, and a shell treats an unknown code as a
   closure like any other): `0 closed` (orderly; a detach), `4401 auth_failures`, `4401 not_authenticated`, `4403
   peer_revoked`, `4403 peer_not_allowed`, `4403 protocol_not_authorized`, `4404 unknown_alpn`, `4429
   auth_rate_limited`.
8. **Frames written before a close are delivered before it** wherever the transport allows. Iroh may drop unacknowledged
   stream data when a connection is closed, so the contract lets a session that the node refuses and then closes fail with
   either the refusal or the closure (fixture: `auth`, session `b`).
9. **After close**, `sendControl`, `openCall`, `push` and `NodeCallLane.send` throw `NodeCarrierException(Closed)`, every
   call lane ends, and `inbound`/`control`/`calls` complete.
10. **Closing never cancels node work.** A turn the shell started keeps running on the node (it is detached); the shell
    catches up from the feed.
11. **Optional encodings.** The node calls `NodePeerLink.onPeerCapabilities(set)` once, after the peer authenticated,
    with what the peer advertised in its accepted `auth`; the link trusts what it is given, so nothing calls it earlier. The Iroh carrier uses it to decide whether it may split node-to-peer frames into `frame_part`s; the
    in-process carrier ignores it.

## Session protocol

`NodeSession.open(carrier, NodeOpenRequest(address, credential, peerName, clientCapabilities), scope, requestIds)`:

1. Dials, then starts routing inbound frames in `scope`: a `CONTROL` frame whose `request_id` a pending request waits for
   answers it; every other frame goes to `session.inbound`.
2. Sends `{"type":"auth","request_id":..,"token"?:..,"peer_name"?:..,"capabilities":[sorted]}`. `NodeCredential.Token`
   sends `token`; `NodeCredential.CarrierIdentity` sends none, and the node admits the peer by its carrier-verified
   identity (the in-process shell, admitted as an admin peer by construction). `clientCapabilities` defaults to
   `["frame_part"]`.
3. `auth_response` with `success: true` opens the session: `capabilities.host` is its `capabilities`, `capabilities.paired`
   its `paired`. `success: false` closes the link and throws `AuthRejected(error)`. A link that ends first throws
   `Closed(closure)`.

Then:

| Call | Frames |
| --- | --- |
| `request(frame)` | writes a control frame carrying `request_id`; returns the control frame answering it (`runtime_start_response`, `input_accepted`, `sync_response`, or `error` such as a capability denial). |
| `send(frame)` | writes a control frame and waits for nothing. |
| `adminRpc(method, params)` | for one-shot methods only. Opens a call lane with `{"type":"admin_rpc","request_id":..,"method":..,"params"?:..}`, finishes sending, returns the `admin_rpc_response` (success or not). A lane that ends without one throws `Closed`. Finishing the send half cancels a streamed method, so a streamed method (`voice.speech`, `voice.transcribe`) must not be called through it; a session method for streamed calls is out of scope for v1. |
| `resumeFeed(conversation, agent, from)` | `feed.subscribe` with `{"conversation_id","agent_id","from"?:{epoch,seq}}`, again from each page's `cursor` while `has_more`; returns the pages. `success: false` throws `Refused("feed.subscribe", error)`. A node still answering `has_more` after 256 pages throws `Incomplete("feed.subscribe", 256)`, never a truncated history that looks complete. Pages are applied by [conversation-feed-v1.md](../conversation-feed-v1.md). |
| `inbound` | pushes and unanswered control frames, per lane in order. The session buffers them without bound until they are collected, so a shell collects `inbound` for the session's whole life (or closes the session). |
| `close()` | closes the link with `0 closed`: a detach. |

Request ids come from `requestIds` (default `AppServerRequestIdFactory("node")`). The wire frames are today's node
frames, so an existing Iroh host serves a `NodeSession` that authenticates with a token unchanged. One thing is new on
the wire: an `auth` frame without `token` (`NodeCredential.CarrierIdentity`). Today's clients always send a token and
today's node denies an unpaired peer that sends none; admitting the in-process shell this way is the in-process slice's
node-side change.

If the open fails or is cancelled at any point after the dial (a refusal, a closure, the caller's cancellation), the
session closes the dialled link before it rethrows, so no link is left without an owner. If the routing coroutine ends
because its scope was cancelled, the session closes the link as `0 closed` (a detach) and pending requests fail with
that closure.

## Scenarios

Each scenario in the fixture is a script of steps. The suite runs it from the shell's end with `NodeSession`;
`ScriptedContractNode` serves the same script from the node's end and matches every frame it receives exactly.

| Scenario | What it fixes |
| --- | --- |
| `auth` | A refused token fails the open with the node's reason; a refusal followed by a node close (`4429`) fails it with the reason or the closure; an accepted token yields the node's capabilities and `paired`; a carrier-verified identity authenticates with no token; an orderly close reaches the node as `0 closed`. |
| `capability_gate` | Capability negotiation (`supports`); a denied `admin_rpc` and a denied runtime command are ordinary responses naming the missing capability, on the call lane and the control lane; a permitted call on the same session still succeeds. |
| `admin_rpc` | Two concurrent calls on their own lanes, answered in reverse order, each resolve with their own response; an unknown method is a response, not a carrier failure. |
| `turn` | `runtime_start`, then `input`; `input_accepted` answers on the control lane while the turn's `stream_delta` frames and its closing `turn_finished` arrive on the push lane, in `event_seq` order. |
| `detach` | Closing mid-turn reaches the node as an orderly close; a new session reads the rest of the turn from the feed from the cursor the first session last applied. |
| `feed_resume` | A cursor from an earlier node start gets a `snapshot` page with `has_more`; the session continues from that page's cursor with a `replay` read; a refused read fails the resume with the node's error. |

### Fixture format

`{"contract":"node-carrier","version":1,"scenarios":[{"name","summary","steps":[...]}]}`. A step has `op` and `session`
(a label; the node maps the n-th link it accepts to the n-th session opened). Session `x` mints request ids `x-1`,
`x-2`, ... (its `auth` is always `x-1`).

| `op` | Fields | Shell does | Node does |
| --- | --- | --- | --- |
| `open` | `credential` (`{"kind":"token","token"}` or `{"kind":"carrier_identity"}`), `peer_name`?, `expect_frame`, `replies`, `node_close`?, `expect` | `NodeSession.open`; `expect` is `{capabilities, paired, supports?}` or `{auth_rejected, or_closed?}` | matches the `auth` frame, sends `replies`, then closes with `node_close` if set |
| `request` | `frame`, `replies` | `request(frame)`; expects the control reply with its `request_id`, then the `push` replies on `inbound`, in order | matches the frame, sends `replies` on their lanes |
| `call` | `method`, `params`, `expect_frame`, `response` | `adminRpc`; expects `response` | matches the call lane's request, answers on it |
| `calls` | `calls`: list of `call` bodies, optional `answer_after` | starts them concurrently in order; expects each `response` | answers a call only after the calls named in `answer_after` |
| `resume` | `conversation_id`, `agent_id`, `from`, `calls`, `expect`? | `resumeFeed`; expects the decoded `result` of each call's response, or `{refused}` | answers each `feed.subscribe` call |
| `close` | | `close()`; expects its closure `0 closed` at once and at the node, and a later send to fail `Closed` | observes the closure |

A `replies` entry is `{"lane":"control"|"push","frame":{...}}`.

## Plugging a carrier into the suite

```kotlin
class InProcessNodeCarrierContractTest : NodeCarrierContractSuite() {
    override fun harness() = NodeCarrierHarness { acceptor, scope ->
        // Start a node end that hands every link to `acceptor`, in `scope`.
        NodeCarrierEndpoint(carrier = ..., address = ..., unreachable = ..., stop = { ... })
    }
}
```

The suite supplies the acceptor (the scripted node); the harness only wires the carrier's node end to it. A carrier
that needs real I/O (Iroh) puts its subclass in `jvmTest`; the suite runs scenarios on `Dispatchers.Default` with real
timeouts. A carrier is conformant when every test passes: the six scenarios, `dialingAnAddressWhereNoNodeAnswersIsUnreachable`,
and the rule tests that need no fixture:

- `aNodeCloseEndsOpenCallsAndRefusesLaterWrites` (rules 7 and 9): the node closes the link with `4403 peer_revoked`
  while a call lane is open. Both ends see that closure, the open call lane completes, and `sendControl`, `openCall`,
  `push` and `NodeCallLane.send` all throw `Closed` afterwards.
- `peerCapabilitiesReachTheCarrier` (rule 11): after the node reports `frame_part`, a push frame larger than one Iroh
  frame (1.5 MiB) arrives whole and unchanged.
- `aTransportLossIsLost` (rule 7): the harness drops the transport under an open link (`NodeCarrierEndpoint.sever`)
  and both ends see `NodeLinkClosure.LOST`. A carrier whose transport a test cannot drop passes `sever = null` and
  says why in its test; the suite then skips only this test.

When the scripted node sees a frame that does not match the script, its mismatch text is the failure message, whatever
the shell's end reported (usually `Closed` with `4900 contract_mismatch`).

The suite proves the transport and the session. That the real node serves the same frames is proven separately, by
running the real node behind the in-process carrier (next slice) and by the existing `WireContractsTest`.

## How each carrier maps onto the SPI

### In-process carrier (`InProcessNodeCarrier`, commonMain or jvmAndAndroid)

- **Shape**: the reference `LoopbackNodeCarrier` is the model: one link is a set of channels, one per lane direction,
  plus a channel pair per call lane, and one shared closure. The node end is handed to the node's `NodePeerAcceptor`
  on every dial. Address: `in-process:<name>`; peer identity: `NodePeerIdentity.LOCAL_SHELL`; node identity: the node's
  own Iroh endpoint id (so the shell sees the same node either way).
- **Frames**: a `JsonObject` may wrap a map its builder still holds, so the in-process carrier hands the other end a
  deep snapshot of each frame (no re-encoding), never the sender's tree; nothing else may cross.
- **Backpressure**: none. Every lane is an unbounded channel, so the node's writes never suspend on a slow shell; the
  shell keeps collecting `inbound` for the link's whole life (`NodeSession` does). A carrier that serializes, when the
  node moves out of process, bounds its lanes instead. The in-process node end does not apply the Iroh frame limits.
- **Admission**: the local shell is admitted by how its link was made, never by its peer label. Only the in-process
  carrier's own acceptor (`NodeComposition.inProcessCarrier`) serves links as the local shell; a link that reports
  `LOCAL_SHELL` through the general acceptor (`NodeComposition.peerAcceptor`) meets the endpoint's policy.
- **A node that goes away**: a node that stops serving a link the shell had not closed closes it as `LOST`, so the
  shell never mistakes it for its own detach. In the suite, stopping the node is this carrier's transport loss.
- **The node side is the real work**: `IrohNodeConnection` serves `computer.iroh.Connection` directly (control =
  first `acceptBi`, push = second, calls via `AdminRpcStreamServer` over later ones). Generalize it to serve a
  `NodePeerLink`: control frames from `link.control`, writes to `link.sendControl`/`link.push`, admin calls from
  `link.calls`, `remoteEndpointId` from `link.peer`, closes through `link.close(NodeLinkClosure.AUTH_FAILURES ...)`, and
  `link.onPeerCapabilities` where it records `peerCapabilities`. `NodeComposition` then exposes the node's acceptor.
  `PeerSessionAuth` admits `LOCAL_SHELL` through the auth policy (pre-authenticated, admin grants).
- **Wraps**: `NodeComposition` (the node), `IrohNodeConnection` (to serve a link), `ConnectionRegistry` (viewer per
  link), `PeerSessionAuth` / auth policy (admit `LOCAL_SHELL`). It replaces `DesktopLocalAppServerClientRegistry` as
  the shell's way into the local node (that migration is N3).

### Iroh carrier (`IrohNodeCarrier`, jvmAndAndroid)

- **Shell end (`NodeLink`)**: dial with the endpoint and ALPN `IrohAppServerTransport` uses (`endpoint.connect`),
  `openBi` the control lane, then `openBi` the push lane and finish its send half at once. `sendControl` writes with
  `IrohFrameCodec.write(..., allowFrameParts = true)`; `inbound` reads both lanes with `IrohFrameCodec.readAll`.
  `openCall` is `openBi` plus the request (`adminRpcOverStream` and `adminRpcStream` show both one-shot and cancel use).
  `node` is the remote endpoint id in hex (`IrohDiagnostics.endpointIdHex`). `close(c)` is `connection.close(c.code,
  c.reason)`; a remote application close maps back to `NodeLinkClosure(code, reason)`, anything else to `LOST`.
- **Node end (`NodePeerLink`)**: wrap one accepted `Connection` as `IrohNodeConnection.serve` does today: first
  `acceptBi` is control, second is push (wait for the peer's readiness as `serveStreamReadiness` does), every later
  `acceptBi` is a call lane. `peer` is the authenticated remote endpoint id. Writes use `frame_part` only after
  `onPeerCapabilities` reported it, as `peerSupportsFrameParts()` does now. Keep `IrohFrameCodec`'s frame limits.
  Nothing is read ahead of the node without bound: control frames, each call lane's frames and call lanes not yet
  taken wait in a small fixed buffer (8), and a full buffer stops the read, so QUIC flow control holds a peer that has
  not authenticated. A frame that is not a JSON object ends the link as `4400 malformed_frame`, so a request is never
  dropped silently.
- **Wraps**: `IrohFrameCodec`, the dial path of `IrohAppServerTransport` / `IrohDialer`, `IrohNodeEndpoint`'s accept
  loop, `IrohDiagnostics`. It must not change `IrohNodeConnection` (the in-process slice owns that refactor).
- **Suite**: a `jvmTest` subclass binds two loopback Iroh endpoints, wraps each accepted connection as a
  `NodePeerLink` handed to the suite's acceptor, and dials with the carrier. `unreachable` is a well-formed address of a
  node id nobody binds. It passes `sever = null`: every way a test can end an Iroh connection is an application
  close, never a silent loss.

## Out of scope for v1

Migrating shell callers (`IChannelTransport`, the desktop chat gateway) onto `NodeSession`; deleting the direct
App Server path; a session-level streamed `admin_rpc` (the call lane already carries `admin_rpc_cancel`); reconnect
policy (a supervisor above `NodeSession` re-opens and resumes from cursors); node-to-node links.
