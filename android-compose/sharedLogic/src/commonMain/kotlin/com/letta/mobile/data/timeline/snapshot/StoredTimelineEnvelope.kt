package com.letta.mobile.data.timeline.snapshot

import kotlinx.serialization.Serializable

/**
 * Versioned storage envelope for confirmed timeline snapshots.
 *
 * Persists domain timeline facts and lightweight attachment pointers,
 * decoupled from UI projection models, Compose state, and transient loaders.
 */
@Serializable
data class StoredTimelineEnvelope(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val scope: TimelineScope,
    val revision: Long,
    val liveCursor: String? = null,
    val backfillCursor: String? = null,
    val releasedOlderCount: Int = 0,
    val events: List<StoredTimelineEvent> = emptyList(),
    val writtenAtMillis: Long = 0L,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * Persisted representation of a confirmed timeline event.
 */
@Serializable
data class StoredTimelineEvent(
    val position: Double,
    val otid: String,
    val content: String = "",
    val serverId: String,
    val messageType: String,
    val dateIso: String,
    val runId: String? = null,
    val stepId: String? = null,
    val agentId: String? = null,
    val seqId: Int? = null,
    val toolCalls: List<StoredToolCall> = emptyList(),
    val approvalRequestId: String? = null,
    val approvalDecided: Boolean = false,
    val approvalDecision: String? = null,
    val toolReturnContent: String? = null,
    val toolReturnIsError: Boolean = false,
    val toolReturnContentByCallId: Map<String, String> = emptyMap(),
    val toolReturnIsErrorByCallId: Map<String, Boolean> = emptyMap(),
    val toolReturnTruncationByCallId: Map<String, StoredToolReturnTruncation> = emptyMap(),
    val attachments: List<StoredImageAttachmentPointer> = emptyList(),
)

/**
 * Persisted tool call metadata.
 */
@Serializable
data class StoredToolCall(
    val id: String,
    val name: String,
    val arguments: String = "",
)

/**
 * Pointer to a truncated tool return whose full body can be fetched on demand.
 */
@Serializable
data class StoredToolReturnTruncation(
    val messageId: String,
    val byteLen: Long = -1L,
)

/**
 * Lightweight pointer/metadata for an image attachment.
 * Heavy image bytes are never duplicated into the confirmed timeline snapshot.
 */
@Serializable
data class StoredImageAttachmentPointer(
    val mediaType: String,
    val byteSize: Long = -1L,
    val uriOrUrl: String? = null,
    val thumbnailBase64: String? = null,
)
