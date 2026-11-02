package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.records.MAX_ID_LENGTH
import com.letta.mobile.data.controller.node.iroh.records.TextField
import com.letta.mobile.data.controller.node.iroh.records.WrapperRecordStore
import com.letta.mobile.data.controller.node.iroh.records.StoredRunRecord
import com.letta.mobile.data.model.AgentId
import java.security.MessageDigest
import java.time.Instant
import com.letta.mobile.data.controller.node.iroh.records.CronReference
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
    val taskId = params.text(TextField("task_id", MAX_ID_LENGTH))!!
    CronReference(taskId, "task_id").validate()
    val agent = params.text(TextField("agentId", MAX_ID_LENGTH, optional = true))
        ?: params.text(TextField("agent_id", MAX_ID_LENGTH, optional = true))
    agent?.let { requireRecordAgentScope(context, AgentId(it)) }
    val request = AppServerCommand.CronRuns(NativeAdmin.requestId(), taskId, UPSTREAM_PAGE_SIZE, 0)
    val filter = CronRunFilter(taskId, agent, context)
    val upstream = readUpstreamRuns(nativeClient, request).filter(filter::includesUpstream)
        .map { JsonObject(it + mapOf("reason" to JsonNull, "source" to JsonNull)) }
    val own = store.snapshot().runs.filter(filter::includesStored).sortedBy { it.id }.map { it.cronRow() }
    // Stable sorting preserves upstream-before-wrapper ties and wrapper-id order.
    val merged = (upstream + own).sortedByDescending { (it["ts"] as? JsonPrimitive)?.doubleOrNull ?: Double.NEGATIVE_INFINITY }
    return buildJsonObject { put("page", recordPage(merged, params, "entries")) }
}

private suspend fun readUpstreamRuns(client: AppServerClient, request: AppServerCommand.CronRuns): List<JsonObject> {
    val rows = mutableListOf<JsonObject>()
    var command = request
    var offset = request.offset ?: 0
    do {
        val entriesPage = upstreamPage(client, command)
        val page = entriesPage.first
        val entries = entriesPage.second
        val hasMore = validateUpstreamPage(page, entries, offset)
        val next = offset + entries.size
        rows += entries.map(::upstreamRow)
        offset = next
        if (hasMore) command = command.copy(requestId = NativeAdmin.requestId(), offset = next)
    } while (hasMore)
    return rows
}

private class CronRunFilter(
    private val taskId: String,
    private val agent: String?,
    private val context: AdminRpcRequestContext,
) {
    fun includesUpstream(row: JsonObject): Boolean {
        val owner = (row["agentId"] as? JsonPrimitive)?.contentOrNull
        return (context.authorizedAgentIds == null || owner in context.authorizedAgentIds) && matchesAgent(owner)
    }

    fun includesStored(row: StoredRunRecord): Boolean =
        (row.input.scheduleId ?: wrapperTaskId(AgentId(row.input.agentId))) == taskId &&
            IrohPeerCapabilities.isAgentInScope(context.authorizedAgentIds, row.input.agentId) && matchesAgent(row.input.agentId)

    private fun matchesAgent(owner: String?): Boolean = agent == null || owner == agent
}

private suspend fun upstreamPage(client: AppServerClient, command: AppServerCommand.CronRuns): Pair<JsonObject, JsonArray> {
    val response = client.cronRuns(command)
    if (!response.success) adminError(response.error ?: "cron_runs failed")
    val page = response.page ?: adminError("cron_runs invalid page")
    val entries = page["entries"] as? JsonArray ?: adminError("cron_runs invalid page")
    return page to entries
}

private fun upstreamRow(entry: kotlinx.serialization.json.JsonElement): JsonObject =
    entry as? JsonObject ?: adminError("cron_runs invalid row")

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
    if (invalidPageSize(total, next, entries)) adminError("cron_runs invalid page")
    if (invalidPageBounds(page, offset)) adminError("cron_runs invalid page")
    if (invalidContinuation(page, entries, total, next, hasMore)) adminError("cron_runs invalid page")
    return hasMore
}

private fun invalidPageSize(total: Int, next: Int, entries: JsonArray): Boolean =
    total !in 0..UPSTREAM_RUN_CAP || invalidPageExtent(next, total, entries)

private fun invalidPageExtent(next: Int, total: Int, entries: JsonArray): Boolean =
    next > total || entries.size > UPSTREAM_PAGE_SIZE

private fun invalidPageBounds(page: JsonObject, offset: Int): Boolean =
    page["offset"] != JsonPrimitive(offset) || page["limit"] != JsonPrimitive(UPSTREAM_PAGE_SIZE)

private fun invalidContinuation(page: JsonObject, entries: JsonArray, total: Int, next: Int, hasMore: Boolean): Boolean {
    val expectedNext = if (hasMore) JsonPrimitive(next) else JsonNull
    return hasMore != (next < total) || invalidNextOffset(page, expectedNext, hasMore, entries)
}

private fun invalidNextOffset(
    page: JsonObject,
    expectedNext: kotlinx.serialization.json.JsonElement,
    hasMore: Boolean,
    entries: JsonArray,
): Boolean = page["nextOffset"] != expectedNext || emptyContinuation(hasMore, entries)

private fun emptyContinuation(hasMore: Boolean, entries: JsonArray): Boolean = hasMore && entries.isEmpty()
