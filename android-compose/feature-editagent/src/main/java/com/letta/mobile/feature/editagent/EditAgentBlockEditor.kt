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

@JvmInline
internal value class EditableBlockLabel(val value: String)

@JvmInline
internal value class AttachedBlockId(val value: String)

internal data class BlockValueUpdate(val label: EditableBlockLabel, val value: String)

internal data class BlockDescriptionUpdate(val label: EditableBlockLabel, val description: String)

internal data class BlockLimitUpdate(val label: EditableBlockLabel, val limit: Int?)

internal data class BlockAttachRequest(val blockIds: List<AttachedBlockId>)

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
    fun updateBlockValue(update: BlockValueUpdate) {
        updateBlock(update.label) { it.copy(value = update.value) }
    }

    fun updateBlockDescription(update: BlockDescriptionUpdate) {
        updateBlock(update.label) { it.copy(description = update.description) }
    }

    fun updateBlockLimit(update: BlockLimitUpdate) {
        updateBlock(update.label) { it.copy(limit = update.limit) }
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

    fun attachExistingBlock(blockId: AttachedBlockId) {
        attachExistingBlocks(BlockAttachRequest(listOf(blockId)), "Failed to attach block")
    }

    fun attachExistingBlocks(request: BlockAttachRequest) {
        attachExistingBlocks(request, "Failed to attach blocks")
    }

    fun deleteBlock(blockId: AttachedBlockId) {
        deps.scope.launch {
            try {
                deps.blockRepository.detachBlock(deps.agentId, blockId.value)
                deps.state.updateField {
                    copy(blocks = blocks.filter { it.id != blockId.value }.toImmutableList())
                }
            } catch (e: Exception) {
                android.util.Log.w("EditAgentVM", "Failed to delete block", e)
            }
        }
    }

    private fun updateBlock(blockLabel: EditableBlockLabel, transform: (EditableBlock) -> EditableBlock) {
        deps.state.updateField {
            copy(
                blocks = blocks.map { block ->
                    if (block.label == blockLabel.value) transform(block) else block
                }.toImmutableList()
            )
        }
    }

    private fun attachExistingBlocks(request: BlockAttachRequest, errorMessage: String) {
        deps.scope.launch {
            try {
                request.blockIds.forEach { deps.blockRepository.attachBlock(deps.agentId, it.value) }
                deps.reload()
            } catch (e: Exception) {
                deps.state.setError(e.message ?: errorMessage)
            }
        }
    }
}
