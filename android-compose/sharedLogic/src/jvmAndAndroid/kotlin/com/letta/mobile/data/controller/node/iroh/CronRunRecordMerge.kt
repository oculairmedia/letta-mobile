package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.records.MAX_ID_LENGTH
import com.letta.mobile.data.controller.node.iroh.records.WrapperRecordStore
import com.letta.mobile.data.controller.node.iroh.records.StoredRunRecord
import com.letta.mobile.data.model.AgentId
import java.security.MessageDigest
import java.time.Instant
import com.letta.mobile.data.controller.node.iroh.records.safeCronReference
import com.letta.mobile.data.controller.node.iroh.records.recordPage
import com.letta.mobile.data.controller.node.iroh.records.text
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

private const val UPSTREAM_PAGE_SIZE = 200
private const val UPSTREAM_RUN_CAP = 5000

internal suspend fun mergedCronRuns(
    nativeClient: AppServerClient,
    store: WrapperRecordStore,
    params: JsonObject?,
    context: AdminRpcRequestContext,
): JsonObject {
    requireRecordAuthentication(context)
    val taskId = params.text("task_id", MAX_ID_LENGTH)!!
    safeCronReference(taskId, "task_id")
    val agent = params.text("agentId", MAX_ID_LENGTH, optional = true)
        ?: params.text("agent_id", MAX_ID_LENGTH, optional = true)
    agent?.let { requireRecordAgentScope(context, AgentId(it)) }
    val request = AppServerCommand.CronRuns(NativeAdmin.requestId(), taskId, UPSTREAM_PAGE_SIZE, 0)
    val upstream = readUpstreamRuns(nativeClient, request).filter { row ->
        val owner = (row["agentId"] as? JsonPrimitive)?.contentOrNull
        (context.authorizedAgentIds == null || owner in context.authorizedAgentIds) &&
            (agent == null || owner == agent)
    }.map { JsonObject(it + mapOf("reason" to JsonNull, "source" to JsonNull)) }
    val own = store.snapshot().runs.filter {
        (it.input.scheduleId ?: wrapperTaskId(AgentId(it.input.agentId))) == taskId &&
            IrohPeerCapabilities.isAgentInScope(context.authorizedAgentIds, it.input.agentId) &&
            (agent == null || it.input.agentId == agent)
    }.sortedBy { it.id }.map { it.cronRow() }
    // Stable sorting preserves upstream-before-wrapper ties and wrapper-id order.
    val merged = (upstream + own).sortedByDescending { (it["ts"] as? JsonPrimitive)?.doubleOrNull ?: Double.NEGATIVE_INFINITY }
    return buildJsonObject { put("page", recordPage(merged, params, "entries")) }
}

private suspend fun readUpstreamRuns(client: AppServerClient, request: AppServerCommand.CronRuns): List<JsonObject> {
    val rows = mutableListOf<JsonObject>()
    var command = request
    var offset = request.offset ?: 0
    do {
        val response = client.cronRuns(command)
        if (!response.success) adminError(response.error ?: "cron_runs failed")
        val page = response.page ?: adminError("cron_runs invalid page")
        val entries = page["entries"] as? JsonArray ?: adminError("cron_runs invalid page")
        val hasMore = validateUpstreamPage(page, entries, offset)
        val next = offset + entries.size
        rows += entries.map { it as? JsonObject ?: adminError("cron_runs invalid row") }
        offset = next
        if (hasMore) command = command.copy(requestId = NativeAdmin.requestId(), offset = next)
    } while (hasMore)
    return rows
}

// A virtual task key must fit the native task-id bound for every valid owner, including non-path-safe ids.
private fun wrapperTaskId(agent: AgentId): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(agent.value.toByteArray(Charsets.UTF_8))
    return "wrapper:${digest.toHexString()}"
}

private fun StoredRunRecord.cronRow() = buildJsonObject {
    put("ts", input.instantMs)
    put("jobId", input.scheduleId ?: wrapperTaskId(AgentId(input.agentId)))
    put("action", "finished")
    put("status", input.status.name.lowercase())
    put("agentId", input.agentId)
    put("runId", id)
    put("scheduleName", input.scheduleName)
    put("instant", Instant.ofEpochMilli(input.instantMs).toString())
    put("reason", input.reason)
    put("source", input.source.name.lowercase())
    put("detail", input.detail)
}

private fun validateUpstreamPage(page: JsonObject, entries: JsonArray, offset: Int): Boolean {
    val total = (page["total"] as? JsonPrimitive)?.intOrNull ?: adminError("cron_runs invalid page")
    val hasMore = (page["hasMore"] as? JsonPrimitive)?.booleanOrNull ?: adminError("cron_runs invalid page")
    val next = offset + entries.size
    if (total !in 0..UPSTREAM_RUN_CAP || next > total || entries.size > UPSTREAM_PAGE_SIZE) {
        adminError("cron_runs invalid page")
    }
    if (page["offset"] != JsonPrimitive(offset) || page["limit"] != JsonPrimitive(UPSTREAM_PAGE_SIZE)) {
        adminError("cron_runs invalid page")
    }
    val expectedNext = if (hasMore) JsonPrimitive(next) else JsonNull
    if (hasMore != (next < total) || page["nextOffset"] != expectedNext || (hasMore && entries.isEmpty())) {
        adminError("cron_runs invalid page")
    }
    return hasMore
}
