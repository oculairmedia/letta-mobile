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
 */
internal class OrderedTurnRelay(
    private val fanout: ConversationTurnFanout,
    private val protocol: IrohRelayedTurnProtocol,
) {
    private var heldTerminal: RuntimeEventDraft? = null

    /** Relays [draft] now, or holds it when it is the turn's terminal lifecycle. */
    suspend fun relay(draft: RuntimeEventDraft) {
        if (fanout.isTerminalLifecycle(draft.payload)) {
            hold(draft)
            return
        }
        if (heldTerminal != null) recordLateDraft(draft)
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
            relayDraft(fanout, protocol, terminal)
            return
        }
        withContext(NonCancellable) {
            withTimeoutOrNull(CANCELLED_RELEASE_TIMEOUT_MS) { relayDraft(fanout, protocol, terminal) }
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
    }
}
