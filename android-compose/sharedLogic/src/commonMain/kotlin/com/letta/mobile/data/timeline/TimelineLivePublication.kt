package com.letta.mobile.data.timeline

/** Immutable bounded projection supplied by the shared live reducer, separate from settled Paging. */
data class TimelineLiveBlock(
    val records: List<TimelineRemoteRecord>,
    val terminal: Boolean,
)

data class TimelineSettledRecord(
    val key: TimelinePageKey,
    val contentType: String,
    val body: ByteArray,
    val revision: Long,
)

data class TimelineLiveFence(val selection: TimelineEngineSelection, val requestId: TimelineRequestId)

data class TimelineLivePublication(
    val fence: TimelineLiveFence,
    val block: TimelineLiveBlock,
    val settlementRevision: Long? = null,
)
