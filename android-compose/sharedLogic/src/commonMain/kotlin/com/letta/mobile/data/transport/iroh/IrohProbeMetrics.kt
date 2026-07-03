package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import kotlinx.serialization.Serializable

@Serializable
data class IrohProbeTurnMetrics(
    val turn: Int,
    val dialMs: Long? = null,
    val firstFrameMs: Long? = null,
    val assistantDeltaCount: Int = 0,
    val assistantMessageIds: List<String> = emptyList(),
    val reasoningMessageIds: List<String> = emptyList(),
    val reasoningRowEstimate: Int = reasoningMessageIds.size,
    val turnDoneCount: Int = 0,
    val errorFrames: List<String> = emptyList(),
    val dialSucceeded: Boolean = dialMs != null,
    val timedOut: Boolean = false,
)

@Serializable
data class IrohProbeSummary(
    val ok: Boolean,
    val violations: List<String>,
    val turns: List<IrohProbeTurnMetrics>,
)

object IrohProbeAssertions {
    fun metricsForFrames(
        turn: Int,
        frames: List<ServerFrame>,
        dialMs: Long? = 1L,
        firstFrameMs: Long? = if (frames.isEmpty()) null else 1L,
        timedOut: Boolean = false,
    ): IrohProbeTurnMetrics {
        val assistantIds = linkedSetOf<String>()
        val reasoningIds = linkedSetOf<String>()
        val errors = mutableListOf<String>()
        var assistantDeltaCount = 0
        var turnDoneCount = 0

        frames.forEach { frame ->
            when (frame) {
                is ServerFrame.AssistantMessage -> {
                    assistantDeltaCount += 1
                    assistantIds += frame.id
                }
                is ServerFrame.ReasoningMessage -> reasoningIds += frame.id
                is ServerFrame.TurnDone -> turnDoneCount += 1
                is ServerFrame.Error -> errors += listOf(frame.code, frame.message)
                    .filter { it.isNotBlank() }
                    .joinToString(": ")
                else -> Unit
            }
        }

        return IrohProbeTurnMetrics(
            turn = turn,
            dialMs = dialMs,
            firstFrameMs = firstFrameMs,
            assistantDeltaCount = assistantDeltaCount,
            assistantMessageIds = assistantIds.toList(),
            reasoningMessageIds = reasoningIds.toList(),
            reasoningRowEstimate = reasoningIds.size,
            turnDoneCount = turnDoneCount,
            errorFrames = errors,
            dialSucceeded = dialMs != null,
            timedOut = timedOut,
        )
    }

    fun summarize(turns: List<IrohProbeTurnMetrics>): IrohProbeSummary {
        val violations = buildList {
            turns.forEach { turn ->
                val prefix = "turn${turn.turn}"
                if (!turn.dialSucceeded) add("$prefix:dial_failed")
                if (turn.turn == 2 && !turn.dialSucceeded) add("accept_wedge:turn2_dial_failed")
                if (turn.timedOut) add("$prefix:timeout_missing_terminal")
                if (turn.assistantDeltaCount < 1) add("$prefix:no_assistant_delta")
                if (turn.turnDoneCount != 1) add("$prefix:turn_done_count_${turn.turnDoneCount}")
                if (turn.assistantMessageIds.size != 1) add("$prefix:assistant_message_ids_${turn.assistantMessageIds.size}")
                if (turn.reasoningMessageIds.size > 2) add("$prefix:reasoning_message_ids_${turn.reasoningMessageIds.size}")
            }
        }
        return IrohProbeSummary(ok = violations.isEmpty(), violations = violations, turns = turns)
    }
}
