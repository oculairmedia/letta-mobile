package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.chat.projection.classifyToolCallState
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiToolCall
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * A message's (or a run's) tool calls and the approval requests among them: what [RunToolSteps]
 * lists as step lines, in a run or on its own.
 */
@Immutable
internal data class ToolRunCalls(
    val toolCalls: ImmutableList<UiToolCall>,
    val approvals: ImmutableList<UiApprovalRequest> = persistentListOf(),
)

/** The request (if any) that carries [call]. */
internal fun List<UiApprovalRequest>.requestFor(call: UiToolCall): UiApprovalRequest? =
    firstOrNull { request -> request.toolCalls.any { it.toolCallId == call.toolCallId } }

/**
 * A call a parked permission gate holds reads "awaiting approval", not
 * "running": the shared classifier only knows runtime questions, since a gate is only parked while
 * the request carries the host's control request ([isWaitingOn]).
 */
internal fun UiToolCall.runState(request: UiApprovalRequest?, toolCalls: List<UiToolCall>): ToolTimelineState {
    val state = classifyToolCallState(this, request)
    val parked = request != null && result == null && approvalDecision == null && request.isWaitingOn(toolCalls)
    return if (parked && state == ToolTimelineState.Running) ToolTimelineState.AwaitingApproval else state
}

/** Seconds since [startedAtEpochMs] (or since first shown), ticking once a second while [active]. */
@Composable
internal fun rememberElapsedSeconds(active: Boolean, startedAtEpochMs: Long?): State<Long> =
    produceState(0L, active, startedAtEpochMs) {
        value = elapsedSecondsSince(startedAtEpochMs) ?: 0L
        while (active) {
            delay(ELAPSED_TICK_MILLIS)
            value = elapsedSecondsSince(startedAtEpochMs) ?: (value + 1L)
        }
    }

private fun elapsedSecondsSince(startedAtEpochMs: Long?): Long? =
    startedAtEpochMs?.let { ((Clock.System.now().toEpochMilliseconds() - it).coerceAtLeast(0L)) / ELAPSED_TICK_MILLIS }

private const val ELAPSED_TICK_MILLIS = 1_000L
