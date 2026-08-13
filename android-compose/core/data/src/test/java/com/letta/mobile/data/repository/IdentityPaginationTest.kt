package com.letta.mobile.data.repository

import com.letta.mobile.data.api.IdentityApi
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityId
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Phase 2.2 (data-efficiency-audit Q3): focused pagination tests for
 * [IdentityRepository.listAgentsForIdentity] and
 * [IdentityRepository.listBlocksForIdentity]. Both previously used
 * `limit = 1000` and now route through [exhaustCursorPages].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class IdentityPaginationTest {

    @Test
    fun `listAgentsForIdentity fetches both pages when API returns exactly two`() = runTest {
        val agents = (1..60).map { Agent(id = AgentId("agent-$it"), name = "Agent $it") }
        val api = PaginatingIdentityApi(agents = agents)
        val repo = IdentityRepository(api)

        val result = repo.listAgentsForIdentity(IdentityId("identity-1"))

        assertEquals(60, result.size)
        assertEquals(listOf<String?>(null, "agent-50"), api.observedAftersForAgents)
    }

    @Test
    fun `listBlocksForIdentity fetches both pages when API returns exactly two`() = runTest {
        val blocks = (1..60).map { Block(id = BlockId("block-$it"), label = "label-$it", value = "value-$it") }
        val api = PaginatingIdentityApi(blocks = blocks)
        val repo = IdentityRepository(api)

        val result = repo.listBlocksForIdentity(IdentityId("identity-1"))

        assertEquals(60, result.size)
        assertEquals(listOf<String?>(null, "block-50"), api.observedAftersForBlocks)
    }

    private class PaginatingIdentityApi(
        private val agents: List<Agent> = listOf(Agent(id = AgentId("agent-1"), name = "Agent 1")),
        private val blocks: List<Block> = listOf(Block(id = BlockId("block-1"), label = "l", value = "v")),
    ) : IdentityApi(mockk(relaxed = true)) {
        val observedAftersForAgents = mutableListOf<String?>()
        val observedAftersForBlocks = mutableListOf<String?>()

        override suspend fun listAgentsForIdentity(
            identityId: String,
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

        override suspend fun listBlocksForIdentity(
            identityId: String,
            limit: Int?,
            before: String?,
            after: String?,
            order: String?,
        ): List<Block> {
            observedAftersForBlocks += after
            val pageSize = limit ?: 50
            val start = after?.let { id ->
                blocks.indexOfFirst { it.id.value == id }.let { if (it < 0) blocks.size else it + 1 }
            } ?: 0
            val end = (start + pageSize).coerceAtMost(blocks.size)
            return blocks.subList(start, end)
        }

        override suspend fun listIdentities(): List<Identity> = emptyList()
    }
}