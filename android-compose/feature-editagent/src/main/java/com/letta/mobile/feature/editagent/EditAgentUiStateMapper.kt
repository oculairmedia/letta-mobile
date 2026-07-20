package com.letta.mobile.feature.editagent

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.Tool
import kotlinx.collections.immutable.toImmutableList

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
        val modelFields = input.agent.modelSettingsFields()
        val compaction = input.agent.compactionFields()
        return input.baseUiState().copy(
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
}
