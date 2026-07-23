package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.Json
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
 */
internal object PairingAdminHandlers {
    private val json = Json { ignoreUnknownKeys = true }

    fun register(router: AdminRpcRouter, pairing: IrohPairingService?) {
        if (pairing == null) return
        router.register("pair.invite.create") { params ->
            val name = params?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "paired-peer"
            val ttlMs = params?.get("ttl_ms")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: IrohPairingService.DEFAULT_TTL_MS
            pairing.pruneExpired()
            val invite = pairing.createInvite(suggestedName = name, ttlMs = ttlMs)
            buildJsonObject {
                put("invite", IrohPairingService.INVITE_TOKEN_PREFIX + invite.secret)
                put("deep_link", invite.deepLink())
                put("expires_at_ms", invite.expiresAtMs)
                put("suggested_name", invite.suggestedName)
            }
        }
        router.register("pair.peer.list") { _ ->
            json.encodeToJsonElement(pairing.listPeers())
        }
        router.register("pair.peer.revoke") { params ->
            val nodeId = params?.get("node_id")?.jsonPrimitive?.contentOrNull
                ?: adminError("node_id is required")
            buildJsonObject { put("revoked", pairing.revoke(nodeId)) }
        }
    }

    val methods: Set<String> = setOf("pair.invite.create", "pair.peer.list", "pair.peer.revoke")
}
