package com.letta.mobile.feature.editagent

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
