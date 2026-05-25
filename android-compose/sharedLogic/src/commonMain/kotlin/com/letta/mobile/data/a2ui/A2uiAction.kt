package com.letta.mobile.data.a2ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class A2uiAction(
    val name: String,
    val surfaceId: String,
    val context: JsonObject = JsonObject(emptyMap()),
    val runId: String? = null,
    val turnId: String? = null,
    val actionId: String? = null,
    val raw: JsonObject = buildJsonObject {
        put("actionName", name)
        put("name", name)
        put("surfaceId", surfaceId)
        put("context", context)
        runId?.let { put("runId", it) }
        turnId?.let { put("turnId", it) }
        actionId?.let { put("actionId", it) }
    },
)
