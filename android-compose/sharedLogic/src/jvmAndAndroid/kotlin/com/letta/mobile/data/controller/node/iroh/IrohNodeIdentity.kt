package com.letta.mobile.data.controller.node.iroh

import computer.iroh.EndpointId
import computer.iroh.SecretKey

/**
 * letta-mobile-g2d2i: derive the hex NodeId that an Iroh endpoint would bind
 * to for a given 32-byte secret, WITHOUT creating a live [computer.iroh.Endpoint].
 *
 * This is used by the mobile pairing-invite (server mode) surface, which
 * needs to mint a `letta-qr-v1` envelope's `node_id` field before (or without)
 * ever standing up a listening endpoint: the value is purely a function of
 * the secret key (Ed25519 public key derivation), so it is stable across
 * calls and identical to whatever `IrohNodeEndpoint.nodeIdHex()` would report
 * once the same secret is used to bind a real endpoint.
 *
 * Mirrors the private `nodeIdHexFromSecret` helper in
 * `IrohAgentIdentity.kt` (same derivation, same hex formatting) — duplicated
 * here as a small public utility because that helper is `private` and scoped
 * to on-disk identity migration, not a general-purpose seam.
 */
object IrohNodeIdentity {
    fun nodeIdHexFromSecretBytes(secretBytes: ByteArray): String {
        SecretKey.fromBytes(secretBytes).use { key ->
            val endpointId: EndpointId = key.public()
            return endpointId.use { id -> id.toBytes().joinToString("") { b -> "%02x".format(b) } }
        }
    }
}
