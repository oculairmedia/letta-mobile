package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputMessage
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRequestFailedException
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import com.letta.mobile.runtime.ToolPolicy
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonPrimitive

/**
 * letta-mobile-qygvv.1: what the App Server said about one `create_message` input.
 *
 * The server acknowledges an `input` with `input_accepted` only when it carried a
 * `request_id`. Without the ack the engine could not tell a queued input (the
 * runtime is still streaming, has a pending approval, …) or a rejected one
 * ("Runtime is no longer active") from a turn that is simply slow, so both hung
 * silently until the 300s idle watchdog fired.
 */
internal sealed interface InputAcceptance {
    /** The input started a turn right away. */
    data object Started : InputAcceptance

    /** The input was queued behind an active turn or a pending approval. */
    data object Queued : InputAcceptance

    /** The input will never run; [failureReason] is what the turn fails with. */
    sealed interface Failure : InputAcceptance {
        val failureReason: String
        val kind: String
    }

    /** `accepted=false`: the input will never run. */
    data class Rejected(val error: String) : Failure {
        override val failureReason: String get() = error
        override val kind: String get() = "rejected"
    }

    /**
     * The transport lost the connection generation before the ack arrived. The
     * input may or may not have reached the server, but no frame for it can reach
     * this lease any more.
     */
    data class ConnectionLost(val error: String) : Failure {
        override val failureReason: String get() = INPUT_CONNECTION_LOST_REASON
        override val kind: String get() = "connection_lost"
    }

    /**
     * No ack to act on: the client cannot correlate `input_accepted` (a fake or a
     * pre-0.32 bridge) or the ack timed out. The turn proceeds exactly as it did
     * before acknowledgement existed — the idle watchdog stays the backstop.
     */
    data class Unacknowledged(val reason: String) : InputAcceptance
}

internal const val INPUT_REJECTED_FALLBACK_ERROR = "Input was rejected by the App Server"
internal const val INPUT_QUEUED_REASON = "Queued behind an active turn or pending approval"
internal const val INPUT_CONNECTION_LOST_REASON = "App Server connection lost before the input was accepted"
internal const val QUEUED_INPUT_CANCELLED_REASON = "Queued input was cancelled by the App Server"

/**
 * Sends [command] with [requestId] and waits for its `input_accepted`.
 *
 * Falls back to fire-and-forget [AppServerClient.input] of the ORIGINAL command
 * (no `request_id`) when the client does not support acknowledgement, so clients
 * and fakes that only implement `input` keep their exact wire shape.
 */
internal suspend fun AppServerClient.sendInputAwaitingAcceptance(
    command: AppServerCommand.Input,
    requestId: String,
): InputAcceptance {
    val ack = try {
        inputAwaitingAcceptance(command.copy(requestId = requestId))
    } catch (unsupported: UnsupportedOperationException) {
        input(command)
        return InputAcceptance.Unacknowledged("unsupported")
    } catch (timeout: AppServerRequestTimeoutException) {
        // The send already happened; only the ack is missing (e.g. a pre-0.32
        // server that ignores request_id). Do not fail a turn that may be running.
        return InputAcceptance.Unacknowledged("ack_timeout")
    } catch (failed: AppServerRequestFailedException) {
        return InputAcceptance.ConnectionLost(
            failed.cause?.message ?: failed.message ?: "App Server connection lost",
        )
    }
    return ack.toInputAcceptance()
}

internal fun AppServerInboundFrame.InputAccepted.toInputAcceptance(): InputAcceptance = when {
    !accepted -> InputAcceptance.Rejected(error?.takeIf { it.isNotBlank() } ?: INPUT_REJECTED_FALLBACK_ERROR)
    queued -> InputAcceptance.Queued
    // "started", or an absent/unknown disposition on an accepted input: treat as
    // running so the watchdog keeps guarding it.
    else -> InputAcceptance.Started
}

/**
 * Records `input.accepted` / `input.rejected` / `input.unacknowledged` telemetry and
 * returns the failure when the input will never run, else null.
 */
