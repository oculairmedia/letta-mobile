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
     * Excludes volatile revisions, written timestamps, UI models, and transient loaders.
     */
    fun computeStoredEnvelopeFingerprint(envelope: StoredTimelineEnvelope): Long =
        StoredEnvelopeFingerprint.compute(envelope)

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

private object StoredEnvelopeFingerprint {
    private const val OFFSET_BASIS = -3750763034362895579L
    private const val PRIME = 1099511628211L
    private const val NULL_STRING = -1L

    fun compute(envelope: StoredTimelineEnvelope): Long = Hasher().apply {
        mix(envelope.schemaVersion.toLong())
        mixString(envelope.scope.backendId)
        mixString(envelope.scope.agentId)
        mixString(envelope.scope.conversationId)
        mixString(envelope.liveCursor)
        mixString(envelope.backfillCursor)
        mix(envelope.releasedOlderCount.toLong())
        mix(envelope.events.size.toLong())
        envelope.events.forEach(::mixEvent)
    }.value

    private class Hasher {
        var value: Long = OFFSET_BASIS
            private set

        fun mix(valueToAdd: Long) {
            value = (value xor valueToAdd) * PRIME
        }

        fun mixString(valueToAdd: String?) {
            if (valueToAdd == null) {
                mix(NULL_STRING)
                return
            }
            mix(valueToAdd.length.toLong())
            valueToAdd.forEach { character -> mix(character.code.toLong()) }
        }

        fun mixEvent(event: StoredTimelineEvent) {
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
            mixBoolean(event.approvalDecided)
            mixString(event.approvalRequestId)
            mixString(event.approvalDecision)
            mixString(event.toolReturnContent)
            mixBoolean(event.toolReturnIsError)
            mixToolCalls(event.toolCalls)
            mixStringMap(event.toolReturnContentByCallId)
            mixBooleanMap(event.toolReturnIsErrorByCallId)
            mixTruncationMap(event.toolReturnTruncationByCallId)
            mixAttachments(event.attachments)
        }

        private fun mixBoolean(valueToAdd: Boolean) = mix(if (valueToAdd) 1L else 0L)

        private fun mixToolCalls(toolCalls: List<StoredToolCall>) {
            mix(toolCalls.size.toLong())
            toolCalls.forEach { toolCall ->
                mixString(toolCall.id)
                mixString(toolCall.name)
                mixString(toolCall.arguments)
            }
        }

        private fun mixStringMap(values: Map<String, String>) {
            mix(values.size.toLong())
            values.entries.sortedBy { it.key }.forEach { (key, mapValue) ->
                mixString(key)
                mixString(mapValue)
            }
        }

        private fun mixBooleanMap(values: Map<String, Boolean>) {
            mix(values.size.toLong())
            values.entries.sortedBy { it.key }.forEach { (key, mapValue) ->
                mixString(key)
                mixBoolean(mapValue)
            }
        }

        private fun mixTruncationMap(values: Map<String, StoredToolReturnTruncation>) {
            mix(values.size.toLong())
            values.entries.sortedBy { it.key }.forEach { (key, truncation) ->
                mixString(key)
                mixString(truncation.messageId)
                mix(truncation.byteLen)
            }
        }

        private fun mixAttachments(attachments: List<StoredImageAttachmentPointer>) {
            mix(attachments.size.toLong())
            attachments.forEach { attachment ->
                mixString(attachment.mediaType)
                mix(attachment.byteSize)
                mixString(attachment.uriOrUrl)
                mixString(attachment.thumbnailBase64)
            }
        }
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
