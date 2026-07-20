package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.api.NoActiveRunException
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.UserMessage
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import io.mockk.mockk
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class TimelineTestMessageSpec(
    val id: String,
    val content: JsonElement,
    val otid: String? = null,
)

internal data class ConversationListQuery(
    val limit: Int? = null,
    val after: String? = null,
    val order: String? = null,
)

private fun conversationListQuery(
    limit: Int?,
    after: String?,
    order: String?,
): ConversationListQuery = ConversationListQuery(limit = limit, after = after, order = order)

private val timelineTestJson = Json { encodeDefaults = true }

internal suspend fun timelineListConversationMessages(
    limit: Int?,
    after: String?,
    order: String?,
    deliver: suspend (ConversationListQuery) -> List<LettaMessage>,
): List<LettaMessage> {
    val query = conversationListQuery(limit, after, order)
    return deliver(query)
}

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

internal open class TimelineTestMessageApi : MessageApi(mockk(relaxed = true)) {
    final override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = timelineListConversationMessages(limit, after, order, ::onListConversationMessages)

    protected open suspend fun onListConversationMessages(query: ConversationListQuery): List<LettaMessage> =
        emptyList()
}

internal class BlockingListApi : TimelineTestMessageApi() {
    val listStarted = CompletableDeferred<Unit>()
    val releaseList = CompletableDeferred<List<LettaMessage>>()

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        kotlinx.coroutines.awaitCancellation()
    }

    override suspend fun onListConversationMessages(query: ConversationListQuery): List<LettaMessage> {
        listStarted.complete(Unit)
        return releaseList.await()
    }
}

internal class OpenStreamApi : TimelineTestMessageApi() {
    val streamOpened = CompletableDeferred<Unit>()
    @Volatile var listMessagesCalls: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        streamOpened.complete(Unit)
        return ByteChannel(autoFlush = true)
    }

    override suspend fun onListConversationMessages(query: ConversationListQuery): List<LettaMessage> {
        listMessagesCalls++
        return emptyList()
    }
}

internal class OneShotAssistantStreamApi : TimelineTestMessageApi() {
    private var opened = false

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        if (opened) kotlinx.coroutines.awaitCancellation()
        opened = true
        val message = timelineAssistantMessage(
            TimelineTestMessageSpec(id = "asst-dynamic", content = JsonPrimitive("late listener works")),
        )
        return encodeLettaMessagesAsSse(listOf(message))
    }
}

internal class SilentAfterHeartbeatApi : TimelineTestMessageApi() {
    @Volatile var streamCallCount: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        streamCallCount++
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8(": ping\n\n")
        return channel
    }
}

internal class AlwaysIdleApi : TimelineTestMessageApi() {
    @Volatile var streamCallCount: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        streamCallCount++
        throw NoActiveRunException(conversationId.value)
    }
}

/**
 * Fake api for the gqz3 regression test. The real `MessageApi.streamConversation`
 * classifies EXPIRED bodies into `NoActiveRunException` (letta-mobile-t8q7) so
 * this fake mirrors that contract: first call throws `NoActiveRunException`,
 * subsequent calls idle. Before t8q7 the fake threw `ApiException` and the
 * subscriber re-classified by message-text in its catch block.
 */
internal class ExpiredThenIdleApi : TimelineTestMessageApi() {
    @Volatile var streamCallCount: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        streamCallCount++
        if (streamCallCount == 1) {
            throw NoActiveRunException(conversationId.value)
        }
        kotlinx.coroutines.awaitCancellation()
    }
}

/**
 * Fake [MessageApi] that simulates:
 * - a stored message list (returned by listMessages)
 * - a programmable stream (returned by sendConversationMessage as a real SSE byte channel)
 *
 * On each send: the user message is added to the store with its otid preserved,
 * and the stream yields [nextStreamMessages] as SSE events.
 */
