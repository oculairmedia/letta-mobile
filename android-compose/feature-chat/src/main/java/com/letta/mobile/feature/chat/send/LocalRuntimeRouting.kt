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

    // An on-device conversation is local-conv-<localAgentId>, and local agents
    // are local-agent-*. The bare `local-conv-` prefix is NOT sufficient: the
    // REMOTE letta-code-local backend (Meridian over Iroh) also names its
    // conversations local-conv-<n> (local-conv-101, …). Matching the bare prefix
    // classified those remote conversations as local-runtime, so follow-up sends
    // routed to the (null) on-device backend → "missing_backend" /
    // "local runtime not available". Require the suffix to be a local agent id.
    private fun String.isLocalRuntimeConversationId(): Boolean =
        startsWith("local-conv-") && removePrefix("local-conv-").isLocalRuntimeAgentId()
}
