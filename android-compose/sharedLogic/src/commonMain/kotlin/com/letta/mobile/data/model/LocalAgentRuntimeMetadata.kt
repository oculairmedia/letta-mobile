package com.letta.mobile.data.model

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object LocalAgentRuntimeMetadata {
    const val RuntimeKey = "runtime"
    const val RuntimeProviderKey = "runtime_provider"
    const val RuntimeIdKey = "runtime_id"
    const val LocalModelHandleKey = "local_model_handle"
    const val LocalModelRuntimeKey = "local_model_runtime"
    const val LocalModelAcceleratorKey = "local_model_accelerator"
    const val LocalLettaCodeRuntime = "local-lettacode"

    val bindingKeys: Set<String> = setOf(
        RuntimeKey,
        RuntimeIdKey,
        "runtimeId",
        RuntimeProviderKey,
        "runtimeProvider",
    )
}

object AgentRuntimeBinding {
    fun isLocalRuntime(config: LettaConfig?): Boolean {
        val trimmedServerUrl = config?.serverUrl?.trim().orEmpty()
        return config?.mode == LettaConfig.Mode.LOCAL &&
            trimmedServerUrl.substringBefore("://", missingDelimiterValue = trimmedServerUrl).lowercase() in localRuntimeSchemes
    }

    fun isLocalBound(agent: Agent?): Boolean {
        if (agent == null) return false
        if (isKnownLocalModelHandle(agent.model)) return true
        if (isCloudModelHandle(agent.model)) return false
        val explicitRuntime = agent.metadata
            .filterKeys { it in LocalAgentRuntimeMetadata.bindingKeys }
            .values
            .mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            .firstOrNull { it.isNotBlank() }
        if (explicitRuntime != null) return explicitRuntime.startsWith("local-")
        if (agent.id.value.startsWith("local-agent-")) return true
        return false
    }

    private fun isKnownLocalModelHandle(model: String?): Boolean =
        model?.trim()?.lowercase() in localModelHandles

    private fun isCloudModelHandle(model: String?): Boolean {
        val handle = model?.trim()?.lowercase().orEmpty()
        if (handle.isBlank()) return false
        if (isKnownLocalModelHandle(handle)) return false
        return handle.substringBefore('/', missingDelimiterValue = handle) in cloudModelProviders
    }

    private val localModelHandles: Set<String> = setOf(
        "google/gemma-3n-e2b-it-litert-lm",
        "lmstudio/google/gemma-3n-e2b-it-litert-lm",
    )

    private val cloudModelProviders: Set<String> = setOf(
        "anthropic",
        "google-ai",
        "groq",
        "letta",
        "lmstudio",
        "openai",
        "openrouter",
        "perplexity",
        "together",
        "xai",
    )

    private val localRuntimeSchemes: Set<String> = setOf(
        "local-lettacode",
        "local-letta-code",
    )
}
