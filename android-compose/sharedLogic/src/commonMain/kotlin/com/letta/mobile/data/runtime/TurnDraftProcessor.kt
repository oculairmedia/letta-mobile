package com.letta.mobile.data.runtime

import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.isAdvisory
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
    /** Ends the turn (signals its [TurnOutcome.Completed]; it no longer throws). */
    val complete: () -> Unit,
    val settleDelayMs: Long,
    /** Whether the turn already ended, by this processor or another path (an abrupt local ending). */
    val isComplete: () -> Boolean = { false },
)

internal class TurnDraftProcessor(
    private val callbacks: TurnDraftCallbacks,
    private val coroutineScope: CoroutineScope,
    /** The turn's one identity stamper; every stream-frame draft passes through it. */
    val identity: TurnStreamIdentity = turnStreamIdentityFor(null),
) {
    val ledger = TurnToolCallLedger()
    private val tail = TurnTailBuffer()

    /**
     * The completion lifecycle ([TurnPhase]), in place of the former
     * `terminalArmed` / `speculativeCompletionArmed` / `terminalEmitted` flags. The settle window is
     * armed exactly when a completion is held, i.e. on leaving [TurnPhase.Streaming] for a pending
     * phase.
     */
    private val lifecycle = TurnStateMachine("draft_processor", TurnPhase.Streaming, ::turnTransition)
    private var evidence = SpeculationEvidence.None

    /** The completion held while its settle window runs (set only in a pending [TurnPhase]). */
    private var pendingCompleted: RuntimeEventDraft? = null
    private var pendingCompletedSeq: Long? = null
    var terminalSettleJob: Job? = null
        private set

    val phase: TurnPhase get() = lifecycle.phase

    /**
     * The transport session carrying this turn is gone, so no server terminal
     * will ever arrive. Ends the turn through the same terminal path a server terminal takes: a
     * completion already waiting out its settle window is published as it stands, otherwise
     * [fallback] is. Never emits a second terminal. Reports what it did, then completes the turn.
     */
    suspend fun cutOff(fallback: RuntimeEventDraft, report: (TurnCutOffOutcome) -> Unit) {
        terminalSettleJob?.cancelAndJoin()
        terminalSettleJob = null
        report(publishCutOffTerminal(fallback))
        callbacks.complete()
    }

    private suspend fun publishCutOffTerminal(fallback: RuntimeEventDraft): TurnCutOffOutcome {
        if (phase == TurnPhase.Terminal) return TurnCutOffOutcome.AlreadyTerminal
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
     * A round's tail (usage_statistics, stop_reason) is closed once its
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
     * (`turn_finished`, or an idle loop status after evidence ). Its terminal
     * lifecycle completes the turn immediately, superseding any pending settle window, instead of
     * waiting out the quiet period the `stop_reason` delta fallback needs.
     */
    suspend fun process(incoming: RuntimeEventDraft, frameSeq: Long?, authoritative: Boolean = false) {
        // letta-mobile-by4vm.7: completing no longer throws, so nothing after the turn's terminal
        // (a later draft of the same frame, a frame already in flight) may be processed.
        val draft = stampWhileLive(incoming) ?: return
        flushClosedRoundTail(draft)
        if (emitAutoApproved(draft)) return
        callbacks.track(draft, ledger)
        observeContinuedActivity(draft)
        if (bufferTail(draft, frameSeq)) return
        if (authoritative && draft.isTerminalLifecycle()) {
            // An authoritative terminal supersedes a held completion: its window must not fire.
            dropPendingCompletion()
            emitTerminal(draft, frameSeq)
            return
        }
        if (draft.isCompletedLifecycle()) {
            pendingCompleted = draft
            hold(TurnEvent.CompletionHeld)
            return
        }
        if (draft.isTerminalLifecycle()) {
            emitTerminal(draft, frameSeq)
            return
        }
        callbacks.emit(draft)
    }

    private fun stampWhileLive(incoming: RuntimeEventDraft): RuntimeEventDraft? =
        if (isTurnOver()) null else identity.stampDraft(incoming)

    private fun isTurnOver(): Boolean = phase == TurnPhase.Terminal || callbacks.isComplete()

    private suspend fun emitAutoApproved(draft: RuntimeEventDraft): Boolean {
        val approved = callbacks.autoApprovedDraft(draft) ?: return false
        callbacks.track(approved, ledger)
        callbacks.emit(approved)
        return true
    }

    private fun observeContinuedActivity(draft: RuntimeEventDraft) {
        val continued = listOf(
            draft.isToolCallFrame(),
            draft.isToolReturnFrame(),
            draft.isAssistantFrame(),
        ).any { it }
        if (continued) {
            val step = lifecycle.fire(TurnEvent.ContinuedActivity)
            // The speculation was wrong: drop the held completion and its window.
            if (step?.from == TurnPhase.SpeculativeCompletionPending) dropPendingCompletion()
        }
        if (draft.isToolReturnFrame()) evidence = evidence.afterToolReturn()
        if (draft.isAssistantFrame()) evidence = evidence.afterAssistant()
    }

    private fun bufferTail(draft: RuntimeEventDraft, frameSeq: Long?): Boolean {
        if (!draft.isTailFrame()) return false
        tail.add(draft, closesRound = draft.isStopReasonFrame())
        if (draft.isUsageStatisticsFrame()) armSpeculativeCompletionAfterUsage(draft, frameSeq)
        return true
    }

    private fun armSpeculativeCompletionAfterUsage(draft: RuntimeEventDraft, frameSeq: Long?) {
        if (evidence != SpeculationEvidence.AnsweredAfterToolReturn) return
        if (pendingCompleted == null) {
            pendingCompleted = callbacks.completedDraft(draft.runId)
            pendingCompletedSeq = frameSeq
        }
        evidence = SpeculationEvidence.None
        hold(TurnEvent.SpeculativeCompletionHeld)
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
        lifecycle.fire(TurnEvent.TerminalPublished)
    }

    private fun dropPendingCompletion() {
        terminalSettleJob?.cancel()
        terminalSettleJob = null
        pendingCompleted = null
        pendingCompletedSeq = null
    }

    /** Holds a completion; the settle window starts when the turn enters a pending phase. */
    private fun hold(event: TurnEvent) {
        val step = lifecycle.fire(event) ?: return
        if (step.from == TurnPhase.Streaming) armSettleWindow()
    }

    private fun armSettleWindow() {
        terminalSettleJob = coroutineScope.launch {
            delay(callbacks.settleDelayMs.milliseconds)
            if (isTurnOver()) return@launch
            val terminal = pendingCompleted ?: return@launch
            flushTail()
            callbacks.noteCompleted(pendingCompletedSeq)
            emitMarkingTerminal(terminal)
            callbacks.complete()
        }
    }
}

/**
 * The buffered end-of-round frames, kept in arrival order (the App Server
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

/** How [TurnDraftProcessor.cutOff] ended a turn whose session was lost. */
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

/**
 * A frame that carries the turn on past a closed round tail: neither a tail frame nor a terminal,
 * nor an advisory status (a loop status or retry notice after a round's
 * stop_reason says nothing about another round starting).
 */
private fun RuntimeEventDraft.continuesTurn(): Boolean =
    !isTailFrame() && !isTerminalLifecycle() && !payload.isAdvisory

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

