package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEvent
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.toPersistentList

/** Use the existing semantic mapper for optimistic attachments and delivery flags on every host. */
fun CanonicalPendingLocalStore.Record.toRenderItem(ownAgentId: String? = null): ChatRenderItem.Single {
    val event = TimelineEvent.Local(
        position = 0.0, otid = otid, content = content, sentAt = parseTimelineInstant(sentAt),
        deliveryState = when (delivery) {
            CanonicalPendingLocalStore.Delivery.Sending -> DeliveryState.SENDING
            CanonicalPendingLocalStore.Delivery.Sent -> DeliveryState.SENT
            CanonicalPendingLocalStore.Delivery.Failed -> DeliveryState.FAILED
        },
        attachments = attachments.toPersistentList(),
    )
    val message = requireNotNull(com.letta.mobile.data.chat.projection.timelineEventToUiMessage(event, ownAgentId))
    return ChatRenderItem.Single(message, com.letta.mobile.ui.common.GroupPosition.None)
}

/** Immutable bounded projection supplied by the shared live reducer, separate from settled Paging. */
data class TimelineLiveBlock(
    val records: List<TimelineRemoteRecord>,
    val terminal: Boolean,
    val events: List<TimelineEvent.Confirmed> = emptyList(),
)

data class TimelineSettledRecord(
    val key: TimelinePageKey,
    val contentType: String,
    val body: ByteArray,
    val revision: Long,
    val pointer: TimelineBodyPointer? = null,
) {
    val isPreview: Boolean get() = pointer?.encodedBytes?.let { it > body.size } ?: false
}

/** Deferred content is explicit, not a null/missing message or a truncated serialized event. */
sealed interface TimelineSettledProjection {
    data class Rendered(val item: ChatRenderItem) : TimelineSettledProjection
    data class Deferred(val reference: TimelineBodyReference) : TimelineSettledProjection
    data object NotRenderable : TimelineSettledProjection
}

fun TimelineSettledRecord.projectBounded(
    scope: TimelineScope,
    ownAgentId: String? = null,
): TimelineSettledProjection {
    require(pointer == null || body.size.toLong() <= pointer.encodedBytes) { "Body exceeds pointer length" }
    if (isPreview || body.size.toLong() > TimelineBoundedReader.MAX_PAGE_BODY_BYTES) {
        return TimelineSettledProjection.Deferred(TimelineBodyReference(
            scope, key, requireNotNull(pointer) { "Deferred body requires a pointer" }, contentType, revision,
        ))
    }
    return toRenderItem(ownAgentId)?.let { TimelineSettledProjection.Rendered(it) }
        ?: TimelineSettledProjection.NotRenderable
}

/**
 * What a settled record can become on screen, decided before any suppression lookup so the
 * decision is testable without a session behind it.
 */
sealed interface TimelineSettledPresentation {
    /**
     * Durable, and absent from the conversation. Either the body is opaque to this client - a
     * compaction marker, or anything a newer protocol writes - or it is an event type the chat
     * surface deliberately does not show, such as a system seed or a standalone tool return.
     * Neither has a renderer or a pager, so the only thing presenting one could produce is a
     * placeholder where the user expects their own history (letta-mobile-r5v5t).
     */
    data object Drop : TimelineSettledPresentation

    /** Stored whole but held back from inline decoding; the card reads it a page at a time. */
    data object Defer : TimelineSettledPresentation

    /** Decoded and renderable. Carries the event so the caller need not decode it twice. */
    data class Render(val event: TimelineEvent.Confirmed) : TimelineSettledPresentation
}

/** Malformed bodies still throw: a body that cannot be read is a fault, not an empty conversation. */
fun TimelineSettledRecord.presentation(ownAgentId: String? = null): TimelineSettledPresentation = when {
    contentType != TIMELINE_EVENT_CONTENT_TYPE -> TimelineSettledPresentation.Drop
    isPreview -> TimelineSettledPresentation.Defer
    else -> {
        val event = com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec.json.decodeFromString(
            com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent.serializer(),
            body.decodeToString(throwOnInvalidSequence = true),
        ).toConfirmedTimelineEvent()
        if (com.letta.mobile.data.chat.projection.timelineEventToUiMessage(event, ownAgentId) == null) {
            TimelineSettledPresentation.Drop
        } else {
            TimelineSettledPresentation.Render(event)
        }
    }
}

/** Project only complete canonical bodies; partial JSON must never become a missing message. */
fun TimelineSettledRecord.toRenderItem(ownAgentId: String? = null): com.letta.mobile.data.chat.projection.ChatRenderItem? {
    require(!isPreview) { "Resolve bounded body before projection" }
    // Opaque protocol records remain durable but have no renderer in this client version.
    if (contentType != TIMELINE_EVENT_CONTENT_TYPE) return null
    val stored = com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec.json.decodeFromString(
        com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent.serializer(),
        body.decodeToString(throwOnInvalidSequence = true),
    )
    val event = stored.toConfirmedTimelineEvent()
    val message = com.letta.mobile.data.chat.projection.timelineEventToUiMessage(event, ownAgentId) ?: return null
    return if (message.runId != null) com.letta.mobile.data.chat.projection.ChatRenderItem.RunBlock(
        message.runId, listOf(message to com.letta.mobile.ui.common.GroupPosition.None),
        stableKey = "segment-${key.identity.value}",
    ) else com.letta.mobile.data.chat.projection.ChatRenderItem.Single(
        message, com.letta.mobile.ui.common.GroupPosition.None, keyOverride = "segment-${key.identity.value}",
    )
}

internal const val STRAND_GUARD_REVISION_DELTA = 1024L

data class TimelineLiveFence(val selection: TimelineEngineSelection, val requestId: TimelineRequestId)

data class TimelineLivePublication(
    val fence: TimelineLiveFence,
    val block: TimelineLiveBlock,
    /** First durable revision able to carry this turn; only the sync writer can reach it. */
    val settlementRevision: Long? = null,
    val aliases: Map<String, TimelineMessageId> = emptyMap(),
) {
    private fun isEventResident(
        event: TimelineEvent.Confirmed,
        presented: Map<TimelineMessageId, Long>,
    ): Boolean {
        if (TimelineMessageId(event.serverId) in presented) return true
        val alias = aliases[event.serverId]
        return alias != null && alias in presented
    }

    /**
     * Drain on turn identity, not on a ledger watermark revision.
     * Settled once all events in the block have a resident row in [presented].
     * Releases via strand guard if the ledger head runs well past settlement revision (+1024L).
     */
    fun isSettled(presented: Map<TimelineMessageId, Long>): Boolean {
        val revision = settlementRevision ?: return false
        // A turn that produced nothing has nothing to wait for; never strand the fence on it.
        if (block.events.isEmpty() && block.records.isEmpty()) return true
        val allEvents = block.events.all { isEventResident(it, presented) }
        val allRecords = block.records.all { it.identity in presented }
        if (allEvents && allRecords) return true

        val maxRevision = presented.values.maxOrNull() ?: return false
        if (maxRevision >= revision + STRAND_GUARD_REVISION_DELTA) {
            Telemetry.event(
                "TimelineLivePublication", "strandGuard.drained",
                "settlementRevision" to revision,
                "headRevision" to maxRevision,
                level = Telemetry.Level.WARN,
            )
            return true
        }
        return false
    }

    /** Overlay stays resident, draining individual events as they become resident in settled rows. */
    fun overlayEvents(presented: Map<TimelineMessageId, Long>): List<TimelineEvent.Confirmed> {
        if (isSettled(presented)) return emptyList()
        return block.events.filterNot { isEventResident(it, presented) }
    }
}
