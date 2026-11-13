package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The host store for tests and hosts that need no durability. */
class InMemoryCanvasRelayStore : CanvasRelayStore {
    private val mutex = Mutex()
    private val bindings = mutableMapOf<String, CanvasId>()
    private val logs = mutableMapOf<String, MutableList<CanvasRelayEntry>>()
    private val cursorsByOp = mutableMapOf<String, MutableMap<String, Long>>()

    override suspend fun bind(topic: String, proposed: CanvasId): CanvasId = mutex.withLock {
        bindings.getOrPut(topic) { proposed }
    }

    override suspend fun isBound(canvas: CanvasId, via: ConversationId?): Boolean =
        mutex.withLock { bindings[CanvasRelayProtocol.bindingTopic(canvas, via)] == canvas }

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
