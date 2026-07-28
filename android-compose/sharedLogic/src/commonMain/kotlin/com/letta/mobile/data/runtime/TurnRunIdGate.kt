package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.update
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tracks mid-turn run-id promotion so late frames from a superseded run cannot
 * mutate the active lease (lgns8.22.4). Extracted from [AppServerTurnEngine] to
 * keep that hotspot's cohesion from degrading further under fanout wiring.
 */
internal class TurnRunIdGate(
    private val activeLeaseRef: AtomicRef<TurnLease?>,
    private val activeTurnOwnerRef: AtomicRef<AppServerTurnEngine.ActiveTurnOwner?>,
) {
    private val supersededRunIds = mutableSetOf<String>()
    private var activeLeaseToken: Long? = null

    /** Clear superseded IDs when a new turn lease begins. */
    fun beginLease(leaseToken: Long) {
        if (activeLeaseToken != leaseToken) {
            supersededRunIds.clear()
            activeLeaseToken = leaseToken
        }
    }

    fun accepts(received: AppServerReceivedFrame, leaseToken: Long): Boolean {
        val lease = activeLeaseRef.value ?: return true
        if (lease.token != leaseToken) return false
        val frameRunId = received.frameRunIdOrNull() ?: return true
        return frameRunId !in supersededRunIds
    }

    fun promote(runId: String, leaseToken: Long) {
        if (activeLeaseRef.value?.token != leaseToken) return
        val current = activeTurnOwnerRef.value ?: return
        if (current.runId == runId) return
        current.runId?.takeIf { it.isNotBlank() }?.let { supersededRunIds.add(it) }
        activeTurnOwnerRef.value = current.copy(runId = runId)
        activeLeaseRef.update { lease -> lease.withPromotedRunId(runId, leaseToken) }
    }
}

private fun TurnLease?.withPromotedRunId(runId: String, leaseToken: Long): TurnLease? {
    val lease = this ?: return null
    if (lease.token != leaseToken) return lease
    if (lease.runId == runId) return lease
    return lease.copy(runId = runId)
}

internal fun AppServerReceivedFrame.frameRunIdOrNull(): String? {
    val streamDelta = frame as? AppServerInboundFrame.StreamDelta ?: return null
    return runCatching {
        streamDelta.delta.jsonObject["run_id"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

internal fun AppServerReceivedFrame.terminalMessageTypeOrNull(): String? {
    val streamDelta = frame as? AppServerInboundFrame.StreamDelta ?: return null
    return runCatching {
        streamDelta.delta.jsonObject["message_type"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}

internal fun AppServerReceivedFrame.stopReasonOrNull(): String? {
    val streamDelta = frame as? AppServerInboundFrame.StreamDelta ?: return null
    return runCatching {
        val delta = streamDelta.delta.jsonObject
        delta["stop_reason"]?.jsonPrimitive?.contentOrNull
            ?: delta["reason"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}

internal fun AppServerReceivedFrame.carriesLifecycleTerminal(): Boolean {
    val messageType = terminalMessageTypeOrNull() ?: return false
    return messageType == "stop_reason" ||
        messageType == "error_message" ||
        messageType == "loop_error"
}

internal fun AppServerReceivedFrame.lifecycleStatusFromTerminal(): com.letta.mobile.runtime.RuntimeRunStatus? {
    if (!carriesLifecycleTerminal()) return null
    return when (terminalMessageTypeOrNull()) {
        "error_message", "loop_error" -> com.letta.mobile.runtime.RuntimeRunStatus.Failed
        "stop_reason" -> when (stopReasonOrNull()) {
            "cancelled" -> com.letta.mobile.runtime.RuntimeRunStatus.Cancelled
            "error" -> com.letta.mobile.runtime.RuntimeRunStatus.Failed
            else -> com.letta.mobile.runtime.RuntimeRunStatus.Completed
        }
        else -> null
    }
}
