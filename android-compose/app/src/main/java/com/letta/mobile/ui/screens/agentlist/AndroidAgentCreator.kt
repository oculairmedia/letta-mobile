package com.letta.mobile.ui.screens.agentlist

import com.letta.mobile.data.agenteditor.AgentCreated
import com.letta.mobile.data.agenteditor.AgentCreator
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentRuntimeBinding
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LocalAgentRuntimeMetadata
import com.letta.mobile.data.model.ModelSettings
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.runtime.local.EmbeddedLettaCodeModelSelection
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatusProvider
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogItem
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDownloadState
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelRepository
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

data class LocalLettaCodeCreateReadiness(
    val runtimeEnabled: Boolean = false,
    val modelSelected: Boolean = false,
    val modelDownloaded: Boolean = false,
    val activeConfigIsLocal: Boolean = false,
) {
    val ready: Boolean
        get() = runtimeEnabled && modelSelected && modelDownloaded && activeConfigIsLocal

    val setupMessage: String?
        get() = when {
            !runtimeEnabled -> "This build does not include the embedded Local LettaCode runtime."
            !activeConfigIsLocal -> "Enable Local LettaCode in Settings to create agents that run on this device."
            !modelDownloaded -> "Download or import a model in Settings before creating a local agent."
            !modelSelected -> "Choose which downloaded model Local LettaCode should use."
            else -> null
        }
}

/**
 * letta-mobile-ckmub.3.24: how Android creates an agent from the shared agent editor (moved from the
 * agent list's create dialog). Under the on-device runtime (Local LettaCode) the agent is bound to
 * it and opens on its one embedded conversation; anywhere else it is created on the session's
 * repository, which the roster then refreshes from.
 */
class AndroidAgentCreator @Inject constructor(
    private val agentRepository: IAgentRepository,
    private val settingsRepository: ISettingsRepository,
    private val embeddedRuntimeStatusProvider: EmbeddedLettaCodeRuntimeStatusProvider,
    private val embeddedModelRepository: EmbeddedModelRepository,
) : AgentCreator {
    override suspend fun create(params: AgentCreateParams): AgentCreated {
        val config = settingsRepository.activeConfig.value
        if (config == null || !AgentRuntimeBinding.isLocalRuntime(config)) {
            val agent = agentRepository.createAgent(params)
            agentRepository.refreshAgents()
            return AgentCreated(agent.id.value)
        }
        val readiness = config.localLettaCodeCreateReadiness(
            runtimeRunnable = embeddedRuntimeStatusProvider.status.runnable,
            downloadedModelHandles = embeddedModelRepository.catalog.value.downloadedModelHandles(),
        )
        readiness.setupMessage?.let { throw IllegalStateException(it) }
        val agent = agentRepository.createLocalAgent(params.withLocalLettaCodeRuntimeBinding(config))
        // Stable per agent: every embedded session is the same on-disk default conversation; a random
        // suffix would fragment the timeline and never match list rows.
        return AgentCreated(agent.id.value, conversationId = "local-conv-${agent.id.value}")
    }
}

internal fun LettaConfig?.localLettaCodeCreateReadiness(
    runtimeRunnable: Boolean,
    downloadedModelHandles: Set<String>,
): LocalLettaCodeCreateReadiness {
    val config = this
    val activeConfigIsLocal = AgentRuntimeBinding.isLocalRuntime(config)
    // A custom OpenAI-compatible endpoint replaces the on-device model
    // entirely (letta-mobile-3icw7) — no download/import required.
    val customProviderConfigured = config?.localProviderBaseUrl?.trim()?.takeIf { it.isNotBlank() } != null
    val selectedModel = config?.selectedLocalModelHandle()
    val selectedImportedPath = config?.localModelPath?.trim()?.takeIf { it.isNotBlank() }
    val modelDownloaded = customProviderConfigured ||
        selectedImportedPath != null ||
        (selectedModel != null && selectedModel in downloadedModelHandles)
    return LocalLettaCodeCreateReadiness(
        runtimeEnabled = runtimeRunnable,
        modelSelected = customProviderConfigured || (selectedModel != null && modelDownloaded),
        modelDownloaded = modelDownloaded,
        activeConfigIsLocal = activeConfigIsLocal,
    )
}

private fun LettaConfig.selectedLocalModelHandle(): String? = localModelHandle
    ?.trim()
    ?.takeIf { it.isNotBlank() && it != EmbeddedLettaCodeModelSelection.DEFAULT_MODEL_HANDLE }

internal fun List<EmbeddedModelCatalogItem>.downloadedModelHandles(): Set<String> =
    mapNotNull { item ->
        item.entry.modelId.takeIf { item.state is EmbeddedModelDownloadState.Downloaded }
    }.toSet()

/** Text only, tools and approvals off: what the on-device runtime runs today. */
private fun AgentCreateParams.withLocalLettaCodeRuntimeBinding(config: LettaConfig): AgentCreateParams {
    val selection = EmbeddedLettaCodeModelSelection.from(config)
    val pickedModel = model?.trim()?.takeIf { it.isNotBlank() }
    val effectiveModel = pickedModel ?: selection.modelHandle
    val localMetadata = mapOf(
        LocalAgentRuntimeMetadata.RUNTIME_KEY to JsonPrimitive(LocalAgentRuntimeMetadata.LOCAL_LETTA_CODE_RUNTIME),
        LocalAgentRuntimeMetadata.RUNTIME_PROVIDER_KEY to JsonPrimitive(LocalAgentRuntimeMetadata.LOCAL_LETTA_CODE_RUNTIME),
        LocalAgentRuntimeMetadata.RUNTIME_ID_KEY to JsonPrimitive("${LocalAgentRuntimeMetadata.LOCAL_LETTA_CODE_RUNTIME}:${config.id}"),
        // Track the effective model, not the config default — otherwise the
        // agent record's model and its metadata handle disagree and the
        // per-agent override could be lost on later reads (CodeRabbit).
        LocalAgentRuntimeMetadata.LOCAL_MODEL_HANDLE_KEY to JsonPrimitive(effectiveModel),
        LocalAgentRuntimeMetadata.LOCAL_MODEL_RUNTIME_KEY to JsonPrimitive(selection.runtime),
        LocalAgentRuntimeMetadata.LOCAL_MODEL_ACCELERATOR_KEY to JsonPrimitive(selection.accelerator),
    )
    return copy(
        model = effectiveModel,
        modelSettings = (modelSettings ?: ModelSettings()).copy(
            providerType = LocalAgentRuntimeMetadata.LOCAL_LETTA_CODE_RUNTIME,
            parallelToolCalls = false,
            maxOutputTokens = selection.maxTokens,
        ),
        metadata = metadata.orEmpty() + localMetadata,
        toolIds = null,
        tools = null,
        enableSleeptime = false,
        includeBaseTools = false,
        includeMultiAgentTools = false,
        includeBaseToolRules = false,
        parallelToolCalls = false,
    )
}
