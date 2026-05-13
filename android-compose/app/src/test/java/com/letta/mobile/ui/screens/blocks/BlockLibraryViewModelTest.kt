package com.letta.mobile.ui.screens.blocks

import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.AgentId
import app.cash.turbine.test
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.ui.common.UiState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class BlockLibraryViewModelTest {

    private lateinit var fakeRepo: FakeBlockRepo
    private lateinit var mockAgentRepo: AgentRepository
    private lateinit var viewModel: BlockLibraryViewModel
    private val testDispatcher = UnconfinedTestDispatcher()
    private val agentsFlow = MutableStateFlow<List<Agent>>(emptyList())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeBlockRepo()
        mockAgentRepo = mockk(relaxed = true)
        every { mockAgentRepo.agents } returns agentsFlow
        viewModel = BlockLibraryViewModel(fakeRepo, mockAgentRepo)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loadBlocks sets Success with block list`() = runTest {
        fakeRepo.allBlocks = listOf(
            Block(id = BlockId("b1"), label = "persona", value = "I am helpful."),
            Block(id = BlockId("b2"), label = "human", value = "User info."),
        )
        viewModel.loadBlocks()
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals(2, state.data.blocks.size)
        }
    }

    @Test
    fun `loadBlocks sets Error on failure`() = runTest {
        fakeRepo.shouldFail = true
        viewModel.loadBlocks()
        viewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
    }

    @Test
    fun `setFilter updates filter and reloads`() = runTest {
        fakeRepo.allBlocks = listOf(
            Block(id = BlockId("b1"), label = "persona", value = "Persona block."),
            Block(id = BlockId("b2"), label = "human", value = "Human block."),
        )
        viewModel.setFilter(label = "persona", isTemplate = null)
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals(1, state.data.blocks.size)
            assertEquals("persona", state.data.blocks[0].label)
            assertEquals("persona", state.data.filterLabel)
        }
    }

    @Test
    fun `loadBlocks with template filter returns only templates`() = runTest {
        fakeRepo.allBlocks = listOf(
            Block(id = BlockId("b1"), label = "persona", value = "Template", isTemplate = true),
            Block(id = BlockId("b2"), label = "human", value = "Not template", isTemplate = false),
        )
        viewModel.setFilter(label = null, isTemplate = true)
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals(1, state.data.blocks.size)
            assertEquals(true, state.data.blocks[0].isTemplate)
            assertEquals(true, state.data.filterTemplate)
        }
    }

    @Test
    fun `updateSearchQuery filters loaded blocks locally`() = runTest {
        fakeRepo.allBlocks = listOf(
            Block(id = BlockId("b1"), label = "persona", value = "Template block", description = "Behavior rules"),
            Block(id = BlockId("b2"), label = "human", value = "Human block", description = "User profile"),
        )
        viewModel.loadBlocks()

        viewModel.updateSearchQuery("behavior")

        val filtered = viewModel.getFilteredBlocks()
        assertEquals(1, filtered.size)
        assertEquals("persona", filtered.first().label)
    }

    @Test
    fun `deleteBlock delegates to repository`() = runTest {
        fakeRepo.allBlocks = listOf(Block(id = BlockId("b1"), label = "persona", value = "Template block"))

        viewModel.deleteBlock("b1")

        assertEquals(listOf("b1"), fakeRepo.deletedBlockIds)
    }

    @Test
    fun `createBlock delegates to repository and reloads blocks`() = runTest {
        viewModel.createBlock(
            label = "persona",
            value = "Created from library",
            description = "Useful behavior",
            limit = 256,
        )

        assertEquals(1, fakeRepo.createdBlocks.size)
        assertEquals("persona", fakeRepo.createdBlocks.single().label)
        val state = viewModel.uiState.value as UiState.Success
        assertEquals(1, state.data.blocks.size)
        assertEquals("Created from library", state.data.blocks.single().value)
    }

    @Test
    fun `updateBlock delegates to repository by block id and reloads blocks`() = runTest {
        fakeRepo.allBlocks = listOf(Block(id = BlockId("b1"), label = "persona", value = "Old value"))

        viewModel.updateGlobalBlock(
            blockId = "b1",
            value = "Updated value",
            description = "Updated description",
            limit = 128,
            onSuccess = {},
        )

        assertEquals(listOf("b1"), fakeRepo.updatedBlockIds)
        val state = viewModel.uiState.value as UiState.Success
        assertEquals("Updated value", state.data.blocks.single().value)
        assertEquals("Updated description", state.data.blocks.single().description)
        assertEquals(128, state.data.blocks.single().limit)
    }

    @Test
    fun `updateGlobalBlock can clear optional metadata`() = runTest {
        fakeRepo.allBlocks = listOf(
            Block(id = BlockId("b1"), label = "persona", value = "Old value", description = "desc", limit = 64)
        )

        viewModel.updateGlobalBlock(
            blockId = "b1",
            value = "Updated value",
            description = "",
            limit = null,
            onSuccess = {},
        )

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(null, state.data.blocks.single().description)
        assertEquals(null, state.data.blocks.single().limit)
    }

    @Test
    fun `createBlock failure preserves success state with operation error`() = runTest {
        fakeRepo.allBlocks = listOf(Block(id = BlockId("b1"), label = "persona", value = "Existing block"))
        viewModel.loadBlocks()
        fakeRepo.shouldFail = true

        viewModel.createBlock("new", "value", "", null)

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(1, state.data.blocks.size)
        assertEquals("Failed to create block", state.data.operationError)
    }

    @Test
    fun `loadBlocks populates agentsByBlock from agent repository`() = runTest {
        fakeRepo.allBlocks = listOf(
            Block(id = BlockId("b1"), label = "persona", value = "Persona block"),
            Block(id = BlockId("b2"), label = "human", value = "Human block"),
        )
        agentsFlow.value = listOf(
            Agent(id = AgentId("a1"), name = "Agent One", blocks = listOf(
                Block(id = BlockId("b1"), label = "persona", value = "Persona block"),
            )),
            Agent(id = AgentId("a2"), name = "Agent Two", blocks = listOf(
                Block(id = BlockId("b1"), label = "persona", value = "Persona block"),
                Block(id = BlockId("b2"), label = "human", value = "Human block"),
            )),
        )

        viewModel.loadBlocks()

        val state = viewModel.uiState.value as UiState.Success
        assertEquals(2, state.data.agentsByBlock["b1"]?.size)
        assertEquals(1, state.data.agentsByBlock["b2"]?.size)
        assertEquals("Agent Two", state.data.agentsByBlock["b2"]?.first()?.name)
    }

    @Test
    fun `detachBlockFromAgent calls repository and reloads`() = runTest {
        fakeRepo.allBlocks = listOf(Block(id = BlockId("b1"), label = "persona", value = "Block"))
        var successCalled = false

        viewModel.detachBlockFromAgent("b1", "a1") { successCalled = true }

        assertTrue(successCalled)
        assertTrue(fakeRepo.detachedPairs.contains("a1" to "b1"))
    }

    @Test
    fun `attachBlockToAgent calls repository and reloads`() = runTest {
        fakeRepo.allBlocks = listOf(Block(id = BlockId("b1"), label = "persona", value = "Block"))
        var successCalled = false

        viewModel.attachBlockToAgent("b1", "a1") { successCalled = true }

        assertTrue(successCalled)
        assertTrue(fakeRepo.attachedPairs.contains("a1" to "b1"))
    }

    private class FakeBlockRepo : IBlockRepository {
        var allBlocks = listOf<Block>()
        var shouldFail = false
        val deletedBlockIds = mutableListOf<String>()
        val createdBlocks = mutableListOf<BlockCreateParams>()
        val updatedBlockIds = mutableListOf<String>()
        val attachedPairs = mutableListOf<Pair<String, String>>()
        val detachedPairs = mutableListOf<Pair<String, String>>()

        override suspend fun listAllBlocks(label: String?, isTemplate: Boolean?): List<Block> {
            if (shouldFail) throw Exception("Failed to load blocks")
            return allBlocks.filter { block ->
                (label == null || block.label == label) &&
                (isTemplate == null || block.isTemplate == isTemplate)
            }
        }

        override suspend fun getBlocks(agentId: String): List<Block> = emptyList()
        override suspend fun retrieveBlock(blockId: String): Block =
            allBlocks.firstOrNull { it.id.value == blockId } ?: throw IllegalArgumentException("Unknown block $blockId")

        override suspend fun countBlocks(): Int = allBlocks.size

        override suspend fun updateAgentBlock(agentId: String, blockLabel: String, params: BlockUpdateParams): Block =
            Block(id = BlockId("stub"), label = blockLabel, value = params.value ?: "")

        override suspend fun updateGlobalBlock(
            blockId: String,
            params: BlockUpdateParams,
            clearDescription: Boolean,
            clearLimit: Boolean,
        ): Block {
            updatedBlockIds.add(blockId)
            val existing = allBlocks.firstOrNull { it.id.value == blockId } ?: Block(id = BlockId(blockId), value = params.value ?: "")
            val updated = existing.copy(
                value = params.value ?: existing.value,
                description = when {
                    params.description != null -> params.description
                    clearDescription -> null
                    else -> existing.description
                },
                limit = when {
                    params.limit != null -> params.limit
                    clearLimit -> null
                    else -> existing.limit
                },
            )
            allBlocks = allBlocks.map { if (it.id.value == blockId) updated else it }
            return updated
        }
        override suspend fun createBlock(params: BlockCreateParams): Block {
            if (shouldFail) throw Exception("Failed to create block")
            createdBlocks.add(params)
            val created = Block(
                id = BlockId("block-${createdBlocks.size}"),
                label = params.label,
                value = params.value,
                description = params.description,
                limit = params.limit,
            )
            allBlocks = allBlocks + created
            return created
        }
        override suspend fun deleteBlock(blockId: String) {
            deletedBlockIds.add(blockId)
            allBlocks = allBlocks.filterNot { it.id.value == blockId }
        }
        override suspend fun attachBlock(agentId: String, blockId: String) {
            attachedPairs.add(agentId to blockId)
        }

        override suspend fun detachBlock(agentId: String, blockId: String) {
            detachedPairs.add(agentId to blockId)
        }

        override suspend fun listAgentsForBlock(blockId: String): List<Agent> = emptyList()

        override suspend fun attachIdentityToBlock(blockId: String, identityId: String): Block =
            retrieveBlock(blockId)

        override suspend fun detachIdentityFromBlock(blockId: String, identityId: String): Block =
            retrieveBlock(blockId)
    }
}
