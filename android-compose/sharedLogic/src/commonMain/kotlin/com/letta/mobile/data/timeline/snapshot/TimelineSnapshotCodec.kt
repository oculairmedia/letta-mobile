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

    /**
     * Computes a deterministic 64-bit FNV-1a fingerprint of canonical timeline content.
     * Excludes volatile written timestamps, UI models, and transient loaders.
     */
    fun computeStoredEnvelopeFingerprint(envelope: StoredTimelineEnvelope): Long {
        var hash = -3750763034362895579L
        fun mix(value: Long) {
            hash = (hash xor value) * 1099511628211L
        }
        fun mixString(s: String?) {
            if (s == null) {
                mix(0L)
                return
            }
            for (i in 0 until s.length) {
                hash = (hash xor s[i].code.toLong()) * 1099511628211L
            }
        }
        mix(envelope.schemaVersion.toLong())
        mixString(envelope.scope.backendId)
        mixString(envelope.scope.agentId)
        mixString(envelope.scope.conversationId)
        mixString(envelope.liveCursor)
        mixString(envelope.backfillCursor)
        mix(envelope.releasedOlderCount.toLong())
        mix(envelope.events.size.toLong())
        for (event in envelope.events) {
            mix(event.position.toBits())
            mixString(event.otid)
            mixString(event.content)
            mixString(event.serverId)
            mixString(event.messageType)
            mixString(event.dateIso)
            mixString(event.runId)
            mixString(event.stepId)
            mixString(event.agentId)
            mix(event.seqId?.toLong() ?: -1L)
            mix(if (event.approvalDecided) 1L else 0L)
            mixString(event.approvalRequestId)
            mixString(event.approvalDecision)
            mixString(event.toolReturnContent)
            mix(if (event.toolReturnIsError) 1L else 0L)
            mix(event.toolCalls.size.toLong())
            for (tc in event.toolCalls) {
                mixString(tc.id)
                mixString(tc.name)
                mixString(tc.arguments)
            }
            mix(event.toolReturnContentByCallId.size.toLong())
            for ((k, v) in event.toolReturnContentByCallId) {
                mixString(k)
                mixString(v)
            }
            mix(event.toolReturnIsErrorByCallId.size.toLong())
            for ((k, v) in event.toolReturnIsErrorByCallId) {
                mixString(k)
                mix(if (v) 1L else 0L)
            }
            mix(event.toolReturnTruncationByCallId.size.toLong())
            for ((k, v) in event.toolReturnTruncationByCallId) {
                mixString(k)
                mixString(v.messageId)
                mix(v.byteLen)
            }
            mix(event.attachments.size.toLong())
            for (att in event.attachments) {
                mixString(att.mediaType)
                mix(att.byteSize)
                mixString(att.uriOrUrl)
                mixString(att.thumbnailBase64)
            }
        }
        return hash
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
        attachments = attachments.mapNotNull {
            val rawBase64 = it.base64
            val estimatedBytes = if (rawBase64.isNotEmpty()) {
                val padding = rawBase64.takeLast(2).count { ch -> ch == '=' }
                ((rawBase64.length * 3L) / 4L) - padding
            } else {
                -1L
            }
            val thumbnail = if (rawBase64.isNotEmpty() && rawBase64.length <= 16384) {
                rawBase64
            } else {
                null
            }
            if (rawBase64.isEmpty() && thumbnail == null) {
                null
            } else {
                StoredImageAttachmentPointer(
                    mediaType = it.mediaType,
                    byteSize = estimatedBytes,
                    thumbnailBase64 = thumbnail,
                )
            }
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
        attachments = attachments.mapNotNull { pointer ->
            pointer.thumbnailBase64?.let { thumb ->
                MessageContentPart.Image(
                    base64 = thumb,
                    mediaType = pointer.mediaType,
                )
            }
        }.toPersistentList(),
        source = MessageSource.LETTA_SERVER,
    )
}
