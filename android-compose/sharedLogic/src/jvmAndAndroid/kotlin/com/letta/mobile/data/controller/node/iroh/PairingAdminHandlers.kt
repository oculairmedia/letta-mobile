package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Admin RPC surface for one-time pairing invitations (d6e8g.5). Registered
 * only when a pairing service is configured; all methods run behind the
 * connection's authentication gate, so only an already-authenticated peer can
 * mint invites or manage pairings. Invite secrets appear ONLY in the response
 * to the caller who minted them — never in telemetry or errors.
 *
 * letta-mobile-gw0h1 (sixv8.2): the canonical blessed pair.invite.create
 * extension is wired through here. When the caller sets `qr: true`, the
 * response carries an additive `qr_invite` field (the `letta-qr-v1.<base64url>`
 * wire format from `reference/qr-pairing-protocol.md` §5.1 + §7.1). The
 * existing fields (`invite`, `deep_link`, `expires_at_ms`, `suggested_name`)
 * are unchanged, so v0 callers keep working — the protocol decision in §4 is
 * "additive and versioned".
 */
object PairingAdminHandlers {
    private val json = Json { ignoreUnknownKeys = true }

    /** Backward-compatible registration (no QR support). */
    fun register(router: AdminRpcRouter, pairing: IrohPairingService?) {
        register(router, pairing, qrSigner = NoOpPairQrSigner, qrNodeIdHex = null)
    }

    /**
     * Register with QR minting enabled. `qrSigner` produces the protocol
     * signature; `qrNodeIdHex` is the minting identity the wrap it signs under
     * (the wrapper's Iroh node id). When the caller sets `qr: true` AND
     * both signer + node id are wired, we emit the `qr_invite` field;
     * otherwise the field is omitted and the response shape is byte-identical
     * to the pre-extension one.
     *
     * Safety: a null or blank `qrNodeIdHex` collapses to the no-signer path
     * so a misconfigured wrapper never publishes an unsigned QR. Production
     * callers (the wrapper command) always pass both.
     */
    fun register(
        router: AdminRpcRouter,
        pairing: IrohPairingService?,
        qrSigner: PairQrSigner,
        qrNodeIdHex: String?,
    ) {
        if (pairing == null) return
        val qrEnabled = qrSigner !is NoOpPairQrSigner &&
            !qrNodeIdHex.isNullOrBlank()
        val effectiveSigner = if (qrEnabled) qrSigner else NoOpPairQrSigner
        val effectiveNodeId = qrNodeIdHex?.takeIf { it.isNotBlank() } ?: ""

        router.register("pair.invite.create") { params ->
            val name = params?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "paired-peer"
            val ttlMs = params?.get("ttl_ms")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: IrohPairingService.DEFAULT_TTL_MS
            val wantQr = params?.get("qr")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
            pairing.pruneExpired()
            val invite = pairing.createInvite(suggestedName = name, ttlMs = ttlMs)
            val signedSecret = IrohPairingService.INVITE_TOKEN_PREFIX + invite.secret
            buildJsonObject {
                put("invite", signedSecret)
                put("deep_link", invite.deepLink())
                put("expires_at_ms", invite.expiresAtMs)
                put("suggested_name", invite.suggestedName)
                if (wantQr && qrEnabled) {
                    val qr = PairQrEnvelope.encode(
                        nodeIdHex = effectiveNodeId,
                        signedSecret = signedSecret,
                        expiresAtMs = invite.expiresAtMs,
                        // The signed_secret in the QR carries the `invite:` prefix
                        // by the protocol's choice (§5.1); the secret returned
                        // to the QR consumer is therefore the same string the
                        // RPC issuer will hand to the redemption path.
                        signature = effectiveSigner.sign(effectiveNodeId, signedSecret, invite.expiresAtMs),
                    )
                    put("qr_invite", qr)
                }
            }
        }
        router.register("pair.peer.list") { _ ->
            json.encodeToJsonElement(pairing.listPeers())
        }
        router.register("pair.peer.get") { params ->
            val nodeId = params?.get("node_id")?.jsonPrimitive?.contentOrNull
                ?: adminError("node_id is required")
            buildJsonObject {
                put("peer", pairing.peer(nodeId)?.let(json::encodeToJsonElement) ?: JsonNull)
            }
        }
        router.register("pair.peer.rename") { params ->
            val nodeId = params?.get("node_id")?.jsonPrimitive?.contentOrNull
                ?: adminError("node_id is required")
            val name = params?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: adminError("name is required")
            val updated = pairing.rename(nodeId, name)
                ?: adminError("no paired peer for node_id")
            json.encodeToJsonElement(updated)
        }
        router.register("pair.peer.set_capabilities") { params ->
            val nodeId = params?.get("node_id")?.jsonPrimitive?.contentOrNull
                ?: adminError("node_id is required")
            val capabilities = readCapabilities(params)
            val unknown = capabilities - IrohPeerCapabilities.ALL
            if (unknown.isNotEmpty()) adminError("unknown capabilities: ${unknown.sorted().joinToString(",")}")
            val updated = pairing.setCapabilities(nodeId, capabilities)
                ?: adminError("no paired peer for node_id")
            json.encodeToJsonElement(updated)
        }
        router.register("pair.peer.revoke") { params ->
            val nodeId = params?.get("node_id")?.jsonPrimitive?.contentOrNull
                ?: adminError("node_id is required")
            buildJsonObject { put("revoked", pairing.revoke(nodeId)) }
        }
    }

    /**
     * Capabilities may arrive as a JSON array (`["chat.read", ...]`) or a
     * comma-separated string (`"chat.read,chat.send"`) so both structured
     * callers and CLI operators can drive the same method.
     */
    private fun readCapabilities(params: kotlinx.serialization.json.JsonObject?): Set<String> {
        val element = params?.get("capabilities") ?: adminError("capabilities is required")
        return when (element) {
            is JsonArray -> element.map { it.jsonPrimitive.content }
            else -> element.jsonPrimitive.contentOrNull.orEmpty().split(",")
        }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    val methods: Set<String> = setOf(
        "pair.invite.create",
        "pair.peer.list",
        "pair.peer.get",
        "pair.peer.rename",
        "pair.peer.set_capabilities",
        "pair.peer.revoke",
    )
}
