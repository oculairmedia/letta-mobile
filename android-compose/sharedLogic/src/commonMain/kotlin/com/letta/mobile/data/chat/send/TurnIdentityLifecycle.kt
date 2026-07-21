package com.letta.mobile.data.chat.send

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

/** Owns turn identity independently from UI and transport cleanup state. */
internal class TurnIdentityLifecycle {
    var active: ActiveTurnIdentity? = null
        private set

    private var generation: Long = 0L

    fun acceptedSend(conversationId: String): ActiveTurnIdentity {
        generation += 1L
        return ActiveTurnIdentity(conversationId, turnId = null, runId = null, generation = generation)
            .also { active = it }
    }

    fun turnStarted(conversationId: String, turnId: String, runId: String): TurnIdentityTransition {
        val previous = active
        val sameDelayedTurn = previous?.turnId == null && previous?.conversationId == conversationId
        val sameKnownTurn = previous?.turnId == turnId && previous.conversationId == conversationId
        if (sameDelayedTurn || sameKnownTurn) {
            val promoted = previous.runId?.startsWith(SYNTHETIC_RUN_PREFIX) == true &&
                !runId.startsWith(SYNTHETIC_RUN_PREFIX)
            val updated = ActiveTurnIdentity(conversationId, turnId, runId, previous.generation)
            active = updated
            return TurnIdentityTransition.SameTurn(updated, promoted)
        }

        generation += 1L
        val replacement = ActiveTurnIdentity(conversationId, turnId, runId, generation)
        active = replacement
        return if (previous == null) {
            TurnIdentityTransition.Accepted(replacement)
        } else {
            TurnIdentityTransition.Replacement(replacement)
        }
    }

    fun owns(identity: ActiveTurnIdentity): Boolean = active == identity

    fun clear() {
        active = null
    }

}
