package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LocalAgentRuntimeMetadata
import com.letta.mobile.data.model.ModelSettings
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.feature.chat.state.ChatBannerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Model picker and local-runtime binding updates for [AdminChatViewModel].
 */
internal class AdminChatModelCoordinator(
    private val scope: CoroutineScope,
    private val agentId: AgentId,
    private val agentRepository: IAgentRepository,
    private val modelRepository: IModelRepository,
    private val settingsRepository: ISettingsRepository,
    private val activeAgent: StateFlow<Agent?>,
    private val bannerController: ChatBannerController,
) {
    private val localRuntimeModelSwitchMetadataKeys: Set<String> = LocalAgentRuntimeMetadata.bindingKeys + setOf(
        LocalAgentRuntimeMetadata.LocalModelHandleKey,
        LocalAgentRuntimeMetadata.LocalModelRuntimeKey,
        LocalAgentRuntimeMetadata.LocalModelAcceleratorKey,
    )

    fun refreshModels() {
        scope.launch {
            runCatching { modelRepository.refreshLlmModels() }
        }
    }

    fun updateActiveAgentModel(handle: String) {
        scope.launch {
            try {
                val config = settingsRepository.activeConfig.firstOrNull()
                agentRepository.updateAgent(
                    agentId,
                    modelSwitchUpdateParams(handle, config, activeAgent.value),
                )
                refreshModels()
            } catch (e: Exception) {
                val currentModel = activeAgent.value?.model ?: "unknown"
                bannerController.showError("Couldn't switch model — still on $currentModel")
                runCatching { agentRepository.refreshAgents() }
            }
        }
    }

    fun modelSwitchUpdateParams(handle: String, config: LettaConfig?, agent: Agent?): AgentUpdateParams {
        val normalizedHandle = handle.trim()
        val baseMetadata = agent?.metadata.orEmpty().filterKeys { it !in localRuntimeModelSwitchMetadataKeys }
        val localSelection = resolveLocalModelSelection(normalizedHandle, config)
        if (!localSelection.isSelected || config == null) {
            return AgentUpdateParams(model = normalizedHandle, metadata = baseMetadata)
        }
        return buildLocalModelUpdateParams(
            agent = agent,
            baseMetadata = baseMetadata,
            effectiveLocalModelHandle = localSelection.effectiveHandle,
            config = config,
        )
    }
}

private data class LocalModelSelection(
    val isSelected: Boolean,
    val effectiveHandle: String?,
)

private fun resolveKnownGemmaLocalHandle(normalizedLower: String): String? = when (normalizedLower) {
    "google/gemma-3n-e2b-it-litert-lm",
    "lmstudio/google/gemma-3n-e2b-it-litert-lm" -> "google/gemma-3n-E2B-it-litert-lm"
    else -> null
}

private fun resolveLocalModelSelection(
    normalizedHandle: String,
    config: LettaConfig?,
): LocalModelSelection {
    val configuredLocalModelHandle = config?.localModelHandle?.trim()?.takeIf { it.isNotBlank() }
    val knownGemmaLocalHandle = resolveKnownGemmaLocalHandle(normalizedHandle.lowercase())
    val effectiveLocalModelHandle = knownGemmaLocalHandle ?: configuredLocalModelHandle
    val localLeattaCodeHandle = effectiveLocalModelHandle?.let { "lmstudio/${it.removePrefix("lmstudio/")}" }
    val localSelected = com.letta.mobile.data.model.AgentRuntimeBinding.isLocalRuntime(config) &&
        effectiveLocalModelHandle != null &&
        (knownGemmaLocalHandle != null || normalizedHandle in setOf(effectiveLocalModelHandle, localLeattaCodeHandle))
    return LocalModelSelection(
        isSelected = localSelected,
        effectiveHandle = effectiveLocalModelHandle,
    )
}

private fun buildLocalModelUpdateParams(
    agent: Agent?,
    baseMetadata: Map<String, kotlinx.serialization.json.JsonElement>,
    effectiveLocalModelHandle: String?,
    config: LettaConfig,
): AgentUpdateParams {
    val runtime = config.localModelRuntime?.trim()?.takeIf { it.isNotBlank() } ?: "litert-lm"
    val accelerator = config.localModelAccelerator?.trim()?.takeIf { it.isNotBlank() } ?: "gpu"
    val maxTokens = config.localModelMaxTokens?.takeIf { it > 0 } ?: 4096
    val localMetadata = mapOf(
        LocalAgentRuntimeMetadata.RuntimeKey to JsonPrimitive(LocalAgentRuntimeMetadata.LocalLettaCodeRuntime),
        LocalAgentRuntimeMetadata.RuntimeProviderKey to JsonPrimitive(LocalAgentRuntimeMetadata.LocalLettaCodeRuntime),
        LocalAgentRuntimeMetadata.RuntimeIdKey to JsonPrimitive("${LocalAgentRuntimeMetadata.LocalLettaCodeRuntime}:${config.id}"),
        LocalAgentRuntimeMetadata.LocalModelHandleKey to JsonPrimitive(effectiveLocalModelHandle),
        LocalAgentRuntimeMetadata.LocalModelRuntimeKey to JsonPrimitive(runtime.lowercase()),
        LocalAgentRuntimeMetadata.LocalModelAcceleratorKey to JsonPrimitive(accelerator.lowercase()),
    )
    return AgentUpdateParams(
        model = effectiveLocalModelHandle,
        metadata = baseMetadata + localMetadata,
        modelSettings = (agent?.modelSettings ?: ModelSettings()).copy(
            providerType = LocalAgentRuntimeMetadata.LocalLettaCodeRuntime,
            parallelToolCalls = false,
            maxOutputTokens = maxTokens,
        ),
    )
}
