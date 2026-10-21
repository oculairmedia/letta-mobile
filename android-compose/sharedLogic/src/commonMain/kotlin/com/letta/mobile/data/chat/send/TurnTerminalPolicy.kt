package com.letta.mobile.data.chat.send

import com.letta.mobile.data.runtime.TurnFailureNotice
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.transport.BridgeTurnStatus

/**
 * Read-only terminal decisions, evaluated under the coordinator's lifecycle mutex. Existing failure
 * notices remain authoritative; the caller supplies the current UI error and applies all effects.
 */
internal object TurnTerminalPolicy {
    /** Delivery without a buffered error also completes Agent replies that omit a stop reason. */
    fun classify(
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

    /** A delivered-then-failed reply preserves the error already visible to the user. */
    fun nextError(
        state: ConversationTurnState,
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
