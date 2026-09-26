package com.letta.mobile.data.timeline

/**
 * The stored row an incoming event merges onto, read once: where it lives, its exact stored bytes
 * and the event those bytes decode to. A row with no body is a fresh write under [identity].
 *
 * [vacated] marks a row whose body belonged to a different part of the same source message
 * (letta-mobile-iyj4s). The incoming event replaces that body rather than folding into it, and the
 * terminal ownership evidence recorded for the misfiled reply no longer describes the row.
 * [renamed] marks an event whose alias was refused, so it drops the shared otid that named the
 * other part.
 */
internal class CanonicalResidentRow(
    val identity: TimelineMessageId,
    val key: TimelinePageKey,
    val bytes: ByteArray?,
    val event: TimelineEvent.Confirmed?,
    val vacated: Boolean = false,
    val renamed: Boolean = false,
) {
    fun vacate() = CanonicalResidentRow(identity, key, null, null, vacated = true, renamed = renamed)

    /** The event's alias was refused and it is stored under its own server id instead. */
    fun renamed() = CanonicalResidentRow(identity, key, bytes, event, vacated, renamed = true)

    /**
     * True when this row holds the other prose part of [incoming]'s source message: a reasoning
     * part where a reply arrives, or a reply where a reasoning part arrives.
     *
     * One LocalBackend assistant message fans out to a reasoning frame and a reply frame. Older
     * wire projections gave both the source message's otid, so an alias keyed by that otid (or by
     * a reply's server id recorded while the two were collapsed) folded the second part onto the
     * first: the settled ledger kept one part and the live turn the other. A reasoning frame and
     * a reply are never the same message, so their bodies must never share a row.
     */
    fun holdsOtherPartOf(incoming: TimelineEvent.Confirmed): Boolean {
        val resident = event?.messageType?.isReasoningProse() ?: return false
        val arriving = incoming.messageType.isReasoningProse() ?: return false
        return resident != arriving
    }
}

/** True for reasoning, false for a reply, null for every kind that is not prose. */
private fun TimelineMessageType.isReasoningProse(): Boolean? = when (this) {
    TimelineMessageType.REASONING -> true
    TimelineMessageType.ASSISTANT -> false
    else -> null
}
