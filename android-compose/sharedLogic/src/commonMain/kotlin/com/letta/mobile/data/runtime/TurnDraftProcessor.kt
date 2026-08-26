package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private var pendingStop: RuntimeEventDraft? = null
    private var pendingUsage: RuntimeEventDraft? = null
    private var terminalArmed = false
    private var speculativeCompletionArmed = false
    private var sawToolReturn = false
    private var sawAssistantAfterToolReturn = false
    private var pendingCompletedSeq: Long? = null
    var terminalSettleJob: Job? = null
        private set

    suspend fun flushTail() {
        callbacks.clearApprovals()
        pendingStop?.let { callbacks.emit(it) }
        pendingStop = null
        pendingUsage?.let { callbacks.emit(it) }
        pendingUsage = null
    }

    suspend fun process(draft: RuntimeEventDraft, frameSeq: Long?) {
        if (emitAutoApproved(draft)) return
        callbacks.track(draft, ledger)
        observeContinuedActivity(draft)
        if (bufferTail(draft, frameSeq)) return
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
        if (draft.isStopReasonFrame()) {
            pendingStop = draft
            return true
        }
        if (!draft.isUsageStatisticsFrame()) return false
        if (pendingUsage == null) pendingUsage = draft
        armSpeculativeCompletionAfterUsage(draft, frameSeq)
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
        if (draft.isAbnormalTerminal()) {
            callbacks.settle(ledger, "Tool execution interrupted by turn termination")
        }
        flushTail()
        callbacks.recordTerminal(draft, frameSeq)
        callbacks.emit(draft)
        callbacks.complete()
    }

    private fun cancelSpeculativeCompletion() {
        if (!speculativeCompletionArmed) return
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
            callbacks.emit(terminal)
            callbacks.complete()
        }
    }
}

private val terminalStatuses = setOf(
    RuntimeRunStatus.Completed,
    RuntimeRunStatus.Failed,
    RuntimeRunStatus.Cancelled,
)
private val abnormalTerminalStatuses = setOf(RuntimeRunStatus.Failed, RuntimeRunStatus.Cancelled)
private val toolReturnTypes = setOf("client_tool_end", "tool_return_message")
private val toolCallTypes = setOf("client_tool_start", "tool_call_message")

private fun RuntimeEventDraft.isTerminalLifecycle(): Boolean =
    (payload as? RuntimeEventPayload.RunLifecycleChanged)?.status in terminalStatuses

private fun RuntimeEventDraft.isCompletedLifecycle(): Boolean =
    (payload as? RuntimeEventPayload.RunLifecycleChanged)?.status == RuntimeRunStatus.Completed

private fun RuntimeEventDraft.isAbnormalTerminal(): Boolean =
    (payload as? RuntimeEventPayload.RunLifecycleChanged)?.status in abnormalTerminalStatuses

private fun RuntimeEventDraft.isToolReturnFrame(): Boolean = when (val event = payload) {
    is RuntimeEventPayload.ToolReturnObserved -> true
    is RuntimeEventPayload.RemoteStreamFrame -> event.matchesAnyType(toolReturnTypes)
    else -> false
}

private fun RuntimeEventDraft.isAssistantFrame(): Boolean = when (val event = payload) {
    is RuntimeEventPayload.RemoteStreamFrame -> event.matchesAnyType(setOf("assistant_message"))
    else -> false
}

private fun RuntimeEventDraft.isToolCallFrame(): Boolean = when (val event = payload) {
    is RuntimeEventPayload.ToolCallObserved -> true
    is RuntimeEventPayload.ApprovalRequested -> true
    is RuntimeEventPayload.RemoteStreamFrame -> event.matchesAnyType(toolCallTypes)
    else -> false
}

private fun RuntimeEventDraft.isUsageStatisticsFrame(): Boolean = when (val event = payload) {
    is RuntimeEventPayload.RemoteStreamFrame -> event.matchesAnyType(setOf("usage_statistics"))
    is RuntimeEventPayload.ExternalTransportFrame -> listOf(
        event.body.startsWith("usage:"),
        frameMessageType(event.body) == "usage_statistics",
    ).any { it }
    else -> false
}

private fun RuntimeEventDraft.isStopReasonFrame(): Boolean = when (val event = payload) {
    is RuntimeEventPayload.RemoteStreamFrame -> event.matchesAnyType(setOf("stop_reason"))
    is RuntimeEventPayload.ExternalTransportFrame -> frameMessageType(event.body) == "stop_reason"
    else -> false
}

private fun RuntimeEventPayload.RemoteStreamFrame.matchesAnyType(types: Set<String>): Boolean =
    listOf(messageType, frameMessageType(body)).any(types::contains)

private fun frameMessageType(body: String): String? = runCatching {
    val raw = AppServerProtocol.json.parseToJsonElement(body).jsonObject
    val delta = raw["delta"]?.jsonObject ?: raw
    delta["message_type"]?.jsonPrimitive?.content
}.getOrNull()
