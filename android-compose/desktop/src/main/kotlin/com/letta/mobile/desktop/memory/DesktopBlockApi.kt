package com.letta.mobile.desktop.memory

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohBlockApi

data class BlockCreateAttachParams(
    val agentId: AgentId,
    val label: String,
    val value: String,
    val limit: Int? = null,
)

data class BlockValueUpdate(
    val value: String,
    val limit: Int? = null,
)

/**
 * Desktop-side core-memory block API powering the Memory CRUD panel. Served over an Iroh host's
 * `admin_rpc` relay ([DesktopIrohBlockApi]); letta-mobile-ckmub.4 removed the Letta REST
 * implementation, so other backends edit memory through the shared memory page instead.
 */
interface DesktopBlockApi : AutoCloseable {
    suspend fun getBlockById(blockId: BlockId): Block
    suspend fun updateBlockById(blockId: BlockId, update: BlockValueUpdate): Block
    suspend fun deleteBlockById(blockId: BlockId)
    suspend fun createAndAttachBlock(params: BlockCreateAttachParams): Block

    override fun close() = Unit
}

class DesktopIrohBlockApi(
    directory: IrohAdminRpcAgentDirectory,
) : DesktopBlockApi {
    private val api = IrohBlockApi(directory)

    override suspend fun getBlockById(blockId: BlockId): Block = api.getBlockById(blockId.value)

    override suspend fun updateBlockById(blockId: BlockId, update: BlockValueUpdate): Block =
        api.updateBlockById(blockId.value, update.value, update.limit)

    override suspend fun deleteBlockById(blockId: BlockId) = api.deleteBlockById(blockId.value)

    override suspend fun createAndAttachBlock(params: BlockCreateAttachParams): Block =
        api.createAndAttachBlock(params.agentId.value, params.label, params.value, params.limit)
}
