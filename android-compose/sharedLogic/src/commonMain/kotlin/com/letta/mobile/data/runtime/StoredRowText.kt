package com.letta.mobile.data.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal val TEXT_MESSAGE_TYPES = setOf("assistant_message", "reasoning_message", "hidden_reasoning_message")

internal val TOOL_CALL_ROW_TYPES = setOf("tool_call_message", "approval_request_message")

/** The text of an `assistant_message` (content) or reasoning (reasoning, else content) JSON object. */
internal fun JsonObject.messageText(): String? {
    val type = stringField("message_type")
    val field = if (type == "assistant_message") this["content"] else this["reasoning"] ?: this["content"]
    return textOf(field)
}

internal fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun textOf(element: JsonElement?): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull
    is JsonArray -> element.joinToString("") { part -> textPart(part) }.takeIf { it.isNotEmpty() }
    else -> null
}

private fun textPart(part: JsonElement): String {
    val obj = part as? JsonObject ?: return ""
    return if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty() else ""
}
