package com.letta.mobile.feature.editagent

import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonArray

internal fun EditAgentMapperInput.baseUiState(): EditAgentUiState {
    val agent = agent
    return EditAgentUiState(
        agent = agent,
        agentId = agent.id.value,
        name = agent.name,
        description = agent.description ?: "",
        model = agent.model ?: "",
        embedding = resolvedEmbedding,
        blocks = editableBlocks.toImmutableList(),
        systemPrompt = agent.system ?: "",
        tags = agent.tags.toImmutableList(),
        attachedTools = agent.tools.toImmutableList(),
        availableTools = availableTools.toImmutableList(),
        availableBlocks = availableBlocks.toImmutableList(),
        toolRulesJson = agent.toolRules
            .takeIf { it.isNotEmpty() }
            ?.let { JsonArray(it).toSettingsJson() }
            .orEmpty(),
        agentSecrets = agent.secrets.toEditableEnvironmentVariables(),
        toolEnvironmentVariables = agent.toolExecEnvironmentVariables.toEditableEnvironmentVariables(),
        providerType = normalizedProviderType,
        contextWindow = agent.contextWindowLimit ?: agent.llmConfig?.contextWindow ?: 0,
        enableSleeptime = agent.enableSleeptime ?: false,
        agentType = agent.agentType ?: "",
        embeddingDim = agent.embeddingConfig?.embeddingDim,
        embeddingChunkSize = agent.embeddingConfig?.embeddingChunkSize,
    )
}
