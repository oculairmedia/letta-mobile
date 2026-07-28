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

private fun AppServerReceivedFrame.frameRunIdOrNull(): String? {
    val streamDelta = frame as? AppServerInboundFrame.StreamDelta ?: return null
    return runCatching {
        streamDelta.delta.jsonObject["run_id"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
