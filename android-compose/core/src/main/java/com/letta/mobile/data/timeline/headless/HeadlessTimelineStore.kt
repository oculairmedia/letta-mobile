package com.letta.mobile.data.timeline.headless

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.HydratedTimelineResult
import com.letta.mobile.data.timeline.MessageSource
import com.letta.mobile.data.timeline.Role
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineHydrationReducer
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.TimelineReducerInput
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.timeline.reduceStreamFrame
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Headless timeline facade for CLI and regression harnesses.
 *
 * The store intentionally delegates stream and hydration state changes to the
 * production reducers. It is not another timeline implementation; it is the
 * smallest in-memory adapter needed to drive those reducers without Android UI
 * or Room.
 */
class HeadlessTimelineStore(
    private val json: Json = defaultHeadlessTimelineJson,
) : TimelineExternalTransportWriter {
    private val mutex = Mutex()
    private val timelines = LinkedHashMap<String, Timeline>()
    private val pendingToolReturnsByConversation =
        LinkedHashMap<String, Map<String, ToolReturnMessage>>()

    suspend fun hydrate(
        conversationId: String,
        serverMessagesChronological: List<LettaMessage>,
    ): HydratedTimelineResult = mutex.withLock {
        val current = timelineLocked(conversationId)
        val result = TimelineHydrationReducer.reduce(
            conversationId = conversationId,
            serverMessagesChronological = serverMessagesChronological,
            timelineBeforeFetch = current,
            currentTimeline = current,
            diskRecords = emptyList(),
        )
        timelines[conversationId] = result.timeline
        result
    }

    suspend fun ingest(conversationId: String, message: LettaMessage): Timeline = mutex.withLock {
        val pending = pendingToolReturnsByConversation[conversationId].orEmpty()
        val output = reduceStreamFrame(
            TimelineReducerInput(
                prev = timelineLocked(conversationId),
                frame = message,
                pendingToolReturnsByCallId = pending,
            )
        )
        timelines[conversationId] = output.next
        pendingToolReturnsByConversation[conversationId] = output.updatedPendingToolReturnsByCallId
        output.next
    }

    suspend fun snapshot(conversationId: String): Timeline = mutex.withLock {
        timelineLocked(conversationId)
    }

    suspend fun dumpJson(conversationId: String, pretty: Boolean = true): String {
        val dump = mutex.withLock { timelineLocked(conversationId).toDumpJson() }
        return if (pretty) {
            json.encodeToString(JsonObject.serializer(), dump)
        } else {
            compactJson.encodeToString(JsonObject.serializer(), dump)
        }
    }

    suspend fun assertTimeline(
        conversationId: String,
        assertNoDuplicateUiMessages: Boolean,
        assertOtidUnique: Boolean,
        assertSeqMonotonic: Boolean,
    ): TimelineAssertionReport = mutex.withLock {
        val timeline = timelineLocked(conversationId)
        val failures = mutableListOf<String>()
        if (assertNoDuplicateUiMessages) {
            val duplicateIds = timeline.events
                .mapNotNull { it.uiIdentityOrNull() }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .sorted()
            if (duplicateIds.isNotEmpty()) {
                failures += "duplicate UiMessage ids: ${duplicateIds.joinToString()}"
            }
            val duplicateSemanticKeys = timeline.events
                .mapNotNull { it.uiSemanticIdentityOrNull() }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .sorted()
            if (duplicateSemanticKeys.isNotEmpty()) {
                failures += "duplicate UiMessage semantic keys: ${duplicateSemanticKeys.joinToString()}"
            }
        }
        if (assertOtidUnique) {
            val duplicates = timeline.events
                .map { it.otid }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .sorted()
            if (duplicates.isNotEmpty()) {
                failures += "duplicate otids: ${duplicates.joinToString()}"
            }
        }
        if (assertSeqMonotonic) {
            val byRun = timeline.events
                .filterIsInstance<TimelineEvent.Confirmed>()
                .filter { it.runId != null && it.seqId != null }
                .groupBy { it.runId.orEmpty() }
            byRun.forEach { (runId, events) ->
                val seqs = events.mapNotNull { it.seqId }
                if (seqs.zipWithNext().any { (a, b) -> b < a }) {
                    failures += "non-monotonic seq ids for run $runId: ${seqs.joinToString()}"
                }
            }
        }
        TimelineAssertionReport(
            conversationId = conversationId,
            eventCount = timeline.events.size,
            failures = failures,
        )
    }

    override suspend fun appendExternalTransportLocal(
        conversationId: String,
        content: String,
        otid: String,
        attachments: List<MessageContentPart.Image>,
    ): String = mutex.withLock {
        val timeline = timelineLocked(conversationId)
        timelines[conversationId] = timeline.append(
            TimelineEvent.Local(
                position = timeline.nextLocalPosition(),
                otid = otid,
                content = content,
                role = Role.USER,
                sentAt = Instant.now(),
                deliveryState = DeliveryState.SENDING,
                attachments = attachments,
                source = MessageSource.LETTA_SERVER,
            )
        )
        otid
    }

    override suspend fun ingestExternalTransportMessage(
        conversationId: String,
        message: LettaMessage,
    ) {
        ingest(conversationId, message)
    }

    override suspend fun markExternalTransportLocalSent(conversationId: String, otid: String) {
        mutex.withLock {
            timelines[conversationId] = timelineLocked(conversationId).markSent(otid)
        }
    }

    override suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String) {
        mutex.withLock {
            timelines[conversationId] = timelineLocked(conversationId).markFailed(otid)
        }
    }

    override suspend fun reconcileExternalTransportSend(
        conversationId: String,
        agentId: String,
        externalConversationId: String,
        otid: String,
    ) {
        markExternalTransportLocalSent(conversationId, otid)
    }

    override suspend fun clearExternalTransportActive(conversationId: String) = Unit

    private fun timelineLocked(conversationId: String): Timeline =
        timelines.getOrPut(conversationId) { Timeline(conversationId = conversationId) }
}

