package com.letta.mobile.data.feed.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** The position of one delta: the host start it belongs to and its sequence number in the conversation. */
@Serializable
data class FeedCursor(
    @SerialName(ConversationFeedWire.EPOCH) val epoch: Long,
    @SerialName(ConversationFeedWire.SEQ) val seq: Long,
)

/**
 * One journal entry. Exactly one of [payload] and [pointer] is set: [pointer] replaces a payload bigger than
 * [ConversationFeedWire.MAX_INLINE_PAYLOAD_BYTES]. [epoch] is the host start that wrote the entry; to resume,
 * use the page's cursor, never an entry's epoch. [seq] only grows: a superseded cumulative snapshot leaves a hole,
 * and a hole is never a loss signal (the page's mode is).
 */
@Serializable
data class FeedDelta(
    @SerialName(ConversationFeedWire.EPOCH) val epoch: Long,
    @SerialName(ConversationFeedWire.SEQ) val seq: Long,
    @SerialName("kind") val kind: String,
    @SerialName("turn") val turn: String,
    @SerialName("payload") val payload: JsonObject? = null,
    @SerialName("pointer") val pointer: JsonObject? = null,
)

/**
 * The answer of `feed.subscribe`. [mode] `replay` continues the caller's cursor; `snapshot` starts the retained
 * journal over and says why in [resyncReason]. Either way the caller applies [entries] in order, then continues from
 * [cursor] while [hasMore]. [baseSeq] is the compaction horizon (entries at or below it may be gone), [headSeq] the
 * newest seq issued. A v1 host pushes nothing: ask again to see later deltas.
 */
@Serializable
data class FeedPage(
    @SerialName("mode") val mode: String,
    @SerialName("resync_reason") val resyncReason: String? = null,
    @SerialName("cursor") val cursor: FeedCursor,
    @SerialName("base_seq") val baseSeq: Long,
    @SerialName("head_seq") val headSeq: Long,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("entries") val entries: List<FeedDelta>,
)
