# Node protocol v1: endpoint, session, auth, control lane and framing

Status: **v1 core, transcribed from the Kotlin node on main. Both nodes are held to it: the Rust core runs every
fixture below (`core/crates/core-proto/tests/`), and the Kotlin node runs the codec vectors
(`IrohFrameCodecVectorsTest`).** A change to this wire is made here first, with its fixture.

Fixtures, beside this file:

- [`codec-vectors-v1.json`](codec-vectors-v1.json): byte vectors for the frame codec (plain frames, `frame_part`
  sequences, oversized and malformed input).
- [`node-protocol-v1.json`](node-protocol-v1.json): the ALPN table, closures, auth error labels, limits, auth-failure
  limiter scenarios, and control-lane frame examples as exact wire JSON.

Rules marked **N-x** are normative: a conforming node does exactly this. Notes marked *Kotlin today* record where the
Kotlin node differs in behaviour that no conforming peer relies on.

Related: node-carrier v1 (`docs/reference/contracts/node-carrier/node-carrier-v1.md`, the carrier SPI and client
session over this wire; it lands separately), [conversation feed v1](../conversation-feed-v1.md) (the feed read over call lanes), and
[QR pairing](../../qr-pairing-protocol.md) (how invites are minted).

## 1. Endpoint and ALPN routing

A node has one Iroh endpoint and one identity, its Ed25519 key. Its NodeId is the public key as 64 lowercase hex
characters. Every protocol a node speaks is a TLS ALPN on that endpoint.

| ALPN | Route | Admission | Refusal closure |
| --- | --- | --- | --- |
| `/letta/appserver/0` | the node session (§2) | the endpoint's admission check, then the session's `auth` frame | `4403 peer_not_allowed` |
| `meridian/canvas-relay/1` | canvas relay | only while the same NodeId has an authenticated `/letta/appserver/0` session on this endpoint | `4401 not_authenticated` |
| `/letta/a2a/0` | agent-to-agent messages | the protocol's handler authorizes the NodeId | `4403 protocol_not_authorized` |
| `meridian/automerge-repo/1` | Automerge repository sync | the protocol's handler authorizes the NodeId | `4403 protocol_not_authorized` |

- **N-1.1** The node registers `/letta/appserver/0` always, and each other ALPN only when that service is configured.
  ALPNs are unique; a configuration that registers one twice fails at startup.
- **N-1.2** A TLS handshake that offers none of the registered ALPNs fails in the handshake (`no_application_protocol`)
  and never reaches the node. The node gives a handshake at most 15 s.
- **N-1.3** Routing order for an accepted connection: (1) an ALPN with its own handler: the handler authorizes the
  NodeId or the connection closes `4403 protocol_not_authorized`; the endpoint's admission check does not apply.
  (2) The admission check: when a static allowlist is configured, pairing is not, and the NodeId is not listed, close
  `4403 peer_not_allowed`. Admission never authenticates. (3) `meridian/canvas-relay/1`: served only while the NodeId
  has an authenticated session, else `4401 not_authenticated`. (4) `/letta/appserver/0`: the session. (5) Any other
  registered ALPN: `4404 unknown_alpn`.
- **N-1.4** Closures are an application close code and an ASCII reason. A peer treats an unknown code as a closure like
  any other. The v1 codes:

| Code | Reason | When |
| --- | --- | --- |
| `0` | `closed` | orderly close by either side; a detach, which never cancels node work |
| `4401` | `auth_failures` | the third refused `auth` on one session (§3) |
| `4401` | `not_authenticated` | a canvas connection without an authenticated session |
| `4403` | `peer_revoked` | the peer's pairing record was removed while it was connected |
| `4403` | `peer_not_allowed` | the admission check refused the NodeId |
| `4403` | `protocol_not_authorized` | a protocol handler refused the NodeId |
| `4404` | `unknown_alpn` | a registered ALPN with no route |
| `4429` | `auth_rate_limited` | the NodeId is locked out (§3) |

## 2. Session lanes and push readiness

A `/letta/appserver/0` connection carries three kinds of bi-directional QUIC stream ("lanes"). The peer opens all of
them.