internal fun InputAcceptance.recordAndFailure(conversationId: ConversationId): InputAcceptance.Failure? = when (this) {
    InputAcceptance.Started -> recordAccepted(conversationId, "started")
    InputAcceptance.Queued -> recordAccepted(conversationId, "queued")
    is InputAcceptance.Failure -> recordRejected(conversationId, this)
    is InputAcceptance.Unacknowledged -> {
        Telemetry.event(
            TELEMETRY_TAG, "input.unacknowledged",
            "conversationId" to conversationId.value,
            "reason" to reason,
        )
        null
    }
}

private fun recordAccepted(conversationId: ConversationId, disposition: String): InputAcceptance.Failure? {
    Telemetry.event(
        TELEMETRY_TAG, "input.accepted",
        "conversationId" to conversationId.value,
        "disposition" to disposition,
    )
    return null
}

private fun recordRejected(
    conversationId: ConversationId,
    failure: InputAcceptance.Failure,
): InputAcceptance.Failure {
    Telemetry.event(
        TELEMETRY_TAG, "input.rejected",
        "conversationId" to conversationId.value,
        "error" to failure.failureReason,
        "kind" to failure.kind,
        level = Telemetry.Level.WARN,
    )
    return failure
}

private const val TELEMETRY_TAG = "AppServerTurnEngine"

private fun TurnCommand.lifecycleDraft(
    status: RuntimeRunStatus,
    reason: String? = null,
    runId: RunId? = null,
): RuntimeEventDraft =
    RuntimeEventDraft(
        backendId = backendId,
        runtimeId = runtimeId,
        agentId = agentId,
        conversationId = conversationId,
        runId = runId,
        source = RuntimeEventSource.LocalRuntime,
        payload = RuntimeEventPayload.RunLifecycleChanged(status, reason = reason),
    )

/** The visible "queued" lifecycle: Running with a reason, which every chat surface already renders. */
internal fun TurnCommand.queuedInputDraft(): RuntimeEventDraft =
    lifecycleDraft(RuntimeRunStatus.Running, reason = INPUT_QUEUED_REASON)

/** Upstream `QueueRemovalTransition.disposition`. */
internal enum class QueueRemovalDisposition {
    /** Taken off the queue to START a turn. */
    Dequeued,

    /** Dropped without running (abort pause purge, WS failover cleanup, remove_queue_item). */
    Cancelled,
}

/**
 * letta-mobile-qygvv.1: per-turn view of whether THIS lease's input is waiting in
 * the server queue.
 *
 * While queued the idle watchdog is paused — silence is expected until the turn
 * ahead finishes. The pause lifts on the only evidence that names THIS input: an
 * `update_queue` removal with disposition `dequeued` for [clientMessageId]. A
 * `stream_delta` is not evidence; it may belong to the turn ahead (see
 * [QueuedLeaseFrameGate]). A `cancelled` removal means the input will never run,
 * and the engine settles the lease Cancelled.
 *
 * The dequeue can race ahead of the ack (the collector processes frames while the
 * send coroutine is still resuming from `input_accepted`), so [markQueued] is a
 * no-op once the input is known to have started.
 */
internal class QueuedInputTracker(val clientMessageId: String?) {
    private val state = atomic(PENDING)

    val isQueued: Boolean get() = state.value == QUEUED

    fun isWatchdogPaused(isConnectionSuperseded: Boolean): Boolean =
        isQueued && !isConnectionSuperseded

    /** Returns true when the lease actually entered the queued state. */
    fun markQueued(): Boolean = state.compareAndSet(PENDING, QUEUED)

    /** Returns true when this call ended a queued wait. */
    fun markStarted(): Boolean {
        while (true) {
            val current = state.value
            if (current == STARTED) return false
            if (state.compareAndSet(current, STARTED)) return current == QUEUED
        }
    }

    /** The removal transition for [clientMessageId] in [frame], if any. */
    fun removalIn(frame: AppServerInboundFrame.UpdateQueue): QueueRemovalDisposition? {
        val id = clientMessageId ?: return null
        val transition = frame.removed.lastOrNull { it.clientMessageId == id } ?: return null
        return when (transition.disposition) {
            "dequeued" -> QueueRemovalDisposition.Dequeued
            "cancelled" -> QueueRemovalDisposition.Cancelled
            else -> null
        }
    }

