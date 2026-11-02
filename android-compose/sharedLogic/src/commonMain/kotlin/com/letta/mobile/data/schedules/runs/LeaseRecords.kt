package com.letta.mobile.data.schedules.runs

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.schedules.RunRecord
import com.letta.mobile.data.schedules.leases.Lease
import com.letta.mobile.data.schedules.leases.LeaseState
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** Maps a wrapper lease.list response to read-only leases, skipping malformed records. */
fun leaseRecords(response: AppServerInboundFrame.AdminRpcResponse): List<Lease> =
    if (response.success) ((response.result as? JsonObject)?.get("leases") as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.toLease() } else emptyList()

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.requiredText(key: String): String? = text(key)?.takeIf { it.isNotBlank() }

private fun JsonObject.instant(key: String): Instant? =
    text(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun JsonObject.toLease(): Lease? {
    val id = requiredText("id")
    val owner = requiredText("owner")
    val label = requiredText("label")
    val startedAt = instant("startedAt")
    val dueAt = instant("dueAt")
    if (listOf(id, owner, label, startedAt, dueAt).any { it == null }) return null
    val state = when (text("state")) {
        "active" -> LeaseState.ACTIVE
        "done" -> LeaseState.DONE
        "expired" -> LeaseState.EXPIRED
        "recovered" -> LeaseState.RECOVERED
        else -> LeaseState.UNKNOWN
    }
    return Lease(
        id = requireNotNull(id),
        owner = requireNotNull(owner),
        label = requireNotNull(label),
        startedAt = requireNotNull(startedAt),
        dueAt = requireNotNull(dueAt),
        state = state,
        runId = text("runId"),
        detail = text("detail"),
    )
}

/** Optional shared wrapper record readers for schedule runs and read-only leases. */
data class ScheduleRecordSources(
    /** Loads wrapper run records for the selected agent and schedules. */
    val runHistory: (suspend (AgentId, List<String>) -> List<RunRecord>)? = null,
    /** Loads read-only wrapper leases for the selected agent. */
    val leases: (suspend (AgentId) -> List<Lease>)? = null,
)

/** Reads all lease pages only when the wrapper advertises the method. */
class LeaseHistory(private val transport: IChannelTransport) {
    /** Loads leases visible to [agentId]. */
    suspend fun load(agentId: AgentId): List<Lease> {
        if ("lease.list" !in transport.hostCapabilities) return emptyList()
        val leases = mutableListOf<Lease>()
        var offset = 0
        do {
            val body = buildJsonObject { put("agentId", agentId.value); put("limit", 200); put("offset", offset) }
            val response = transport.adminRpc("lease.list", "/v1/leases", body.toString())
            check(response.success) { response.error ?: "lease.list failed" }
            val page = response.result as? JsonObject ?: error("lease.list missing page")
            leases += leaseRecords(response)
            offset = nextOffset(page, offset) ?: return leases
        } while (true)
    }

    private fun nextOffset(page: JsonObject, offset: Int): Int? {
        val rows = page["leases"] as? JsonArray ?: error("lease.list invalid leases")
        val more = (page["hasMore"] as? JsonPrimitive)?.booleanOrNull ?: error("lease.list invalid page")
        if (!more) return null
        val next = (page["nextOffset"] as? JsonPrimitive)?.intOrNull
        check(next != null && rows.isNotEmpty() && next == offset + rows.size && next <= 10000) {
            "lease.list invalid continuation"
        }
        return next
    }
}
