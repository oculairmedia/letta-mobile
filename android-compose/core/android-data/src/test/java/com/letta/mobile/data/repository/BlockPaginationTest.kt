package com.letta.mobile.data.repository

import com.letta.mobile.data.api.BlockApi
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Phase 2.2 (data-efficiency-audit Q3): focused pagination tests for
 * [BlockRepository.listAllBlocks] (offset-based) and
 * [BlockRepository.listAgentsForBlock] (cursor-based).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class BlockPaginationTest {

    @Test
    fun `listAllBlocks fetches both offset pages when API returns exactly two`() = runTest {
        // 75 blocks, default pageSize = 50 -> first page full, second short.
        val blocks = (1..75).map { Block(id = BlockId("b-$it"), label = "label-$it", value = "value-$it") }
        val api = PaginatingBlockApi(blocks)
        val repo = BlockRepository(api)

        val result = repo.listAllBlocks()

        assertEquals(75, result.size)
        assertEquals(listOf(0, 50), api.observedOffsets)
        assertEquals(listOf(50, 50), api.observedLimits)
    }

    @Test
    fun `listAgentsForBlock fetches both cursor pages when API returns exactly two`() = runTest {
        val agents = (1..60).map { Agent(id = AgentId("a-$it"), name = "Agent $it") }
        val api = PaginatingBlockApi(blocks = emptyList(), agents = agents)
        val repo = BlockRepository(api)

        val result = repo.listAgentsForBlock("block-1")

        assertEquals(60, result.size)
        assertEquals(listOf<String?>(null, "a-50"), api.observedAftersForAgents)
    }

    private class PaginatingBlockApi(
        private val blocks: List<Block>,
        private val agents: List<Agent> = emptyList(),
    ) : BlockApi(mockk(relaxed = true)) {
        val observedOffsets = mutableListOf<Int?>()
        val observedLimits = mutableListOf<Int?>()
        val observedAftersForAgents = mutableListOf<String?>()

        override suspend fun listAllBlocks(
            label: String?,
            isTemplate: Boolean?,
            limit: Int?,
            offset: Int?,
        ): List<Block> {
            observedOffsets += offset
            observedLimits += limit
            val pageSize = limit ?: 50
            val start = offset ?: 0
            val end = (start + pageSize).coerceAtMost(blocks.size)
            return blocks.subList(start, end)
        }

        override suspend fun listAgentsForBlock(
            blockId: String,
            limit: Int?,
            before: String?,
            after: String?,
            order: String?,
        ): List<Agent> {
            observedAftersForAgents += after
            val pageSize = limit ?: 50
            val start = after?.let { id ->
                agents.indexOfFirst { it.id.value == id }.let { if (it < 0) agents.size else it + 1 }
            } ?: 0
            val end = (start + pageSize).coerceAtMost(agents.size)
            return agents.subList(start, end)
        }
    }
}