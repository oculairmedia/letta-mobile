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
        if (agent.id.value.startsWith("local-agent-")) return true
        return agent.metadata.any { (key, value) ->
            key in LocalAgentRuntimeMetadata.bindingKeys &&
                value.jsonPrimitive.contentOrNull?.startsWith("local-") == true
        }
    }

    private val localRuntimeSchemes: Set<String> = setOf(
        "local-lettacode",
        "local-letta-code",
    )
}
