package com.letta.mobile.data.runtime

/**
 * Repairs backend context state before an ordinary user turn is submitted.
 *
 * Implementations must preserve transcript history and only change the active
 * context projection. Approval responses bypass this hook because they resume
 * an intentionally pending tool call.
 */
fun interface TurnContextRecovery {
    suspend fun recover(agentId: String, conversationId: String): List<String>

    companion object {
        val None = TurnContextRecovery { _, _ -> emptyList() }
    }
}
