package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.api.NoActiveRunException
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class ConversationListQuery(
    val limit: Int? = null,
    val after: String? = null,
    val order: String? = null,
)

internal data class TimelineCursorRecord(
    val conversationId: String,
    val seq: Long,
)

internal data class TimelineCursorKey(
    val conversationId: String,
)

internal data class TimelineCursorMapView(
    val highestByConversation: Map<String, Long>,
)

internal data class TimelineCursorClearRequest(
    val key: TimelineCursorKey,
)

internal data class TimelineMessageBatch(
    val messages: List<LettaMessage>,
)

internal data class TimelineOrderedMessageView(
    val query: ConversationListQuery,
    val messages: List<LettaMessage>,
)

internal data class TimelineSendCapture(
    val request: MessageCreateRequest,
    val streamMessages: List<LettaMessage>,
)

internal data class TimelineStoredMessageSink(
    val stored: MutableList<LettaMessage>,
)

internal data class TimelineListFailurePolicy(
    val remainingFailures: Int,
    val failure: Throwable?,
)

internal data class TimelineStreamChannelRequest(
    val openIdleChannel: Boolean,
)

internal data class TimelineAssistantStreamPlan(
    val message: LettaMessage,
)

internal data class TimelineHeartbeatStreamPlan(
    val channel: ByteChannel,
)

internal data class TimelineIdleStreamPlan(
    val conversation: ConversationId,
)

internal data class TimelineExpiredStreamPlan(
    val conversation: ConversationId,
    val callCount: Int,
)

internal data class TimelineListGate(
    val listStarted: CompletableDeferred<Unit>,
    val releaseList: CompletableDeferred<List<LettaMessage>>,
)

internal data class TimelineStreamOpenSignal(
    val streamOpened: CompletableDeferred<Unit>,
)

internal data class TimelineCursorPairView(
    val records: List<Pair<String, Long>>,
)

internal data class TimelineUserPersistPlan(
    val sink: TimelineStoredMessageSink,
    val request: MessageCreateRequest,
)

internal data class TimelineSendPipeline(
    val sink: TimelineStoredMessageSink,
    val capture: TimelineSendCapture,
)

internal class RecordingConversationCursorStore : ConversationCursorStore {
    val records = mutableListOf<Pair<String, Long>>()
    internal val highestByConversation = mutableMapOf<String, Long>()

    override suspend fun recordFrame(conversationId: String, seq: Long) {
        rememberCursorRecord(TimelineCursorRecord(conversationId, seq))
    }

    fun record(record: TimelineCursorRecord) {
        rememberCursorRecord(record)
    }

    override suspend fun getCursor(conversationId: String): Long? =
        highestSeqFor(TimelineCursorKey(conversationId))

    override suspend fun getAllCursors(): Map<String, Long> =
        snapshotHighestCursors(TimelineCursorMapView(highestByConversation))

    override suspend fun clearCursor(conversationId: String) {
        clearCursorFor(TimelineCursorClearRequest(TimelineCursorKey(conversationId)))
    }

    private fun rememberCursorRecord(record: TimelineCursorRecord) {
        appendCursorPair(record)
        bumpHighestCursor(record)
    }

    private fun appendCursorPair(record: TimelineCursorRecord) {
        records += record.conversationId to record.seq
    }

    private fun bumpHighestCursor(record: TimelineCursorRecord) {
        val previous = highestByConversation[record.conversationId] ?: Long.MIN_VALUE
        highestByConversation[record.conversationId] = maxOf(previous, record.seq)
    }

    private fun highestSeqFor(key: TimelineCursorKey): Long? =
        highestByConversation[key.conversationId]?.takeIf { it != Long.MIN_VALUE }

    private fun snapshotHighestCursors(view: TimelineCursorMapView): Map<String, Long> =
        view.highestByConversation.filterValues { it != Long.MIN_VALUE }

    private fun clearCursorFor(request: TimelineCursorClearRequest) {
        highestByConversation.remove(request.key.conversationId)
    }

    internal fun recordedPairs(): TimelineCursorPairView =
        TimelineCursorPairView(records.toList())
}

internal open class TimelineTestMessageApi : MessageApi(mockk(relaxed = true)) {
    final override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = timelineListConversationMessages(
        ConversationListQuery(limit = limit, after = after, order = order),
        ::onListConversationMessages,
    )

    protected open suspend fun onListConversationMessages(query: ConversationListQuery): List<LettaMessage> =
        emptyMessageBatch().messages

