package com.letta.mobile.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class VibesyncEvent(
    val type: String,
    val data: JsonObject? = null,
    val id: String? = null,
) {
    val projectId: String?
        get() = data?.get("projectId")?.stringOrNull()
            ?: data?.get("project_id")?.stringOrNull()
            ?: data?.get("project")?.stringOrNull()
}

@Serializable
data class VibesyncRawEventEnvelope(
    val event: String? = null,
    val type: String? = null,
    val data: JsonObject? = null,
)

private fun JsonElement.stringOrNull(): String? = runCatching { jsonPrimitive.content }.getOrNull()