| Lane | Which stream | Carries |
| --- | --- | --- |
| control | the first stream the peer opens | `auth` and its reply, runtime commands and their replies, `error` frames, `admin_rpc` (§4) |
| push | the second stream | node-to-peer only: turn frames in `event_seq` order, `feed_delta`, agent and conversation events, other pushes |
| call | every later stream, one per call | one `admin_rpc` request, then the node's frames ending in `admin_rpc_response` |

- **N-2.1 Push readiness.** The peer opens the control stream, then the push stream, and finishes the push stream's send
  half at once without writing to it. A QUIC peer only learns of a stream when data or a FIN arrives on it, and streams
  are accepted in stream-id order, so this FIN is what lets the node accept both: the control stream (lower id) first,
  then the push stream. The node reads at most one byte of the push stream's receive half and ignores it; it never reads
  frames from the push lane.
- **N-2.2** The node accepts call lanes only after it accepted both the control and the push lane. Every stream accepted
  after those two is a call lane.
- **N-2.3** The node writes pushes only on the push lane, and writes a session's push frames one at a time, in order.
  It advertises `event_seq_ordered_v1` (§3.4): every push frame of a connection that carries `event_seq` carries a
  strictly greater one than the one before.
- **N-2.4** A call lane's first frame must arrive within 15 s; otherwise the node answers
  `admin_rpc_response {request_id:"", success:false, error:"admin_rpc request stream closed before request"}` and
  finishes the lane. A node serves at most 16 call lanes of one session at once; later ones wait for a free slot and are
  not refused.
- **N-2.5** There is no order across lanes. Frames on one lane arrive in send order.
- **N-2.6** The node writes each lane's frames under one writer at a time, so the parts of one logical frame are never
  interleaved with another frame on the same lane (§5).

## 3. Authentication

### 3.1 The `auth` frame

`{"type":"auth","request_id":..,"token"?:..,"peer_name"?:..,"capabilities"?:[..]}` on the control lane.
`token` is the bootstrap bearer token, or `invite:<secret>` to redeem a one-time invite, or absent for a paired NodeId.
`capabilities` lists the peer's transport capabilities; clients send `["frame_part"]`.

The reply is `auth_response`:

- accepted: `{"type":"auth_response","request_id":..,"success":true,"paired"?:true,"already_paired"?:true,"capabilities":[..]}`
- refused: `{"type":"auth_response","request_id":..,"success":false,"error":<label>}`

### 3.2 State and decision order

A session starts **authenticated** when the node's policy vouches for the NodeId before any frame (an explicit
anonymous test mode, or a static allowlist policy that lists it) or the NodeId is paired; otherwise
**unauthenticated**.

- **N-3.1** An `auth` frame without `request_id` is answered
  `{"type":"auth_response","success":false,"error":"request_id is required"}` (no `request_id` key). It changes no
  state and counts against nothing.
- **N-3.2** Otherwise the node decides in this order; the first matching step wins:
  1. **Invite.** `token` starts with `invite:` and pairing is configured. If the NodeId is locked out: refuse
     `rate_limited`. If the NodeId is already paired: accept as `paired_node_id` with `paired:true,already_paired:true`;
     the invite stays unused for the device it was meant for. If the secret names a live invite: consume it (exactly one
     redeemer wins), pair the NodeId with the invite's grant, accept as `invite_redeemed` with `paired:true`. Unknown or
     already used secret: a failure `invite_invalid`; expired: a failure `invite_expired`.
  2. **Paired NodeId.** The NodeId is in the paired-peer store: accept as `paired_node_id`. The TLS handshake already
     proved the key; no token is needed.
  3. **Bootstrap allowance.** If the NodeId is locked out: refuse `rate_limited`. If no bootstrap token is configured:
     accept as `bootstrap` when the policy vouches for this NodeId, else a failure `pairing_required`. If a static
     allowlist is configured and the NodeId is not on it: a failure `peer_not_allowlisted` (a leaked token is useless
     from any other key). If `token` equals the configured token (compared in constant time): accept as `bootstrap`.
     Otherwise a failure: `missing_token` when `token` is absent or blank, else `invalid_token`.
- **N-3.3 Failures.** A failure is first counted against the NodeId (§3.3). If counting locks the NodeId out (or the
  NodeId cannot be tracked), the reply is `rate_limited` instead of the failure's label.