    protected fun emptyMessageBatch(): TimelineMessageBatch =
        TimelineMessageBatch(emptyList())
}

internal class BlockingListApi : TimelineTestMessageApi() {
    val listStarted = CompletableDeferred<Unit>()
    val releaseList = CompletableDeferred<List<LettaMessage>>()

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        awaitCancellationFor(TimelineIdleStreamPlan(conversationId))
    }

    override suspend fun onListConversationMessages(query: ConversationListQuery): List<LettaMessage> =
        awaitGatedList(TimelineListGate(listStarted, releaseList), query)
}

internal class OpenStreamApi : TimelineTestMessageApi() {
    val streamOpened = CompletableDeferred<Unit>()
    @Volatile var listMessagesCalls: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel =
        openIdleStream(
            TimelineStreamOpenSignal(streamOpened),
            TimelineIdleStreamPlan(conversationId),
        )

    override suspend fun onListConversationMessages(query: ConversationListQuery): List<LettaMessage> {
        listMessagesCalls++
        return emptyForQuery(query).messages
    }
}

internal class OneShotAssistantStreamApi : TimelineTestMessageApi() {
    private var opened = false

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        if (opened) awaitCancellationFor(TimelineIdleStreamPlan(conversationId))
        opened = true
        return encodeAssistantStream(
            TimelineAssistantStreamPlan(
                timelineAssistantMessage(
                    TimelineTestMessageSpec(
                        id = "asst-dynamic",
                        content = JsonPrimitive("late listener works"),
                    ),
                ),
            ),
        )
    }
}

internal class SilentAfterHeartbeatApi : TimelineTestMessageApi() {
    @Volatile var streamCallCount: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        streamCallCount++
        return encodeHeartbeatStream(TimelineHeartbeatStreamPlan(ByteChannel(autoFlush = true)))
    }
}

internal class AlwaysIdleApi : TimelineTestMessageApi() {
    @Volatile var streamCallCount: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        streamCallCount++
        throwIdleFor(TimelineIdleStreamPlan(conversationId))
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
        return handleExpiredThenIdle(TimelineExpiredStreamPlan(conversationId, streamCallCount))
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
        appendStoredMessage(TimelineStoredMessageSink(stored), msg)
    }

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel =
        openStreamFor(
            TimelineStreamChannelRequest(streamConversationReturnsOpenChannel),
            TimelineIdleStreamPlan(conversationId),
        )

    override suspend fun onListConversationMessages(query: ConversationListQuery): List<LettaMessage> {
        listMessagesCalls++
        rememberListQuery(query)
        applyListFailurePolicy(this)
        return orderStoredMessages(TimelineOrderedMessageView(query, stored.toList()))
    }

    override suspend fun sendConversationMessage(
        conversationId: ConversationId,
        request: MessageCreateRequest,
    ): ByteReadChannel {
        sendCalls++
        return completeSend(
            TimelineSendPipeline(
                sink = TimelineStoredMessageSink(stored),
                capture = TimelineSendCapture(request, nextStreamMessages),
            ),
        )
    }

    private fun rememberListQuery(query: ConversationListQuery) {
        lastConversationLimit = query.limit
        conversationLimits += query.limit
        conversationOrders += query.order
    }

    private suspend fun completeSend(pipeline: TimelineSendPipeline): ByteReadChannel {
        nextSendFailure?.let { failure ->
            nextSendFailure = null
            throw failure
        }
        lastSendRequest = pipeline.capture.request
        sendResponseGate?.await()
        persistUserMessageFromRequest(
            TimelineUserPersistPlan(pipeline.sink, pipeline.capture.request),
        )
        appendStreamMessages(pipeline.sink, pipeline.capture.streamMessages)
        return encodeMessageBatch(TimelineMessageBatch(pipeline.capture.streamMessages))
    }

    private fun persistUserMessageFromRequest(plan: TimelineUserPersistPlan) {
        val firstMessage = plan.request.messages?.firstOrNull() ?: return
        val otid = firstMessage.extractOtid() ?: return
        val userContent = (firstMessage as? JsonObject)?.get("content")
        appendStoredMessage(
            plan.sink,
            timelineUserMessage(
                TimelineTestMessageSpec(
                    id = "message-$otid",
                    content = userContent ?: JsonPrimitive(""),
                    otid = otid,
                ),
            ),
        )
    }

    private fun kotlinx.serialization.json.JsonElement.extractOtid(): String? =
        (this as? JsonObject)?.get("otid")?.let { value ->
            (value as? JsonPrimitive)?.contentOrNull
        }
}

