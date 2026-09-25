package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.api.IAgentBlockWriteRepository
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider

/** Full-value block load and committed write for the memory card editor. */
interface MemoryBlockContentPort {
    /** Whether the active backend exposes an agent + label block write. */
    fun canWrite(): Boolean

    suspend fun load(ref: MemoryBlockRef): Block

    /** Writes and commits the block (MemFS-backed on the App Server). */
    suspend fun save(ref: MemoryBlockRef, value: String): Block
}

/**
 * Reads and writes through the session graph's block repository, the same
 * repository the overview loads blocks from. Writes use
 * [IAgentBlockWriteRepository.updateAgentBlock] (`block.update_agent` over Iroh,
 * which the App Server commits via `write_memory_file`), never the global-id
 * route that fails closed on the local backend.
 */
class SessionGraphMemoryBlockPort(
    private val sessionGraphProvider: SessionRepositoryGraphProvider<*>,
) : MemoryBlockContentPort {
    override fun canWrite(): Boolean = graph().blockRepository is IAgentBlockWriteRepository

    override suspend fun load(ref: MemoryBlockRef): Block {
        val blocks = graph().blockRepository?.getBlocks(ref.agentId) ?: embeddedBlocks(ref.agentId)
        return blocks.pick(ref) ?: throw NoSuchElementException("Memory block ${ref.label} was not found")
    }

    override suspend fun save(ref: MemoryBlockRef, value: String): Block {
        val writer = graph().blockRepository as? IAgentBlockWriteRepository
            ?: throw UnsupportedOperationException("Memory blocks cannot be edited on this backend.")
        return writer.updateAgentBlock(ref.agentId, ref.label, BlockUpdateParams(value = value))
    }

    private fun graph(): SessionRepositoryGraph = sessionGraphProvider.current

    private fun embeddedBlocks(agentId: String): List<Block> =
        graph().agentRepository.agents.value.firstOrNull { it.id.value == agentId }?.coreBlocks.orEmpty()

    private fun List<Block>.pick(ref: MemoryBlockRef): Block? =
        firstOrNull { it.id.value == ref.blockId } ?: firstOrNull { it.label == ref.label }
}
