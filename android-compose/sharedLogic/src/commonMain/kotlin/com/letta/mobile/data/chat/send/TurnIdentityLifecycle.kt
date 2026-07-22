package com.letta.mobile.data.chat.send

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

private const val SYNTHETIC_RUN_PREFIX = "iroh-run-"

private fun isSyntheticRunId(runId: String): Boolean = runId.startsWith(SYNTHETIC_RUN_PREFIX)

internal data class ActiveTurnIdentity(
    val conversationId: String,
    val turnId: String?,
    val runId: String?,
    val generation: Long,
) {
    val durableRunId: String?
        get() = runId?.takeUnless(::isSyntheticRunId)
}

internal sealed interface TurnIdentityTransition {
    data class Accepted(val identity: ActiveTurnIdentity) : TurnIdentityTransition
    data class SameTurn(val identity: ActiveTurnIdentity, val runPromoted: Boolean) : TurnIdentityTransition
    data class Replacement(val identity: ActiveTurnIdentity) : TurnIdentityTransition
}

/**
 * Owns turn identity independently from UI and transport cleanup state.
 *
 * Sends and transport events run on separate coroutines, so every lifecycle read and transition is
 * serialized by [lock]. The last identified turn survives [clear] as a terminal fence: while an
 * accepted send is waiting for `TurnStarted`, its immediate identified or blank failure owns the new
 * generation, but a delayed terminal for the previous turn does not.
 */
internal class TurnIdentityLifecycle {
    private val lock = SynchronizedObject()
    private var current: ActiveTurnIdentity? = null
    private var generation: Long = 0L
    private var terminalFenceTurnId: String? = null

    val active: ActiveTurnIdentity?
        get() = synchronized(lock) { current }

    fun acceptedSend(conversationId: String): ActiveTurnIdentity = synchronized(lock) {
        establishAcceptedSend(conversationId)
    }

    /**
     * Keeps terminal ownership blocked while the transport decides acceptance and the coordinator
     * publishes its matching optimistic state. This closes the synchronous send-to-terminal race.
     */
    fun acceptSend(
        conversationId: String,
        send: () -> Boolean,
        onAccepted: (ActiveTurnIdentity) -> Unit,
    ): Boolean = synchronized(lock) {
        if (!send()) return@synchronized false
        onAccepted(establishAcceptedSend(conversationId))
        true
    }

    private fun establishAcceptedSend(conversationId: String): ActiveTurnIdentity {
        generation += 1L
        return ActiveTurnIdentity(conversationId, turnId = null, runId = null, generation = generation)
            .also { current = it }
    }

    fun turnStarted(conversationId: String, turnId: String, runId: String): TurnIdentityTransition =
        synchronized(lock) {
            val previous = current
            val sameDelayedTurn = previous?.turnId == null && previous?.conversationId == conversationId
            val sameKnownTurn = previous?.turnId == turnId && previous.conversationId == conversationId
            if (sameDelayedTurn || sameKnownTurn) {
                val promoted = previous.runId?.startsWith(SYNTHETIC_RUN_PREFIX) == true &&
                    !runId.startsWith(SYNTHETIC_RUN_PREFIX)
                val updated = ActiveTurnIdentity(conversationId, turnId, runId, previous.generation)
                current = updated
                terminalFenceTurnId = turnId
                return@synchronized TurnIdentityTransition.SameTurn(updated, promoted)
            }

            generation += 1L
            val replacement = ActiveTurnIdentity(conversationId, turnId, runId, generation)
            current = replacement
            terminalFenceTurnId = turnId
            if (previous == null) {
                TurnIdentityTransition.Accepted(replacement)
            } else {
                TurnIdentityTransition.Replacement(replacement)
            }
        }

    fun owns(identity: ActiveTurnIdentity): Boolean = synchronized(lock) { current == identity }

    fun acceptsTerminal(turnId: String): Boolean = synchronized(lock) {
        val activeTurnId = current?.turnId
        when {
            current == null -> true
            activeTurnId != null -> turnId.isNotBlank() && turnId == activeTurnId
            turnId.isBlank() -> true
            else -> turnId != terminalFenceTurnId
        }
    }

    fun clear() = synchronized(lock) {
        current?.turnId?.let { terminalFenceTurnId = it }
        current = null
    }
}
