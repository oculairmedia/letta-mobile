package com.letta.mobile.feature.editagent

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentEnvironmentVariable
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.Tool
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

@androidx.compose.runtime.Immutable
internal data class EditableBlock(
    val id: String,
    val label: String,
    val value: String,
    val description: String? = null,
    val limit: Int? = null,
    val isTemplate: Boolean = false,
    val readOnly: Boolean = false,
)

@androidx.compose.runtime.Immutable
internal data class EditableAgentEnvironmentVariable(
    val key: String = "",
    val value: String = "",
    val originalKey: String? = null,
    val originalValue: String? = null,
    val hasStoredValue: Boolean = false,
)

internal data class EditAgentUiState(
    val agent: Agent? = null,
    val agentId: String = "",
    val name: String = "",
    val description: String = "",
    val model: String = "",
    val embedding: String = "",
    val blocks: ImmutableList<EditableBlock> = persistentListOf(),
    val systemPrompt: String = "",
    val tags: ImmutableList<String> = persistentListOf(),
    val attachedTools: ImmutableList<Tool> = persistentListOf(),
    val availableTools: ImmutableList<Tool> = persistentListOf(),
    val availableBlocks: ImmutableList<Block> = persistentListOf(),
    val toolRulesJson: String = "",
    val agentSecrets: ImmutableList<EditableAgentEnvironmentVariable> = persistentListOf(),
    val toolEnvironmentVariables: ImmutableList<EditableAgentEnvironmentVariable> = persistentListOf(),
    val providerType: String = "",
    val temperature: Float = 1.0f,
    val maxOutputTokens: Int = 4096,
    val parallelToolCalls: Boolean = true,
    val modelProviderName: String = "",
    val modelProviderCategory: String = "",
    val modelEnableReasoner: Boolean = false,
    val modelReasoningEffort: String = "",
    val modelMaxReasoningTokens: String = "",
    val modelReasoningJson: String = "",
    val modelFrequencyPenalty: String = "",
    val modelVerbosity: String = "",
    val modelStrictToolCalling: Boolean = false,
    val modelResponseFormatJson: String = "",
    val modelResponseSchemaJson: String = "",
    val modelThinkingConfigJson: String = "",
    val modelPutInnerThoughtsInKwargs: Boolean = false,
    val modelToolCallParser: String = "",
    val modelAnthropicEffort: String = "",
    val contextWindow: Int = 0,
    val enableSleeptime: Boolean = false,
    val agentType: String = "",
    val embeddingDim: Int? = null,
    val embeddingChunkSize: Int? = null,
    val isCloning: Boolean = false,
    val clientModeEnabled: Boolean = false,
    val clientModeBaseUrl: String = "",
    val clientModeApiKey: String = "",
    val clientModeConnectionState: com.letta.mobile.bot.connection.ClientModeConnectionState = com.letta.mobile.bot.connection.ClientModeConnectionState.Idle,
    val summarizationPrompt: String = "",
    val compactionClipChars: Int = 50_000,
    val slidingWindowPercentage: Float = 0.3f,
    val promptAcknowledgement: Boolean = false,
    val compactionMode: String = "sliding_window",
    val compactionModel: String = "",
    val compactionModelSettingsJson: String = "",
) {
    typealias BlockState = EditableBlock
}

internal fun List<AgentEnvironmentVariable>.toEditableEnvironmentVariables(): ImmutableList<EditableAgentEnvironmentVariable> {
    return map { variable ->
        EditableAgentEnvironmentVariable(
            key = variable.key,
            value = variable.value.orEmpty(),
            originalKey = variable.key,
            originalValue = variable.value,
            hasStoredValue = variable.value != null || variable.valueEnc != null || variable.id != null,
        )
    }.toImmutableList()
}
