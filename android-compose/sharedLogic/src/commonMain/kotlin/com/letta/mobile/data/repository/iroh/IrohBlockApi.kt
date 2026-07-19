package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams

class IrohBlockApi(
    private val directory: IrohAdminRpcAgentDirectory,
) {
    suspend fun getBlockById(blockId: String): Block =
        directory.getBlock(blockId) ?: throw NoSuchElementException("Block $blockId not found over iroh admin_rpc")

    suspend fun updateBlockById(blockId: String, value: String, limit: Int? = null): Block =
        directory.updateBlock(blockId, BlockUpdateParams(value = value, limit = limit))

    suspend fun deleteBlockById(blockId: String) {
        directory.deleteBlock(blockId)
    }

    suspend fun createAndAttachBlock(agentId: String, label: String, value: String, limit: Int? = null): Block {
        val block = directory.createBlock(BlockCreateParams(label = label, value = value, limit = limit))
        return try {
            directory.attachBlock(agentId, block.id.value)
            block
        } catch (t: Throwable) {
            runCatching { directory.deleteBlock(block.id.value) }
            throw t
        }
    }
}
