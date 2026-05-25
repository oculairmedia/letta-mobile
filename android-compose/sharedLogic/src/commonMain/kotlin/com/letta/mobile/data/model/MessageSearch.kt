package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class CancelAgentRunRequest(
    @SerialName("run_ids") val runIds: List<String>? = null,
)

@Serializable
data class MessageSearchRequest(
    val query: String? = null,
    @SerialName("search_mode") val searchMode: String = "hybrid",
    val roles: List<String>? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("template_id") val templateId: String? = null,
    val limit: Int = 50,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
)

@Serializable
data class MessageSearchResult(
    @SerialName("embedded_text") val embeddedText: String,
    val message: JsonObject,
)

data class ParsedSearchMessage(
    val messageId: String?,
    val agentId: String?,
    val role: String?,
    val content: String?,
    val date: String?,
    val conversationId: String?,
)

fun MessageSearchResult.toParsed(): ParsedSearchMessage {
    return ParsedSearchMessage(
        messageId = message.primitive("id") ?: message.primitive("message_id"),
        agentId = message.primitive("agent_id"),
        role = message.primitive("role") ?: message.primitive("message_type"),
        content = message["content"].asSearchText() ?: message.primitive("text") ?: embeddedText,
        date = message.primitive("date") ?: message.primitive("created_at"),
        conversationId = message.primitive("conversation_id"),
    )
}

private fun JsonObject.primitive(key: String): String? {
    return (this[key] as? JsonPrimitive)?.contentOrNull
}

fun JsonElement?.asSearchText(): String? {
    return when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonArray -> mapNotNull { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull
                is JsonObject -> part.primitive("text") ?: part.primitive("content")
                else -> null
            }
        }.joinToString(" ").takeIf { it.isNotBlank() }
        is JsonObject -> primitive("text") ?: primitive("content") ?: get("value").asSearchText()
        else -> null
    }
}