- **N-3.4 Replies to refusals.** `rate_limited`: reply, then close the connection `4429 auth_rate_limited`; it does not
  count against the session. Any other refusal: reply with the label and add one to the session's refusal count; at
  3 refusals the node closes the connection `4401 auth_failures`. A closure may overtake the reply (the transport can
  drop unacknowledged data on close), so a peer treats either as the refusal.
- **N-3.5** A refused `auth` leaves the session unauthenticated, even if it was authenticated before.
- **N-3.6** On acceptance the node records the peer's advertised `capabilities` for this session and replies with its
  own (§3.4). A later `auth` may re-authenticate; the last accepted one's capabilities apply.
- **N-3.7 Grants.** A paired session holds exactly its pairing record's capabilities and agent scope, re-read on every
  check, so re-scoping applies at once. If the record disappears, the session is deauthenticated and the connection
  closed `4403 peer_revoked`; it is never widened to the bootstrap grant. A bootstrap session holds `admin.full`.
- **N-3.8** Labels are fixed strings, never derived from the credential, and the node never logs or echoes a token.

| Label | Meaning | Counts against the session |
| --- | --- | --- |
| `request_id is required` | the frame had no `request_id` | no |
| `missing_token` | a bootstrap token is configured and none (or a blank one) was sent | yes |
| `invalid_token` | the token is not the configured one | yes |
| `pairing_required` | no bootstrap credential, not paired, no invite | yes |
| `peer_not_allowlisted` | the allowlist does not list this NodeId, so its token is not honoured | yes |
| `invite_invalid` | the invite secret is unknown or already used | yes |
| `invite_expired` | the invite expired | yes |
| `rate_limited` | the NodeId is locked out; the connection closes `4429` | no |

### 3.3 Limits and the failure limiter

| Limit | Value |
| --- | --- |
| failures per NodeId inside the window before lockout | 5 |
| sliding window | 60 000 ms |
| NodeIds holding failure state at once | 4 096 |
| refusals per session before `4401 auth_failures` | 3 |
| shortest bootstrap token a node accepts in its configuration | 16 characters |
| TLS handshake | 15 000 ms |

- **N-3.9** One limiter serves every session of an endpoint, so redialling never resets a NodeId's budget. A NodeId is
  locked out while it has 5 failures no older than 60 000 ms (a failure exactly 60 000 ms old still counts). A locked
  out NodeId is refused `rate_limited` even with a valid credential, except that a paired NodeId authenticates by
  step 2 before the limiter is consulted.
- **N-3.10** At most 4 096 NodeIds hold failure state. When the table is full, NodeIds whose newest failure has left the
  window are dropped, oldest first. A NodeId still inside its window is never dropped (that would lift its lockout or
  refund its budget). A failure from a new NodeId that still does not fit is answered `rate_limited` and not tracked.
- **N-3.11** The limiter scenarios in `node-protocol-v1.json` are normative; a node passes them with the limits they
  give.

### 3.4 Capabilities in `auth_response`

- **N-3.12** An accepted `auth_response` lists, first and in this order, `conversation_activity_v1`, `frame_part`,
  `event_seq_ordered_v1`; then the node's feature capabilities; then `app_tool_relay_v1` while an app-tool relay is
  attached to the session. The ordered-`event_seq` capability is spelled exactly `event_seq_ordered_v1`. The feature
  capability registry is its own contract.
- **N-3.13** The node sends `frame_part` sequences (§5) to a session only after an accepted `auth` that advertised
  `frame_part`.

## 4. Control lane

### 4.1 Frames the node accepts

| `type` | Typed here | What the node does |
| --- | --- | --- |
| `auth` | yes | §3 |
| `runtime_start` | yes | starts or attaches the conversation's runtime; replies `runtime_start_response` |
| `input` | no (runtime command) | starts a turn; the reply `input_accepted` comes on the control lane, the turn's frames on the push lane |
| `admin_rpc` | yes | one admin call; replies `admin_rpc_response` on the control lane |
| `app_tool_call_response` | no | a relayed app tool's answer |
| `sync` | no (runtime command) | replies `sync_response` |
| `abort_message` | no (runtime command) | replies `abort_message_response` |
| `remove_queue_item`, `resume_queue` | no (runtime command) | forwarded to the runtime |

"Typed here" frames have their shapes in this contract and in `core-proto`'s `frames` module. The others belong to the
runtime protocol or to later contracts (turn host, app-tool relay); this contract fixes only how they are gated and
that their fields pass through unchanged.

