package com.letta.mobile.feature.editagent

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.repository.api.IToolRepository
import kotlinx.coroutines.flow.first

internal data class EditAgentLoadSnapshot(
    val uiState: EditAgentUiState,
    val originalBlocks: Map<String, EditableBlock>,
    val originalEmbedding: String,
    val originalProviderType: String,
)

internal class EditAgentAgentLoader(
    private val agentId: String,
    private val agentRepository: IAgentRepository,
    private val blockRepository: IBlockRepository,
    private val toolRepository: IToolRepository,
) {
    suspend fun load(llmModels: List<LlmModel>): EditAgentLoadSnapshot {
        val agent = agentRepository.getAgent(AgentId(agentId)).first()
        val editableBlocks = agent.blocks.map { block ->
            EditableBlock(
                id = block.id.value,
                label = block.label ?: "",
                value = block.value ?: "",
                description = block.description,
                limit = block.limit,
                isTemplate = block.isTemplate ?: false,
                readOnly = block.readOnly ?: false,
            )
        }
        runCatching { toolRepository.refreshTools() }
            .onFailure { android.util.Log.w("EditAgentVM", "Failed to load tools", it) }
        val availableTools = toolRepository.getTools().value
        val availableBlocks = runCatching { blockRepository.listAllBlocks() }
            .onFailure { android.util.Log.w("EditAgentVM", "Failed to load available blocks", it) }
            .getOrDefault(emptyList())
        val resolvedEmbedding = agent.embedding
            ?: agent.embeddingConfig?.handle
            ?: agent.embeddingConfig?.embeddingModel
            ?: ""
        val resolvedProviderType = agent.modelSettings?.providerType
            ?: agent.llmConfig?.modelEndpointType
            ?: agent.llmConfig?.providerName
            ?: ""
        val normalizedProviderType = EditAgentUseCases.normalizeModelSettingsProviderType(
            providerType = resolvedProviderType,
            modelHandle = agent.model ?: agent.llmConfig?.handle ?: agent.llmConfig?.model,
        ).orEmpty()
        return EditAgentLoadSnapshot(
            uiState = EditAgentUiStateMapper.fromAgent(
                agent = agent,
                editableBlocks = editableBlocks,
                availableTools = availableTools,
                availableBlocks = availableBlocks,
                resolvedEmbedding = resolvedEmbedding,
                normalizedProviderType = normalizedProviderType,
            ),
            originalBlocks = editableBlocks.associateBy { it.label },
            originalEmbedding = resolvedEmbedding,
            originalProviderType = normalizedProviderType,
        )
    }
}
