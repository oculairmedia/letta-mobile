package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonObject

/**
 * Central registry of agent/conversation fields that are captured at
 * `runtime_start` and therefore require runtime eviction after mutation.
 *
 * Phase 2 (runbook): classify every mutable field as live-read,
 * runtime-captured, or restart-required — and keep invalidation in one place
 * instead of scattering ad-hoc checks across handlers.
 */
object RuntimeInvalidationPolicy {
    /**
     * Agent fields that force [com.letta.mobile.data.controller.AppServerController.stopRuntime]
     * after a successful `agent.update`.
     */
    val AGENT_RESTART_FIELDS: Set<String> = setOf(
        "model",
        "context_window_limit",
        "contextWindowLimit",
        "llm_config",
        "llmConfig",
        "model_settings",
        "modelSettings",
        "tools",
        "tool_ids",
        "toolIds",
        "tool_rules",
        "toolRules",
        "skills",
        "skill_ids",
        "skillIds",
        "memory",
        "memory_blocks",
        "memoryBlocks",
        "system",
        "system_prompt",
        "systemPrompt",
        "agent_type",
        "agentType",
        "embedding",
        "embedding_config",
        "embeddingConfig",
        "message_buffer_autoclear",
        "messageBufferAutoclear",
    )

    /**
     * Nested keys under `model_settings` / `llm_config` that also force restart.
     */
    val AGENT_NESTED_RESTART_FIELDS: Set<String> = setOf(
        "context_window_limit",
        "contextWindowLimit",
        "context_window",
        "contextWindow",
        "model",
        "handle",
    )

    /**
     * Conversation override fields captured into a live runtime.
     */
    val CONVERSATION_RESTART_FIELDS: Set<String> = setOf(
        "model",
        "context_window_limit",
        "contextWindowLimit",
        "model_settings",
        "modelSettings",
        "llm_config",
        "llmConfig",
        "system",
        "system_prompt",
        "systemPrompt",
        "tools",
        "tool_ids",
        "toolIds",
        "skills",
        "skill_ids",
        "skillIds",
        "memory",
    )

    fun agentUpdateRequiresRestart(params: JsonObject?): Boolean {
        if (params == null) return false
        if (params.keys.any { it in AGENT_RESTART_FIELDS }) return true
        return nestedRestartObject(params, "model_settings", "modelSettings") ||
            nestedRestartObject(params, "llm_config", "llmConfig")
    }

    private fun nestedRestartObject(params: JsonObject, snake: String, camel: String): Boolean {
        val nested = (params[snake] as? JsonObject) ?: (params[camel] as? JsonObject) ?: return false
        return nested.keys.any { it in AGENT_NESTED_RESTART_FIELDS }
    }

    fun conversationUpdateRequiresRestart(params: JsonObject?): Boolean {
        if (params == null) return false
        if (params.keys.any { it in CONVERSATION_RESTART_FIELDS }) return true
        return nestedRestartObject(params, "model_settings", "modelSettings") ||
            nestedRestartObject(params, "llm_config", "llmConfig")
    }

    /** Skill enable/disable changes the toolset captured at runtime start. */
    fun skillMutationRequiresRestart(): Boolean = true
}
