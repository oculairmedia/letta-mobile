package com.letta.mobile.data.schedules.runs

import com.letta.mobile.data.canvas.compose.Sha256
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.schedules.RunRecord
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Reads merged run pages over the existing wrapper admin RPC. */
class CronRunHistory(private val transport: IChannelTransport) {
    /** Loads known schedule IDs and the wrapper's per-agent schedule-less task when supported. */
    suspend fun load(agentId: AgentId, scheduleIds: List<String>): List<RunRecord> {
        if ("run.record" !in transport.hostCapabilities) return emptyList()
        val hash = Sha256.digest(agentId.value.encodeToByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return (scheduleIds + "wrapper:$hash").distinct().flatMap { task -> loadTask(agentId, task) }
    }

    private suspend fun loadTask(agentId: AgentId, task: String): List<RunRecord> {
        val records = mutableListOf<RunRecord>()
        var offset = 0
        do {
            val body = buildJsonObject { put("task_id", task); put("agentId", agentId.value); put("limit", 200); put("offset", offset) }
            val response = transport.adminRpc("cron.runs", "/v1/crons/$task/runs", body.toString())
            check(response.success) { response.error ?: "cron.runs failed" }
            val page = (response.result as? JsonObject)?.get("page") as? JsonObject ?: error("cron.runs missing page")
            records += cronRunRecords(page).filter { it.scheduleId == task }
            offset = nextOffset(page, offset) ?: return records
        } while (true)
    }

    private fun nextOffset(page: JsonObject, offset: Int): Int? {
        val entries = page["entries"] as? JsonArray ?: error("cron.runs invalid entries")
        val more = (page["hasMore"] as? JsonPrimitive)?.booleanOrNull ?: error("cron.runs invalid page")
        if (!more) return null
        val next = (page["nextOffset"] as? JsonPrimitive)?.intOrNull
        check(next != null && entries.isNotEmpty() && next == offset + entries.size && next <= 15000) {
            "cron.runs invalid continuation"
        }
        return next
    }
}
