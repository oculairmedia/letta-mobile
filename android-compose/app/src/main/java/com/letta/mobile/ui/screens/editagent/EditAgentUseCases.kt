package com.letta.mobile.ui.screens.editagent

import com.letta.mobile.data.model.AgentEnvironmentVariable
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.CompactionSettings
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.ModelSettings
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.util.mapErrorToUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class EditAgentUseCases(
    private val agentId: String,
    private val agentRepository: AgentRepository,
    private val blockRepository: BlockRepository,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
    private val uiState: MutableStateFlow<UiState<EditAgentUiState>>,
    private val originalBlocks: Map<String, EditableBlock>,
    private val originalEmbedding: String,
    private val originalProviderType: String,
) {
    companion object {
        private const val DEFAULT_COMPACTION_CLIP_CHARS = 50_000
        private const val DEFAULT_SLIDING_WINDOW_PERCENTAGE = 0.3f
        private const val DEFAULT_COMPACTION_MODE = "sliding_window"
        private val advancedSettingsJson = Json { prettyPrint = true }

        private val supportedModelSettingsProviderTypes = setOf(
            "openai",
            "sglang",
            "anthropic",
            "google_ai",
            "google_vertex",
            "azure",
            "xai",
            "zai",
            "groq",
            "deepseek",
            "together",
            "bedrock",
            "baseten",
            "openrouter",
            "chatgpt_oauth",
        )

        internal fun normalizeModelSettingsProviderType(providerType: String?, modelHandle: String?): String? {
            val normalizedProviderType = providerType?.trim()?.lowercase().orEmpty()
            if (normalizedProviderType in supportedModelSettingsProviderTypes) {
                return normalizedProviderType
            }

            val handleProvider = modelHandle
                ?.substringBefore('/', missingDelimiterValue = "")
                ?.trim()
                ?.lowercase()
                .orEmpty()

            return handleProvider.takeIf { it in supportedModelSettingsProviderTypes }
        }
    }

    suspend fun saveAgent(onSuccess: () -> Unit) {
        try {
            val state = (uiState.value as? UiState.Success)?.data ?: return
            val embeddingChanged = state.embedding != originalEmbedding
            val resolvedProviderType = normalizeModelSettingsProviderType(
                providerType = state.providerType,
                modelHandle = state.model,
            )
                ?: normalizeModelSettingsProviderType(
                    providerType = originalProviderType,
                    modelHandle = state.model,
                )

            if (resolvedProviderType == null) {
                uiState.value = UiState.Error(
                    "Couldn't determine a supported provider type for the selected model. Please re-select the model and try again."
                )
                return
            }
            agentRepository.updateAgent(
                agentId,
                AgentUpdateParams(
                    name = state.name,
                    description = state.description,
                    model = state.model,
                    embedding = if (embeddingChanged) state.embedding.ifBlank { null } else null,
                    system = state.systemPrompt,
                    tags = state.tags,
                    enableSleeptime = state.enableSleeptime,
                    contextWindowLimit = state.contextWindow.takeIf { it > 0 },
                    modelSettings = state.toModelSettings(resolvedProviderType),
                    secrets = state.toAgentSecretsMap(),
                    toolExecEnvironmentVariables = state.toToolEnvironmentVariablesMap(),
                    compactionSettings = state.toCompactionSettings(),
                    toolRules = state.toToolRules(),
                )
            )
            state.blocks.forEach { block ->
                val original = originalBlocks[block.label]
                val normalizedDescription = block.description?.ifBlank { null }
                if (original == null ||
                    block.value != original.value ||
                    normalizedDescription != original.description?.ifBlank { null } ||
                    block.limit != original.limit
                ) {
                    blockRepository.updateAgentBlock(
                        agentId,
                        block.label,
                        BlockUpdateParams(
                            value = block.value,
                            description = normalizedDescription,
                            limit = block.limit,
                        )
                    )
                }
            }
            settingsRepository.setClientModeEnabled(state.clientModeEnabled)
            settingsRepository.setClientModeBaseUrl(state.clientModeBaseUrl.trim())
            settingsRepository.setClientModeApiKey(state.clientModeApiKey.trim().ifBlank { null })
            onSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState.value = UiState.Error(e.message ?: "Failed to save agent")
        }
    }

    private fun EditAgentUiState.toModelSettings(resolvedProviderType: String): ModelSettings {
        val existing = agent?.modelSettings
        val llmConfig = agent?.llmConfig
        return ModelSettings(
            providerType = resolvedProviderType,
            providerName = modelProviderName.trim().ifBlank { null },
            providerCategory = modelProviderCategory.trim().ifBlank { null },
            temperature = temperature.toDouble(),
            maxOutputTokens = maxOutputTokens,
            parallelToolCalls = parallelToolCalls,
            maxReasoningTokens = parseOptionalInt(modelMaxReasoningTokens, "Max reasoning tokens"),
            enableReasoner = modelEnableReasoner.toNullableOverride(existing?.enableReasoner ?: llmConfig?.enableReasoner),
            reasoning = parseOptionalJsonObject(modelReasoningJson, "Reasoning"),
            reasoningEffort = modelReasoningEffort.trim().ifBlank { null },
            effort = modelAnthropicEffort.trim().ifBlank { null },
            frequencyPenalty = parseOptionalDouble(modelFrequencyPenalty, "Frequency penalty"),
            verbosity = modelVerbosity.trim().ifBlank { null },
            responseFormat = parseOptionalJsonObject(modelResponseFormatJson, "Response format"),
            responseSchema = parseOptionalJsonObject(modelResponseSchemaJson, "Response schema"),
            thinkingConfig = parseOptionalJsonObject(modelThinkingConfigJson, "Thinking config"),
            strict = modelStrictToolCalling.toNullableOverride(existing?.strict),
            toolCallParser = modelToolCallParser.trim().ifBlank { null },
            putInnerThoughtsInKwargs = modelPutInnerThoughtsInKwargs
                .toNullableOverride(existing?.putInnerThoughtsInKwargs ?: llmConfig?.putInnerThoughtsInKwargs),
        )
    }

    private fun EditAgentUiState.toAgentSecretsMap(): Map<String, String>? {
        return buildEnvironmentVariableMap(
            label = "Secrets",
            current = agentSecrets,
            original = agent?.secrets.orEmpty(),
        )
    }

    private fun EditAgentUiState.toToolEnvironmentVariablesMap(): Map<String, String>? {
        return buildEnvironmentVariableMap(
            label = "Tool environment variables",
            current = toolEnvironmentVariables,
            original = agent?.toolExecEnvironmentVariables.orEmpty(),
        )
    }

    private fun EditAgentUiState.toCompactionSettings(): CompactionSettings {
        return CompactionSettings(
            model = compactionModel.trim().ifBlank { null },
            modelSettings = parseOptionalJsonObject(compactionModelSettingsJson, "Compaction model settings"),
            prompt = summarizationPrompt.trim().ifBlank { null },
            promptAcknowledgement = promptAcknowledgement,
            clipChars = compactionClipChars.takeIf { it > 0 },
            mode = compactionMode.ifBlank { DEFAULT_COMPACTION_MODE },
            slidingWindowPercentage = slidingWindowPercentage.coerceIn(0f, 1f).toDouble(),
        )
    }

    private fun EditAgentUiState.toToolRules(): List<JsonObject>? {
        return parseOptionalJsonObjectArray(toolRulesJson, "Tool rules")
    }

    private fun JsonElement.toSettingsJson(): String {
        return advancedSettingsJson.encodeToString(JsonElement.serializer(), this)
    }

    private fun Boolean.toNullableOverride(original: Boolean?): Boolean? {
        return if (this || original != null) this else null
    }

    private fun parseOptionalInt(rawValue: String, label: String): Int? {
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("$label must be a whole number.")
    }

    private fun parseOptionalDouble(rawValue: String, label: String): Double? {
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.toDoubleOrNull()
            ?: throw IllegalArgumentException("$label must be a number.")
    }

    private fun parseOptionalJsonObject(rawJson: String, label: String): JsonElement? {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return null

        return try {
            advancedSettingsJson.parseToJsonElement(trimmed).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("$label must be a valid JSON object.", e)
        }
    }

    private fun parseOptionalJsonObjectArray(rawJson: String, label: String): List<JsonObject>? {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return null

        return try {
            advancedSettingsJson.parseToJsonElement(trimmed).jsonArray.mapIndexed { index, element ->
                element as? JsonObject
                    ?: throw IllegalArgumentException("$label item ${index + 1} must be a JSON object.")
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("$label must be a valid JSON array of objects.", e)
        }
    }

    private fun buildEnvironmentVariableMap(
        label: String,
        current: List<EditableAgentEnvironmentVariable>,
        original: List<AgentEnvironmentVariable>,
    ): Map<String, String>? {
        if (!environmentVariablesChanged(current, original)) return null

        val normalized = current.map { variable ->
            variable.copy(key = variable.key.trim())
        }
        val blankKey = normalized.firstOrNull { it.key.isBlank() }
        if (blankKey != null) {
            throw IllegalArgumentException("$label cannot contain blank keys.")
        }

        val duplicateKey = normalized
            .groupBy { it.key }
            .entries
            .firstOrNull { it.value.size > 1 }
            ?.key
        if (duplicateKey != null) {
            throw IllegalArgumentException("$label contains a duplicate key: $duplicateKey.")
        }

        val hiddenValue = normalized.firstOrNull {
            it.value.isBlank() && it.hasStoredValue && it.originalValue == null
        }
        if (hiddenValue != null) {
            throw IllegalArgumentException(
                "$label includes hidden existing values. Re-enter those values before changing this section so they are not overwritten."
            )
        }

        val blankValue = normalized.firstOrNull { it.value.isBlank() }
        if (blankValue != null) {
            throw IllegalArgumentException("$label values cannot be blank. Remove the row instead.")
        }

        return normalized.associate { it.key to it.value }
    }

    private fun environmentVariablesChanged(
        current: List<EditableAgentEnvironmentVariable>,
        original: List<AgentEnvironmentVariable>,
    ): Boolean {
        val originalKeys = original.map { it.key }
        val retainedOriginalKeys = current.mapNotNull { it.originalKey }
        if (originalKeys.any { it !in retainedOriginalKeys }) return true

        return current.any { variable ->
            variable.originalKey == null ||
                variable.key.trim() != variable.originalKey ||
                variable.value != variable.originalValue.orEmpty()
        }
    }

    suspend fun exportAgent(onResult: (String) -> Unit) {
        try {
            val data = agentRepository.exportAgent(agentId)
            onResult(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to export agent"))
        }
    }

    suspend fun cloneAgent(
        cloneName: String?,
        overrideExistingTools: Boolean,
        stripMessages: Boolean,
        onSuccess: (ImportedAgentsResponse) -> Unit,
    ) {
        val state = (uiState.value as? UiState.Success)?.data ?: return
        uiState.value = UiState.Success(state.copy(isCloning = true))
        try {
            val exportData = agentRepository.exportAgent(agentId)
            val response = agentRepository.importAgent(
                fileName = "${state.name.ifBlank { "agent" }}.json",
                fileBytes = exportData.encodeToByteArray(),
                overrideName = cloneName?.takeIf { it.isNotBlank() },
                overrideExistingTools = overrideExistingTools,
                stripMessages = stripMessages,
            )
            uiState.value = UiState.Success(state.copy(isCloning = false))
            onSuccess(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to clone agent"))
        }
    }

    suspend fun resetMessages(onSuccess: () -> Unit = {}) {
        try {
            messageRepository.resetMessages(agentId)
            onSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to reset messages"))
        }
    }

    suspend fun deleteAgent(onSuccess: () -> Unit) {
        try {
            agentRepository.deleteAgent(agentId)
            onSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState.value = UiState.Error(mapErrorToUserMessage(e, "Failed to delete agent"))
        }
    }
}
