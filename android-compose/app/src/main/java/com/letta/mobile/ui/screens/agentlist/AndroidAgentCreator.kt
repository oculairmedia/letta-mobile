package com.letta.mobile.ui.screens.agentlist

import com.letta.mobile.data.agenteditor.AgentCreated
import com.letta.mobile.data.agenteditor.AgentCreator
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.runtime.local.EmbeddedLettaCodeModelSelection
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatusProvider
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogItem
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDownloadState
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelRepository
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
 * letta-mobile-ckmub.3.24: how Android creates an agent from the shared agent editor. Every backend
 * creates it on the session's App Server (letta-mobile-by4vm.110.6); under the on-device runtime the
 * agent defaults to the model Settings picked for it, once that model is ready.
 */
class AndroidAgentCreator @Inject constructor(
    private val agentRepository: IAgentRepository,
    private val settingsRepository: ISettingsRepository,
    private val embeddedRuntimeStatusProvider: EmbeddedLettaCodeRuntimeStatusProvider,
    private val embeddedModelRepository: EmbeddedModelRepository,
) : AgentCreator {
    override suspend fun create(params: AgentCreateParams): AgentCreated {
        val config = settingsRepository.activeConfig.value
        val toCreate = if (config?.mode == LettaConfig.Mode.LOCAL) {
            config.localLettaCodeCreateReadiness(
                runtimeRunnable = embeddedRuntimeStatusProvider.status.runnable,
                downloadedModelHandles = embeddedModelRepository.catalog.value.downloadedModelHandles(),
            ).setupMessage?.let { throw IllegalStateException(it) }
            params.withOnDeviceModel(config)
        } else {
            params
        }
        val agent = agentRepository.createAgent(toCreate)
        agentRepository.refreshAgents()
        return AgentCreated(agent.id.value)
    }
}

internal fun LettaConfig?.localLettaCodeCreateReadiness(
    runtimeRunnable: Boolean,
    downloadedModelHandles: Set<String>,
): LocalLettaCodeCreateReadiness {
    val config = this
    val activeConfigIsLocal = config?.mode == LettaConfig.Mode.LOCAL
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

/** The on-device runtime's `lc-lmstudio` handle for the configured model, unless the editor picked one. */
private fun AgentCreateParams.withOnDeviceModel(config: LettaConfig): AgentCreateParams =
    copy(model = model?.trim()?.takeIf { it.isNotBlank() } ?: EmbeddedLettaCodeModelSelection.from(config).lettaCodeModelHandle)
