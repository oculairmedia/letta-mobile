package com.letta.mobile.data.repository

import com.letta.mobile.data.repository.api.SubagentParentScope
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * letta-mobile-fxoew.5: which parent scopes the [SubagentRepository] has
 * already fetched, and which ones are being collected right now.
 *
 * The host's `subagent.list` is conversation-scoped, so one fetch per
 * repository only ever covered the first conversation the user opened. This
 * tracker lets the repository fetch each distinct scope once, retry a scope
 * whose fetch failed, and re-fetch the scopes on screen after a reconnect.
 * No polling: every fetch is driven by a collection or a reconnect.
 */
internal class SubagentScopeFetchTracker {
    private val fetched = atomic<Set<SubagentParentScope>>(emptySet())
    private val collecting = atomic<Map<SubagentParentScope, Int>>(emptyMap())

    /** Marks [scope] fetched; true when the caller must issue the fetch. */
    fun claim(scope: SubagentParentScope): Boolean {
        var claimed = false
        fetched.update { current ->
            claimed = scope !in current
            current + scope
        }
        return claimed
    }

    /** Clears a failed fetch so the next collection or reconnect retries it. */
    fun release(scope: SubagentParentScope) {
        fetched.update { it - scope }
    }

    fun collectionStarted(scope: SubagentParentScope) {
        collecting.update { it + (scope to (it[scope] ?: 0) + 1) }
    }

    fun collectionStopped(scope: SubagentParentScope) {
        collecting.update { current ->
            val remaining = (current[scope] ?: 0) - 1
            if (remaining > 0) current + (scope to remaining) else current - scope
        }
    }

    /**
     * Forgets every fetch (the socket dropped, so all snapshots may be stale)
     * and returns the scopes still collected, which must be fetched again now.
     */
    fun resetForReconnect(): Set<SubagentParentScope> {
        fetched.value = emptySet()
        return collecting.value.keys
    }
}
