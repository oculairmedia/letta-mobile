package com.letta.mobile.data.controller.fanout

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.ConversationId
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Fanout layer for routing App Server runtime events to multiple UI clients.
 *
 * PROBLEM:
 * The App Server process enforces ONE control client per process, but we need
 * to support MULTIPLE UI clients (mobile + desktop + Matrix) consuming events
 * from DIFFERENT runtimes running in the SAME App Server process.
 *
 * SOLUTION:
 * This fanout sits between the single AppServerController and multiple UI subscribers.
 * It:
 * 1. Routes events (stream_delta, update_loop_status, update_queue, update_device_status,
 *    update_subagent_state) to ONLY the subscribers for that specific runtime
 *    (identified by agent_id + conversation_id)
 * 2. Enforces per-runtime turn locks: allows parallel work across DIFFERENT runtimes
 *    but serializes turns on the SAME runtime (queues second turn until first completes)
 * 3. Manages subscribe/unsubscribe lifecycle (multiple subscribers per runtime)
 *
 * USAGE:
 * ```
 * val fanout = RuntimeEventFanout()
 *
 * // Subscribe to a runtime
 * val events: Flow<AppServerReceivedFrame> = fanout.subscribe(agentId, conversationId)
 *
 * // Feed events from the controller into the fanout
 * controllerClient.events.collect { received ->
 *     fanout.route(received)
 * }
 *
 * // Unsubscribe when done
 * fanout.unsubscribe(subscriberId)
 * ```
 *
 * THREAD-SAFETY:
 * All public methods are thread-safe and can be called from multiple coroutines.
 */
