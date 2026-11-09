package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.chat.runtime.ChatRunInterruptHost
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.data.presence.ActiveTurnPhase
import com.letta.mobile.data.presence.ActiveTurnState
import com.letta.mobile.data.presence.ActiveTurnTerminalKind

/*
 * letta-mobile-by4vm.81: a TimelineChatSessionPort's side of the shared
 * [com.letta.mobile.data.chat.runtime.ChatRunInterrupter], the same Stop lifecycle Android's
 * composer and desktop's controller run.
 */

/** Telemetry tag the timeline port's stop events go under. */
internal const val TIMELINE_STOP_TELEMETRY_TAG = "TimelineChat"

/** Fallback words for a stop the transport threw on before it reached the server. */
internal const val TIMELINE_STOP_FAILED = "The server did not accept that"

/**
 * Every step of a stop lands on the port's [TimelineRunState]: "stopping…" while the abort is out,
 * idle once the run's terminal lands, and the local clear ([TimelineRunState.clearedLocally]) when
 * the interrupter gives up on the terminal (second press, abort not sent, terminal timeout).
 */
internal class TimelineRunStopHost(
    private val updateRun: ((TimelineRunState) -> TimelineRunState) -> Unit,
    /** The run was cleared locally; the port watches for the timeline's own run to end. */
    private val onClearedLocally: () -> Unit,
) : ChatRunInterruptHost<String> {
    override fun onCancelling(key: String) = updateRun { it.copy(cancelling = true) }

    override fun onForcedClear(key: String, reason: String) {
        updateRun { it.copy(cancelling = false, clearedLocally = true) }
        onClearedLocally()
    }

    override fun onSettled(key: String) = updateRun { it.copy(cancelling = false) }

    override fun onAbortFailed(key: String, failure: Exception) =
        updateRun { it.copy(error = failure.message ?: TIMELINE_STOP_FAILED) }
}

/**
 * [this] with the run dropped locally: no streaming, typing or "stopping…", so the page lets the
 * user move on although the server turn may still be running. Unchanged unless [clearedLocally].
 */
fun ChatUiState.withRunClearedLocally(clearedLocally: Boolean): ChatUiState =
    if (clearedLocally) copy(isStreaming = false, isAgentTyping = false, isCancelling = false) else this

/** Owner-backed ports derive foreground activity entirely from the captured session snapshot. */
internal fun ChatUiState.withActiveTurn(turn: ActiveTurnState, outcome: ActiveTurnTerminalKind?): ChatUiState = copy(
    isStreaming = turn.phase != ActiveTurnPhase.Idle,
    isAgentTyping = isAgentTyping && turn.phase != ActiveTurnPhase.Idle,
    isCancelling = turn.phase == ActiveTurnPhase.Cancelling,
).withRunClearedLocally(outcome == ActiveTurnTerminalKind.LocalClear)
