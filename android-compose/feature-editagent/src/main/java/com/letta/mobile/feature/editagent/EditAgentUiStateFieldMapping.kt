package com.letta.mobile.feature.editagent

import com.letta.mobile.data.model.Agent
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonArray

internal object EditAgentUiStateFieldMapping {
    fun modelSettingsFields(agent: Agent): ModelSettingsFields {
        val modelSettings = agent.modelSettings
        val llmConfig = agent.llmConfig
        val core = coreModelSettingsFields(modelSettings, llmConfig)
        val reasoning = reasoningModelSettingsFields(modelSettings, llmConfig)
        val response = responseModelSettingsFields(modelSettings, agent)
        return ModelSettingsFields(
            temperature = core.temperature,
            maxOutputTokens = core.maxOutputTokens,
            parallelToolCalls = core.parallelToolCalls,
            modelProviderName = core.modelProviderName,
            modelProviderCategory = core.modelProviderCategory,
            modelEnableReasoner = reasoning.modelEnableReasoner,
            modelReasoningEffort = reasoning.modelReasoningEffort,
            modelMaxReasoningTokens = reasoning.modelMaxReasoningTokens,
            modelReasoningJson = reasoning.modelReasoningJson,
            modelFrequencyPenalty = core.modelFrequencyPenalty,
            modelVerbosity = core.modelVerbosity,
            modelStrictToolCalling = response.modelStrictToolCalling,
            modelResponseFormatJson = response.modelResponseFormatJson,
            modelResponseSchemaJson = response.modelResponseSchemaJson,
            modelThinkingConfigJson = response.modelThinkingConfigJson,
            modelPutInnerThoughtsInKwargs = reasoning.modelPutInnerThoughtsInKwargs,
            modelToolCallParser = response.modelToolCallParser,
            modelAnthropicEffort = response.modelAnthropicEffort,
        )
    }

    fun compactionFields(agent: Agent): CompactionFields {
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

    fun baseUiState(input: EditAgentMapperInput): EditAgentUiState {
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

    private fun coreModelSettingsFields(
        modelSettings: com.letta.mobile.data.model.ModelSettings?,
        llmConfig: com.letta.mobile.data.model.LlmConfig?,
    ): CoreModelSettingsFields = CoreModelSettingsFields(
        temperature = modelSettings?.temperature?.toFloat() ?: llmConfig?.temperature?.toFloat() ?: 1.0f,
        maxOutputTokens = modelSettings?.maxOutputTokens ?: llmConfig?.maxTokens ?: 4096,
        parallelToolCalls = modelSettings?.parallelToolCalls ?: llmConfig?.parallelToolCalls ?: true,
        modelProviderName = modelSettings?.providerName ?: llmConfig?.providerName.orEmpty(),
        modelProviderCategory = modelSettings?.providerCategory ?: llmConfig?.providerCategory.orEmpty(),
        modelFrequencyPenalty = (modelSettings?.frequencyPenalty ?: llmConfig?.frequencyPenalty)
            ?.toString()
            .orEmpty(),
        modelVerbosity = modelSettings?.verbosity ?: llmConfig?.verbosity.orEmpty(),
    )

    private fun reasoningModelSettingsFields(
        modelSettings: com.letta.mobile.data.model.ModelSettings?,
        llmConfig: com.letta.mobile.data.model.LlmConfig?,
    ): ReasoningModelSettingsFields = ReasoningModelSettingsFields(
        modelEnableReasoner = modelSettings?.enableReasoner ?: llmConfig?.enableReasoner ?: false,
        modelReasoningEffort = modelSettings?.reasoningEffort ?: llmConfig?.reasoningEffort.orEmpty(),
        modelMaxReasoningTokens = (modelSettings?.maxReasoningTokens ?: llmConfig?.maxReasoningTokens)
            ?.toString()
            .orEmpty(),
        modelReasoningJson = modelSettings?.reasoning?.toSettingsJson().orEmpty(),
        modelPutInnerThoughtsInKwargs = modelSettings?.putInnerThoughtsInKwargs
            ?: llmConfig?.putInnerThoughtsInKwargs
            ?: false,
    )

    private fun responseModelSettingsFields(
        modelSettings: com.letta.mobile.data.model.ModelSettings?,
        agent: Agent,
    ): ResponseModelSettingsFields = ResponseModelSettingsFields(
        modelStrictToolCalling = modelSettings?.strict ?: false,
        modelResponseFormatJson = (modelSettings?.responseFormat ?: agent.responseFormat)
            ?.toSettingsJson()
            .orEmpty(),
        modelResponseSchemaJson = modelSettings?.responseSchema?.toSettingsJson().orEmpty(),
        modelThinkingConfigJson = modelSettings?.thinkingConfig?.toSettingsJson().orEmpty(),
        modelToolCallParser = modelSettings?.toolCallParser.orEmpty(),
        modelAnthropicEffort = modelSettings?.effort.orEmpty(),
    )
}

internal data class ModelSettingsFields(
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

internal data class CompactionFields(
    val summarizationPrompt: String,
    val compactionClipChars: Int,
    val slidingWindowPercentage: Float,
    val promptAcknowledgement: Boolean,
    val compactionMode: String,
    val compactionModel: String,
    val compactionModelSettingsJson: String,
)

private data class CoreModelSettingsFields(
    val temperature: Float,
    val maxOutputTokens: Int,
    val parallelToolCalls: Boolean,
    val modelProviderName: String,
    val modelProviderCategory: String,
    val modelFrequencyPenalty: String,
    val modelVerbosity: String,
)

private data class ReasoningModelSettingsFields(
    val modelEnableReasoner: Boolean,
    val modelReasoningEffort: String,
    val modelMaxReasoningTokens: String,
    val modelReasoningJson: String,
    val modelPutInnerThoughtsInKwargs: Boolean,
)

private data class ResponseModelSettingsFields(
    val modelStrictToolCalling: Boolean,
    val modelResponseFormatJson: String,
    val modelResponseSchemaJson: String,
    val modelThinkingConfigJson: String,
    val modelToolCallParser: String,
    val modelAnthropicEffort: String,
)