class RuntimeEventFanout(
    /**
     * lgns8.22.4: correlates server→client control / external-tool requests so
     * the same request_id is not rebroadcast after the first delivery.
     */
    private val inboundControlRegistry: InboundControlRequestRegistry = InboundControlRequestRegistry(),
    /**
     * Connection generation stamped onto inbound control registrations. The
     * controller bumps this on disconnect; fanout reads it at route time.
     */
    private val connectionGenerationProvider: () -> Long = { 0L },
) {
    /**
     * Per-subscriber channels. Buffering starts at [subscribe], not at collect,
     * so frames cannot be lost in the subscribe→collect handoff window. There is
     * no shared replay cache (lgns8.22.3): a new subscriber never sees a prior
     * turn's terminal.
     *
     * Capacity is bounded ([SUBSCRIBER_BUFFER_CAPACITY]) for memory safety.
     * [route] delivers to subscribers concurrently so a full buffer on one
     * runtime cannot head-of-line block delivery to others.
     */
    private val subscribers = mutableMapOf<String, SubscriberSlot>()

    /** Exposed for TurnEngine / controller correlation (same instance). */
    fun inboundControlRegistry(): InboundControlRequestRegistry = inboundControlRegistry

    /**
     * Per-runtime turn locks. Ensures only one turn executes at a time per runtime.
     * Entries are retained while acquired/queued or while viewers remain, then retired.
     */
    private val runtimeTurnLocks = mutableMapOf<RuntimeKey, TurnLockEntry>()

    /**
     * Master lock for protecting internal state.
     *
     * Blocking [SynchronizedObject] (not a suspending [Mutex]) so [closeAllSubscribersSync]
     * from non-suspend [AppServerRuntimeEventRouter.detach] never skips cleanup when
     * route/subscribe holds the lock.
     */
    private val stateLock = SynchronizedObject()

    /**
     * Subscribes to events for a specific runtime.
     *
     * Returns a Flow of all received frames (stream_delta, update_loop_status, etc.)
     * for the given runtime. Multiple subscribers can subscribe to the same runtime;
     * each receives events on its own buffered channel with full channel/raw fidelity.
     */
    suspend fun subscribe(
        agentId: AgentId,
        conversationId: ConversationId,
        subscriberId: String = generateSubscriberId(),
    ): Pair<String, Flow<AppServerReceivedFrame>> = synchronized(stateLock) {
        val key = RuntimeKey(agentId.value, conversationId.value)
        val channel = Channel<AppServerReceivedFrame>(
            capacity = SUBSCRIBER_BUFFER_CAPACITY,
        )
        subscribers[subscriberId] = SubscriberSlot(key = key, channel = channel)
        subscriberId to channel.receiveAsFlow()
    }

    /**
     * Unsubscribes a subscriber by ID.
     *
     * Closing the subscriber channel drops its buffer. Per-runtime turn locks are
     * **not** removed here — an active turn/lease must survive viewer churn
     * (lgns8.22.3). Idle locks are retired from [releaseTurnLock] once no waiters
     * remain and no viewers are subscribed.
     */
    suspend fun unsubscribe(subscriberId: String): Boolean = synchronized(stateLock) {
        val slot = subscribers.remove(subscriberId) ?: return false
        slot.channel.close()
        true
    }

    /**
     * Routes a received frame to subscribers for its runtime scope.
     *
     * Runtime-scoped events go to exact (agent, conversation) subscribers.
     * Terminal-bearing stream deltas also reach same-conversation subscribers
     * (different agent) so TurnEngine can apply authoritative-terminal release.
     * Unscoped external-tool / control requests register in
     * [inboundControlRegistry] then deliver once (duplicate request_ids drop).
     */
    suspend fun route(received: AppServerReceivedFrame) {
        val plan = synchronized(stateLock) { planRoute(received) }
        val delivered = deliverToChannels(plan.channels, received)
        plan.markDispatchedIfNeeded(delivered)
    }

    private fun RoutePlan.markDispatchedIfNeeded(delivered: Boolean) {
        if (!delivered) return
        val requestId = controlRequestId ?: return
        val generation = controlGeneration ?: return
        inboundControlRegistry.markDispatched(requestId, generation)
    }

    private data class RoutePlan(
        val channels: List<Channel<AppServerReceivedFrame>>,
        val controlRequestId: String? = null,
        val controlGeneration: Long? = null,
    )

    private fun planRoute(received: AppServerReceivedFrame): RoutePlan {
        val runtime = received.frame.runtime
        return when {
            runtime == null -> planUnscopedControl(received)
            received.isTerminalBearingStreamDelta() -> RoutePlan(
                channels = subscribers.values
                    .filter { it.key.conversationId == runtime.conversationId }
                    .map { it.channel },
            )
            else -> planScoped(received, runtime)
        }
    }

    private fun planUnscopedControl(received: AppServerReceivedFrame): RoutePlan {
        if (!received.frame.isServerInitiatedControlFrame()) return RoutePlan(emptyList())
        val targets = subscribers.values.map { it.channel }
        if (targets.isEmpty()) return RoutePlan(emptyList())
        if (!registerUnscopedControl(received, runtime = null)) return RoutePlan(emptyList())
        return RoutePlan(
            channels = targets,
            controlRequestId = received.frame.requestId,
            controlGeneration = frameGeneration(received),
        )
    }

    private fun planScoped(
        received: AppServerReceivedFrame,
        runtime: com.letta.mobile.data.transport.appserver.AppServerRuntimeScope,
    ): RoutePlan {
        val key = RuntimeKey(runtime.agentId, runtime.conversationId)
        val targets = subscribers.values.filter { it.key == key }.map { it.channel }
        if (!received.frame.isServerInitiatedControlFrame()) return RoutePlan(targets)
        if (targets.isEmpty()) return RoutePlan(emptyList())
        if (!registerUnscopedControl(received, runtime)) return RoutePlan(emptyList())
        return RoutePlan(
            channels = targets,
            controlRequestId = received.frame.requestId,
            controlGeneration = frameGeneration(received),
        )
    }

    private suspend fun deliverToChannels(
        channels: List<Channel<AppServerReceivedFrame>>,
        received: AppServerReceivedFrame,
    ): Boolean {
        // Concurrent sends: a full buffer on one subscriber cannot HOL-block
        // unrelated runtimes (bounded channels + isolated sends).
        return coroutineScope {
            channels.map { channel ->
                async {
                    try {
                        channel.send(received)
                        true
                    } catch (_: ClosedSendChannelException) {
                        false
                    }
                }
            }.awaitAll().any { it }
        }
    }

    /** Close every subscriber channel (used by [AppServerRuntimeEventRouter.detach]). */
    fun closeAllSubscribersSync() = synchronized(stateLock) {
        val open = subscribers.values.toList()
        subscribers.clear()
        // Close with a cause so turn collectors fail the lease instead of
        // treating detach as a clean end-of-stream completion.
        val cause = kotlinx.coroutines.CancellationException(
            "AppServerRuntimeEventRouter detached",
        )
        open.forEach { it.channel.close(cause) }
    }

    private fun frameGeneration(received: AppServerReceivedFrame): Long =
        received.connectionGeneration ?: connectionGenerationProvider()

    /**
     * @return true when this frame should be delivered to turn subscribers.
     */
    private fun registerUnscopedControl(
        received: AppServerReceivedFrame,
        runtime: com.letta.mobile.data.transport.appserver.AppServerRuntimeScope?,
    ): Boolean {
        val frame = received.frame
        val requestId = frame.requestId ?: return false
        val kind = when (frame) {
            is AppServerInboundFrame.ExternalToolCallRequest ->
                InboundControlRequestRegistry.Kind.ExternalTool
            is AppServerInboundFrame.ControlRequest ->
                InboundControlRequestRegistry.Kind.Approval
            else -> return false
        }
        val toolCallId = (frame as? AppServerInboundFrame.ExternalToolCallRequest)?.toolCallId
        val generation = frameGeneration(received)
        return when (
            val result = inboundControlRegistry.register(
                InboundControlRequestRegistry.RegisterRequest(
                    requestId = requestId,
                    kind = kind,
                    connectionGeneration = generation,
                    agentId = runtime?.agentId,
                    conversationId = runtime?.conversationId,
                    toolCallId = toolCallId,
                ),
            )
        ) {
            is InboundControlRequestRegistry.RegisterResult.Accepted -> true
            // Retriable until a lease claims: Pending (never delivered / send-failed
            // release) or Dispatched (buffered but collector cancelled before claim).
            is InboundControlRequestRegistry.RegisterResult.Duplicate ->
                result.entry.state == InboundControlRequestRegistry.State.Pending ||
                    result.entry.state == InboundControlRequestRegistry.State.Dispatched
            is InboundControlRequestRegistry.RegisterResult.GenerationFailed -> false
        }
    }

    suspend fun acquireTurnLock(agentId: AgentId, conversationId: ConversationId) {
        val key = RuntimeKey(agentId.value, conversationId.value)
        val entry = synchronized(stateLock) {
            runtimeTurnLocks.getOrPut(key) { TurnLockEntry() }.also {
                it.waiters.incrementAndGet()
            }
        }
        try {
            entry.mutex.lock()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            retireTurnLockIfIdle(key, entry)
            throw cancelled
        }
    }

    suspend fun releaseTurnLock(agentId: AgentId, conversationId: ConversationId) {
        val key = RuntimeKey(agentId.value, conversationId.value)
        val entry = synchronized(stateLock) { runtimeTurnLocks[key] } ?: return
        entry.mutex.unlock()
        retireTurnLockIfIdle(key, entry)
    }

    suspend fun <T> withTurnLock(
        agentId: AgentId,
        conversationId: ConversationId,
        block: suspend () -> T,
    ): T {
        acquireTurnLock(agentId, conversationId)
        try {
            return block()
        } finally {
            releaseTurnLock(agentId, conversationId)
        }
    }

    suspend fun subscriberCount(): Int = synchronized(stateLock) {
        subscribers.size
    }

    suspend fun runtimeFlowCount(): Int = synchronized(stateLock) {
        subscribers.values.map { it.key }.toSet().size
    }

    /** Test/telemetry: number of retained per-runtime turn locks. */
    suspend fun turnLockCount(): Int = synchronized(stateLock) {
        runtimeTurnLocks.size
    }

    suspend fun subscriberCountForRuntime(agentId: AgentId, conversationId: ConversationId): Int =
        synchronized(stateLock) {
            val key = RuntimeKey(agentId.value, conversationId.value)
            subscribers.values.count { it.key == key }
        }

    private fun retireTurnLockIfIdle(key: RuntimeKey, entry: TurnLockEntry) {
        synchronized(stateLock) {
            if (entry.waiters.decrementAndGet() > 0) return
            if (subscribers.values.any { it.key == key }) return
            if (runtimeTurnLocks[key] === entry) {
                runtimeTurnLocks.remove(key)
            }
        }
    }

    private data class SubscriberSlot(
        val key: RuntimeKey,
        val channel: Channel<AppServerReceivedFrame>,
    )

    private class TurnLockEntry(
        val mutex: Mutex = Mutex(),
        val waiters: kotlinx.atomicfu.AtomicInt = atomic(0),
    )

    private data class RuntimeKey(val agentId: String, val conversationId: String)

    companion object {
        /**
         * Per-subscriber buffer. Large enough for bursty stream deltas; when full,
         * that subscriber's send suspends while other runtimes continue via
         * concurrent [route] delivery.
         */
        const val SUBSCRIBER_BUFFER_CAPACITY = 256

        private val nextSubscriberId = atomic(0)

        private fun generateSubscriberId(): String =
            "subscriber-${nextSubscriberId.incrementAndGet()}"
    }
}

private fun AppServerInboundFrame.isServerInitiatedControlFrame(): Boolean =
    this is AppServerInboundFrame.ExternalToolCallRequest ||
        this is AppServerInboundFrame.ControlRequest

private fun AppServerReceivedFrame.isTerminalBearingStreamDelta(): Boolean {
    val streamDelta = frame as? AppServerInboundFrame.StreamDelta ?: return false
    val messageType = runCatching {
        (streamDelta.delta as? kotlinx.serialization.json.JsonObject)
            ?.get("message_type")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }.getOrNull()
    return messageType == "stop_reason" ||
        messageType == "error_message" ||
        messageType == "loop_error"
}