data class TimelineAssertionReport(
    val conversationId: String,
    val eventCount: Int,
    val failures: List<String>,
) {
    val passed: Boolean get() = failures.isEmpty()
}

private fun Timeline.toDumpJson(): JsonObject = buildJsonObject {
    put("conversationId", conversationId)
    put("eventCount", events.size)
    put("liveCursor", liveCursor)
    put("backfillCursor", backfillCursor)
    put("events", buildJsonArray {
        events.forEachIndexed { index, event -> add(event.toDumpJson(index)) }
    })
}

private fun TimelineEvent.toDumpJson(index: Int): JsonObject = when (this) {
    is TimelineEvent.Local -> buildJsonObject {
        putCommon(index, this@toDumpJson)
        put("kind", "Local")
        put("role", role.name)
        put("deliveryState", deliveryState.name)
        put("timestamp", sentAt.toString())
        put("messageType", messageType.name)
        put("isPending", deliveryState == DeliveryState.SENDING)
        put("attachments", attachments.toDumpJson())
    }
    is TimelineEvent.Confirmed -> buildJsonObject {
        putCommon(index, this@toDumpJson)
        put("kind", "Confirmed")
        put("serverId", serverId)
        put("messageType", messageType.name)
        put("timestamp", date.toString())
        put("runId", runId)
        put("stepId", stepId)
        put("seqId", seqId)
        put("toolCallCount", toolCalls.size)
        put("approvalRequestId", approvalRequestId)
        put("approvalDecided", approvalDecided)
        put("attachments", attachments.toDumpJson())
    }
}

private fun JsonObjectBuilder.putCommon(index: Int, event: TimelineEvent) {
    put("index", index)
    put("position", event.position)
    put("otid", event.otid)
    put("content", event.content)
    put("contentLength", event.content.length)
    put("source", event.source.name)
    put("uiId", event.uiIdentityOrNull())
}

private fun List<MessageContentPart.Image>.toDumpJson(): JsonArray = buildJsonArray {
    this@toDumpJson.forEach { image ->
        add(buildJsonObject {
            put("mediaType", image.mediaType)
            put("base64Length", image.base64.length)
        })
    }
}

private fun TimelineEvent.uiIdentityOrNull(): String? = when (this) {
    is TimelineEvent.Local -> when (messageType) {
        TimelineMessageType.TOOL_RETURN,
        TimelineMessageType.OTHER,
        TimelineMessageType.SYSTEM -> null
        else -> otid
    }
    is TimelineEvent.Confirmed -> when (messageType) {
        TimelineMessageType.SYSTEM,
        TimelineMessageType.TOOL_RETURN,
        TimelineMessageType.OTHER -> null
        TimelineMessageType.REASONING -> "$serverId:${messageType.name}"
        else -> serverId
    }
}

private fun TimelineEvent.uiSemanticIdentityOrNull(): String? = when (this) {
    is TimelineEvent.Local -> null
    is TimelineEvent.Confirmed -> when (messageType) {
        TimelineMessageType.ASSISTANT,
        TimelineMessageType.REASONING,
        TimelineMessageType.TOOL_CALL,
        TimelineMessageType.ERROR -> listOf(
            messageType.name,
            runId.orEmpty(),
            content.trim(),
        ).joinToString("|")
        TimelineMessageType.USER,
        TimelineMessageType.SYSTEM,
        TimelineMessageType.TOOL_RETURN,
        TimelineMessageType.OTHER -> null
    }
}

private typealias JsonObjectBuilder = kotlinx.serialization.json.JsonObjectBuilder

private val defaultHeadlessTimelineJson = Json {
    prettyPrint = true
    explicitNulls = false
    encodeDefaults = true
}

private val compactJson = Json {
    explicitNulls = false
    encodeDefaults = true
}
