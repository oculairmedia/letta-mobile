package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeModelSource
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDownloadState
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Models available in local-runtime mode, served to every model picker
 * through the repository routing:
 *
 *  - custom OpenAI-compatible endpoint configured → the endpoint's own
 *    model list (letta-mobile-3icw7), live from GET {base}/models
 *  - downloaded embedded-catalog models (on-device LiteRT)
 */
@Singleton
class EmbeddedCatalogModelSource @Inject constructor(
    private val embeddedModelRepository: EmbeddedModelRepository,
    private val endpointCatalog: EndpointOpenAiModelCatalog,
    private val settingsRepository: ISettingsRepository,
) : LocalRuntimeModelSource {
    override suspend fun listLlmModels(): List<LlmModel> {
        val config = settingsRepository.activeConfig.value
        val endpointModels = config?.localProviderBaseUrl
            ?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?.let { baseUrl -> endpointCatalog.listModels(baseUrl, config.localProviderApiKey) }
            .orEmpty()
            .map { model -> model.copy(handle = model.handle?.toLettaCodeHandle()) }

        embeddedModelRepository.refresh()
        val downloaded = embeddedModelRepository.catalog.value
            .filter { item -> item.state is EmbeddedModelDownloadState.Downloaded }
            .map { item ->
                LlmModel(
                    id = item.entry.modelId,
                    name = item.entry.name,
                    handle = item.entry.modelId,
                    providerType = "local-lettacode",
                    providerName = "Embedded LettaCode",
                    contextWindow = null,
                    maxOutputTokens = item.entry.defaultConfig.maxTokens,
                )
            }

        return endpointModels + downloaded
    }

    // Remote endpoint selections need the lmstudio/ provider prefix; embedded
    // LiteRT selections stay bare so they remain on the on-device path.
    private fun String.toLettaCodeHandle(): String =
        if (startsWith("lmstudio/")) this else "lmstudio/$this"
}
