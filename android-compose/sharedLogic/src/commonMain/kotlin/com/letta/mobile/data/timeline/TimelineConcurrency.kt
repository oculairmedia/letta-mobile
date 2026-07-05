package com.letta.mobile.data.timeline

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TimelineAtomicCounter(initialValue: Int = 0) {
    private val value = atomic(initialValue)

    fun incrementAndGet(): Int = value.incrementAndGet()

    fun decrementAndGet(): Int = value.decrementAndGet()
}

class TimelineAtomicFlag(initialValue: Boolean = false) {
    private val value = atomic(initialValue)

    fun get(): Boolean = value.value

    fun set(newValue: Boolean) {
        value.value = newValue
    }
}

class TimelineSeenRunTracker {
    private val mutex = Mutex()
    private val seenRunIds = mutableSetOf<String>()

    suspend fun markSeen(runId: String): Boolean =
        mutex.withLock { seenRunIds.add(runId) }
}

/**
 * letta-mobile-h30cy: process-wide external-transport-active state keyed by
 * conversationId, shared across ALL TimelineSyncLoop instances for that
 * conversation.
 *
 * The dual-ingest guard (coordinator/external-transport path marks active; stream
 * subscriber path skips when active) previously lived on the per-loop-instance
 * [TimelineWsSubscription]. Over Iroh a scoped (agentId, conv) and an unscoped
 * (null, conv) request could resolve to TWO loop instances for the same
 * conversation (aliasing gap), so `markActive` on one instance did not stop
 * `submitStreamEvent` on the other — both ingested every frame, doubling the
 * reducer input (proven via IrohFrameFlowDiagnostics: ingest count == 2x emit
 * count, skippedDualIngest fired 0x). That double-ingest drove both the duplicate
 * assistant row and the random character/word drops.
 *
 * Keying the active-flag on the conversationId (not the loop instance) makes the
 * guard correct regardless of how many loop instances exist for the conversation.
 */
object TimelineExternalTransportRegistry {
    private val activeConversations = atomic(emptySet<String>())

    fun isActive(conversationId: String): Boolean =
        activeConversations.value.contains(conversationId)

    fun markActive(conversationId: String) {
        activeConversations.update { it + conversationId }
    }

    fun clear(conversationId: String) {
        activeConversations.update { it - conversationId }
    }
}
