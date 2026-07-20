package com.letta.mobile.feature.editagent

/**
 * Table-driven validation lookup keeps the top-level dispatch a flat map
 * rather than a nested `when` — this keeps the file free of the
 * "Global Conditionals" blob CodeScene was flagging previously and lets
 * each per-tab helper stay small (CC ≤ 4).
 *
 * Only tabs that can surface a warning are listed; any other tab returns
 * `false` via the `?: false` fallback in [hasValidationWarning].
 */
private val tabValidationCheckers: Map<EditAgentConfigTab, (EditAgentUiState) -> Boolean> = mapOf(
    EditAgentConfigTab.Advanced to ::advancedTabHasValidationWarning,
    EditAgentConfigTab.Memory to ::memoryTabHasValidationWarning,
    EditAgentConfigTab.Tools to ::toolsTabHasValidationWarning,
)

/**
 * Whether this tab currently has a validation warning that should surface in
 * the UI.
 */
internal fun EditAgentConfigTab.hasValidationWarning(state: EditAgentUiState): Boolean =
    tabValidationCheckers[this]?.invoke(state) ?: false

private fun advancedTabHasValidationWarning(state: EditAgentUiState): Boolean =
    hasInvalidAdvancedJson(state) ||
        isInvalidWholeNumberIfPresent(state.modelMaxReasoningTokens) ||
        isInvalidNumberIfPresent(state.modelFrequencyPenalty)

private fun hasInvalidAdvancedJson(state: EditAgentUiState): Boolean = listOf(
    state.modelReasoningJson,
    state.modelResponseFormatJson,
    state.modelResponseSchemaJson,
    state.modelThinkingConfigJson,
).any(::isInvalidJsonObjectIfPresent)

private fun memoryTabHasValidationWarning(state: EditAgentUiState): Boolean =
    isInvalidJsonObjectIfPresent(state.compactionModelSettingsJson)

private fun toolsTabHasValidationWarning(state: EditAgentUiState): Boolean =
    isInvalidJsonArrayIfPresent(state.toolRulesJson) ||
        state.agentSecrets.hasDuplicateKeys() ||
        state.toolEnvironmentVariables.hasDuplicateKeys()
