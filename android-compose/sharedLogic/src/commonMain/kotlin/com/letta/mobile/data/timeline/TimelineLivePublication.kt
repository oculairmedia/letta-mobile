package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEvent
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

/** Project only complete canonical bodies; partial JSON must never become a missing message. */
fun TimelineSettledRecord.toRenderItem(ownAgentId: String? = null): com.letta.mobile.data.chat.projection.ChatRenderItem? {
    require(!isPreview) { "Resolve bounded body before projection" }
    // Opaque protocol records remain durable but have no renderer in this client version.
    if (contentType != "application/vnd.letta.timeline-event+json;version=1") return null
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

data class TimelineLiveFence(val selection: TimelineEngineSelection, val requestId: TimelineRequestId)

data class TimelineLivePublication(
    val fence: TimelineLiveFence,
    val block: TimelineLiveBlock,
    val settlementRevision: Long? = null,
    val settlementIdentities: Map<TimelineMessageId, TimelineMessageId> = emptyMap(),
) {
    /** Derive overlay from the same resident snapshot being rendered, before acknowledging settlement. */
    fun unpresentedEvents(presented: Map<TimelineMessageId, Long>): List<TimelineEvent.Confirmed> =
        block.events.filter { event ->
            val revision = settlementRevision
            val incoming = TimelineMessageId(event.serverId)
            revision == null || (presented[settlementIdentities[incoming] ?: incoming] ?: -1) < revision
        }
}
