package com.letta.mobile.data.timeline.snapshot

import kotlinx.serialization.Serializable

/**
 * Scopes a confirmed timeline snapshot to a specific backend endpoint and conversation ownership,
 * ensuring snapshots cannot leak across different servers, accounts, or agents.
 */
@Serializable
data class TimelineScope(
    val backendId: String,
    val conversationId: String,
    val agentId: String? = null,
) {
    init {
        require(backendId.isNotBlank()) { "backendId must not be blank" }
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
    }

    /** Unique storage key for indexing in flat key-value or file-based stores. */
    val storageKey: String
        get() = "${backendId}|${agentId.orEmpty()}|${conversationId}"
}
