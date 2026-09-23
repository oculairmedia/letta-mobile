package com.letta.mobile.data.canvas

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * An app's durable record of canvas delivery to its host, beside the local op log.
 *
 * - The queue: per topic, the ids of local ops not yet acknowledged by the host. An op joins it the
 *   moment it is published (it is already in the local op log), and leaves it only on the host's
 *   ack - never on a socket write - or when the host rejects it for good.
 * - The cursor: per host and topic, how far into the host's log this app has applied. Catch-up
 *   starts after it.
 * - Whether this app has ever joined the topic on that host: the first join uploads every local op
 *   of the canvas, which is how canvases made before sharing (or on another host) reach it.
 */
interface CanvasDeliveryStore {
    suspend fun queued(topic: String): List<String>
    suspend fun enqueue(topic: String, opIds: Collection<String>)
    suspend fun acknowledge(topic: String, opId: String)
    suspend fun reject(topic: String, opId: String, reason: String)
    suspend fun rejected(topic: String): Map<String, String>
    suspend fun cursor(hostId: String, topic: String): Long?
    suspend fun advanceCursor(hostId: String, topic: String, cursor: Long)
}

/** The whole delivery record, as the durable stores keep it. */
@Serializable
data class CanvasDeliveryState(
    val queues: Map<String, List<String>> = emptyMap(),
    val rejected: Map<String, Map<String, String>> = emptyMap(),
    val cursors: Map<String, Long> = emptyMap(),
) {
    fun enqueue(topic: String, opIds: Collection<String>): CanvasDeliveryState {
        val queue = queues[topic].orEmpty()
        val added = opIds.filter { it !in queue && it !in rejected[topic].orEmpty() }
        return if (added.isEmpty()) this else copy(queues = queues + (topic to queue + added))
    }

    fun acknowledge(topic: String, opId: String): CanvasDeliveryState {
        val queue = queues[topic] ?: return this
        return if (opId !in queue) this else copy(queues = queues + (topic to queue - opId))
    }

    fun reject(topic: String, opId: String, reason: String): CanvasDeliveryState =
        acknowledge(topic, opId).let { it.copy(rejected = it.rejected + (topic to it.rejected[topic].orEmpty() + (opId to reason))) }

    fun advanceCursor(hostId: String, topic: String, cursor: Long): CanvasDeliveryState {
        val key = cursorKey(hostId, topic)
        return if ((cursors[key] ?: -1L) >= cursor) this else copy(cursors = cursors + (key to cursor))
    }

    companion object {
        fun cursorKey(hostId: String, topic: String): String = "$hostId|$topic"
    }
}

/** A [CanvasDeliveryStore] over any durable holder of a [CanvasDeliveryState]. */
abstract class StateCanvasDeliveryStore : CanvasDeliveryStore {
    private val mutex = Mutex()

    protected abstract suspend fun load(): CanvasDeliveryState
    protected abstract suspend fun save(state: CanvasDeliveryState)

    private var cached: CanvasDeliveryState? = null

    private suspend fun state(): CanvasDeliveryState = cached ?: load().also { cached = it }

    private suspend fun update(change: (CanvasDeliveryState) -> CanvasDeliveryState) = mutex.withLock {
        val before = state()
        val after = change(before)
        if (after != before) {
            save(after)
            cached = after
        }
    }

    override suspend fun queued(topic: String): List<String> = mutex.withLock { state().queues[topic].orEmpty() }
    override suspend fun enqueue(topic: String, opIds: Collection<String>) = update { it.enqueue(topic, opIds) }
    override suspend fun acknowledge(topic: String, opId: String) = update { it.acknowledge(topic, opId) }
    override suspend fun reject(topic: String, opId: String, reason: String) = update { it.reject(topic, opId, reason) }
    override suspend fun rejected(topic: String): Map<String, String> = mutex.withLock { state().rejected[topic].orEmpty() }
    override suspend fun cursor(hostId: String, topic: String): Long? =
        mutex.withLock { state().cursors[CanvasDeliveryState.cursorKey(hostId, topic)] }
    override suspend fun advanceCursor(hostId: String, topic: String, cursor: Long) = update { it.advanceCursor(hostId, topic, cursor) }
}

/** Delivery record for tests; [snapshot] is what a durable store would have written. */
class InMemoryCanvasDeliveryStore(initial: CanvasDeliveryState = CanvasDeliveryState()) : StateCanvasDeliveryStore() {
    var snapshot: CanvasDeliveryState = initial
        private set

    override suspend fun load(): CanvasDeliveryState = snapshot
    override suspend fun save(state: CanvasDeliveryState) {
        snapshot = state
    }
}
