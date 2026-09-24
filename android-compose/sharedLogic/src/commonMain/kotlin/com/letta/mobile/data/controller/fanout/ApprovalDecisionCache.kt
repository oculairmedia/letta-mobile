package com.letta.mobile.data.controller.fanout

import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * letta-mobile-qygvv.5: the decisions this client sent, keyed by
 * (agent_id, conversation_id, request_id) and independent of connection generation.
 *
 * A `control_request` the server replays (sync `recover_approvals`, or an approval
 * replayed after reconnect) means the server still considers it pending, so the
 * answer was lost. The replay is re-answered with the SAME cached decision instead
 * of being dropped (which wedged the turn) or surfaced as a second approval card.
 * Mirrors letta-agent-sdk `app-server-session.ts` (256-entry LRU).
 */
class ApprovalDecisionCache(private val capacity: Int = DEFAULT_CAPACITY) {
    data class Key(val agentId: String, val conversationId: String, val requestId: String)

    data class CachedDecision(
        val runtime: AppServerRuntimeScope,
        val requestId: String,
        val decision: AppServerApprovalResponseDecision,
    ) {
        val key: Key get() = Key(runtime.agentId, runtime.conversationId, requestId)
    }

    private val lock = SynchronizedObject()

    /** Insertion order doubles as recency: [remember] and [lookup] move an entry to the end. */
    private val entries = LinkedHashMap<Key, CachedDecision>()

    fun remember(decision: CachedDecision) {
        synchronized(lock) {
            entries.remove(decision.key)
            entries[decision.key] = decision
            while (entries.size > capacity) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
    }

    /** Drops [decision]'s key only while it still holds [decision], so a newer decision survives. */
    fun forget(decision: CachedDecision) {
        synchronized(lock) {
            if (entries[decision.key] == decision) entries.remove(decision.key)
        }
    }

    /**
     * The cached decision for a replayed [frame]. A replay without runtime scope
     * matches by request id alone, and only when exactly one entry carries it.
     */
    fun lookup(frame: AppServerInboundFrame.ControlRequest): CachedDecision? = synchronized(lock) {
        val scope = frame.runtime
        val hit = if (scope != null) {
            entries[Key(scope.agentId, scope.conversationId, frame.requestId)]
        } else {
            entries.values.singleOrNull { it.requestId == frame.requestId }
        } ?: return null
        entries.remove(hit.key)
        entries[hit.key] = hit
        hit
    }

    fun size(): Int = synchronized(lock) { entries.size }

    companion object {
        const val DEFAULT_CAPACITY = 256
    }
}
