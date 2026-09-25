package com.letta.mobile.data.runtime

import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal class TurnToolCallLedger {
    val emitted = mutableSetOf<String>()
    val returned = mutableSetOf<String>()
}

internal data class TurnDraftCallbacks(
    val autoApprovedDraft: suspend (RuntimeEventDraft) -> RuntimeEventDraft?,
    val track: (RuntimeEventDraft, TurnToolCallLedger) -> Unit,
    val clearApprovals: () -> Unit,
    val emit: suspend (RuntimeEventDraft) -> Unit,
    val settle: suspend (TurnToolCallLedger, String) -> Unit,
    val completedDraft: (RunId?) -> RuntimeEventDraft,
    val recordTerminal: (RuntimeEventDraft, Long?) -> Unit,
    val noteCompleted: (Long?) -> Unit,
    val complete: () -> Nothing,
    val settleDelayMs: Long,
)

internal class TurnDraftProcessor(
    private val callbacks: TurnDraftCallbacks,
    private val coroutineScope: CoroutineScope,
) {
    val ledger = TurnToolCallLedger()
    private var pendingCompleted: RuntimeEventDraft? = null
    private val tail = TurnTailBuffer()
    private var terminalArmed = false
    private var speculativeCompletionArmed = false
    private var sawToolReturn = false
    private var sawAssistantAfterToolReturn = false
    private var pendingCompletedSeq: Long? = null
    var terminalSettleJob: Job? = null
        private set

    /** letta-mobile-qygvv.16: set once this turn's terminal lifecycle has been emitted. */
    var terminalEmitted = false
        private set

    /**
     * letta-mobile-qygvv.16: the transport session carrying this turn is gone, so no server terminal
     * will ever arrive. Ends the turn through the same terminal path a server terminal takes: a
     * completion already waiting out its settle window is published as it stands, otherwise
     * [fallback] is. Never emits a second terminal. Reports what it did, then completes the turn.
     */
    suspend fun cutOff(fallback: RuntimeEventDraft, report: (TurnCutOffOutcome) -> Unit): Nothing {
        terminalSettleJob?.cancelAndJoin()
        terminalSettleJob = null
        report(publishCutOffTerminal(fallback))
        callbacks.complete()
    }

    private suspend fun publishCutOffTerminal(fallback: RuntimeEventDraft): TurnCutOffOutcome {
        if (terminalEmitted) return TurnCutOffOutcome.AlreadyTerminal
        val pending = pendingCompleted
        if (pending != null) {
            flushTail()
            callbacks.noteCompleted(pendingCompletedSeq)
            emitMarkingTerminal(pending)
            return TurnCutOffOutcome.PendingCompletionPublished
        }
        publishTerminal(fallback, frameSeq = null)
        return TurnCutOffOutcome.FallbackPublished
    }

    /** The turn's terminal flush: the final round's tail, in the order the server sent it. */
    suspend fun flushTail() {
        callbacks.clearApprovals()
        tail.drain().forEach { callbacks.emit(it) }
    }

    /**
     * letta-mobile-qygvv.26: a round's tail (usage_statistics, stop_reason) is closed once its
     * stop_reason arrived. The next non-terminal frame means the turn continues into another round,
     * so that round's tail goes out now, ahead of the frame, instead of being overwritten or held
     * back until the turn's terminal.
     */
    private suspend fun flushClosedRoundTail(draft: RuntimeEventDraft) {
        if (!tail.closed || !draft.continuesTurn()) return
        tail.drain().forEach { callbacks.emit(it) }
    }

    /**
     * [authoritative] marks a draft projected from the server's own end-of-turn signal
     * (`turn_finished`, or an idle loop status after evidence — letta-mobile-qygvv.2). Its terminal
     * lifecycle completes the turn immediately, superseding any pending settle window, instead of
     * waiting out the quiet period the `stop_reason` delta fallback needs.
     */
    suspend fun process(draft: RuntimeEventDraft, frameSeq: Long?, authoritative: Boolean = false) {
        flushClosedRoundTail(draft)
        if (emitAutoApproved(draft)) return
        callbacks.track(draft, ledger)
        observeContinuedActivity(draft)
        if (bufferTail(draft, frameSeq)) return
        if (authoritative && draft.isTerminalLifecycle()) {
            cancelPendingCompletion()
            emitTerminal(draft, frameSeq)
            return
        }
        if (draft.isCompletedLifecycle()) {
            pendingCompleted = draft
            armCompletedTerminalOnce()
            return
        }
        if (draft.isTerminalLifecycle()) {
            emitTerminal(draft, frameSeq)
            return
        }
        callbacks.emit(draft)
        armCompletedTerminalOnce()
    }

    private suspend fun emitAutoApproved(draft: RuntimeEventDraft): Boolean {
        val approved = callbacks.autoApprovedDraft(draft) ?: return false
        callbacks.track(approved, ledger)
        callbacks.emit(approved)
        armCompletedTerminalOnce()
        return true
    }

    private fun observeContinuedActivity(draft: RuntimeEventDraft) {
        val continued = listOf(
            draft.isToolCallFrame(),
            draft.isToolReturnFrame(),
            draft.isAssistantFrame(),
        ).any { it }
        if (continued) cancelSpeculativeCompletion()
        if (draft.isToolReturnFrame()) sawToolReturn = true
        if (sawToolReturn && draft.isAssistantFrame()) sawAssistantAfterToolReturn = true
    }

    private fun bufferTail(draft: RuntimeEventDraft, frameSeq: Long?): Boolean {
        if (!draft.isTailFrame()) return false
        tail.add(draft, closesRound = draft.isStopReasonFrame())
        if (draft.isUsageStatisticsFrame()) armSpeculativeCompletionAfterUsage(draft, frameSeq)
        return true
    }

    private fun armSpeculativeCompletionAfterUsage(draft: RuntimeEventDraft, frameSeq: Long?) {
        if (!sawAssistantAfterToolReturn) return
        if (pendingCompleted == null) {
            pendingCompleted = callbacks.completedDraft(draft.runId)
            pendingCompletedSeq = frameSeq
        }
        sawAssistantAfterToolReturn = false
        sawToolReturn = false
        speculativeCompletionArmed = true
        armCompletedTerminalOnce()
    }

    private suspend fun emitTerminal(draft: RuntimeEventDraft, frameSeq: Long?) {
        publishTerminal(draft, frameSeq)
        callbacks.complete()
    }

    private suspend fun publishTerminal(draft: RuntimeEventDraft, frameSeq: Long?) {
        if (draft.isAbnormalTerminal()) {
            callbacks.settle(ledger, "Tool execution interrupted by turn termination")
        }
        flushTail()
        callbacks.recordTerminal(draft, frameSeq)
        emitMarkingTerminal(draft)
    }

    private suspend fun emitMarkingTerminal(terminal: RuntimeEventDraft) {
        callbacks.emit(terminal)
        terminalEmitted = true
    }

    private fun cancelSpeculativeCompletion() {
        if (!speculativeCompletionArmed) return
        cancelPendingCompletion()
    }

    private fun cancelPendingCompletion() {
        terminalSettleJob?.cancel()
        terminalSettleJob = null
        terminalArmed = false
        speculativeCompletionArmed = false
        pendingCompleted = null
        pendingCompletedSeq = null
    }

    private fun armCompletedTerminalOnce() {
        if (terminalArmed || pendingCompleted == null) return
        terminalArmed = true
        terminalSettleJob = coroutineScope.launch {
            delay(callbacks.settleDelayMs.milliseconds)
            val terminal = pendingCompleted ?: return@launch
            flushTail()
            callbacks.noteCompleted(pendingCompletedSeq)
            emitMarkingTerminal(terminal)
            callbacks.complete()
        }
    }
}

