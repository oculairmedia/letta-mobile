package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.api.LocalRuntimeModelSource
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDownloadState
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serves downloaded embedded-catalog models as [LlmModel]s so model pickers
 * (edit-agent, dashboards) have real options in local-runtime mode instead of
 * an empty remote list.
 */
@Singleton
class EmbeddedCatalogModelSource @Inject constructor(
    private val embeddedModelRepository: EmbeddedModelRepository,
) : LocalRuntimeModelSource {
    override suspend fun listLlmModels(): List<LlmModel> {
        embeddedModelRepository.refresh()
        return embeddedModelRepository.catalog.value
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
    }
}
