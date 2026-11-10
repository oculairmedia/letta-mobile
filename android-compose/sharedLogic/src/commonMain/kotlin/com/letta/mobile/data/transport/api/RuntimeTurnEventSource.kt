package com.letta.mobile.data.transport.api

/** Session event tap installed before a conversation is exposed; disposal never stops a turn. */
interface RuntimeTurnEventSource {
    /** Register one session observer; the returned disposer removes only that registration. */
    fun observeTurns(
        observer: (com.letta.mobile.runtime.RuntimeEventDraft, com.letta.mobile.runtime.RunId, com.letta.mobile.runtime.RunId?) -> Unit,
    ): () -> Unit

    /** Captured, non-null server identity only; true requires authoritative acceptance. */
    suspend fun abortExact(
        agent: com.letta.mobile.data.model.AgentId,
        conversation: com.letta.mobile.runtime.ConversationId,
        run: com.letta.mobile.runtime.RunId,
    ): Boolean
}