/**
 * letta-mobile-qygvv.26: the buffered end-of-round frames, kept in arrival order (the App Server
 * sends usage_statistics, then stop_reason) and every one of them: a multi-round turn reports each
 * round's usage and stop_reason, not the first usage and the last stop_reason.
 */
private class TurnTailBuffer {
    private val frames = mutableListOf<RuntimeEventDraft>()

    /** A stop_reason is buffered: the round these frames close has ended. */
    var closed = false
        private set

    fun add(draft: RuntimeEventDraft, closesRound: Boolean) {
        frames += draft
        if (closesRound) closed = true
    }

    fun drain(): List<RuntimeEventDraft> {
        val drained = frames.toList()
        frames.clear()
        closed = false
        return drained
    }
}

/** letta-mobile-qygvv.16: how [TurnDraftProcessor.cutOff] ended a turn whose session was lost. */
internal enum class TurnCutOffOutcome {
    /** The turn's own terminal was already out; nothing more was emitted. */
    AlreadyTerminal,

    /** A completion waiting out its settle window was published as the terminal. */
    PendingCompletionPublished,

    /** No terminal was in sight, so the session-loss fallback terminal was published. */
    FallbackPublished,
}

private val terminalStatuses = setOf(
    RuntimeRunStatus.Completed,
    RuntimeRunStatus.Failed,
    RuntimeRunStatus.Cancelled,
)
private val abnormalTerminalStatuses = setOf(RuntimeRunStatus.Failed, RuntimeRunStatus.Cancelled)

