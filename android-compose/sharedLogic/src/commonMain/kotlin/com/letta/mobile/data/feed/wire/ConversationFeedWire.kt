package com.letta.mobile.data.feed.wire

/**
 * Names, limits and codes of the conversation feed v1 contract, shared by host and client. The host
 * keeps one durable, ordered journal of turn events per conversation, stamped with an epoch (one per
 * host start) and a sequence number (monotonic per conversation); a client resumes it from the last
 * `{epoch, seq}` it applied. A host without [CAPABILITY] has no feed.
 */
object ConversationFeedWire {
    /** Advertised by a host that serves [SUBSCRIBE]. */
    const val CAPABILITY: String = "conversation_feed_v1"

    /** Reads the journal of one conversation from a cursor: the catch-up half of subscribing. */
    const val SUBSCRIBE: String = "feed.subscribe"

    /** Every v1 method. */
    val METHODS: Set<String> = setOf(SUBSCRIBE)

    /**
     * Advertised, besides [CAPABILITY], by a host that also pushes [FRAME_TYPE] frames. A client that renders the
     * feed needs both: a host with [CAPABILITY] alone can be read from a cursor but never tells a viewer that
     * something was appended.
     */
    const val PUSH_CAPABILITY: String = "conversation_feed_push_v1"

    /** Frame type of the live push: the entries appended to one conversation's journal since the host's last push to it. */
    const val FRAME_TYPE: String = "feed_delta"

    /** Frame field: the conversation as the journal keys it (the bare `default` for an agent's default conversation). */
    const val CONVERSATION: String = "conversation"

    /** Frame field: the seq of the newest entry the frame carries, or the journal's head when it carries none (a resync marker). */
    const val FRAME_SEQ: String = "seq"

    /** Frame field: the seq the frame continues from; a viewer whose cursor is below it missed entries and resyncs. */
    const val AFTER_SEQ: String = "after_seq"

    /** Frame field: the pushed entries, oldest first; empty in a resync marker. */
    const val ENTRIES: String = "entries"

    /** Param: the conversation to read. */
    const val CONVERSATION_ID: String = "conversation_id"

    /** Param: the agent owning the conversation; required for the bare default conversation and for agent-scoped peers. */
    const val AGENT_ID: String = "agent_id"

    /** Param: the `{epoch, seq}` of the last delta the caller applied, or the previous page's `cursor`; absent asks for a snapshot. */
    const val FROM: String = "from"

    /** Param: most deltas in one page. */
    const val LIMIT: String = "limit"

    /** Field of a cursor and of every delta: the host start the sequence belongs to. */
    const val EPOCH: String = "epoch"

    /** Field of a cursor and of every delta: the position in the conversation's journal. */
    const val SEQ: String = "seq"

    /** A delta opening a turn. */
    const val KIND_TURN_OPEN: String = "turn_open"

    /** A delta carrying one App Server stream delta body, verbatim. */
    const val KIND_STREAM_DELTA: String = "stream_delta"

    /**
     * A delta recording that the host stopped observing the turn. Its `terminal_observed` payload field says
     * whether a real terminal went through the feed earlier; `false` is a fact about the host, never a terminal.
     */
    const val KIND_TURN_CLOSED: String = "turn_closed"

    /** The page continues the caller's cursor with the deltas after it. */
    const val MODE_REPLAY: String = "replay"

    /** The page starts the retained journal over: the caller replaces what it holds, then continues in [MODE_REPLAY] from the page's cursor. */
    const val MODE_SNAPSHOT: String = "snapshot"

    /** The caller named no cursor. */
    const val RESYNC_NO_CURSOR: String = "no_cursor"

    /** The caller's epoch is not the host's: the host restarted. */
    const val RESYNC_EPOCH_CHANGED: String = "epoch_changed"

    /** The caller's cursor is older than the horizon retention has compacted past. */
    const val RESYNC_BEHIND_HORIZON: String = "behind_horizon"

    /** The caller's cursor is beyond the host's head: the host lost journal data. */
    const val RESYNC_AHEAD_OF_HEAD: String = "ahead_of_head"

    /** The refusal of a malformed request. */
    const val INVALID_REQUEST: String = "invalid_request"

    /** Pointer field: size of the omitted payload in bytes. */
    const val POINTER_BYTE_LEN: String = "byte_len"

    /** Pointer field: the `message_type` of the omitted delta. */
    const val POINTER_MESSAGE_TYPE: String = "message_type"

    /** Pointer field: the row id the omitted delta belongs to, when it names one. */
    const val POINTER_MESSAGE_ID: String = "message_id"

    /** Largest payload a delta carries inline; a bigger one is replaced by a pointer. Well under the 1 MiB frame cap. */
    const val MAX_INLINE_PAYLOAD_BYTES: Int = 256 * 1024

    /** Most payload bytes one page carries; one inline payload always fits. */
    const val MAX_PAGE_BYTES: Int = 768 * 1024

    /** Most deltas one page carries when the caller names no limit. */
    const val DEFAULT_LIMIT: Int = 200

    /** Most deltas one page carries whatever the caller asks. */
    const val MAX_LIMIT: Int = 500
}
