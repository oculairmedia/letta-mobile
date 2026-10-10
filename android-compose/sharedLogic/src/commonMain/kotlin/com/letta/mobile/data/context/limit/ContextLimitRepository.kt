package com.letta.mobile.data.context.limit

import com.letta.mobile.data.compaction.wireConversationIdOf
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId

/** letta-mobile-joigh: set one conversation's (or, on its default conversation, the agent's) context limit. */
data class ContextLimitRequest(
    val agentId: AgentId,
    /** Null is the agent's default conversation. */
    val conversationId: ConversationId?,
    val tokens: Int,
) {
    val wireConversationId: String get() = wireConversationIdOf(agentId, conversationId)
}

/** What a limit change came to. */
sealed interface ContextLimitOutcome {
    data class Applied(val result: ContextLimitResult) : ContextLimitOutcome

    /** This backend cannot change the limit (no `/context-limit`, no relay): the slider hides. */
    data object Unsupported : ContextLimitOutcome

    /** letta-code refused or the host failed; [message] is letta-code's own text where it gave one. */
    data class Failed(val message: String) : ContextLimitOutcome
}

/**
 * letta-mobile-joigh: the context-limit change, whichever way the session reaches letta-code —
 * the Iroh host's `conversation.context_limit` ([AdminRpcContextLimitRepository]) or a direct App
 * Server client ([AppServerContextLimitRepository]). Both run letta-code's own `/context-limit`.
 */
interface ContextLimitRepository {
    suspend fun apply(request: ContextLimitRequest): ContextLimitOutcome
}

/** Tries this repository, then [fallback] when this one cannot change the limit at all. */
fun ContextLimitRepository.orElse(fallback: ContextLimitRepository): ContextLimitRepository = object : ContextLimitRepository {
    override suspend fun apply(request: ContextLimitRequest): ContextLimitOutcome {
        val first = this@orElse.apply(request)
        return if (first == ContextLimitOutcome.Unsupported) fallback.apply(request) else first
    }
}
