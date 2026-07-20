package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.api.NoActiveRunException
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.UserMessage
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import io.mockk.mockk
import kotlin.random.Random
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import app.cash.turbine.test
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
internal class BlockingListApi : MessageApi(mockk(relaxed = true)) {
    val listStarted = CompletableDeferred<Unit>()
    val releaseList = CompletableDeferred<List<LettaMessage>>()

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        kotlinx.coroutines.awaitCancellation()
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        listStarted.complete(Unit)
        return releaseList.await()
    }
}

internal class OpenStreamApi : MessageApi(mockk(relaxed = true)) {
    val streamOpened = CompletableDeferred<Unit>()
    @Volatile var listMessagesCalls: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        streamOpened.complete(Unit)
        return ByteChannel(autoFlush = true)
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        listMessagesCalls++
        return emptyList()
    }
}

internal class OneShotAssistantStreamApi : MessageApi(mockk(relaxed = true)) {
    private var opened = false

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        if (opened) kotlinx.coroutines.awaitCancellation()
        opened = true
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val message = AssistantMessage(
            id = "asst-dynamic",
            contentRaw = JsonPrimitive("late listener works"),
        )
        val sseBody = buildString {
            append("data: ")
            append(json.encodeToString(LettaMessage.serializer(), message))
            append("\n\n")
            append("data: [DONE]\n\n")
        }
        return ByteReadChannel(sseBody.toByteArray())
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = emptyList()
}

internal class SilentAfterHeartbeatApi : MessageApi(mockk(relaxed = true)) {
    @Volatile var streamCallCount: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        streamCallCount++
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8(": ping\n\n")
        return channel
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = emptyList()
}

internal class AlwaysIdleApi : MessageApi(mockk(relaxed = true)) {
    @Volatile var streamCallCount: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        streamCallCount++
        // letta-mobile-t8q7: real MessageApi classifies the "No active runs"
        // 400 body into NoActiveRunException before it reaches the subscriber.
        throw NoActiveRunException(conversationId.value)
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = emptyList()
}

/**
 * Fake api for the gqz3 regression test. The real `MessageApi.streamConversation`
 * classifies EXPIRED bodies into `NoActiveRunException` (letta-mobile-t8q7) so
 * this fake mirrors that contract: first call throws `NoActiveRunException`,
 * subsequent calls idle. Before t8q7 the fake threw `ApiException` and the
 * subscriber re-classified by message-text in its catch block.
 */
internal class ExpiredThenIdleApi : MessageApi(mockk(relaxed = true)) {
    @Volatile var streamCallCount: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        streamCallCount++
        if (streamCallCount == 1) {
            throw NoActiveRunException(conversationId.value)
        }
        kotlinx.coroutines.awaitCancellation()
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = emptyList()
}

/**
 * Fake [MessageApi] that simulates:
 * - a stored message list (returned by listMessages)
 * - a programmable stream (returned by sendConversationMessage as a real SSE byte channel)
 *
 * On each send: the user message is added to the store with its otid preserved,
 * and the stream yields [nextStreamMessages] as SSE events.
 */
internal class FakeSyncApi : MessageApi(mockk(relaxed = true)) {
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

    // The default `MessageApi.streamConversation` calls into a relaxed-mockk
    // HttpClient and returns an ApiException on every invocation, which sends
    // `runStreamSubscriber` into a 5s-delay retry loop that accumulates
    // timers for the full duration of each test (observed: 91s for one test
    // that never exercises the subscriber). Idle here instead so the loop
    // suspends until the test's scope is cancelled. letta-mobile-o8pr.
    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        if (streamConversationReturnsOpenChannel) {
            return ByteChannel()
        }
        kotlinx.coroutines.awaitCancellation()
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        listMessagesCalls++
        lastConversationLimit = limit
        conversationLimits += limit
        conversationOrders += order
        if (listMessagesFailuresBeforeSuccess > 0) {
            listMessagesFailuresBeforeSuccess--
            throw listMessagesFailure
                ?: java.io.IOException("injected listConversationMessages failure")
        }
        val ordered = if (order == "desc") stored.reversed() else stored.toList()
        return if (limit != null) ordered.take(limit) else ordered
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
        // Extract otid from request and create a UserMessage in the store to
        // mimic server persistence.
        val firstMessage = request.messages?.firstOrNull()
        val otid = firstMessage?.let {
            (it as? kotlinx.serialization.json.JsonObject)?.get("otid")?.let { v ->
                (v as? JsonPrimitive)?.contentOrNull
            }
        }
        val userContent = firstMessage?.let {
            (it as? JsonObject)?.get("content")
        }
        if (otid != null) {
            stored.add(
                UserMessage(
                    id = "message-$otid",
                    contentRaw = userContent ?: JsonPrimitive(""),
                    otid = otid,
                )
            )
        }

        // Emit nextStreamMessages as real SSE frames and close
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val sseBody = buildString {
            nextStreamMessages.forEach { msg ->
                append("data: ")
                append(json.encodeToString(LettaMessage.serializer(), msg))
                append("\n\n")
            }
            append("data: [DONE]\n\n")
        }

        // Also add stream messages to the store so subsequent listMessages reflects them
        stored.addAll(nextStreamMessages)

        // Pre-filled ByteReadChannel — no background writer coroutine. The
        // previous GlobalScope.launch pattern leaked coroutines across tests
        // in the same JVM worker, eventually OOMing CI (letta-mobile-o8pr).
        return ByteReadChannel(sseBody.toByteArray())
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

internal val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else content.takeIf { it != "null" }
