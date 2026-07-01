package com.letta.mobile.data.controller.fanout

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.atomicfu.atomic

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
 * val events: Flow<AppServerInboundFrame> = fanout.subscribe(agentId, conversationId)
 *
 * // Feed events from the controller into the fanout
 * controllerClient.events.collect { receivedFrame ->
 *     fanout.route(receivedFrame.frame)
 * }
 *
 * // Unsubscribe when done
 * fanout.unsubscribe(subscriberId)
 * ```
 *
 * THREAD-SAFETY:
 * All public methods are thread-safe and can be called from multiple coroutines.
 */
class RuntimeEventFanout {
    /**
     * Map of runtime key -> shared flow for that runtime's events.
     * Each runtime gets its own flow, and multiple subscribers can collect from it.
     */
    private val runtimeFlows = mutableMapOf<RuntimeKey, MutableSharedFlow<AppServerInboundFrame>>()

    /**
     * Map of subscriber ID -> runtime key, for tracking active subscriptions.
     */
    private val subscribers = mutableMapOf<String, RuntimeKey>()

    /**
     * Per-runtime turn locks. Ensures only one turn executes at a time per runtime.
     */
    private val runtimeTurnLocks = mutableMapOf<RuntimeKey, Mutex>()

    /**
     * Master lock for protecting internal state.
     */
    private val stateMutex = Mutex()

    /**
     * Subscribes to events for a specific runtime.
     *
     * Returns a Flow of all events (stream_delta, update_loop_status, etc.) for
     * the given runtime. Multiple subscribers can subscribe to the same runtime;
     * each will receive all events.
     *
     * The flow is hot: events are buffered with a replay of 0 (no replay).
     * Subscribers will only receive events emitted AFTER they subscribe.
     *
     * @param agentId The agent ID for the runtime
     * @param conversationId The conversation ID for the runtime
     * @param subscriberId Optional unique ID for this subscriber (auto-generated if not provided)
     * @return A pair of (subscriberId, event flow) for this subscription
     */
    suspend fun subscribe(
        agentId: AgentId,
        conversationId: ConversationId,
        subscriberId: String? = null,
    ): Pair<String, Flow<AppServerInboundFrame>> = stateMutex.withLock {
        val key = RuntimeKey(agentId.value, conversationId.value)
        // Generate subscriber ID INSIDE the lock so concurrent calls cannot
        // race and produce duplicates (critical fix, Codex review).
        val sid = subscriberId ?: generateSubscriberId()

        // Get or create the shared flow for this runtime
        val flow = runtimeFlows.getOrPut(key) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 64, // Buffer up to 64 events if no subscriber is ready
            )
        }

        // Register the subscriber
        subscribers[sid] = key

        sid to flow.asSharedFlow()
    }

    /**
     * Unsubscribes a subscriber by ID.
     *
     * If this is the last subscriber for a runtime, the runtime's flow is cleaned up.
     *
     * @param subscriberId The subscriber ID returned by subscribe()
     * @return true if the subscriber was found and removed, false otherwise
     */
    suspend fun unsubscribe(subscriberId: String): Boolean = stateMutex.withLock {
        val key = subscribers.remove(subscriberId) ?: return false

        // Check if there are any remaining subscribers for this runtime
        val hasRemainingSubscribers = subscribers.values.any { it == key }

        if (!hasRemainingSubscribers) {
            // No more subscribers for this runtime, clean up the flow.
            // Keep runtimeTurnLocks alive — an in-flight turn may still hold
            // the Mutex.lock. Removing the lock from the map while a turn is
            // active would orphan the Mutex and create a fresh (uncontended)
            // one on the next acquireTurnLock, breaking per-runtime serialization.
            runtimeFlows.remove(key)
        }

        true
    }

    /**
     * Routes an event to the appropriate runtime's subscribers.
     *
     * Only events with a runtime scope are routed (stream_delta, update_loop_status,
     * update_device_status, update_queue, update_subagent_state). Other events are
     * ignored.
     *
     * Events are routed to ALL subscribers for that runtime. If no subscribers exist
     * for a runtime, the event is dropped (not buffered).
     *
     * @param frame The inbound frame to route
     */
    suspend fun route(frame: AppServerInboundFrame) {
        if (!frame.isRuntimeEventFrame()) return
        val runtime = frame.runtime ?: return

        val key = RuntimeKey(runtime.agentId, runtime.conversationId)

        // Get the flow for this runtime (non-blocking lookup)
        val flow = stateMutex.withLock {
            runtimeFlows[key]
        } ?: return // No subscribers for this runtime, drop the event

        // Emit to all subscribers
        flow.emit(frame)
    }

    /**
     * Acquires the turn lock for a specific runtime.
     *
     * This ensures only one turn executes at a time on a given runtime, while
     * allowing parallel turns on different runtimes.
     *
     * The caller MUST call releaseTurnLock when the turn completes.
     *
     * @param agentId The agent ID
     * @param conversationId The conversation ID
     */
    suspend fun acquireTurnLock(agentId: AgentId, conversationId: ConversationId) {
        val key = RuntimeKey(agentId.value, conversationId.value)

        val mutex = stateMutex.withLock {
            runtimeTurnLocks.getOrPut(key) { Mutex() }
        }

        mutex.lock()
    }

    /**
     * Releases the turn lock for a specific runtime.
     *
     * @param agentId The agent ID
     * @param conversationId The conversation ID
     */
    suspend fun releaseTurnLock(agentId: AgentId, conversationId: ConversationId) {
        val key = RuntimeKey(agentId.value, conversationId.value)

        val mutex = stateMutex.withLock {
            runtimeTurnLocks[key]
        } ?: return // No lock exists, nothing to release

        mutex.unlock()
    }

    /**
     * Executes a turn with the per-runtime lock.
     *
     * This is a convenience wrapper that acquires the lock, executes the block,
     * and releases the lock even if the block throws.
     *
     * @param agentId The agent ID
     * @param conversationId The conversation ID
     * @param block The turn execution block
     * @return The result of the block
     */
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

    /**
     * Returns the number of active subscribers.
     */
    suspend fun subscriberCount(): Int = stateMutex.withLock {
        subscribers.size
    }

    /**
     * Returns the number of active runtime flows.
     */
    suspend fun runtimeFlowCount(): Int = stateMutex.withLock {
        runtimeFlows.size
    }

    /**
     * Returns the number of subscribers for a specific runtime.
     *
     * @param agentId The agent ID
     * @param conversationId The conversation ID
     * @return The count of subscribers for this runtime
     */
    suspend fun subscriberCountForRuntime(agentId: AgentId, conversationId: ConversationId): Int = stateMutex.withLock {
        val key = RuntimeKey(agentId.value, conversationId.value)
        subscribers.values.count { it == key }
    }

    /**
     * Internal key for runtime identification.
     */
    private data class RuntimeKey(val agentId: String, val conversationId: String)

    companion object {
        private val nextSubscriberId = atomic(0)

        private fun AppServerInboundFrame.isRuntimeEventFrame(): Boolean =
            this is AppServerInboundFrame.StreamDelta ||
                this is AppServerInboundFrame.UpdateLoopStatus ||
                this is AppServerInboundFrame.UpdateDeviceStatus ||
                this is AppServerInboundFrame.UpdateQueue ||
                this is AppServerInboundFrame.UpdateSubagentState

        /**
         * Generates a unique subscriber ID.
         * Thread-safe via atomic even if called outside the stateMutex.
         */
        private fun generateSubscriberId(): String {
            return "subscriber-${nextSubscriberId.getAndIncrement()}"
        }
    }
}
