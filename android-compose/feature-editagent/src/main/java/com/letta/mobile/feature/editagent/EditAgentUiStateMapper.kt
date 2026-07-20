package com.letta.mobile.feature.editagent

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.Tool
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonArray

internal object EditAgentUiStateMapper {
    fun fromAgent(
        agent: Agent,
        editableBlocks: List<EditableBlock>,
        availableTools: List<Tool>,
        availableBlocks: List<Block>,
        resolvedEmbedding: String,
        normalizedProviderType: String,
    ): EditAgentUiState {
        val compactionSettings = agent.compactionSettings
        val modelSettings = agent.modelSettings
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
            temperature = modelSettings?.temperature?.toFloat() ?: agent.llmConfig?.temperature?.toFloat() ?: 1.0f,
            maxOutputTokens = modelSettings?.maxOutputTokens ?: agent.llmConfig?.maxTokens ?: 4096,
            parallelToolCalls = modelSettings?.parallelToolCalls ?: agent.llmConfig?.parallelToolCalls ?: true,
            modelProviderName = modelSettings?.providerName ?: agent.llmConfig?.providerName.orEmpty(),
            modelProviderCategory = modelSettings?.providerCategory ?: agent.llmConfig?.providerCategory.orEmpty(),
            modelEnableReasoner = modelSettings?.enableReasoner ?: agent.llmConfig?.enableReasoner ?: false,
            modelReasoningEffort = modelSettings?.reasoningEffort ?: agent.llmConfig?.reasoningEffort.orEmpty(),
            modelMaxReasoningTokens = (modelSettings?.maxReasoningTokens ?: agent.llmConfig?.maxReasoningTokens)
                ?.toString()
                .orEmpty(),
            modelReasoningJson = modelSettings?.reasoning?.toSettingsJson().orEmpty(),
            modelFrequencyPenalty = (modelSettings?.frequencyPenalty ?: agent.llmConfig?.frequencyPenalty)
                ?.toString()
                .orEmpty(),
            modelVerbosity = modelSettings?.verbosity ?: agent.llmConfig?.verbosity.orEmpty(),
            modelStrictToolCalling = modelSettings?.strict ?: false,
            modelResponseFormatJson = (modelSettings?.responseFormat ?: agent.responseFormat)
                ?.toSettingsJson()
                .orEmpty(),
            modelResponseSchemaJson = modelSettings?.responseSchema?.toSettingsJson().orEmpty(),
            modelThinkingConfigJson = modelSettings?.thinkingConfig?.toSettingsJson().orEmpty(),
            modelPutInnerThoughtsInKwargs = modelSettings?.putInnerThoughtsInKwargs
                ?: agent.llmConfig?.putInnerThoughtsInKwargs
                ?: false,
            modelToolCallParser = modelSettings?.toolCallParser.orEmpty(),
            modelAnthropicEffort = modelSettings?.effort.orEmpty(),
            contextWindow = agent.contextWindowLimit ?: agent.llmConfig?.contextWindow ?: 0,
            enableSleeptime = agent.enableSleeptime ?: false,
            agentType = agent.agentType ?: "",
            embeddingDim = agent.embeddingConfig?.embeddingDim,
            embeddingChunkSize = agent.embeddingConfig?.embeddingChunkSize,
            summarizationPrompt = compactionSettings?.prompt.orEmpty(),
            compactionClipChars = compactionSettings?.clipChars ?: 50_000,
            slidingWindowPercentage = compactionSettings
                ?.slidingWindowPercentage
                ?.toFloat()
                ?.coerceIn(0f, 1f)
                ?: 0.3f,
            promptAcknowledgement = compactionSettings?.promptAcknowledgement ?: false,
            compactionMode = compactionSettings?.mode ?: "sliding_window",
            compactionModel = compactionSettings?.model.orEmpty(),
            compactionModelSettingsJson = compactionSettings
                ?.modelSettings
                ?.toSettingsJson()
                .orEmpty(),
        )
    }
}
