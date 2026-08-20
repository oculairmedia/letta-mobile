package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UserMessage
import io.ktor.utils.io.ByteReadChannel
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal data class TimelineTestMessageSpec(
    val id: String,
    val content: JsonElement,
    val otid: String? = null,
)

private val timelineTestJson = Json { encodeDefaults = true }

internal suspend fun timelineListConversationMessages(
    query: ConversationListQuery,
    deliver: suspend (ConversationListQuery) -> List<LettaMessage>,
): List<LettaMessage> = deliver(query)

internal fun timelineUserMessage(spec: TimelineTestMessageSpec): UserMessage =
    UserMessage(
        id = spec.id,
        contentRaw = spec.content,
        otid = spec.otid,
    )

internal fun timelineAssistantMessage(spec: TimelineTestMessageSpec): AssistantMessage =
    AssistantMessage(
        id = spec.id,
        contentRaw = spec.content,
        otid = spec.otid,
    )

internal fun encodeLettaMessagesAsSse(messages: List<LettaMessage>): ByteReadChannel {
    val sseBody = buildString {
        messages.forEach { message ->
            append("data: ")
            append(timelineTestJson.encodeToString(LettaMessage.serializer(), message))
            append("\n\n")
        }
        append("data: [DONE]\n\n")
    }
    return ByteReadChannel(sseBody.toByteArray())
}

internal fun <T> List<T>.randomOrNull(random: Random): T? =
    if (isEmpty()) null else this[random.nextInt(size)]

internal val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else content.takeIf { it != "null" }