- **N-4.1 Gate order** for every frame: parse; then, for `runtime_start`, `input`, `sync`, `abort_message`,
  `remove_queue_item` and `resume_queue` on an authenticated agent-scoped session, the agent scope check (§4.2); then
  dispatch by `type`. Every type except `auth` requires an authenticated session (else `unauthorized`), and runtime
  commands then require their capability (else a capability denial). Denials have no side effect: nothing is forwarded,
  no viewer registration happens.
- **N-4.2 Agent scope.** A frame of an agent-scoped session that names an agent outside its scope, or a conversation
  whose owning agent is outside it (or cannot be resolved), is denied with
  `{"type":"error","request_id":..,"message":"forbidden","reason":"agent_scope"}`.
- **N-4.3** `runtime_start` without `request_id` is answered
  `{"type":"runtime_start_response","success":false,"error":"request_id is required"}`; without `agent_id` or
  `conversation_id`, `error:"agent_id and conversation_id are required"`. A `mode` needs `conversation.manage` (else a
  capability denial naming it); an unknown mode is `error:"unsupported permission mode"`. On success:
  `{"type":"runtime_start_response","request_id":..,"success":true,"runtime":{"agent_id":..,"conversation_id":..}}`,
  and the session becomes a viewer of that conversation.
- **N-4.4** `sync` and `abort_message` without `request_id` are answered `{"type":"sync_response",...}` /
  `{"type":"abort_message_response",...}` with `"success":false,"error":"request_id is required"` and no `request_id`.
- **N-4.5** `admin_rpc` on the control lane: unauthenticated is the generic `unauthorized` error (§4.3). Missing
  `method` or `request_id`: `{"type":"admin_rpc_response","request_id":..,"success":false,"error":"method and request_id
  are required"}`. A method the session's grant does not allow (unknown methods require `admin.full`):
  `{"type":"admin_rpc_response","request_id":..,"success":false,"error":"forbidden","capability":<required>}`.
- **N-4.6** Frames the node does not type pass through with every field, in order. A frame's unknown fields are ignored,
  never rejected.

### 4.2 Error and denial frames

The control lane's error frame is `{"type":"error","request_id"?:..,"message":..,"capability"?:..,"reason"?:..}`.

| Case | Frame |
| --- | --- |
| not authenticated | `{"type":"error","request_id":<id or "">,"message":"unauthorized"}` |
| capability denied (runtime command) | `{"type":"error","request_id":<id or "">,"message":"forbidden","capability":<required>}` |
| agent scope denied | `{"type":"error","request_id":<id or "">,"message":"forbidden","reason":"agent_scope"}` |
| unknown `type` | `{"type":"error","message":"Unknown command type: <type>"}` (`null` when there is no string `type`) |
| not a JSON object | `{"type":"error","message":"Failed to parse frame (<diagnostic>)"}` |

- **N-4.7** A denial carries the request's `request_id`, or `""` when it had none. Unknown-type and parse errors carry
  no `request_id`.
- **N-4.8** An error frame never contains any part of the refused frame except, for an unknown type, its `type`. A parse
  error's `<diagnostic>` names an error class only (a frame can carry a secret). Peers must not parse the diagnostic.
- **N-4.9** An unknown `type` is answered with the unknown-type error even before authentication.
- *Kotlin today:* the diagnostic is a Kotlin exception class name (`SerializationException`,
  `IllegalArgumentException`); the Rust core uses `NotJson`, `NotAnObject`, `MissingType`, `Malformed`. The Kotlin node
  splices the unknown `type` into the message without JSON escaping, and writes `"request_id":"null"` on a control-lane
  `admin_rpc` that lacks one; a conforming node escapes the text and writes `""`.

### 4.3 Call lanes

- **N-4.10** A call lane's first frame must be `admin_rpc`. The node answers on the lane:
  - not `admin_rpc`: `{"type":"admin_rpc_response","request_id":<id or "">,"success":false,"error":"Expected admin_rpc frame"}`;
  - session not authenticated: `...,"success":false,"error":"unauthorized"}`;
  - missing `method` or `request_id`: `...,"error":"method and request_id are required"}`;
  - capability denied: `...,"error":"forbidden","capability":<required>}`;
  - not a JSON object: `{"type":"admin_rpc_response","request_id":"","success":false,"error":"Failed to parse admin_rpc frame (<diagnostic>)"}`;
  - otherwise the method's own `admin_rpc_response` (streamed methods send `admin_rpc_stream` frames first).
