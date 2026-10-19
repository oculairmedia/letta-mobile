# QR Pairing Protocol

**Status:** Implemented v1 contract, source-audited 2026-10-18. This replaces the
2026-08-12 proposal's unimplemented public-signature verification claims.
**Scope:** Shared minting, link parsing, confirmation and redemption. Live device
interoperability and deployed host configuration were not re-run in this audit.

## 1. Scope

QR pairing bootstraps trust to an Iroh host. Pairing grants only the capabilities
and optional agent scope chosen by the administrator; it does not independently
authorize arbitrary terminal access. The shared implementation serves client hosts.

## 2. Trust boundary

The envelope contains a bearer invite secret and the host's node identity. Treat
QR images and links as credentials. The user confirms the host fingerprint; the
Iroh/QUIC connection authenticates the expected node. The scanner does **not**
verify the envelope's host-private HMAC offline. Public Ed25519 envelope verification
was proposed in the original design but is not implemented.

## 3. RPC surface

`PairingAdminHandlers` registers methods when a pairing service is configured.
The authenticated connection also requires `admin.full` for these admin methods:

- `pair.invite.create`, `pair.invite.list`, `pair.invite.revoke`
- `pair.peer.list`, `pair.peer.get`, `pair.peer.rename`
- `pair.peer.set_capabilities`, `pair.peer.set_agents`, `pair.peer.revoke`

Create accepts optional `name`, `ttl_ms`, `qr`, `role`, `capabilities`, `agent_ids`.
The default name is `paired-peer`; default TTL is five minutes, nonpositive TTL
uses that default, and positive TTL is capped at one hour. Default grant is chat,
not admin. Supported role presets are chat, desktop, vibesync and admin; explicit
capabilities must be known. Agent IDs can narrow the grant.

## 4. One minting authority

Use `pair.invite.create`, not a parallel `pair.qr` RPC. Request `qr: true` for
QR fields. Without a usable signer/node configuration, the handler omits QR fields
rather than publishing an unsigned QR. The reply includes `invite_id`, `invite`,
`deep_link`, `expires_at_ms`, `suggested_name`, and when configured `qr_invite`
and `pair_link`.

## 5. Payload format

### 5.1 Canonical envelope

```json
{
  "version": 1,
  "node_id": "<64-hex host node identity>",
  "signed_secret": "invite:<one-time-secret>",
  "expires_at_ms": 1783872000000,
  "signature": "<host-private base64url HMAC>"
}
```

`PairQrEnvelope` emits compact JSON. The canonical message signed by
`HmacPairQrSigner` is version, node_id, signed_secret, expires_at_ms in that order,
without the signature member. HMAC-SHA256 is keyed by the host's persistent
`IrohSecretKeyStore`; the signature is not publicly verifiable from node_id.

### 5.2 Scanner validation

`ScannedInviteParser` requires version 1, a 64-hex node ID (normalized lowercase),
a nonempty `invite:` credential, and positive expiry. It tolerates unknown fields
and currently does not require or verify `signature`. Missing/invalid required
fields produce a format error; an unsupported version is reported separately.

### 5.3 Reachability

The envelope has no address. `PairLink` wraps it as
`meridian://pair?qr=<envelope>&dial=<percent-encoded hint>`. A hint can be a ticket,
node/address or host:port, but the redeemer must still target the envelope's node.
`IrohPairingRedeemer` rejects an explicit hint naming a different node.

## 6. Invitation lifecycle and revocation

The service atomically consumes an invite for a new peer, checks stored expiry,
and persists the peer with the invite's grant. An already paired node returns
`AlreadyPaired` before consuming an invite; repeated requests are not universally
reported as replay failures. A consumed invite cannot pair a second new peer.

`pair.invite.list` returns non-secret pending metadata. `pair.invite.revoke`
takes a non-secret `invite_id`; `pair.peer.revoke` removes established trust.
Pending-invite restart persistence depends on the configured `PairingInviteStore`.
Do not assume that every restart purges invitations.

## 7. Transport and redemption

### 7.1 Encoding

The envelope is `letta-qr-v1.<base64url-json>`. The parser accepts optional
base64url padding (`ABSENT_OPTIONAL`), not a requirement for missing padding to fail.
`PairLink` accepts bare envelopes, `meridian://pair?…`, `letta://pair?…`, and
those URI schemes with a slash before the query.

### 7.2 Confirmation

`PairingFlow` parses and checks expiry, then enters `ConfirmHost`. It allows a
30-second local clock-skew margin; the host still enforces its stored deadline.
Confirmation invokes the redeemer with a bounded timeout. Cancellation prevents
late responses from being persisted. A successful result is saved and activated.

### 7.3 Authentication

The Iroh connection authenticates the expected node identity; the host validates
the invite secret and one-shot lifecycle. There is no scanner-side public signature
check. Do not present the HMAC field as offline proof of issuer authenticity.

## 8. Historical design reference

The original design followed the ZEDRA generate/scan/confirm/redeem interaction.
The external BookStack/reference artifacts are not vendored; this source audit does
not validate their contents or assert schema compatibility with another product.

## 9. Tests and evidence

Source fixtures cover parser/link handling, flow cancellation/expiry, admin handlers
and service replay behavior. These are code-level evidence, not proof of a currently
running server or successful camera scan on every device.

## 10. Source map

Paths are relative to `android-compose/sharedLogic/src/`:

- `commonMain/kotlin/com/letta/mobile/data/pairing/ScannedInvite.kt`
- `commonMain/kotlin/com/letta/mobile/data/pairing/PairLink.kt`
- `commonMain/kotlin/com/letta/mobile/data/pairing/PairingFlow.kt`
- `jvmAndAndroid/kotlin/com/letta/mobile/data/transport/iroh/IrohPairingRedeemer.kt`
- `jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/PairQrSigner.kt`
- `jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/PairingAdminHandlers.kt`
- `jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/IrohPairingService.kt`
- `commonTest/kotlin/com/letta/mobile/data/pairing/PairingFlowTest.kt`
- `jvmTest/kotlin/com/letta/mobile/data/controller/node/iroh/PairingAdminHandlersTest.kt`
- `jvmTest/kotlin/com/letta/mobile/data/controller/node/iroh/IrohPairingServiceTest.kt`

## 11. Remaining verification

Live camera/deep-link entry, host readiness, network reachability and store persistence
must be checked in the intended deployment. Public envelope signatures would be a
new feature, not a documentation promise about the existing HMAC implementation.

## 12. Tracking

Original design: `letta-mobile-7b9on`, parent `letta-mobile-sixv8`.
Implementation contracts in source supersede the original proposed signature policy.
