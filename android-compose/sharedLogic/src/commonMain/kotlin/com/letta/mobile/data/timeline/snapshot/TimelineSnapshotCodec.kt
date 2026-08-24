package com.letta.mobile.data.timeline.snapshot

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.timeline.ApprovalDecision
import com.letta.mobile.data.timeline.MessageSource
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.ToolReturnTruncation
import com.letta.mobile.data.timeline.parseTimelineInstantOrNull
import com.letta.mobile.data.timeline.timelineCurrentTimeMillis
import com.letta.mobile.data.timeline.timelineNow
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.json.Json

/**
 * Codec for serializing and deserializing confirmed timeline snapshots.
 *
 * Implements schema versioning, unknown-enum fallbacks, corruption tolerance,
 * and mapping between in-memory [Timeline] domain models and versioned [StoredTimelineEnvelope]s.
 */
object TimelineSnapshotCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(envelope: StoredTimelineEnvelope): String =
        json.encodeToString(StoredTimelineEnvelope.serializer(), envelope)

    fun decode(payload: String): StoredTimelineEnvelope? {
        if (payload.isBlank()) return null
        return runCatching {
            val envelope = json.decodeFromString(StoredTimelineEnvelope.serializer(), payload)
            migrateIfNeeded(envelope) ?: return null
        }.onFailure { error ->
            Telemetry.error(
                "TimelineSnapshotCodec", "decode.corruptPayload", error,
                "payloadLength" to payload.length,
            )
        }.getOrNull()
    }

    private fun migrateIfNeeded(envelope: StoredTimelineEnvelope): StoredTimelineEnvelope? {
        if (envelope.schemaVersion > StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION) {
            Telemetry.event(
                "TimelineSnapshotCodec", "decode.unsupportedFutureSchema",
                "schemaVersion" to envelope.schemaVersion,
                "targetVersion" to StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION,
                level = Telemetry.Level.WARN,
            )
            return null
        }
        return envelope
    }

    fun timelineToStoredEnvelope(
        timeline: Timeline,
        scope: TimelineScope,
        revision: Long,
        writtenAtMillis: Long = timelineCurrentTimeMillis(),
    ): StoredTimelineEnvelope {
        val storedEvents = timeline.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .map { it.toStoredTimelineEvent() }

        return StoredTimelineEnvelope(
            schemaVersion = StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION,
            scope = scope,
            revision = revision,
            liveCursor = timeline.liveCursor,
            backfillCursor = timeline.backfillCursor,
            releasedOlderCount = timeline.releasedOlderCount,
            events = storedEvents,
            writtenAtMillis = writtenAtMillis,
        )
    }

    fun storedEnvelopeToTimeline(envelope: StoredTimelineEnvelope): Timeline {
        val confirmedEvents = envelope.events.map { it.toConfirmedTimelineEvent() }
        return Timeline(
            conversationId = envelope.scope.conversationId,
            events = confirmedEvents.toPersistentList(),
            liveCursor = envelope.liveCursor,
            backfillCursor = envelope.backfillCursor,
            releasedOlderCount = envelope.releasedOlderCount,
        )
    }
}

fun TimelineEvent.Confirmed.toStoredTimelineEvent(): StoredTimelineEvent =
    StoredTimelineEvent(
        position = position,
        otid = otid,
        content = content,
        serverId = serverId,
        messageType = messageType.name,
        dateIso = date.toString(),
        runId = runId,
        stepId = stepId,
        agentId = agentId,
        seqId = seqId,
        toolCalls = toolCalls.map { StoredToolCall(id = it.effectiveId, name = it.name.orEmpty(), arguments = it.arguments.orEmpty()) },
        approvalRequestId = approvalRequestId,
        approvalDecided = approvalDecided,
        approvalDecision = approvalDecision?.name,
        toolReturnContent = toolReturnContent,
        toolReturnIsError = toolReturnIsError,
        toolReturnContentByCallId = toolReturnContentByCallId,
        toolReturnIsErrorByCallId = toolReturnIsErrorByCallId,
        toolReturnTruncationByCallId = toolReturnTruncationByCallId.mapValues { (_, v) ->
            StoredToolReturnTruncation(messageId = v.messageId, byteLen = v.byteLen)
        },
        attachments = attachments.map {
            StoredImageAttachmentPointer(
                mediaType = it.mediaType,
                byteSize = it.base64.length.toLong(),
            )
        },
    )

fun StoredTimelineEvent.toConfirmedTimelineEvent(): TimelineEvent.Confirmed {
    val resolvedType = when (messageType.lowercase()) {
        "user", "user_message" -> TimelineMessageType.USER
        "assistant", "assistant_message" -> TimelineMessageType.ASSISTANT
        "reasoning", "reasoning_message" -> TimelineMessageType.REASONING
        "tool_call", "tool_call_message" -> TimelineMessageType.TOOL_CALL
        "tool_return", "tool_return_message" -> TimelineMessageType.TOOL_RETURN
        "system", "system_message" -> TimelineMessageType.SYSTEM
        "error", "error_message" -> TimelineMessageType.ERROR
        else -> TimelineMessageType.entries.firstOrNull { it.name.equals(messageType, ignoreCase = true) } ?: TimelineMessageType.OTHER
    }

    val resolvedDecision = approvalDecision?.let { raw ->
        ApprovalDecision.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }

    val resolvedDate = parseTimelineInstantOrNull(dateIso) ?: timelineNow()

    return TimelineEvent.Confirmed(
        position = position,
        otid = otid,
        content = content,
        serverId = serverId,
        messageType = resolvedType,
        date = resolvedDate,
        runId = runId,
        stepId = stepId,
        agentId = agentId,
        seqId = seqId,
        toolCalls = toolCalls.map { ToolCall(id = it.id, name = it.name, arguments = it.arguments) }.toPersistentList(),
        approvalRequestId = approvalRequestId,
        approvalDecided = approvalDecided,
        approvalDecision = resolvedDecision,
        toolReturnContent = toolReturnContent,
        toolReturnIsError = toolReturnIsError,
        toolReturnContentByCallId = toolReturnContentByCallId.toPersistentMap(),
        toolReturnIsErrorByCallId = toolReturnIsErrorByCallId.toPersistentMap(),
        toolReturnTruncationByCallId = toolReturnTruncationByCallId.mapValues { (_, v) ->
            ToolReturnTruncation(messageId = v.messageId, byteLen = v.byteLen)
        }.toPersistentMap(),
        attachments = attachments.map {
            MessageContentPart.Image(
                base64 = it.thumbnailBase64.orEmpty(),
                mediaType = it.mediaType,
            )
        }.toPersistentList(),
        source = MessageSource.LETTA_SERVER,
    )
}
