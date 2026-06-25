package com.letta.mobile.data.transport

import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent map of `{conversationId -> {runId -> lastSeq}}` for
 * non-terminal mobile WebSocket runs.
 *
 * The cursor is the last `seq` received for a run. On reconnect, platform
 * transport adapters can re-subscribe with `cursor = lastSeq` so the shim
 * replays only missed frames.
 */
interface RunCursorStore {
    /**
     * Load persisted state on first call. In-memory implementations may no-op.
     */
    fun ensureLoaded()

    /**
     * Record that [seq] was received for [runId] in [conversationId].
     * No-op if [seq] is not strictly greater than the recorded value.
     */
    fun record(conversationId: String, runId: String, seq: Long, isTerminal: Boolean = false)

    /**
     * Drop the active cursor for [runId] in [conversationId]. Terminal tombstones
     * are preserved so late replay frames cannot reactivate completed runs.
     */
    fun clear(conversationId: String, runId: String)

    /** Drop both active cursor state and terminal tombstone for explicit reset paths. */
    fun clearTerminal(conversationId: String, runId: String)

    /** Snapshot of all non-terminal runs across every conversation. */
    fun allActiveRuns(): Map<String, Map<String, Long>>

    /** Snapshot of non-terminal runs for a single conversation. */
    fun activeRuns(conversationId: String): Map<String, Long>

    companion object {
        /** In-memory implementation for tests and non-persistent runtimes. */
        fun inMemory(): RunCursorStore = InMemoryRunCursorStore()
    }
}

internal class InMemoryRunCursorStore : RunCursorStore {
    private val active = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val terminal = ConcurrentHashMap<String, MutableSet<String>>()

    override fun ensureLoaded() { /* no-op */ }

    override fun record(conversationId: String, runId: String, seq: Long, isTerminal: Boolean) {
        if (conversationId.isEmpty() || runId.isEmpty() || seq <= 0L) return
        synchronized(this) {
            if (isTerminal) {
                active[conversationId]?.remove(runId)
                if (active[conversationId]?.isEmpty() == true) active.remove(conversationId)
                terminal.getOrPut(conversationId) { ConcurrentHashMap.newKeySet() }.add(runId)
                return
            }
            if (terminal[conversationId]?.contains(runId) == true) return
            val perConversation = active.getOrPut(conversationId) { ConcurrentHashMap() }
            val existing = perConversation[runId]
            if (existing == null || seq > existing) {
                perConversation[runId] = seq
            }
        }
    }

    override fun clear(conversationId: String, runId: String) {
        if (conversationId.isEmpty() || runId.isEmpty()) return
        synchronized(this) {
            val perConversation = active[conversationId] ?: return
            if (perConversation.remove(runId) == null) return
            if (perConversation.isEmpty()) active.remove(conversationId)
        }
    }

    override fun clearTerminal(conversationId: String, runId: String) {
        if (conversationId.isEmpty() || runId.isEmpty()) return
        synchronized(this) {
            val perConversation = active[conversationId]
            if (perConversation?.remove(runId) != null) {
                if (perConversation.isEmpty()) active.remove(conversationId)
            }
            terminal[conversationId]?.remove(runId)
            if (terminal[conversationId]?.isEmpty() == true) terminal.remove(conversationId)
        }
    }

    override fun allActiveRuns(): Map<String, Map<String, Long>> {
        return active.mapValues { (_, runs) -> runs.toMap() }
    }

    override fun activeRuns(conversationId: String): Map<String, Long> {
        return active[conversationId]?.toMap().orEmpty()
    }
}
