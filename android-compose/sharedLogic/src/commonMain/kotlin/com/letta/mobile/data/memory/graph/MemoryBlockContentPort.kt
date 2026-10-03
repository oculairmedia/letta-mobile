package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.api.AgentBlockTarget
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

    /** bfooy.5: creates and commits a new block under [agentId]; fails if [label] exists. */
    suspend fun create(agentId: String, label: String, value: String): Block

    /** bfooy.5: deletes the block and commits the deletion. */
    suspend fun delete(ref: MemoryBlockRef)
}

/**
 * Reads and writes through the session graph's block repository, the same
 * repository the overview loads blocks from. Writes use
 * [IAgentBlockWriteRepository.writeAgentBlock] (`block.update_agent` over Iroh,
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

    override suspend fun save(ref: MemoryBlockRef, value: String): Block =
        writer().writeAgentBlock(ref.target(), BlockUpdateParams(value = value))

    override suspend fun create(agentId: String, label: String, value: String): Block =
        writer().createAgentBlock(AgentBlockTarget(agentId, label), value)

    override suspend fun delete(ref: MemoryBlockRef) = writer().deleteAgentBlock(ref.target())

    private fun writer(): IAgentBlockWriteRepository =
        graph().blockRepository as? IAgentBlockWriteRepository
            ?: throw UnsupportedOperationException("Memory blocks cannot be edited on this backend.")

    private fun MemoryBlockRef.target() = AgentBlockTarget(agentId, label)

    private fun graph(): SessionRepositoryGraph = sessionGraphProvider.current

    private fun embeddedBlocks(agentId: String): List<Block> =
        graph().agentRepository.agents.value.firstOrNull { it.id.value == agentId }?.coreBlocks.orEmpty()

    private fun List<Block>.pick(ref: MemoryBlockRef): Block? =
        firstOrNull { it.id.value == ref.blockId } ?: firstOrNull { it.label == ref.label }
}