    private companion object {
        const val PENDING = 0
        const val QUEUED = 1
        const val STARTED = 2
    }
}

internal fun TurnCommand.startedDraft(): RuntimeEventDraft =
    lifecycleDraft(RuntimeRunStatus.Started)

internal fun TurnCommand.completedDraft(runId: RunId?): RuntimeEventDraft =
    lifecycleDraft(RuntimeRunStatus.Completed, runId = runId)

internal fun TurnCommand.failedDraft(reason: String): RuntimeEventDraft =
    lifecycleDraft(RuntimeRunStatus.Failed, reason = reason)

internal fun TurnCommand.cancelledDraft(reason: String): RuntimeEventDraft =
    lifecycleDraft(RuntimeRunStatus.Cancelled, reason = reason)

internal suspend fun enterQueued(
    command: TurnCommand,
    lease: LeaseRef,
    emit: suspend (RuntimeEventDraft) -> Unit,
) {
    if (!lease.queuedInput.markQueued()) return
    lease.slot.updateLease { cur ->
        if (cur?.token == lease.token && cur.phase == TurnLeasePhase.Streaming) {
            cur.copy(phase = TurnLeasePhase.Queued)
        } else {
            cur
        }
    }
    Telemetry.event(
        "AppServerTurnEngine", "turn.queued",
        "key" to lease.key.toString(),
        "leaseToken" to lease.token,
        "clientMessageId" to (lease.queuedInput.clientMessageId ?: "<none>"),
    )
    emit(command.queuedInputDraft())
}

internal fun leaveQueued(lease: LeaseRef, source: String) {
    var left = false
    lease.slot.updateLease { cur ->
        if (cur?.token == lease.token && cur.phase == TurnLeasePhase.Queued) {
            left = true
            // Every run-bearing frame was skipped while queued, so a run id promoted
            // here came from the turn ahead (frames that raced the queued ack).
            cur.copy(phase = TurnLeasePhase.Streaming, runId = null)
        } else {
            cur
        }
    }
    if (left) lease.slot.updateOwner { owner -> owner?.copy(runId = null) }
    recordDequeued(lease, source)
}

internal fun recordDequeued(lease: LeaseRef, source: String) {
    Telemetry.event(
        "AppServerTurnEngine", "turn.dequeued",
        "key" to lease.key.toString(),
        "leaseToken" to lease.token,
        "source" to source,
    )
}

/**
 * letta-mobile-qygvv.1: tracks a queued input through `update_queue`. Returns the
 * removal disposition for this lease's input when [received] carries one.
 *
 * Only the `dequeued` removal of THIS input's client message id ends the queued
 * wait. A `stream_delta` is not start evidence: while this input is pending or
 * queued it may belong to the turn ahead (review of PR #1661). Servers that
 * answer `queued` (0.32+) also send `update_queue.removed`.
 */
internal fun observeQueueProgress(
    received: AppServerReceivedFrame,
    lease: LeaseRef,
): QueueRemovalDisposition? {
    val removal = (received.frame as? AppServerInboundFrame.UpdateQueue)?.let(lease.queuedInput::removalIn)
    if (removal == QueueRemovalDisposition.Dequeued && lease.queuedInput.markStarted()) {
        leaveQueued(lease, "update_queue")
    }
    return removal
}

