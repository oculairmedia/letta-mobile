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
    val assistantFinalTextLengths: List<Int> = emptyList(),
    val scenarioViolations: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val skipped: Boolean = false,
    /** Which probe scenario produced this turn (admin-rpc, idle-send, cancel-midstream, ...). */
    val scenario: String? = null,
    /** Assertion profile selecting which rule set applies in [IrohProbeAssertions.summarize]. */
    val profile: String = IrohProbeAssertions.PROFILE_SEND,
    /** event_seq values observed on stream frames, in arrival order. */
    val eventSeqs: List<Long> = emptyList(),
    /** Stream frames whose delta had no recognized `message_type` (plain-body fallback shape). */
    val untypedFrameCount: Int = 0,
    /** Stream frames for this turn observed AFTER the terminal frame. */
    val framesAfterTerminal: Int = 0,
    /** Terminal status derived from the terminal frame (completed|cancelled|failed). */
    val terminalStatus: String? = null,
    /** run_id carried by the terminal frame. */
    val terminalRunId: String? = null,
    /** run_id observed on the first non-terminal stream frame of the active turn. */
    val activeRunId: String? = null,
    /** tool_call ids that never received a matching tool_return before the terminal frame. */
    val openToolCallIds: List<String> = emptyList(),
    /** Total wall time of the scenario step, for budget assertions (hydrate-heavy). */
    val wallMs: Long? = null,
)

@Serializable
data class IrohProbeSummary(
    val ok: Boolean,
    val violations: List<String>,
    val turns: List<IrohProbeTurnMetrics>,
)

object IrohProbeAssertions {
    /** Regular send turn: exactly one assistant message + exactly one terminal. */
    const val PROFILE_SEND = "send"

    /** Cancel-midstream turn: terminal must be cancelled with the real run_id, no dangling tool_call. */
    const val PROFILE_CANCEL = "cancel"

    /** Scenario-level report row (hydrate-heavy, no-http): only scenario + stream-shape rules apply. */
    const val PROFILE_REPORT = "report"

    /**
     * Delta `message_type` values the client maps to typed [ServerFrame] variants.
     * A stream frame whose delta carries none of these (or no message_type at all)
     * would hit the plain-body AssistantMessage fallback — a probe violation.
     */
    val TYPED_MESSAGE_TYPES: Set<String> = setOf(
        "assistant_message",
        "reasoning_message",
        "hidden_reasoning_message",
        "tool_call_message",
        "approval_request_message",
        "tool_return_message",
        "usage_statistics",
        "stop_reason",
        "loop_error",
        "error_message",
        "user_message",
        "system_message",
    )

