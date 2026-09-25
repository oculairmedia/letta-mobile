package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * letta-mobile-qygvv.18: one ordered writer per relayed turn, with the terminal written LAST.
 *
 * The engine's terminal lifecycle draft is not always the last draft of its flow. A completion
 * published by the settle-window job runs on a sibling coroutine of the engine's collect loop, so
 * on the node's multi-threaded dispatcher the loop can still emit the assistant's trailing deltas
 * after the terminal; the dangling-tool settlement in the engine's `finally` emits after it too.
 * The relay used to write the terminal's stop_reason, idle loop status and `turn_finished` as soon
 * as the terminal draft arrived, so those late drafts reached the phone behind `turn_finished`
 * (device capture 2026-09-24: assistant deltas after TurnDone).
 *
 * The terminal draft is now held until the engine's flow has ended, when no further draft can
 * arrive; drafts arriving meanwhile are relayed first, on the same fanout chain. The flow ends
 * right after its terminal (the engine completes the turn by throwing out of the collector), so
 * holding costs no measurable latency.
 *
 * letta-mobile-qygvv.28: with a [tap], the held terminal is written from the App Server's own
 * tail (see [relayServerTail]) instead of being re-synthesized from the engine's lifecycle.
 */
internal class OrderedTurnRelay(
    private val fanout: ConversationTurnFanout,
    private val protocol: IrohRelayedTurnProtocol,
    private val tap: ServerFrameTap? = null,
) {
    private var heldTerminal: RuntimeEventDraft? = null

    /** Relays [draft] now, or holds it when it is the turn's terminal lifecycle. */
    suspend fun relay(draft: RuntimeEventDraft) {
        if (fanout.isTerminalLifecycle(draft.payload)) {
            hold(draft)
            return
        }
        if (heldTerminal != null) recordLateDraft(draft)
        tap?.noteRelayed(draft)
        relayDraft(fanout, protocol, draft)
    }

    /**
     * Writes the held terminal once the draft flow has ended. Under cancellation the write is
     * still attempted (bounded), so a detached turn's parking sees the server's own terminal.
     */
    suspend fun releaseHeldTerminal() {
        val terminal = heldTerminal ?: return
        heldTerminal = null
        if (currentCoroutineContext().isActive) {
            relayTerminal(terminal)
            return
        }
        withContext(NonCancellable) {
            withTimeoutOrNull(CANCELLED_RELEASE_TIMEOUT_MS) { relayDraft(fanout, protocol, terminal) }
        }
    }

    /** The App Server's own tail when the tap has it, else the terminal synthesized from [terminal]. */
    private suspend fun relayTerminal(terminal: RuntimeEventDraft) {
        val tail = tap?.awaitTurnTail(SERVER_TAIL_WAIT_MS)
        if (tail == null) {
            relayDraft(fanout, protocol, terminal)
        } else {
            relayServerTail(fanout, protocol, terminal, tail)
        }
    }

    private fun hold(draft: RuntimeEventDraft) {
        if (heldTerminal == null && !fanout.anyTerminalWritten) {
            heldTerminal = draft
            return
        }
        recordDuplicateTerminal(draft)
    }

    private fun recordLateDraft(draft: RuntimeEventDraft) {
        Telemetry.event(
            "IrohNode", "stream.late_draft_before_terminal",
            "conversationId" to draft.conversationId?.value,
            "payload" to draft.payload::class.simpleName,
        )
    }

    private fun recordDuplicateTerminal(draft: RuntimeEventDraft) {
        Telemetry.event(
            "IrohNode", "stream.terminal_duplicate_skipped",
            "agentId" to draft.agentId?.value,
            "conversationId" to draft.conversationId?.value,
        )
    }

    private companion object {
        const val CANCELLED_RELEASE_TIMEOUT_MS = 5_000L

        /**
         * How long the terminal waits for the server's `turn_finished` to reach the tap. The engine
         * ends on that frame or on the one just before it, so this is normally already satisfied.
         */
        const val SERVER_TAIL_WAIT_MS = 1_000L
    }
}

/**
 * letta-mobile-qygvv.28: writes a turn's end from the App Server's own frames: the frames the
 * engine consumed after its last relayed delta (the real `error_message` with its run id, the
 * `stop_reason`, the idle loop status), then the server's `turn_finished`. The engine's [terminal]
 * still settles the input ack and dangling tool calls, and supplies the terminal delta only when
 * the server sent none (a cancel the engine ended on its own, for one).
 */
internal suspend fun relayServerTail(
    fanout: ConversationTurnFanout,
    protocol: IrohRelayedTurnProtocol,
    terminal: RuntimeEventDraft,
    tail: ServerTurnTail,
) {
    protocol.beforeDraft(terminal)
    if (fanout.isFailureOrCancelLifecycle(terminal.payload)) fanout.flushOpenToolCalls()
    if (!fanout.anyTerminalDeltaRelayed && tail.frames.none { it.isTerminalDelta }) {
        fanout.onDraft(terminal.payload, terminal.runId)
    }
    tail.frames.forEach { frame ->
        when (frame.type) {
            TapFrame.STREAM_DELTA -> fanout.relayServerDelta(frame.raw)
            TapFrame.UPDATE_LOOP_STATUS, TapFrame.UPDATE_QUEUE -> protocol.forwardServerFrame(frame)
            else -> Unit
        }
    }
    fanout.markTerminalWritten()
    protocol.finishWithServerFrame(tail.turnFinished)
}
