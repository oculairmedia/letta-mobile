package com.letta.mobile.data.controller.fanout

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.runtime.ConversationId
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
     * Per-subscriber buffered channels. Buffering starts at [subscribe], not at
     * collect, so frames cannot be lost in the subscribe→collect handoff window.
     * There is no shared replay cache (lgns8.22.3): a new subscriber never sees
     * a prior turn's terminal.
     */
    private val subscribers = mutableMapOf<String, SubscriberSlot>()

    /**
     * Per-runtime turn locks. Ensures only one turn executes at a time per runtime.
     * Entries are retained while acquired/queued or while viewers remain, then retired.
     */
    private val runtimeTurnLocks = mutableMapOf<RuntimeKey, TurnLockEntry>()

    /**
     * Master lock for protecting internal state.
     */
    private val stateMutex = Mutex()

    /**
     * Subscribes to events for a specific runtime.
     *
     * Returns a Flow of all events (stream_delta, update_loop_status, etc.) for
     * the given runtime. Multiple subscribers can subscribe to the same runtime;
     * each receives events on its own buffered channel.
     *
     * @param agentId The agent ID for the runtime
     * @param conversationId The conversation ID for the runtime
     * @param subscriberId Optional unique ID for this subscriber (auto-generated if not provided)
     * @return A pair of (subscriberId, event flow) for this subscription
     */
    suspend fun subscribe(
        agentId: AgentId,
        conversationId: ConversationId,
        subscriberId: String = generateSubscriberId(),
    ): Pair<String, Flow<AppServerInboundFrame>> = stateMutex.withLock {
        val key = RuntimeKey(agentId.value, conversationId.value)
        val channel = Channel<AppServerInboundFrame>(
            capacity = SUBSCRIBER_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
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
     *
     * @param subscriberId The subscriber ID returned by subscribe()
     * @return true if the subscriber was found and removed, false otherwise
     */
    suspend fun unsubscribe(subscriberId: String): Boolean = stateMutex.withLock {
        val slot = subscribers.remove(subscriberId) ?: return false
        slot.channel.close()
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
        val runtime = frame.runtime ?: return // Only route events with a runtime scope

        val key = RuntimeKey(runtime.agentId, runtime.conversationId)
        val channels = stateMutex.withLock {
            subscribers.values.filter { it.key == key }.map { it.channel }
        }
        for (channel in channels) {
            channel.trySend(frame)
        }
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
        val entry = stateMutex.withLock {
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

    /**
     * Releases the turn lock for a specific runtime.
     *
     * When the last waiter releases and no viewers remain for the runtime, the
     * lock entry is removed so long-lived fanouts do not retain every RuntimeKey forever.
     *
     * @param agentId The agent ID
     * @param conversationId The conversation ID
     */
    suspend fun releaseTurnLock(agentId: AgentId, conversationId: ConversationId) {
        val key = RuntimeKey(agentId.value, conversationId.value)
        val entry = stateMutex.withLock { runtimeTurnLocks[key] } ?: return
        entry.mutex.unlock()
        retireTurnLockIfIdle(key, entry)
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
     * Returns the number of distinct runtimes that currently have subscribers.
     */
    suspend fun runtimeFlowCount(): Int = stateMutex.withLock {
        subscribers.values.map { it.key }.toSet().size
    }

    /** Test/telemetry: number of retained per-runtime turn locks. */
    suspend fun turnLockCount(): Int = stateMutex.withLock {
        runtimeTurnLocks.size
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
        subscribers.values.count { it.key == key }
    }

    private suspend fun retireTurnLockIfIdle(key: RuntimeKey, entry: TurnLockEntry) {
        stateMutex.withLock {
            if (entry.waiters.decrementAndGet() > 0) return
            if (subscribers.values.any { it.key == key }) return
            if (runtimeTurnLocks[key] === entry) {
                runtimeTurnLocks.remove(key)
            }
        }
    }

    private data class SubscriberSlot(
        val key: RuntimeKey,
        val channel: Channel<AppServerInboundFrame>,
    )

    private class TurnLockEntry(
        val mutex: Mutex = Mutex(),
        /** Acquire attempts in flight or holding the mutex. */
        val waiters: kotlinx.atomicfu.AtomicInt = atomic(0),
    )

    /**
     * Internal key for runtime identification.
     */
    private data class RuntimeKey(val agentId: String, val conversationId: String)

    companion object {
        private const val SUBSCRIBER_BUFFER_CAPACITY = 64

        // Atomic so concurrent subscribe() calls that auto-generate IDs cannot
        // collide on the same counter value.
        private val nextSubscriberId = atomic(0)

        /**
         * Generates a unique subscriber ID.
         */
        private fun generateSubscriberId(): String {
            return "subscriber-${nextSubscriberId.incrementAndGet()}"
        }
    }
}
