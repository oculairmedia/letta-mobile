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

internal class EditAgentBlockEditor(
    private val agentId: String,
    private val blockRepository: IBlockRepository,
    private val state: EditAgentViewModelState,
    private val scope: CoroutineScope,
    private val reload: suspend () -> Unit,
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
        scope.launch {
            try {
                val block = blockRepository.createBlock(
                    BlockCreateParams(
                        label = draft.label,
                        value = draft.value,
                        description = draft.description.ifBlank { null },
                        limit = draft.limit,
                    )
                )
                blockRepository.attachBlock(agentId, block.id.value)
                reload()
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
        scope.launch {
            try {
                blockRepository.detachBlock(agentId, blockId)
                state.updateField {
                    copy(blocks = blocks.filter { it.id != blockId }.toImmutableList())
                }
            } catch (e: Exception) {
                android.util.Log.w("EditAgentVM", "Failed to delete block", e)
            }
        }
    }

    private fun updateBlock(blockLabel: String, transform: (EditableBlock) -> EditableBlock) {
        state.updateField {
            copy(
                blocks = blocks.map { block ->
                    if (block.label == blockLabel) transform(block) else block
                }.toImmutableList()
            )
        }
    }

    private fun attachExistingBlocks(blockIds: List<String>, errorMessage: String) {
        scope.launch {
            try {
                blockIds.forEach { blockRepository.attachBlock(agentId, it) }
                reload()
            } catch (e: Exception) {
                state.setError(e.message ?: errorMessage)
            }
        }
    }
}
