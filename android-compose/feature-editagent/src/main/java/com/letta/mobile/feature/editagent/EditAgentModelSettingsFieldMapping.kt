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
    temperature = resolveTemperature(modelSettings, llmConfig),
    maxOutputTokens = resolveMaxOutputTokens(modelSettings, llmConfig),
    parallelToolCalls = resolveParallelToolCalls(modelSettings, llmConfig),
    modelProviderName = resolveProviderName(modelSettings, llmConfig),
    modelProviderCategory = resolveProviderCategory(modelSettings, llmConfig),
    modelFrequencyPenalty = resolveFrequencyPenalty(modelSettings, llmConfig),
    modelVerbosity = resolveVerbosity(modelSettings, llmConfig),
)

private fun resolveTemperature(modelSettings: ModelSettings?, llmConfig: LlmConfig?): Float =
    modelSettings?.temperature?.toFloat() ?: llmConfig?.temperature?.toFloat() ?: 1.0f

private fun resolveMaxOutputTokens(modelSettings: ModelSettings?, llmConfig: LlmConfig?): Int =
    modelSettings?.maxOutputTokens ?: llmConfig?.maxTokens ?: 4096

private fun resolveParallelToolCalls(modelSettings: ModelSettings?, llmConfig: LlmConfig?): Boolean =
    modelSettings?.parallelToolCalls ?: llmConfig?.parallelToolCalls ?: true

private fun resolveProviderName(modelSettings: ModelSettings?, llmConfig: LlmConfig?): String =
    modelSettings?.providerName ?: llmConfig?.providerName.orEmpty()

private fun resolveProviderCategory(modelSettings: ModelSettings?, llmConfig: LlmConfig?): String =
    modelSettings?.providerCategory ?: llmConfig?.providerCategory.orEmpty()

private fun resolveFrequencyPenalty(modelSettings: ModelSettings?, llmConfig: LlmConfig?): String =
    (modelSettings?.frequencyPenalty ?: llmConfig?.frequencyPenalty)?.toString().orEmpty()

private fun resolveVerbosity(modelSettings: ModelSettings?, llmConfig: LlmConfig?): String =
    modelSettings?.verbosity ?: llmConfig?.verbosity.orEmpty()

private fun reasoningModelSettingsFields(
    modelSettings: ModelSettings?,
    llmConfig: LlmConfig?,
): ReasoningModelSettingsFields = ReasoningModelSettingsFields(
    modelEnableReasoner = resolveEnableReasoner(modelSettings, llmConfig),
    modelReasoningEffort = resolveReasoningEffort(modelSettings, llmConfig),
    modelMaxReasoningTokens = resolveMaxReasoningTokens(modelSettings, llmConfig),
    modelReasoningJson = resolveReasoningJson(modelSettings),
    modelPutInnerThoughtsInKwargs = resolvePutInnerThoughtsInKwargs(modelSettings, llmConfig),
)

private fun resolveEnableReasoner(modelSettings: ModelSettings?, llmConfig: LlmConfig?): Boolean =
    modelSettings?.enableReasoner ?: llmConfig?.enableReasoner ?: false

private fun resolveReasoningEffort(modelSettings: ModelSettings?, llmConfig: LlmConfig?): String =
    modelSettings?.reasoningEffort ?: llmConfig?.reasoningEffort.orEmpty()

private fun resolveMaxReasoningTokens(modelSettings: ModelSettings?, llmConfig: LlmConfig?): String =
    (modelSettings?.maxReasoningTokens ?: llmConfig?.maxReasoningTokens)?.toString().orEmpty()

private fun resolveReasoningJson(modelSettings: ModelSettings?): String =
    modelSettings?.reasoning?.toSettingsJson().orEmpty()

private fun resolvePutInnerThoughtsInKwargs(modelSettings: ModelSettings?, llmConfig: LlmConfig?): Boolean =
    modelSettings?.putInnerThoughtsInKwargs ?: llmConfig?.putInnerThoughtsInKwargs ?: false

private fun responseModelSettingsFields(
    modelSettings: ModelSettings?,
    agent: Agent,
): ResponseModelSettingsFields = ResponseModelSettingsFields(
    modelStrictToolCalling = resolveStrictToolCalling(modelSettings),
    modelResponseFormatJson = resolveResponseFormatJson(modelSettings, agent),
    modelResponseSchemaJson = resolveResponseSchemaJson(modelSettings),
    modelThinkingConfigJson = resolveThinkingConfigJson(modelSettings),
    modelToolCallParser = resolveToolCallParser(modelSettings),
    modelAnthropicEffort = resolveAnthropicEffort(modelSettings),
)

private fun resolveStrictToolCalling(modelSettings: ModelSettings?): Boolean =
    modelSettings?.strict ?: false

private fun resolveResponseFormatJson(modelSettings: ModelSettings?, agent: Agent): String =
    (modelSettings?.responseFormat ?: agent.responseFormat)?.toSettingsJson().orEmpty()

private fun resolveResponseSchemaJson(modelSettings: ModelSettings?): String =
    modelSettings?.responseSchema?.toSettingsJson().orEmpty()

private fun resolveThinkingConfigJson(modelSettings: ModelSettings?): String =
    modelSettings?.thinkingConfig?.toSettingsJson().orEmpty()

private fun resolveToolCallParser(modelSettings: ModelSettings?): String =
    modelSettings?.toolCallParser.orEmpty()

private fun resolveAnthropicEffort(modelSettings: ModelSettings?): String =
    modelSettings?.effort.orEmpty()

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