internal class FakeSyncApi : TimelineTestMessageApi() {
    internal val stored = mutableListOf<LettaMessage>()
    var nextStreamMessages: List<LettaMessage> = emptyList()
    var lastSendRequest: MessageCreateRequest? = null
    var sendResponseGate: CompletableDeferred<Unit>? = null
    var nextSendFailure: Throwable? = null
    var sendCalls: Int = 0

    // letta-mobile-j44j: failure-injection for reconcile retry tests.
    // When [listMessagesFailuresBeforeSuccess] > 0, the first N calls to
    // [listConversationMessages] throw [listMessagesFailure] (or a default
    // IOException if none is set). Subsequent calls return normally.
    var listMessagesFailuresBeforeSuccess: Int = 0
    var listMessagesFailure: Throwable? = null
    var listMessagesCalls: Int = 0
    var lastConversationLimit: Int? = null
    val conversationLimits = mutableListOf<Int?>()
    val conversationOrders = mutableListOf<String?>()
    var streamConversationReturnsOpenChannel: Boolean = false

    fun addStoredMessage(msg: LettaMessage) {
        stored.add(msg)
    }

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        if (streamConversationReturnsOpenChannel) {
            return ByteChannel()
        }
        kotlinx.coroutines.awaitCancellation()
    }

    override suspend fun onListConversationMessages(query: ConversationListQuery): List<LettaMessage> {
        listMessagesCalls++
        lastConversationLimit = query.limit
        conversationLimits += query.limit
        conversationOrders += query.order
        if (listMessagesFailuresBeforeSuccess > 0) {
            listMessagesFailuresBeforeSuccess--
            throw listMessagesFailure
                ?: java.io.IOException("injected listConversationMessages failure")
        }
        val ordered = if (query.order == "desc") stored.reversed() else stored.toList()
        return if (query.limit != null) ordered.take(query.limit) else ordered
    }

    override suspend fun sendConversationMessage(
        conversationId: ConversationId,
        request: MessageCreateRequest,
    ): ByteReadChannel {
        sendCalls++
        nextSendFailure?.let { failure ->
            nextSendFailure = null
            throw failure
        }
        lastSendRequest = request
        sendResponseGate?.await()
        persistUserMessageFromRequest(request)
        stored.addAll(nextStreamMessages)
        return encodeLettaMessagesAsSse(nextStreamMessages)
    }

    private fun persistUserMessageFromRequest(request: MessageCreateRequest) {
        val firstMessage = request.messages?.firstOrNull() ?: return
        val otid = firstMessage.extractOtid() ?: return
        val userContent = (firstMessage as? JsonObject)?.get("content")
        stored.add(
            timelineUserMessage(
                TimelineTestMessageSpec(
                    id = "message-$otid",
                    content = userContent ?: JsonPrimitive(""),
                    otid = otid,
                )
            )
        )
    }

    private fun JsonElement.extractOtid(): String? =
        (this as? JsonObject)?.get("otid")?.let { value ->
            (value as? JsonPrimitive)?.contentOrNull
        }
}

internal fun <T> List<T>.randomOrNull(random: Random): T? =
    if (isEmpty()) null else this[random.nextInt(size)]

internal class RecordingConversationCursorStore : ConversationCursorStore {
    val records = mutableListOf<Pair<String, Long>>()
    internal val highestByConversation = mutableMapOf<String, Long>()

    override suspend fun recordFrame(conversationId: String, seq: Long) {
        records += conversationId to seq
        highestByConversation[conversationId] = maxOf(highestByConversation[conversationId] ?: Long.MIN_VALUE, seq)
    }

    override suspend fun getCursor(conversationId: String): Long? =
        highestByConversation[conversationId]?.takeIf { it != Long.MIN_VALUE }

    override suspend fun getAllCursors(): Map<String, Long> =
        highestByConversation.filterValues { it != Long.MIN_VALUE }

    override suspend fun clearCursor(conversationId: String) {
        highestByConversation.remove(conversationId)
    }
}

internal val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else content.takeIf { it != "null" }
