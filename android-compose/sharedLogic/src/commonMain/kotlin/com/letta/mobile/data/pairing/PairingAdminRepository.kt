package com.letta.mobile.data.pairing

import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What the inviter grants the device that scans the code. */
data class PairInviteRequest(
    val deviceName: String,
    /** Agents the new device may use; empty = every agent on the host. */
    val agentIds: Set<String> = emptySet(),
    /** chat (default) | desktop — admin is never offered from the app. */
    val role: String = ROLE_CHAT,
    /** How long the code stays redeemable; the host caps it (dpv7m.1). */
    val ttlMs: Long = DEFAULT_TTL_MS,
) {
    companion object {
        const val ROLE_CHAT = "chat"
        const val ROLE_DESKTOP = "desktop"

        /** Least-privilege default (dpv7m.5): five minutes, one use. */
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L

        /** The role behind the inviter's "Allow settings changes" toggle (off by default). */
        fun roleFor(allowSettingsChanges: Boolean): String = if (allowSettingsChanges) ROLE_DESKTOP else ROLE_CHAT
    }
}

/** A minted invite; [pairLink] is the QR value and contains the one-time secret. */
class MintedPairInvite(
    val pairLink: String,
    val inviteId: String,
    val expiresAtMs: Long,
    val agentIds: Set<String>?,
) {
    override fun toString(): String = "MintedPairInvite(id=$inviteId, <redacted>, expiresAtMs=$expiresAtMs)"
}

/** A device paired with the host (public metadata only). */
data class PairedDevice(
    val nodeId: String,
    val name: String,
    val pairedAtMs: Long,
    val lastSeenAtMs: Long?,
    val capabilities: Set<String>,
    /** Null = every agent. */
    val agentIds: Set<String>?,
)

/** An invite minted but not redeemed yet. */
data class PendingInvite(val inviteId: String, val name: String, val expiresAtMs: Long)

/**
 * Inviter side of scan-to-connect (letta-mobile-dpv7m.2): mint scoped invites,
 * watch them get accepted, and revoke devices — over the host's `pair.*`
 * admin_rpc methods, which require admin.full on the host. Shared by Android
 * and desktop through [AdminRpcInvoker].
 */
class PairingAdminRepository(private val rpc: AdminRpcInvoker) {
    suspend fun mint(request: PairInviteRequest): MintedPairInvite {
        val result = rpc.invoke(
            "pair.invite.create",
            buildJsonObject {
                put("name", request.deviceName.ifBlank { "paired-device" })
                put("role", request.role)
                put("qr", true)
                put("ttl_ms", request.ttlMs)
                if (request.agentIds.isNotEmpty()) put("agent_ids", JsonArray(request.agentIds.sorted().map(::JsonPrimitive)))
            },
        ) as? JsonObject ?: error("pair.invite.create returned no result")
        val link = result.string("pair_link")
            ?: error("This host cannot mint scannable invites yet; update the Meridian host.")
        return MintedPairInvite(
            pairLink = link,
            inviteId = result.string("invite_id").orEmpty(),
            expiresAtMs = result.long("expires_at_ms") ?: 0L,
            agentIds = result.stringSet("agent_ids"),
        )
    }

    suspend fun pairedDevices(): List<PairedDevice> =
        (rpc.invoke("pair.peer.list", JsonObject(emptyMap())) as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            PairedDevice(
                nodeId = obj.string("nodeId") ?: return@mapNotNull null,
                name = obj.string("name").orEmpty(),
                pairedAtMs = obj.long("pairedAtMs") ?: 0L,
                lastSeenAtMs = obj.long("lastSeenAtMs"),
                capabilities = obj.stringSet("capabilities").orEmpty(),
                agentIds = obj.stringSet("agentIds"),
            )
        }

    suspend fun pendingInvites(): List<PendingInvite> =
        (rpc.invoke("pair.invite.list", JsonObject(emptyMap())) as? JsonArray).orEmpty().mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            PendingInvite(
                inviteId = obj.string("invite_id") ?: return@mapNotNull null,
                name = obj.string("suggested_name").orEmpty(),
                expiresAtMs = obj.long("expires_at_ms") ?: 0L,
            )
        }

    suspend fun revokeDevice(nodeId: String): Boolean =
        (rpc.invoke("pair.peer.revoke", buildJsonObject { put("node_id", nodeId) }) as? JsonObject)
            ?.get("revoked")?.let { (it as? JsonPrimitive)?.contentOrNull == "true" } == true

    suspend fun revokeInvite(inviteId: String): Boolean =
        (rpc.invoke("pair.invite.revoke", buildJsonObject { put("invite_id", inviteId) }) as? JsonObject)
            ?.get("revoked")?.let { (it as? JsonPrimitive)?.contentOrNull == "true" } == true

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.stringSet(key: String): Set<String>? = when (val value: JsonElement? = this[key]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        null, JsonNull -> null
        else -> null
    }
}
