package com.letta.mobile.feature.editagent

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.ModelSettings
import com.letta.mobile.data.model.LlmConfig

internal fun Agent.modelSettingsFields(): ModelSettingsFields {
    val core = coreModelSettingsFields(modelSettings, llmConfig)
    val reasoning = reasoningModelSettingsFields(modelSettings, llmConfig)
    val response = responseModelSettingsFields(modelSettings, this)
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

private fun coreModelSettingsFields(
    modelSettings: ModelSettings?,
    llmConfig: LlmConfig?,
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
    modelSettings: ModelSettings?,
    llmConfig: LlmConfig?,
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
    modelSettings: ModelSettings?,
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