internal class TurnInputSender(
    private val client: AppServerClient,
    private val requestIdFactory: () -> String,
    private val externalToolRegistry: ExternalToolRegistry? = null,
) {
    suspend fun sendInput(
        command: TurnCommand,
        scope: AppServerRuntimeScope,
        lease: LeaseRef,
        emit: suspend (RuntimeEventDraft) -> Unit,
    ): InputAcceptance.Failure? {
        val input = command.toInputCommand(scope, externalToolRegistry)
        if (command.input !is TurnInput.UserMessage) {
            client.input(input)
            return null
        }
        val acceptance = client.sendInputAwaitingAcceptance(input, requestIdFactory())
        val failure = acceptance.recordAndFailure(command.conversationId)
        if (acceptance == InputAcceptance.Queued) enterQueued(command, lease, emit)
        return failure
    }

    /**
     * letta-mobile-qygvv.3: [sendInput], abandoned when [collector] ends first. A turn the
     * collector already settled (terminal, watchdog, superseded generation) must not stay
     * parked waiting on an `input_accepted` that will never arrive.
     */
    suspend fun sendInputUntilCollectorEnds(
        command: TurnCommand,
        scope: AppServerRuntimeScope,
        lease: LeaseRef,
        collector: Job,
        emit: suspend (RuntimeEventDraft) -> Unit,
    ): InputAcceptance.Failure? = coroutineScope {
        val ack = async { sendInput(command, scope, lease, emit) }
        select<InputAcceptance.Failure?> {
            ack.onAwait { it }
            collector.onJoin {
                ack.cancel()
                null
            }
        }
    }
}

internal fun TurnCommand.toInputCommand(
    scope: AppServerRuntimeScope,
    externalToolRegistry: ExternalToolRegistry?,
): AppServerCommand.Input =
    when (val turnInput = input) {
        is TurnInput.UserMessage -> AppServerCommand.Input(
            runtime = scope,
            payload = AppServerInputPayload.CreateMessage(
                messages = listOf(
                    AppServerInputMessage(
                        role = "user",
                        content = turnInput.contentPartsJson
                            ?.let { AppServerProtocol.json.parseToJsonElement(it) }
                            ?: JsonPrimitive(turnInput.text),
                        clientMessageId = turnInput.localMessageId,
                    ),
                ),
                clientToolAllowlist = toolPolicy.toWireAllowlist(externalToolRegistry),
            ),
        )
        is TurnInput.ToolApprovalResponse -> AppServerCommand.Input(
            runtime = scope,
            payload = AppServerInputPayload.ApprovalResponse(
                requestId = turnInput.decision.approvalId.value,
                decision = when (turnInput.decision.decision) {
                    ToolApprovalDecisionValue.Approved -> {
                        AppServerApprovalResponseDecision.Allow(
                            message = turnInput.decision.response,
                        )
                    }
                    ToolApprovalDecisionValue.Denied,
                    ToolApprovalDecisionValue.TimedOut,
                    -> AppServerApprovalResponseDecision.Deny(
                        message = turnInput.decision.response ?: "Denied by mobile client.",
                    )
                },
            ),
        )
    }

internal fun ToolPolicy.toWireAllowlist(registry: ExternalToolRegistry?): List<String>? {
    if (allowedTools.isEmpty()) return null
    return allowedTools
        .map { it.value }
        .plus(registry?.listAdvertisedTools().orEmpty().map { it.name })
        .distinct()
        .sorted()
}

internal suspend fun joinCollectorOrHandleFailure(
    collector: Job,
    inputFailure: InputAcceptance.Failure?,
    onFailure: suspend (String) -> Unit,
): String {
    if (inputFailure != null) {
        // letta-mobile-qygvv.1: the server will never run this input — fail
        // now instead of waiting out the idle watchdog.
        collector.cancelAndJoin()
        onFailure(inputFailure.failureReason)
        return "input_rejected"
    }
    collector.join()
    return "normal_completion"
}

/**
 * A turn ended from inside the frame loop without a server terminal: the
 * connection generation rolled, or the server dropped this lease's queued input.
 */
internal enum class AbruptTurnEnding(
    val status: RuntimeRunStatus,
    val reason: String,
    /** Set when the ending is also recorded as the owner's terminal. */
    val ownerTerminalSource: String?,
) {
    GenerationSuperseded(RuntimeRunStatus.Failed, "Connection generation superseded during turn", null),
    QueuedInputCancelled(RuntimeRunStatus.Cancelled, QUEUED_INPUT_CANCELLED_REASON, "update_queue_cancelled"),
    ;

    fun draftFor(command: TurnCommand): RuntimeEventDraft = command.lifecycleDraft(status, reason = reason)
}
