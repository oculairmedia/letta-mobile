package com.letta.mobile.feature.editagent

import com.letta.mobile.data.repository.api.IToolRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class EditAgentToolAttachment(
    private val agentId: String,
    private val toolRepository: IToolRepository,
    private val state: EditAgentViewModelState,
    private val scope: CoroutineScope,
    private val reload: suspend () -> Unit,
) {
    fun attachTool(toolId: String) {
        attachTools(listOf(toolId), "Failed to attach tool")
    }

    fun attachTools(toolIds: List<String>) {
        attachTools(toolIds, "Failed to attach tools")
    }

    fun detachTool(toolId: String) {
        scope.launch {
            try {
                toolRepository.detachTool(agentId, toolId)
                reload()
            } catch (e: Exception) {
                state.setError(e.message ?: "Failed to detach tool")
            }
        }
    }

    private fun attachTools(toolIds: List<String>, errorMessage: String) {
        scope.launch {
            try {
                toolIds.forEach { toolRepository.attachTool(agentId, it) }
                reload()
            } catch (e: Exception) {
                state.setError(e.message ?: errorMessage)
            }
        }
    }
}