    fun metricsForFrames(
        turn: Int,
        frames: List<ServerFrame>,
        dialMs: Long? = 1L,
        firstFrameMs: Long? = if (frames.isEmpty()) null else 1L,
        timedOut: Boolean = false,
    ): IrohProbeTurnMetrics {
        val assistantIds = linkedSetOf<String>()
        val assistantFinalTextLengths = mutableMapOf<String, Int>()
        val reasoningIds = linkedSetOf<String>()
        val errors = mutableListOf<String>()
        var assistantDeltaCount = 0
        var turnDoneCount = 0

        frames.forEach { frame ->
            when (frame) {
                is ServerFrame.AssistantMessage -> {
                    assistantDeltaCount += 1
                    assistantIds += frame.id
                    assistantFinalTextLengths[frame.id] = frame.content.length
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
            assistantFinalTextLengths = assistantFinalTextLengths.values.toList(),
        )
    }

    fun classifyAdminRpc(
        method: String,
        success: Boolean,
        resultIsArray: Boolean,
        error: String?,
    ): String? = when {
        !success -> "admin_rpc_method_missing:$method"
        error?.contains("Unknown method", ignoreCase = true) == true -> "admin_rpc_method_missing:$method"
        !resultIsArray -> "admin_rpc_method_missing:$method"
        else -> null
    }

    fun classifyIdleSendFailure(error: String?): String =
        "idle_send_failed" + error?.takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty()

    fun classifyConversationBootstrap(error: String?): String =
        if (error?.contains("Conversation not found", ignoreCase = true) == true) {
            "conversation_bootstrap_failed"
        } else {
            "probe_error" + error?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        }

    /** True when every adjacent pair is strictly increasing. Empty/singleton lists are monotonic. */
    fun isEventSeqMonotonic(eventSeqs: List<Long>): Boolean =
        eventSeqs.zipWithNext().all { (a, b) -> b > a }

    /**
     * Cross-turn event_seq continuity on the SAME connection: a server that
     * resets event_seq to 0 at each turn boundary stays green under the
     * per-turn [isEventSeqMonotonic] check (each turn's list is individually
     * increasing), so consecutive same-connection turns must also be compared.
     * Returns null when either side is empty or continuity holds.
     */
    fun classifyCrossTurnEventSeq(previousTurnSeqs: List<Long>, nextTurnSeqs: List<Long>): String? {
        val last = previousTurnSeqs.lastOrNull() ?: return null
        val first = nextTurnSeqs.firstOrNull() ?: return null
        return if (first > last) null else "event_seq_reset_across_turns:$last->$first"
    }

    /**
     * no-http invariant (qfa81 headline): zero TCP connections from the probe process
     * to the admin HTTP port while running in iroh:// mode.
     */
    fun classifyNoHttp(socketSamples: List<Int>): String? {
        val max = socketSamples.maxOrNull() ?: return null
        return if (max > 0) "no_http_tcp_connects:$max" else null
    }

    /**
     * duplicate-send: the same client_message_id must yield exactly one assistant turn.
     * [phase] distinguishes the same-connection replay from the forced-redial replay.
     */
    fun classifyDuplicateSend(terminalCount: Int, phase: String): String? =
        if (terminalCount <= 1) null else "duplicate_send_turns_$terminalCount:$phase"

    /** hydrate-heavy: all seeded messages page back over admin_rpc inside the wall budget. */
    fun classifyHydrateHeavy(
        seededCount: Int,
        listedCount: Int,
        wallMs: Long,
        budgetMs: Long,
        pageFailures: List<String> = emptyList(),
    ): List<String> = buildList {
        addAll(pageFailures.map { "hydrate_heavy_page_failed:$it" })
        if (listedCount < seededCount) add("hydrate_heavy_incomplete:$listedCount/$seededCount")
        if (wallMs > budgetMs) add("hydrate_heavy_slow:${wallMs}ms>${budgetMs}ms")
    }

    fun summarize(turns: List<IrohProbeTurnMetrics>): IrohProbeSummary {
        val violations = buildList {
            turns.forEach { turn ->
                if (turn.skipped) return@forEach
                val prefix = buildString {
                    turn.scenario?.let { append(it).append(':') }
                    append("turn").append(turn.turn)
                }
                addAll(turn.scenarioViolations)

                // Stream-shape invariants apply to every profile.
                if (!isEventSeqMonotonic(turn.eventSeqs)) add("$prefix:event_seq_not_monotonic")
                if (turn.untypedFrameCount > 0) add("$prefix:untyped_frames_${turn.untypedFrameCount}")
                if (turn.framesAfterTerminal > 0) add("$prefix:frames_after_terminal_${turn.framesAfterTerminal}")

                when (turn.profile) {
                    PROFILE_REPORT -> {
                        if (turn.timedOut) add("$prefix:timeout")
                    }
                    PROFILE_CANCEL -> {
                        if (!turn.dialSucceeded) add("$prefix:dial_failed")
                        if (turn.timedOut) add("$prefix:timeout_missing_terminal")
                        if (turn.turnDoneCount != 1) add("$prefix:turn_done_count_${turn.turnDoneCount}")
                        val status = turn.terminalStatus
                        if (!turn.timedOut && status != "cancelled") {
                            add("$prefix:cancel_terminal_status_${status ?: "missing"}")
                        }
                        val terminalRunId = turn.terminalRunId
                        if (terminalRunId != null && terminalRunId.startsWith("cancelled-")) {
                            add("$prefix:cancel_synthetic_run_id")
                        } else if (!turn.timedOut && turn.activeRunId != null && terminalRunId != turn.activeRunId) {
                            add("$prefix:cancel_run_id_mismatch")
                        }
                        if (turn.openToolCallIds.isNotEmpty()) {
                            add("$prefix:dangling_tool_call_${turn.openToolCallIds.size}")
                        }
                    }
                    else -> {
                        turn.assistantFinalTextLengths
                            .filter { it in 1..2 }
                            .forEach { add("orphan_fragment:$prefix") }
                        if (!turn.dialSucceeded) add("$prefix:dial_failed")
                        if (turn.turn == 2 && !turn.dialSucceeded) add("accept_wedge:turn2_dial_failed")
                        if (turn.timedOut) add("$prefix:timeout_missing_terminal")
                        if (turn.assistantDeltaCount < 1) add("$prefix:no_assistant_delta")
                        if (turn.turnDoneCount != 1) add("$prefix:turn_done_count_${turn.turnDoneCount}")
                        if (turn.assistantMessageIds.size != 1) add("$prefix:assistant_message_ids_${turn.assistantMessageIds.size}")
                        if (turn.reasoningMessageIds.size > 2) add("$prefix:reasoning_message_ids_${turn.reasoningMessageIds.size}")
                    }
                }
            }
        }
        return IrohProbeSummary(ok = violations.isEmpty(), violations = violations, turns = turns)
    }
}
