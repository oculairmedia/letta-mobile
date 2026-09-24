package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRequestFailedException
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic

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

    /** `accepted=false`: the input will never run. */
    data class Rejected(val error: String) : InputAcceptance

    /**
     * The transport lost the connection generation before the ack arrived. The
     * input may or may not have reached the server, but no frame for it can reach
     * this lease any more.
     */
    data class ConnectionLost(val error: String) : InputAcceptance

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
 * returns the failure reason when the input will never run, else null.
 */
internal fun InputAcceptance.recordAndFailureReason(conversationId: String): String? = when (this) {
    InputAcceptance.Started -> recordAccepted(conversationId, "started")
    InputAcceptance.Queued -> recordAccepted(conversationId, "queued")
    is InputAcceptance.Rejected -> recordRejected(conversationId, error, "rejected")
    is InputAcceptance.ConnectionLost -> recordRejected(conversationId, INPUT_CONNECTION_LOST_REASON, "connection_lost")
    is InputAcceptance.Unacknowledged -> {
        Telemetry.event(
            TELEMETRY_TAG, "input.unacknowledged",
            "conversationId" to conversationId,
            "reason" to reason,
        )
        null
    }
}

private fun recordAccepted(conversationId: String, disposition: String): String? {
    Telemetry.event(
        TELEMETRY_TAG, "input.accepted",
        "conversationId" to conversationId,
        "disposition" to disposition,
    )
    return null
}

private fun recordRejected(conversationId: String, error: String, kind: String): String {
    Telemetry.event(
        TELEMETRY_TAG, "input.rejected",
        "conversationId" to conversationId,
        "error" to error,
        "kind" to kind,
        level = Telemetry.Level.WARN,
    )
    return error
}

private const val TELEMETRY_TAG = "AppServerTurnEngine"

/** The visible "queued" lifecycle: Running with a reason, which every chat surface already renders. */
internal fun TurnCommand.queuedInputDraft(): RuntimeEventDraft =
    RuntimeEventDraft(
        backendId = backendId,
        runtimeId = runtimeId,
        agentId = agentId,
        conversationId = conversationId,
        source = RuntimeEventSource.LocalRuntime,
        payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Running, reason = INPUT_QUEUED_REASON),
    )

internal fun TurnCommand.startedDraft(): RuntimeEventDraft =
    RuntimeEventDraft(
        backendId = backendId,
        runtimeId = runtimeId,
        agentId = agentId,
        conversationId = conversationId,
        source = RuntimeEventSource.LocalRuntime,
        payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Started),
    )

internal fun TurnCommand.completedDraft(runId: RunId?): RuntimeEventDraft =
    RuntimeEventDraft(
        backendId = backendId,
        runtimeId = runtimeId,
        agentId = agentId,
        conversationId = conversationId,
        runId = runId,
        source = RuntimeEventSource.LocalRuntime,
        payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Completed),
    )

internal fun TurnCommand.failedDraft(reason: String): RuntimeEventDraft =
    RuntimeEventDraft(
        backendId = backendId,
        runtimeId = runtimeId,
        agentId = agentId,
        conversationId = conversationId,
        source = RuntimeEventSource.LocalRuntime,
        payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Failed, reason = reason),
    )

internal fun TurnCommand.cancelledDraft(reason: String): RuntimeEventDraft =
    RuntimeEventDraft(
        backendId = backendId,
        runtimeId = runtimeId,
        agentId = agentId,
        conversationId = conversationId,
        source = RuntimeEventSource.LocalRuntime,
        payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Cancelled, reason = reason),
    )

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
 * ahead finishes. The pause lifts on the first evidence the input started: a
 * `stream_delta` for the runtime or an `update_queue` removal with disposition
 * `dequeued` for [clientMessageId]. A `cancelled` removal means the input will
 * never run, and the engine settles the lease Cancelled.
 *
 * Started evidence can race ahead of the ack (the collector processes frames while
 * the send coroutine is still resuming from `input_accepted`), so [markQueued] is
 * a no-op once the input is known to have started.
 */
internal class QueuedInputTracker(val clientMessageId: String?) {
    private val state = atomic(PENDING)

    val isQueued: Boolean get() = state.value == QUEUED

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

internal suspend fun QueuedInputTracker.enterQueued(
    command: TurnCommand,
    lease: LeaseRef,
    emit: suspend (RuntimeEventDraft) -> Unit,
) {
    if (!markQueued()) return
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
        "clientMessageId" to (clientMessageId ?: "<none>"),
    )
    emit(command.queuedInputDraft())
}

internal fun leaveQueued(lease: LeaseRef, source: String) {
    lease.slot.updateLease { cur ->
        if (cur?.token == lease.token && cur.phase == TurnLeasePhase.Queued) {
            cur.copy(phase = TurnLeasePhase.Streaming)
        } else {
            cur
        }
    }
    Telemetry.event(
        "AppServerTurnEngine", "turn.dequeued",
        "key" to lease.key.toString(),
        "leaseToken" to lease.token,
        "source" to source,
    )
}

internal fun QueuedInputTracker.observeQueueProgress(
    received: AppServerReceivedFrame,
    lease: LeaseRef,
): QueueRemovalDisposition? {
    val frame = received.frame
    val removal = (frame as? AppServerInboundFrame.UpdateQueue)?.let(::removalIn)
    val startedBy = when {
        frame is AppServerInboundFrame.StreamDelta -> "stream_delta"
        removal == QueueRemovalDisposition.Dequeued -> "update_queue"
        else -> null
    }
    if (startedBy != null && markStarted()) leaveQueued(lease, startedBy)
    return removal
}
