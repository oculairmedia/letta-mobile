package com.letta.mobile.data.controller.node.iroh.records

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun StoredRunRecord.wire() = buildJsonObject {
    put("id", id)
    put("agentId", input.agentId)
    put("scheduleId", input.scheduleId)
    put("scheduleName", input.scheduleName)
    put("instant", Instant.ofEpochMilli(input.instantMs).toString())
    put("status", input.status.name.lowercase())
    put("reason", input.reason)
    put("source", input.source.name.lowercase())
    put("detail", input.detail)
    put("createdAt", Instant.ofEpochMilli(createdAtMs).toString())
}

internal fun StoredLeaseRecord.wire() = buildJsonObject {
    put("id", input.id)
    put("owner", input.owner)
    put("label", input.label)
    put("startedAt", Instant.ofEpochMilli(input.startedAtMs).toString())
    put("dueAt", Instant.ofEpochMilli(input.dueAtMs).toString())
    put("state", input.state.name.lowercase())
    put("runId", input.runId)
    put("detail", input.detail)
    put("createdAt", Instant.ofEpochMilli(createdAtMs).toString())
    put("updatedAt", Instant.ofEpochMilli(updatedAtMs).toString())
}

internal fun recordPage(rows: List<JsonObject>, params: JsonObject?, key: String) = buildJsonObject {
    val limit = params.integer("limit", DEFAULT_PAGE_LIMIT, MAX_PAGE_LIMIT, min = 1)
    val offset = params.integer("offset", 0)
    val page = rows.drop(offset).take(limit)
    val next = offset.toLong() + page.size
    put(key, JsonArray(page)); put("total", rows.size); put("offset", offset); put("limit", limit)
    put("hasMore", next < rows.size)
    put("nextOffset", if (next < rows.size) JsonPrimitive(next) else JsonNull)
}