private fun RuntimeEventDraft.isTerminalLifecycle(): Boolean =
    (payload as? RuntimeEventPayload.RunLifecycleChanged)?.status in terminalStatuses

private fun RuntimeEventDraft.isCompletedLifecycle(): Boolean =
    (payload as? RuntimeEventPayload.RunLifecycleChanged)?.status == RuntimeRunStatus.Completed

private fun RuntimeEventDraft.isAbnormalTerminal(): Boolean =
    (payload as? RuntimeEventPayload.RunLifecycleChanged)?.status in abnormalTerminalStatuses

private fun RuntimeEventDraft.isToolReturnFrame(): Boolean = when (val event = payload) {
    is RuntimeEventPayload.ToolReturnObserved -> true
    is RuntimeEventPayload.RemoteStreamFrame -> event.matchesAnyType(RuntimeFrameTypes.toolReturn)
    else -> false
}

private fun RuntimeEventDraft.isAssistantFrame(): Boolean = when (val event = payload) {
    is RuntimeEventPayload.RemoteStreamFrame -> event.matchesAnyType(RuntimeFrameTypes.assistant)
    else -> false
}

private fun RuntimeEventDraft.isToolCallFrame(): Boolean = when (val event = payload) {
    is RuntimeEventPayload.ToolCallObserved -> true
    is RuntimeEventPayload.ApprovalRequested -> true
    is RuntimeEventPayload.RemoteStreamFrame -> event.matchesAnyType(RuntimeFrameTypes.toolCall)
    else -> false
}

private fun RuntimeEventDraft.isUsageStatisticsFrame(): Boolean = when (val event = payload) {
    is RuntimeEventPayload.RemoteStreamFrame -> event.matchesAnyType(RuntimeFrameTypes.usage)
    is RuntimeEventPayload.ExternalTransportFrame -> listOf(
        event.body.startsWith("usage:"),
        frameMessageType(event.body) == "usage_statistics",
    ).any { it }
    else -> false
}

/** A frame that carries the turn on past a closed round tail: neither a tail frame nor a terminal. */
private fun RuntimeEventDraft.continuesTurn(): Boolean = !isTailFrame() && !isTerminalLifecycle()

private fun RuntimeEventDraft.isTailFrame(): Boolean = isStopReasonFrame() || isUsageStatisticsFrame()

private fun RuntimeEventDraft.isStopReasonFrame(): Boolean = when (val event = payload) {
    is RuntimeEventPayload.RemoteStreamFrame -> event.matchesAnyType(RuntimeFrameTypes.stopReason)
    is RuntimeEventPayload.ExternalTransportFrame -> frameMessageType(event.body) == "stop_reason"
    else -> false
}

internal suspend fun TurnDraftProcessor.promoteAndProcess(
    drafts: List<RuntimeEventDraft>,
    frameSeq: Long?,
    slot: TurnLeaseSlot,
    leaseToken: Long,
) {
    val runId = drafts.firstOrNull { it.runId != null }?.runId?.value
    if (runId != null) {
        slot.runIdGate.promote(runId, leaseToken)
    }
    drafts.forEach { draft -> process(draft, frameSeq) }
}

