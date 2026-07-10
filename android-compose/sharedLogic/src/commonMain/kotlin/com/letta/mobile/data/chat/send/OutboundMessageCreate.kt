package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.MessageCreate
import com.letta.mobile.data.model.MessageCreateRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The single outbound user message a [MessageCreateRequest] carries on the
 * send path: plain text, the client otid, and (when multimodal) the raw
 * Letta content-parts array preserved byte-for-byte for the transport.
 *
 * Extracted from [com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway]'s
 * private decoder so the desktop App Server gateway can decode the same
 * [MessageCreateRequest] shape [com.letta.mobile.data.timeline.TimelineOutboundSendProcessor]
 * builds without reimplementing the JSON unwrapping.
 */
data class OutboundMessageCreate(
    val text: String,
    val otid: String?,
    val contentParts: JsonArray?,
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
        }

        fun decode(request: MessageCreateRequest): OutboundMessageCreate {
            val element = request.messages?.firstOrNull()
                ?: return OutboundMessageCreate(text = request.input.orEmpty(), otid = null, contentParts = null)
            val create = json.decodeFromJsonElement(MessageCreate.serializer(), element)
            return when (val content = create.content) {
                is JsonPrimitive -> OutboundMessageCreate(
                    text = content.contentOrNull.orEmpty(),
                    otid = create.otid,
                    contentParts = null,
                )
                is JsonArray -> OutboundMessageCreate(
                    text = content.firstTextPart().orEmpty(),
                    otid = create.otid,
                    contentParts = content,
                )
                else -> OutboundMessageCreate(text = "", otid = create.otid, contentParts = null)
            }
        }

        private fun JsonArray.firstTextPart(): String? = asSequence()
            .filterIsInstance<JsonObject>()
            .firstOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            ?.let { (it["text"] as? JsonPrimitive)?.contentOrNull }
    }
}
