package com.letta.mobile.data.chat.send

import com.letta.mobile.data.runtime.TurnFailureNotice
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.timeline.IROH_SYNTHETIC_RUN_ID_PREFIXES

/**
 * Read-only terminal decisions, evaluated under the coordinator's lifecycle mutex. Existing failure
 * notices remain authoritative; the caller supplies the current UI error and applies all effects.
 */
internal object TurnTerminalPolicy {
    /** Delivery without a buffered error also completes Agent replies that omit a stop reason. */
    private fun classify(
        deliveredAssistantContent: Boolean,
        stopReason: String?,
        bufferedErrorMessage: String?,
    ): MainReplyCompletion {
        val hasBufferedTerminalError = !bufferedErrorMessage.isNullOrBlank()
        val completedStop = TurnFailureNotices.isCompletedMainReplyStopReason(stopReason)
        return MainReplyCompletion(
            isAuthoritativelyComplete = deliveredAssistantContent &&
                (completedStop || !hasBufferedTerminalError),
        )
    }

    fun decide(
        evidence: TurnTerminalEvidence,
        request: TurnSettlementRequest,
        cancelledNotice: TurnFailureNotice?,
    ): TurnTerminalOutcome {
        val status = request.event.status
        val completion = classify(evidence.deliveredAssistantContent, evidence.stopReason, evidence.bufferedErrorMessage)
        val notice = when (status) {
            BridgeTurnStatus.Failed -> TurnFailureNotices.forFailedTerminal(
                reason = evidence.bufferedErrorMessage,
                deliveredAssistantContent = evidence.deliveredAssistantContent,
                mainReplyCompleted = completion.isAuthoritativelyComplete,
                kindHint = evidence.bufferedErrorKind,
            )
            BridgeTurnStatus.Cancelled -> cancelledNotice
            else -> null
        }
        val deadTurn = status is BridgeTurnStatus.Failed && notice != null
        // Synthetic terminal identities may differ from the observed run whose fragments need cleanup.
        val cleanup = status is BridgeTurnStatus.Cancelled || (status is BridgeTurnStatus.Failed &&
            (deadTurn || IROH_SYNTHETIC_RUN_ID_PREFIXES.any(request.event.runId::startsWith)))
        return TurnTerminalOutcome(notice, deadTurn, cleanup)
    }

    /** A delivered-then-failed reply preserves the error already visible to the user. */
    fun nextError(
        state: TurnTerminalEvidence,
        status: BridgeTurnStatus,
        terminalNotice: TurnFailureNotice?,
        currentError: String?,
    ): String? {
        val stopReasonError = state.stopReason.equals("error", ignoreCase = true)
        return when (status) {
            BridgeTurnStatus.Completed -> state.bufferedErrorMessage
                ?: if (stopReasonError) BARE_STOP_REASON_ERROR_MESSAGE else currentError
            BridgeTurnStatus.Cancelled -> currentError
            BridgeTurnStatus.Failed -> if (terminalNotice != null) {
                state.bufferedErrorMessage ?: terminalNotice.message
            } else {
                currentError
            }
            is BridgeTurnStatus.Unknown -> state.bufferedErrorMessage
                ?: if (stopReasonError) BARE_STOP_REASON_ERROR_MESSAGE else "Turn ended unexpectedly (${status.raw})"
        }
    }

    private const val BARE_STOP_REASON_ERROR_MESSAGE =
        "Agent run failed after your message was sent. No error details were provided by the shim."
}

/** Whether delivered content provides authoritative main-reply completion evidence. */
internal data class MainReplyCompletion(
    val isAuthoritativelyComplete: Boolean,
)

/** Snapshot of terminal evidence, captured under the existing lifecycle mutex; never owns state. */
internal data class TurnTerminalEvidence(
    val deliveredAssistantContent: Boolean,
    val stopReason: String?,
    val bufferedErrorMessage: String?,
    val bufferedErrorKind: String?,
) {
    companion object {
        fun from(state: ConversationTurnState) = TurnTerminalEvidence(
            state.deliveredAssistantContent, state.stopReason, state.bufferedErrorMessage, state.bufferedErrorKind,
        )
    }
}

/** The same resolved terminal is shared by policy, cleanup, audit and settlement. */
internal data class TurnSettlementRequest(
    val event: WsTimelineEvent.TurnDone,
    val reason: String,
    val recordEvent: Boolean = true,
)

/** Decisions only; applying them cannot run I/O or retire ownership. */
internal data class TurnTerminalOutcome(
    val notice: TurnFailureNotice?,
    val deadTurn: Boolean,
    val cleanupFragments: Boolean,
)

/** A terminal may adopt an unidentified turn only through existing run ownership. */
internal fun ConversationTurnRegistry.resolveTerminal(event: WsTimelineEvent.TurnDone): ConversationTurnState? {
    if (event.turnId.isBlank()) return resolveStateByRun(TurnTail(null, event.runId))
    val states = statesSnapshot
    val runMatch = states.firstOrNull { it.canAdoptTerminal(event) }
    val turnAlreadyKnown = states.any { s -> s.turnId == event.turnId || s.identity.isFenced(event.turnId) }
    if (runMatch != null && !turnAlreadyKnown) {
        return runMatch
    }
    return resolveStateByTurn(TurnTail(event.turnId, event.runId))
}