- **N-4.11** A reply larger than one frame goes out as `frame_part`s when the session advertised `frame_part`.
  Otherwise, or when it exceeds even the reassembly limit, the node sends a one-frame
  `{"type":"admin_rpc_response","request_id":..,"success":false,"error":<why>}` instead of a frame the peer cannot read.
- The `admin_rpc` method registry, `admin_rpc_cancel`, streamed calls and per-method contracts are out of this
  contract's scope (see [the wire-contract index](../README.md)).

## 5. Framing

Every lane is a sequence of frames.

- **N-5.1 Plain frame.** A u32 big-endian length `L` with `L < 0x7FFFFFFF`, then exactly `L` bytes of UTF-8 JSON.
  `L = 0` is a valid empty frame.
- **N-5.2 Part.** The u32 sentinel `0x7FFFFFFF`, then a flags byte, a u32 big-endian part index, a u32 big-endian part
  length `P`, then exactly `P` bytes. Flag bit 0 (`0x01`) marks the final part (FIN); every other flag bit must be 0.
- **N-5.3 Sequences.** A logical frame larger than one frame is sent as parts with indexes `0, 1, 2, ...`, each a full
  `max_frame_bytes` except the FIN part, which may be shorter. The payload is the parts' bytes concatenated; only the
  reassembled payload is decoded as UTF-8 (a part boundary may split a character). The next logical frame starts again
  at index 0. A logical frame that fits one frame is always sent plain.
- **N-5.4 Limits.** `max_frame_bytes` = 1 048 576 (1 MiB) per plain frame and per part payload. `max_reassembled_bytes` =
  33 554 432 (32 MiB) per logical frame, for a session that authenticated and advertised `frame_part`; before that the
  reassembly limit is 1 048 576, so an unauthenticated peer can never make the node hold more than one frame.
- **N-5.5 Protocol errors**, each ending the stream: a plain length above `max_frame_bytes` (including lengths with the
  top bit set); a part with unknown flags; a part index other than the next expected (a gap, a repeat, or a sequence
  not starting at 0); a part length of 0 or above `max_frame_bytes`; a part that would take the reassembled frame past
  the reassembly limit; a plain frame in the middle of a sequence; a stream that ends inside a frame, part or sequence.
  The node checks a frame or part from its header alone, before buffering its payload, in this order: flags, index,
  length, reassembly limit.
- **N-5.6** A frame whose (reassembled) payload is not valid UTF-8 is a protocol error.
  *Kotlin today:* the Kotlin codec decodes malformed UTF-8 with U+FFFD replacement instead; the vector
  `invalid_utf8_payload` records this, and no conforming sender produces such a frame.
- **N-5.7** A sender emits parts only to a peer whose accepted `auth` advertised `frame_part` (§3.4). A peer without it
  reads the sentinel as an oversized plain length and fails cleanly instead of misreading the stream.
- **N-5.8** The codec vectors in `codec-vectors-v1.json` are normative: an encoder produces each vector's `wire_parts`
  exactly; a decoder fed each vector's wire, whole or in pieces of any size, produces its `frames` or fails with its
  error at the stated point.

## 6. How the fixtures are checked

| Fixture | Rust core | Kotlin node |
| --- | --- | --- |
| `codec-vectors-v1.json` | `core-proto/tests/codec_vectors.rs` (encode; decode whole and byte by byte) and property tests in `codec_properties.rs` | `IrohFrameCodecVectorsTest` (the same vectors through `IrohFrameCodec`) |
| `node-protocol-v1.json` | `core-proto/tests/node_protocol.rs` (ALPN table, closures, labels, limits, limiter scenarios, every frame parsed and re-serialized byte for byte, denial constructors) | the frames are the Kotlin node's output, transcribed from `IrohNodeConnection`, `AdminRpcStreamServer` and `PeerSessionAuth` |

## Out of scope for v1 core

The carrier SPI and client session (node-carrier v1); the capability and scope registry; turn hosting, the app-tool
relay and the push-frame catalog; `node.info`; node-to-node links and replication; reconnect policy.