private fun applyListFailurePolicy(api: FakeSyncApi) {
    val policy = TimelineListFailurePolicy(
        remainingFailures = api.listMessagesFailuresBeforeSuccess,
        failure = api.listMessagesFailure,
    )
    if (policy.remainingFailures <= 0) return
    api.listMessagesFailuresBeforeSuccess = policy.remainingFailures - 1
    throw policy.failure
        ?: java.io.IOException("injected listConversationMessages failure")
}

private fun emptyForQuery(query: ConversationListQuery): TimelineMessageBatch =
    TimelineMessageBatch(emptyList()).also { consumeQuery(query) }

private fun consumeQuery(query: ConversationListQuery) {
    query.limit
    query.after
    query.order
}

private fun orderStoredMessages(view: TimelineOrderedMessageView): List<LettaMessage> {
    val ordered = if (view.query.order == "desc") view.messages.reversed() else view.messages
    return if (view.query.limit != null) ordered.take(view.query.limit) else ordered
}

private fun appendStoredMessage(sink: TimelineStoredMessageSink, message: LettaMessage) {
    sink.stored.add(message)
}

private fun appendStreamMessages(sink: TimelineStoredMessageSink, messages: List<LettaMessage>) {
    sink.stored.addAll(messages)
}

private fun encodeMessageBatch(batch: TimelineMessageBatch): ByteReadChannel =
    encodeLettaMessagesAsSse(batch.messages)

private fun encodeAssistantStream(plan: TimelineAssistantStreamPlan): ByteReadChannel =
    encodeMessageBatch(TimelineMessageBatch(listOf(plan.message)))

private suspend fun encodeHeartbeatStream(plan: TimelineHeartbeatStreamPlan): ByteReadChannel {
    plan.channel.writeStringUtf8(": ping\n\n")
    return plan.channel
}

private fun throwIdleFor(plan: TimelineIdleStreamPlan): Nothing =
    throw NoActiveRunException(plan.conversation.value)

private suspend fun awaitCancellationFor(plan: TimelineIdleStreamPlan): Nothing {
    touchIdlePlan(plan)
    kotlinx.coroutines.awaitCancellation()
}

private fun touchIdlePlan(plan: TimelineIdleStreamPlan) {
    plan.conversation.value
}

private suspend fun handleExpiredThenIdle(plan: TimelineExpiredStreamPlan): ByteReadChannel {
    if (plan.callCount == 1) {
        throwIdleFor(TimelineIdleStreamPlan(plan.conversation))
    }
    return awaitCancellationFor(TimelineIdleStreamPlan(plan.conversation))
}

private suspend fun openIdleStream(
    signal: TimelineStreamOpenSignal,
    plan: TimelineIdleStreamPlan,
): ByteReadChannel {
    signal.streamOpened.complete(Unit)
    touchIdlePlan(plan)
    return ByteChannel(autoFlush = true)
}

private suspend fun openStreamFor(
    request: TimelineStreamChannelRequest,
    plan: TimelineIdleStreamPlan,
): ByteReadChannel {
    touchIdlePlan(plan)
    if (request.openIdleChannel) {
        return ByteChannel()
    }
    kotlinx.coroutines.awaitCancellation()
}

private suspend fun awaitGatedList(
    gate: TimelineListGate,
    query: ConversationListQuery,
): List<LettaMessage> {
    consumeQuery(query)
    gate.listStarted.complete(Unit)
    return gate.releaseList.await()
}

private fun messageSpecContent(spec: TimelineTestMessageSpec): JsonElement = spec.content

private fun messageSpecId(spec: TimelineTestMessageSpec): String = spec.id

private fun messageSpecOtid(spec: TimelineTestMessageSpec): String? = spec.otid

private fun copyMessageBatch(batch: TimelineMessageBatch): TimelineMessageBatch =
    TimelineMessageBatch(batch.messages.toList())

private fun mergeMessageBatches(
    left: TimelineMessageBatch,
    right: TimelineMessageBatch,
): TimelineMessageBatch = TimelineMessageBatch(left.messages + right.messages)

private fun takeMessageBatch(
    batch: TimelineMessageBatch,
    query: ConversationListQuery,
): TimelineMessageBatch {
    val limited = if (query.limit != null) batch.messages.take(query.limit) else batch.messages
    return TimelineMessageBatch(limited)
}

