package com.letta.mobile.data.timeline

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
