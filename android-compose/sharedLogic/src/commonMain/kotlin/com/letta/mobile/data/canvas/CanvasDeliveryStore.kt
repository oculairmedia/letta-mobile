package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasOpId
import com.letta.mobile.data.model.HostId
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
    suspend fun queued(topic: String): List<CanvasOpId>
    suspend fun enqueue(topic: String, opIds: Collection<CanvasOpId>)
    suspend fun acknowledge(topic: String, opId: CanvasOpId)
    suspend fun reject(topic: String, opId: CanvasOpId, reason: String)
    suspend fun rejected(topic: String): Map<CanvasOpId, String>
    suspend fun cursor(hostId: HostId, topic: String): Long?
    suspend fun advanceCursor(hostId: HostId, topic: String, cursor: Long)
}

/**
 * The whole delivery record, as the durable stores keep it. Op ids and host ids are stored as their
 * bare strings (the persisted shape); the operations take them typed.
 */
@Serializable
data class CanvasDeliveryState(
    val queues: Map<String, List<String>> = emptyMap(),
    val rejected: Map<String, Map<String, String>> = emptyMap(),
    val cursors: Map<String, Long> = emptyMap(),
) {
    fun enqueue(topic: String, opIds: Collection<CanvasOpId>): CanvasDeliveryState {
        val queue = queues[topic].orEmpty()
        val added = opIds.map { it.value }.filter { it !in queue && it !in rejected[topic].orEmpty() }
        return if (added.isEmpty()) this else copy(queues = queues + (topic to queue + added))
    }

    fun acknowledge(topic: String, opId: CanvasOpId): CanvasDeliveryState {
        val queue = queues[topic] ?: return this
        return if (opId.value !in queue) this else copy(queues = queues + (topic to queue - opId.value))
    }

    fun reject(topic: String, opId: CanvasOpId, reason: String): CanvasDeliveryState =
        acknowledge(topic, opId).let { it.copy(rejected = it.rejected + (topic to it.rejected[topic].orEmpty() + (opId.value to reason))) }

    fun advanceCursor(hostId: HostId, topic: String, cursor: Long): CanvasDeliveryState {
        val key = cursorKey(hostId, topic)
        return if ((cursors[key] ?: -1L) >= cursor) this else copy(cursors = cursors + (key to cursor))
    }

    companion object {
        fun cursorKey(hostId: HostId, topic: String): String = "${hostId.value}|$topic"
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

    override suspend fun queued(topic: String): List<CanvasOpId> =
        mutex.withLock { state().queues[topic].orEmpty().map(::CanvasOpId) }
    override suspend fun enqueue(topic: String, opIds: Collection<CanvasOpId>) = update { it.enqueue(topic, opIds) }
    override suspend fun acknowledge(topic: String, opId: CanvasOpId) = update { it.acknowledge(topic, opId) }
    override suspend fun reject(topic: String, opId: CanvasOpId, reason: String) = update { it.reject(topic, opId, reason) }
    override suspend fun rejected(topic: String): Map<CanvasOpId, String> =
        mutex.withLock { state().rejected[topic].orEmpty().mapKeys { (opId, _) -> CanvasOpId(opId) } }
    override suspend fun cursor(hostId: HostId, topic: String): Long? =
        mutex.withLock { state().cursors[CanvasDeliveryState.cursorKey(hostId, topic)] }
    override suspend fun advanceCursor(hostId: HostId, topic: String, cursor: Long) = update { it.advanceCursor(hostId, topic, cursor) }
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
