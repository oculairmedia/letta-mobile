package com.letta.mobile.feature.editagent

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.Tool
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
        val editableBlocks = agent.toEditableBlocks()
        val availableTools = loadAvailableTools()
        val availableBlocks = loadAvailableBlocks()
        val resolvedEmbedding = agent.resolveEmbedding()
        val normalizedProviderType = agent.resolveProviderType()
        return EditAgentLoadSnapshot(
            uiState = EditAgentUiStateMapper.fromAgent(
                EditAgentMapperInput(
                    agent = agent,
                    editableBlocks = editableBlocks,
                    availableTools = availableTools,
                    availableBlocks = availableBlocks,
                    resolvedEmbedding = resolvedEmbedding,
                    normalizedProviderType = normalizedProviderType,
                )
            ),
            originalBlocks = editableBlocks.associateBy { it.label },
            originalEmbedding = resolvedEmbedding,
            originalProviderType = normalizedProviderType,
        )
    }

    private suspend fun loadAvailableTools(): List<Tool> {
        runCatching { toolRepository.refreshTools() }
            .onFailure { android.util.Log.w("EditAgentVM", "Failed to load tools", it) }
        return toolRepository.getTools().value
    }

    private suspend fun loadAvailableBlocks(): List<Block> =
        runCatching { blockRepository.listAllBlocks() }
            .onFailure { android.util.Log.w("EditAgentVM", "Failed to load available blocks", it) }
            .getOrDefault(emptyList())

    private fun Agent.toEditableBlocks(): List<EditableBlock> =
        blocks.map { block ->
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

    private fun Agent.resolveEmbedding(): String =
        embedding
            ?: embeddingConfig?.handle
            ?: embeddingConfig?.embeddingModel
            ?: ""

    private fun Agent.resolveProviderType(): String {
        val resolvedProviderType = modelSettings?.providerType
            ?: llmConfig?.modelEndpointType
            ?: llmConfig?.providerName
            ?: ""
        return EditAgentUseCases.normalizeModelSettingsProviderType(
            providerType = resolvedProviderType,
            modelHandle = model ?: llmConfig?.handle ?: llmConfig?.model,
        ).orEmpty()
    }
}
