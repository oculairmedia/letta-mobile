package com.letta.mobile.feature.chat.send

internal object LocalRuntimeRouting {
    fun shouldUseLocalRuntime(
        sessionHasLocalRuntimeBackend: Boolean,
        agentId: String,
        conversationId: String?,
    ): Boolean = sessionHasLocalRuntimeBackend ||
        agentId.isLocalRuntimeAgentId() ||
        conversationId.orEmpty().isLocalRuntimeConversationId()

    private fun String.isLocalRuntimeAgentId(): Boolean = startsWith("local-agent-")

    private fun String.isLocalRuntimeConversationId(): Boolean = startsWith("local-conv-")
}
