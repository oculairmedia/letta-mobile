package com.letta.mobile.data.timeline

/**
 * Ids of the failure-notice rows a client writes into the timeline when a turn dies (the copy is
 * `TurnFailureNotices`). A later clean terminal retires every row
 * carrying one, so all writers mint them here.
 */
object TurnFailureNoticeIds {
    private const val PREFIX: String = "turn-failed-"

    /** The notice row id for [key] (a turn and/or run identity). */
    fun of(key: String): String = PREFIX + key

    /** Whether [id] names a client-written notice rather than a server-emitted error. */
    fun isNotice(id: String?): Boolean = id?.startsWith(PREFIX) == true
}