private fun reverseMessageBatch(batch: TimelineMessageBatch): TimelineMessageBatch =
    TimelineMessageBatch(batch.messages.reversed())

private fun cursorRecordKey(record: TimelineCursorRecord): TimelineCursorKey =
    TimelineCursorKey(record.conversationId)

private fun cursorClearRequest(key: TimelineCursorKey): TimelineCursorClearRequest =
    TimelineCursorClearRequest(key)

private fun cursorMapView(store: RecordingConversationCursorStore): TimelineCursorMapView =
    TimelineCursorMapView(store.highestByConversation.toMap())

private fun cursorPairView(store: RecordingConversationCursorStore): TimelineCursorPairView =
    store.recordedPairs()

private fun withCursorRecord(
    store: RecordingConversationCursorStore,
    record: TimelineCursorRecord,
): TimelineCursorRecord {
    store.record(record)
    return record
}

private fun sendCaptureMessages(capture: TimelineSendCapture): TimelineMessageBatch =
    TimelineMessageBatch(capture.streamMessages)

private fun sendCaptureRequest(capture: TimelineSendCapture): MessageCreateRequest =
    capture.request

private fun orderedViewFromBatch(
    query: ConversationListQuery,
    batch: TimelineMessageBatch,
): TimelineOrderedMessageView = TimelineOrderedMessageView(query, batch.messages)

private fun failurePolicy(policy: TimelineListFailurePolicy): TimelineListFailurePolicy = policy

private fun streamChannelRequest(
    request: TimelineStreamChannelRequest,
): TimelineStreamChannelRequest = request

private fun assistantStreamMessage(plan: TimelineAssistantStreamPlan): LettaMessage = plan.message

private fun heartbeatChannel(plan: TimelineHeartbeatStreamPlan): ByteChannel = plan.channel

private fun idleConversation(plan: TimelineIdleStreamPlan): ConversationId = plan.conversation

private fun expiredPlanConversation(plan: TimelineExpiredStreamPlan): ConversationId =
    plan.conversation

private fun listGateStarted(gate: TimelineListGate): CompletableDeferred<Unit> = gate.listStarted

private fun listGateRelease(gate: TimelineListGate): CompletableDeferred<List<LettaMessage>> =
    gate.releaseList

private fun streamOpenSignal(signal: TimelineStreamOpenSignal): CompletableDeferred<Unit> =
    signal.streamOpened

private fun storedSinkMessages(sink: TimelineStoredMessageSink): List<LettaMessage> =
    sink.stored.toList()

private fun touchMessageSpec(spec: TimelineTestMessageSpec): TimelineTestMessageSpec {
    messageSpecId(spec)
    messageSpecContent(spec)
    messageSpecOtid(spec)
    return spec
}

private fun touchCursorRecord(record: TimelineCursorRecord): TimelineCursorRecord {
    cursorRecordKey(record)
    return record
}

private fun touchQueryBatch(
    query: ConversationListQuery,
    batch: TimelineMessageBatch,
): TimelineOrderedMessageView = orderedViewFromBatch(query, copyMessageBatch(batch))

private fun sendPipelineCapture(pipeline: TimelineSendPipeline): TimelineSendCapture =
    pipeline.capture

private fun sendPipelineSink(pipeline: TimelineSendPipeline): TimelineStoredMessageSink =
    pipeline.sink

private fun userPersistSink(plan: TimelineUserPersistPlan): TimelineStoredMessageSink = plan.sink

private fun userPersistRequest(plan: TimelineUserPersistPlan): MessageCreateRequest = plan.request

private fun mergeOrderedViews(
    left: TimelineOrderedMessageView,
    right: TimelineOrderedMessageView,
): TimelineMessageBatch = mergeMessageBatches(
    TimelineMessageBatch(left.messages),
    TimelineMessageBatch(right.messages),
)

private fun limitedOrderedView(view: TimelineOrderedMessageView): TimelineMessageBatch =
    takeMessageBatch(TimelineMessageBatch(view.messages), view.query)

private fun reversedOrderedView(view: TimelineOrderedMessageView): TimelineMessageBatch =
    reverseMessageBatch(TimelineMessageBatch(view.messages))

private fun clearRequestFromRecord(record: TimelineCursorRecord): TimelineCursorClearRequest =
    cursorClearRequest(cursorRecordKey(record))

private fun pairViewRecords(view: TimelineCursorPairView): List<Pair<String, Long>> = view.records

private fun mapViewEntries(view: TimelineCursorMapView): Map<String, Long> =
    view.highestByConversation
