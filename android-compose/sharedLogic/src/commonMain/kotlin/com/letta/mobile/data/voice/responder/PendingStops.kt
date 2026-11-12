package com.letta.mobile.data.voice.responder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Stops requested for sent messages whose run had not started yet, keyed by the message's
 * identity. A stop is pending from the moment it is requested until the session publishes, in
 * [VoiceChatState.startedRuns], the run that message started; it is then applied once: the run is
 * stopped when it is still in flight, and dropped when it already ended. Nothing is matched by text,
 * position or timing, and a pending stop never stops a run attributed to another message.
 *
 * Shared by every reply of one responder, so a stop that outlives its own cancel (the cancel's
 * budget ran out before the run started) is still applied by whichever reply next observes the
 * session. Safe to use from several coroutines: each pending stop is taken exactly once.
 */
internal class PendingStops {
    private val ids = MutableStateFlow<Set<ClientMessageId>>(emptySet())

    /** Remembers a stop for the message sent as [id]. */
    fun add(id: ClientMessageId) {
        ids.update { it + id }
    }

    /** True while the stop for [id] has not been applied. */
    operator fun contains(id: ClientMessageId): Boolean = id in ids.value

    /**
     * Applies every pending stop whose run [state] now publishes: takes those stops and, when any of
     * their runs is still in flight, asks [session] to stop it. Returns true when it asked.
     */
    fun applyTo(session: VoiceChatSession, state: VoiceChatState): Boolean {
        val taken = take(state)
        val running = taken.any { id -> state.startedRuns[id].orEmpty().any { it in state.activeRuns } }
        if (running) session.stopRun()
        return running
    }

    /** Removes and returns the pending stops whose run is published in [state]. */
    private fun take(state: VoiceChatState): Set<ClientMessageId> {
        var taken = emptySet<ClientMessageId>()
        ids.update { current ->
            taken = current.filterTo(mutableSetOf()) { state.startedRuns[it].orEmpty().isNotEmpty() }
            current - taken
        }
        return taken
    }
}
