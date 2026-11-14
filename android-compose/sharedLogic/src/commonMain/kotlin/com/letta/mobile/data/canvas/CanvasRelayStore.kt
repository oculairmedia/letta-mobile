package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId

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
     * Whether [canvas] is already bound under its own canvas topic or, with [via], under that
     * conversation's topic ([CanvasRelayProtocol.bindingTopic]). Read-only: unlike [bind] it never creates a binding,
     * so a lookup cannot bring a canvas into existence.
     */
    suspend fun isBound(canvas: CanvasId, via: ConversationId? = null): Boolean

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
