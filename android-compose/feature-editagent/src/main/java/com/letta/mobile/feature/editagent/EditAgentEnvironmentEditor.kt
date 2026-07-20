package com.letta.mobile.feature.editagent

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

internal enum class EditAgentEnvironmentVariableTarget {
    AGENT_SECRETS,
    TOOL_ENVIRONMENT,
}

internal class EditAgentEnvironmentEditor(
    private val state: EditAgentViewModelState,
) {
    fun add(target: EditAgentEnvironmentVariableTarget) {
        updateList(target) { variables -> variables + EditableAgentEnvironmentVariable() }
    }

    fun updateKey(target: EditAgentEnvironmentVariableTarget, index: Int, value: String) {
        updateAt(target, index) { it.copy(key = value) }
    }

    fun updateValue(target: EditAgentEnvironmentVariableTarget, index: Int, value: String) {
        updateAt(target, index) { it.copy(value = value) }
    }

    fun remove(target: EditAgentEnvironmentVariableTarget, index: Int) {
        state.updateField {
            val variables = target.read(this)
            if (index !in variables.indices) return@updateField this
            target.write(
                this,
                variables.filterIndexed { i, _ -> i != index }.toImmutableList(),
            )
        }
    }

    private fun updateAt(
        target: EditAgentEnvironmentVariableTarget,
        index: Int,
        transform: (EditableAgentEnvironmentVariable) -> EditableAgentEnvironmentVariable,
    ) {
        state.updateField {
            val variables = target.read(this)
            if (index !in variables.indices) return@updateField this
            target.write(
                this,
                variables.mapIndexed { i, variable ->
                    if (i == index) transform(variable) else variable
                }.toImmutableList(),
            )
        }
    }

    private fun updateList(
        target: EditAgentEnvironmentVariableTarget,
        transform: (ImmutableList<EditableAgentEnvironmentVariable>) -> List<EditableAgentEnvironmentVariable>,
    ) {
        state.updateField {
            target.write(this, transform(target.read(this)).toImmutableList())
        }
    }

    private fun EditAgentEnvironmentVariableTarget.read(
        state: EditAgentUiState,
    ): ImmutableList<EditableAgentEnvironmentVariable> {
        return when (this) {
            EditAgentEnvironmentVariableTarget.AGENT_SECRETS -> state.agentSecrets
            EditAgentEnvironmentVariableTarget.TOOL_ENVIRONMENT -> state.toolEnvironmentVariables
        }
    }

    private fun EditAgentEnvironmentVariableTarget.write(
        state: EditAgentUiState,
        variables: ImmutableList<EditableAgentEnvironmentVariable>,
    ): EditAgentUiState {
        return when (this) {
            EditAgentEnvironmentVariableTarget.AGENT_SECRETS -> state.copy(agentSecrets = variables)
            EditAgentEnvironmentVariableTarget.TOOL_ENVIRONMENT -> state.copy(toolEnvironmentVariables = variables)
        }
    }
}
