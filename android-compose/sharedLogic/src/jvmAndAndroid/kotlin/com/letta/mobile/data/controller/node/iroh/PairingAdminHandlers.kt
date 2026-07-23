package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
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
