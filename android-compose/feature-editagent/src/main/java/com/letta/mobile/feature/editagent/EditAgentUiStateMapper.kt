package com.letta.mobile.feature.editagent

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.Tool
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonArray

internal data class EditAgentMapperInput(
    val agent: Agent,
    val editableBlocks: List<EditableBlock>,
    val availableTools: List<Tool>,
    val availableBlocks: List<Block>,
    val resolvedEmbedding: String,
    val normalizedProviderType: String,
)

internal object EditAgentUiStateMapper {
    fun fromAgent(input: EditAgentMapperInput): EditAgentUiState {
        val modelFields = modelSettingsFields(input.agent)
        val compaction = compactionFields(input.agent)
        return baseUiState(input).copy(
            temperature = modelFields.temperature,
            maxOutputTokens = modelFields.maxOutputTokens,
            parallelToolCalls = modelFields.parallelToolCalls,
            modelProviderName = modelFields.modelProviderName,
            modelProviderCategory = modelFields.modelProviderCategory,
            modelEnableReasoner = modelFields.modelEnableReasoner,
            modelReasoningEffort = modelFields.modelReasoningEffort,
            modelMaxReasoningTokens = modelFields.modelMaxReasoningTokens,
            modelReasoningJson = modelFields.modelReasoningJson,
            modelFrequencyPenalty = modelFields.modelFrequencyPenalty,
            modelVerbosity = modelFields.modelVerbosity,
            modelStrictToolCalling = modelFields.modelStrictToolCalling,
            modelResponseFormatJson = modelFields.modelResponseFormatJson,
            modelResponseSchemaJson = modelFields.modelResponseSchemaJson,
            modelThinkingConfigJson = modelFields.modelThinkingConfigJson,
            modelPutInnerThoughtsInKwargs = modelFields.modelPutInnerThoughtsInKwargs,
            modelToolCallParser = modelFields.modelToolCallParser,
            modelAnthropicEffort = modelFields.modelAnthropicEffort,
            summarizationPrompt = compaction.summarizationPrompt,
            compactionClipChars = compaction.compactionClipChars,
            slidingWindowPercentage = compaction.slidingWindowPercentage,
            promptAcknowledgement = compaction.promptAcknowledgement,
            compactionMode = compaction.compactionMode,
            compactionModel = compaction.compactionModel,
            compactionModelSettingsJson = compaction.compactionModelSettingsJson,
        )
    }

    private fun baseUiState(input: EditAgentMapperInput): EditAgentUiState {
        val agent = input.agent
        return EditAgentUiState(
            agent = agent,
            agentId = agent.id.value,
            name = agent.name,
            description = agent.description ?: "",
            model = agent.model ?: "",
            embedding = input.resolvedEmbedding,
            blocks = input.editableBlocks.toImmutableList(),
            systemPrompt = agent.system ?: "",
            tags = agent.tags.toImmutableList(),
            attachedTools = agent.tools.toImmutableList(),
            availableTools = input.availableTools.toImmutableList(),
            availableBlocks = input.availableBlocks.toImmutableList(),
            toolRulesJson = agent.toolRules
                .takeIf { it.isNotEmpty() }
                ?.let { JsonArray(it).toSettingsJson() }
                .orEmpty(),
            agentSecrets = agent.secrets.toEditableEnvironmentVariables(),
            toolEnvironmentVariables = agent.toolExecEnvironmentVariables.toEditableEnvironmentVariables(),
            providerType = input.normalizedProviderType,
            contextWindow = agent.contextWindowLimit ?: agent.llmConfig?.contextWindow ?: 0,
            enableSleeptime = agent.enableSleeptime ?: false,
            agentType = agent.agentType ?: "",
            embeddingDim = agent.embeddingConfig?.embeddingDim,
            embeddingChunkSize = agent.embeddingConfig?.embeddingChunkSize,
        )
    }

    private data class ModelSettingsFields(
        val temperature: Float,
        val maxOutputTokens: Int,
        val parallelToolCalls: Boolean,
        val modelProviderName: String,
        val modelProviderCategory: String,
        val modelEnableReasoner: Boolean,
        val modelReasoningEffort: String,
        val modelMaxReasoningTokens: String,
        val modelReasoningJson: String,
        val modelFrequencyPenalty: String,
        val modelVerbosity: String,
        val modelStrictToolCalling: Boolean,
        val modelResponseFormatJson: String,
        val modelResponseSchemaJson: String,
        val modelThinkingConfigJson: String,
        val modelPutInnerThoughtsInKwargs: Boolean,
        val modelToolCallParser: String,
        val modelAnthropicEffort: String,
    )

    private data class CompactionFields(
        val summarizationPrompt: String,
        val compactionClipChars: Int,
        val slidingWindowPercentage: Float,
        val promptAcknowledgement: Boolean,
        val compactionMode: String,
        val compactionModel: String,
        val compactionModelSettingsJson: String,
    )

    private fun modelSettingsFields(agent: Agent): ModelSettingsFields {
        val modelSettings = agent.modelSettings
        val llmConfig = agent.llmConfig
        return ModelSettingsFields(
            temperature = modelSettings?.temperature?.toFloat() ?: llmConfig?.temperature?.toFloat() ?: 1.0f,
            maxOutputTokens = modelSettings?.maxOutputTokens ?: llmConfig?.maxTokens ?: 4096,
            parallelToolCalls = modelSettings?.parallelToolCalls ?: llmConfig?.parallelToolCalls ?: true,
            modelProviderName = modelSettings?.providerName ?: llmConfig?.providerName.orEmpty(),
            modelProviderCategory = modelSettings?.providerCategory ?: llmConfig?.providerCategory.orEmpty(),
            modelEnableReasoner = modelSettings?.enableReasoner ?: llmConfig?.enableReasoner ?: false,
            modelReasoningEffort = modelSettings?.reasoningEffort ?: llmConfig?.reasoningEffort.orEmpty(),
            modelMaxReasoningTokens = (modelSettings?.maxReasoningTokens ?: llmConfig?.maxReasoningTokens)
                ?.toString()
                .orEmpty(),
            modelReasoningJson = modelSettings?.reasoning?.toSettingsJson().orEmpty(),
            modelFrequencyPenalty = (modelSettings?.frequencyPenalty ?: llmConfig?.frequencyPenalty)
                ?.toString()
                .orEmpty(),
            modelVerbosity = modelSettings?.verbosity ?: llmConfig?.verbosity.orEmpty(),
            modelStrictToolCalling = modelSettings?.strict ?: false,
            modelResponseFormatJson = (modelSettings?.responseFormat ?: agent.responseFormat)
                ?.toSettingsJson()
                .orEmpty(),
            modelResponseSchemaJson = modelSettings?.responseSchema?.toSettingsJson().orEmpty(),
            modelThinkingConfigJson = modelSettings?.thinkingConfig?.toSettingsJson().orEmpty(),
            modelPutInnerThoughtsInKwargs = modelSettings?.putInnerThoughtsInKwargs
                ?: llmConfig?.putInnerThoughtsInKwargs
                ?: false,
            modelToolCallParser = modelSettings?.toolCallParser.orEmpty(),
            modelAnthropicEffort = modelSettings?.effort.orEmpty(),
        )
    }

    private fun compactionFields(agent: Agent): CompactionFields {
        val compactionSettings = agent.compactionSettings
        return CompactionFields(
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
