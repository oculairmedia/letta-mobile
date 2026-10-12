package com.letta.mobile.data.controller.node.iroh

/**
 * letta-mobile-dpv7m.1: the host's QR minting identity for `pair.invite.create`.
 * [nodeIdHex] MUST be the endpoint's real Iroh NodeId (the Ed25519 public key
 * a scanner dials and the QUIC handshake proves); [dialHint] returns the
 * current ticket / `<node>@host:port` for the scannable link (non-secret).
 */
class PairingQrConfig(
    val signer: PairQrSigner,
    val nodeIdHex: String,
    val dialHint: () -> String? = { null },
)
