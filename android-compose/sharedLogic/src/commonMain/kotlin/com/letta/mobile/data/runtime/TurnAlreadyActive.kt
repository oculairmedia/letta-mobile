package com.letta.mobile.data.runtime

/**
 * True when [message] is a busy rejection for an already-running App Server turn.
 *
 * These must not be fan-out as conversation-wide `error_message` terminals: the
 * live turn's viewers would map them to TurnDone(failed) and kill the UI while
 * the owning run is still in progress.
 */
fun isTurnAlreadyActiveMessage(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val normalized = message.lowercase()
    return "already active" in normalized ||
        "already busy" in normalized ||
        "turn engine is already busy" in normalized
}
