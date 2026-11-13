package com.letta.mobile.data.controller.extras.identity

/**
 * Which call a tool runs for: the agent, its conversation and the model's tool call id, each null
 * when the call carried none. It says nothing about whether anyone vouches for it; a tool caller
 * pairs it with a [CallerIdentity].
 */
data class ToolCallScope(
    /** The agent the call is for. */
    val agentId: String?,
    /** The conversation the call is for. */
    val conversationId: String? = null,
    /** The model's tool call id, the key the timeline files the call's return under. */
    val toolCallId: String? = null,
)
