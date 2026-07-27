package com.letta.mobile.data.runtime

/**
 * Establishes a safe backend context before an ordinary user turn is submitted.
 *
 * Approval responses bypass this hook because they resume an intentionally
 * pending tool call and must not mutate context underneath that call.
 */
fun interface TurnContextPreflight {
    suspend fun prepare(agentId: String, conversationId: String): TurnContextPreflightResult

    companion object {
        val None = TurnContextPreflight { _, _ -> TurnContextPreflightResult() }
    }
}

data class TurnContextPreflightResult(
    val configuredContextLimit: Boolean = false,
    val compacted: Boolean = false,
)
