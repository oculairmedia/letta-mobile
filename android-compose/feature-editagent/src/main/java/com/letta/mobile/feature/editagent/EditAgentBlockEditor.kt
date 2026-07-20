package com.letta.mobile.feature.editagent

import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.repository.api.IBlockRepository
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class NewBlockDraft(
    val label: String,
    val value: String,
    val description: String,
    val limit: Int?,
)

internal data class EditAgentBlockEditorDeps(
    val agentId: String,
    val blockRepository: IBlockRepository,
    val state: EditAgentViewModelState,
    val scope: CoroutineScope,
    val reload: suspend () -> Unit,
)

internal class EditAgentBlockEditor(
    private val deps: EditAgentBlockEditorDeps,
) {
    fun updateBlockValue(blockLabel: String, value: String) {
        updateBlock(blockLabel) { it.copy(value = value) }
    }

    fun updateBlockDescription(blockLabel: String, description: String) {
        updateBlock(blockLabel) { it.copy(description = description) }
    }

    fun updateBlockLimit(blockLabel: String, limit: Int?) {
        updateBlock(blockLabel) { it.copy(limit = limit) }
    }

    fun addBlock(draft: NewBlockDraft) {
        deps.scope.launch {
            try {
                val block = deps.blockRepository.createBlock(
                    BlockCreateParams(
                        label = draft.label,
                        value = draft.value,
                        description = draft.description.ifBlank { null },
                        limit = draft.limit,
                    )
                )
                deps.blockRepository.attachBlock(deps.agentId, block.id.value)
                deps.reload()
            } catch (e: Exception) {
                android.util.Log.w("EditAgentVM", "Failed to create block", e)
            }
        }
    }

    fun attachExistingBlock(blockId: String) {
        attachExistingBlocks(listOf(blockId), "Failed to attach block")
    }

    fun attachExistingBlocks(blockIds: List<String>) {
        attachExistingBlocks(blockIds, "Failed to attach blocks")
    }

    fun deleteBlock(blockId: String) {
        deps.scope.launch {
            try {
                deps.blockRepository.detachBlock(deps.agentId, blockId)
                deps.state.updateField {
                    copy(blocks = blocks.filter { it.id != blockId }.toImmutableList())
                }
            } catch (e: Exception) {
                android.util.Log.w("EditAgentVM", "Failed to delete block", e)
            }
        }
    }

    private fun updateBlock(blockLabel: String, transform: (EditableBlock) -> EditableBlock) {
        deps.state.updateField {
            copy(
                blocks = blocks.map { block ->
                    if (block.label == blockLabel) transform(block) else block
                }.toImmutableList()
            )
        }
    }

    private fun attachExistingBlocks(blockIds: List<String>, errorMessage: String) {
        deps.scope.launch {
            try {
                blockIds.forEach { deps.blockRepository.attachBlock(deps.agentId, it) }
                deps.reload()
            } catch (e: Exception) {
                deps.state.setError(e.message ?: errorMessage)
            }
        }
    }
}
