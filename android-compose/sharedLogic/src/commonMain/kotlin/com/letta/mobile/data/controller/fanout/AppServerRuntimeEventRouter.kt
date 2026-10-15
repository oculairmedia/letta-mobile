package com.letta.mobile.data.controller.fanout

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Controller-owned inbound router (lgns8.22.3).
 *
 * Subscribes to [AppServerClient.events] exactly once per attachment and fans
 * full [AppServerReceivedFrame] values (channel + raw JSON preserved) to
 * per-runtime subscribers via [RuntimeEventFanout].
 */
class AppServerRuntimeEventRouter(
    private val inboundControlRegistry: InboundControlRequestRegistry = InboundControlRequestRegistry(),
    val connectionGenerationProvider: () -> Long = { 0L },
    /** letta-mobile-qygvv.5: decisions sent by this client, shared with the turn engine. */
    private val approvalDecisionCache: ApprovalDecisionCache = ApprovalDecisionCache(),
    private val fanout: RuntimeEventFanout = RuntimeEventFanout(
        inboundControlRegistry = inboundControlRegistry,
        connectionGenerationProvider = connectionGenerationProvider,
    ),
) {
    private val collectorJob = atomic<Job?>(null)
    private val attachLock = SynchronizedObject()
    private val integrity = atomic<StreamIntegrityMonitor?>(null)

    init {
        // letta-mobile-by4vm.4: a subscriber the fanout detached for falling behind lost frames;
        // resync the watched runtimes so what is on screen catches up.
        fanout.bindSubscriberOverflowListener { requestResync() }
    }

    /**
     * letta-mobile-bzvro.6: every inbound frame is shown to [monitor] (gap detection, sync cadence)
     * before it is fanned out. One monitor per router; binding again replaces it.
     */
    fun bindStreamIntegrity(monitor: StreamIntegrityMonitor) {
        integrity.value = monitor
    }

    /** letta-mobile-by4vm.95: the connection the monitor watched is gone; frames go unobserved until the next bind. */
    fun unbindStreamIntegrity() {
        integrity.value = null
    }

    /** letta-mobile-bzvro.6: ask the bound monitor to resync what is on screen; no-op without one. */
    fun requestResync() {
        integrity.value?.requestResync()
    }

    /** The runtimes someone is watching right now: what a resync should cover. */
    fun watchedRuntimes(): List<AppServerRuntimeScope> = fanout.watchedRuntimes()

    fun inboundControlRegistry(): InboundControlRequestRegistry = inboundControlRegistry

    fun approvalDecisionCache(): ApprovalDecisionCache = approvalDecisionCache

    /** letta-mobile-qygvv.5: see [RuntimeEventFanout.bindApprovalReplayResponder]. */
    fun bindApprovalReplayResponder(responder: (AppServerInboundFrame.ControlRequest) -> Boolean) {
        fanout.bindApprovalReplayResponder(responder)
    }

    /**
     * Attach a sole collector. No-op when an active collector is already running
     * against the same stable inbound pipe (reconnect must not tear it down —
     * SharedFlow has zero replay and frames in the cancel→resubscribe gap are lost).
     *
     * Uses [CoroutineStart.UNDISPATCHED] so `inbound.collect` subscribes before
     * this method returns, closing the race where a turn starts before the
     * collector is listening. Concurrent attach is serialized on [attachLock] so
     * two callers cannot both subscribe before either wins the collector slot
     * (which would duplicate zero-replay frames).
     */
    fun attach(scope: CoroutineScope, inbound: Flow<AppServerReceivedFrame>) {
        synchronized(attachLock) {
            val existing = collectorJob.value
            if (existing != null && existing.isActive) return
            val newJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                inbound.collect { received ->
                    integrity.value?.observe(received)
                    fanout.route(received)
                }
            }
            collectorJob.value = newJob
        }
    }

    private val sessionLinkState = RuntimeSessionLinkState()

    /** letta-mobile-by4vm.9: whether the session behind this router is carrying frames right now. */
    val sessionLink: StateFlow<RuntimeSessionLink> = sessionLinkState.link

    /**
     * letta-mobile-by4vm.9: the socket under a stable inbound pipe dropped and its owner is
     * redialling. Unlike [detach], nothing is closed: the collector and every subscriber stay, so
     * frames from the successor socket reach the same turns. Turns fail only if the session is not
     * restored within their grace window.
     */
    fun interruptSession(reason: String?) = sessionLinkState.interrupt(reason)

    /**
     * letta-mobile-by4vm.9: the successor socket is attached. Ends an interruption and resyncs the
     * watched runtimes so what streamed during the gap is recovered; a no-op while linked.
     */
    fun restoreSession() {
        if (sessionLinkState.restore()) requestResync()
    }

    /** Permanent teardown: every subscriber's turn ends now (see [RuntimeEventFanout.closeAllSubscribersSync]). */
    fun detach() {
        collectorJob.getAndSet(null)?.cancel()
        endSubscribedTurns()
    }

    /**
     * Every subscriber's turn ends now, as on [detach], but the collector stays attached for the
     * next connection behind a stable inbound pipe (letta-mobile-by4vm.95: an Android Iroh dial
     * ended). Closing subscriber channels unblocks turn collectors waiting on fanout (otherwise
     * they sit until idle watchdog / forever under a user-input gate).
     */
    fun endSubscribedTurns() {
        fanout.closeAllSubscribersSync()
    }

    fun isAttached(): Boolean = collectorJob.value?.isActive == true

    fun subscribe(
        agentId: AgentId,
        conversationId: ConversationId,
        subscriberId: String? = null,
    ): Pair<String, Flow<AppServerReceivedFrame>> =
        if (subscriberId == null) {
            fanout.subscribe(agentId, conversationId)
        } else {
            fanout.subscribe(agentId, conversationId, subscriberId)
        }

    /** Passive probe subscription; see [RuntimeEventFanout.observe]. */
    fun observe(
        agentId: AgentId,
        conversationId: ConversationId,
    ): Pair<String, Flow<AppServerReceivedFrame>> = fanout.observe(agentId, conversationId)

    fun unsubscribe(subscriberId: String): Boolean = fanout.unsubscribe(subscriberId)

    fun subscriberCount(): Int = fanout.subscriberCount()

    fun turnLockCount(): Int = fanout.turnLockCount()
}
