package com.letta.mobile.data.context

import com.letta.mobile.data.model.ContextWindowOverview

/** What a client knows about the focused conversation's context window. */
data class ContextWindowUsageState(
    val usage: ContextWindowUsage? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * The identity a reading belongs to, plus whether the conversation is currently
 * quiet enough to take one.
 */
data class ContextWindowUsageKey(
    val agentId: String?,
    val conversationId: String?,
    /** False while a turn is in flight. */
    val settled: Boolean,
) {
    /** Two keys read the same conversation when their identity matches. */
    fun sameIdentityAs(other: ContextWindowUsageKey?): Boolean =
        other != null && agentId == other.agentId && conversationId == other.conversationId
}

/**
 * State transitions for a context-window reading, kept platform-neutral so
 * every client reloads, retains, and fails over identically — hosts own only
 * the effect that calls the repository and the composable that draws the
 * result.
 *
 * The policy that matters:
 *
 *  - a reading is taken only once the turn settles. The server recounts after
 *    the turn is written back, so a mid-turn reading shows a total that then
 *    jumps;
 *  - switching conversation clears immediately rather than waiting for the new
 *    reading — otherwise a conversation that opens mid-turn keeps showing the
 *    previous one's usage, attributed to the wrong agent;
 *  - a failed read keeps the last good reading and reports the miss alongside
 *    it, since a stale number with an error beats no number at all.
 */
object ContextWindowUsagePolicy {
    /** Whether [key] can produce a reading right now. */
    fun readable(key: ContextWindowUsageKey): Boolean =
        !key.agentId.isNullOrBlank() && key.settled

    /** Nothing to show: no agent in focus, or the focus just changed. */
    fun cleared(): ContextWindowUsageState = ContextWindowUsageState()

    /**
     * State to hold while a read for [key] is in flight, given [current] and the
     * [previous] key it was read for: a reading for another conversation is
     * dropped, one for this conversation stays put under the spinner.
     */
    fun reading(
        current: ContextWindowUsageState,
        key: ContextWindowUsageKey,
        previous: ContextWindowUsageKey?,
    ): ContextWindowUsageState =
        if (key.sameIdentityAs(previous)) {
            current.copy(loading = true, error = null)
        } else {
            ContextWindowUsageState(loading = true)
        }

    fun read(overview: ContextWindowOverview): ContextWindowUsageState =
        ContextWindowUsageState(usage = ContextWindowUsage.from(overview))

    fun failed(current: ContextWindowUsageState, message: String?): ContextWindowUsageState =
        current.copy(
            loading = false,
            error = message?.takeIf { it.isNotBlank() } ?: "Context window unavailable.",
        )
}
