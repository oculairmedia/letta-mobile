package com.letta.mobile.data.controller.fanout

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.ConversationId
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Controller-owned inbound router (lgns8.22.3).
 *
 * Subscribes to [AppServerClient.events] exactly once per attachment and fans
 * full [AppServerReceivedFrame] values (channel + raw JSON preserved) to
 * per-runtime subscribers via [RuntimeEventFanout].
 */
class AppServerRuntimeEventRouter(
    private val fanout: RuntimeEventFanout = RuntimeEventFanout(),
) {
    private val collectorJob = atomic<Job?>(null)

    /** Re-attach when the upstream events flow changes (transport reconnect). */
    /**
     * Attach a sole collector. No-op when an active collector is already running
     * against the same stable inbound pipe (reconnect must not tear it down —
     * SharedFlow has zero replay and frames in the cancel→resubscribe gap are lost).
     */
    fun attach(scope: CoroutineScope, inbound: Flow<AppServerReceivedFrame>) {
        val existing = collectorJob.value
        if (existing != null && existing.isActive) return
        collectorJob.value?.cancel()
        collectorJob.value = scope.launch {
            inbound.collect { received -> fanout.route(received) }
        }
    }

    fun detach() {
        collectorJob.getAndSet(null)?.cancel()
    }

    fun isAttached(): Boolean = collectorJob.value?.isActive == true

    suspend fun subscribe(
        agentId: AgentId,
        conversationId: ConversationId,
        subscriberId: String? = null,
    ): Pair<String, Flow<AppServerReceivedFrame>> =
        if (subscriberId == null) {
            fanout.subscribe(agentId, conversationId)
        } else {
            fanout.subscribe(agentId, conversationId, subscriberId)
        }

    suspend fun unsubscribe(subscriberId: String): Boolean = fanout.unsubscribe(subscriberId)

    suspend fun subscriberCount(): Int = fanout.subscriberCount()

    suspend fun turnLockCount(): Int = fanout.turnLockCount()
}
