package com.letta.mobile.data.canvas

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the host keeps shared canvases: the canvas each topic is bound to, and each topic's
 * append-only op log with a cursor. The relay depends on nothing else, so the durable file store
 * can later be replaced (a MemFS projection, say) without changing the protocol, the apps or
 * [CanvasSession]. [CanvasRelayStoreContract] is the suite every implementation passes.
 */
interface CanvasRelayStore {
    /**
     * The canvas [topic] is bound to: [proposed] when this is the first binding, else the one
     * already bound. Atomic - two apps opening the same conversation at once get one canvas.
     */
    suspend fun bind(topic: String, proposed: CanvasId): CanvasId

    /**
     * Appends [op] to [topic]'s log, received from [origin], and returns its cursor. Idempotent by
     * op id: an op already in the log keeps its cursor and is reported as a duplicate.
     */
    suspend fun append(topic: String, op: CanvasOp, origin: String): CanvasRelayAppend

    /** Up to [limit] entries of [topic] after [afterCursor], in cursor order. */
    suspend fun readAfter(topic: String, afterCursor: Long, limit: Int = DEFAULT_PAGE): List<CanvasRelayEntry>

    /** The cursor of [topic]'s last entry; 0 when empty. */
    suspend fun head(topic: String): Long

    companion object {
        const val DEFAULT_PAGE: Int = 256
    }
}

data class CanvasRelayAppend(val cursor: Long, val duplicate: Boolean)

/** One op in a topic's log: [cursor] counts from 1, [origin] is who the host received it from. */
data class CanvasRelayEntry(val cursor: Long, val op: CanvasOp, val origin: String)

/** The host store for tests and hosts that need no durability. */
class InMemoryCanvasRelayStore : CanvasRelayStore {
    private val mutex = Mutex()
    private val bindings = mutableMapOf<String, CanvasId>()
    private val logs = mutableMapOf<String, MutableList<CanvasRelayEntry>>()
    private val cursorsByOp = mutableMapOf<String, MutableMap<String, Long>>()

    override suspend fun bind(topic: String, proposed: CanvasId): CanvasId = mutex.withLock {
        bindings.getOrPut(topic) { proposed }
    }

    override suspend fun append(topic: String, op: CanvasOp, origin: String): CanvasRelayAppend = mutex.withLock {
        val seen = cursorsByOp.getOrPut(topic) { mutableMapOf() }
        seen[op.opId]?.let { return@withLock CanvasRelayAppend(it, duplicate = true) }
        val log = logs.getOrPut(topic) { mutableListOf() }
        val cursor = log.size + 1L
        log += CanvasRelayEntry(cursor, op, origin)
        seen[op.opId] = cursor
        CanvasRelayAppend(cursor, duplicate = false)
    }

    override suspend fun readAfter(topic: String, afterCursor: Long, limit: Int): List<CanvasRelayEntry> = mutex.withLock {
        val log = logs[topic].orEmpty()
        val from = afterCursor.coerceIn(0L, log.size.toLong()).toInt()
        log.subList(from, minOf(log.size, from + limit)).toList()
    }

    override suspend fun head(topic: String): Long = mutex.withLock { logs[topic]?.size?.toLong() ?: 0L }
}
