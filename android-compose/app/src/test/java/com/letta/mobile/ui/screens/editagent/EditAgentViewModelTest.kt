package com.letta.mobile.ui.screens.editagent

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeBlockApi
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditAgentViewModelTest {

    private lateinit var fakeAgentRepo: FakeAgentRepo
    private lateinit var fakeBlockRepo: FakeBlockRepo
    private lateinit var viewModel: EditAgentViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeAgentRepo = FakeAgentRepo()
        fakeBlockRepo = FakeBlockRepo()
        val savedState = SavedStateHandle(mapOf("agentId" to "a1"))
        viewModel = EditAgentViewModel(savedState, fakeAgentRepo, fakeBlockRepo)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loadAgent populates all fields`() = runTest {
        viewModel.loadAgent()
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals("Test Agent", state.data.name)
            assertEquals(2, state.data.blocks.size)
            assertEquals("persona value", state.data.blocks.first { it.label == "persona" }.value)
            assertEquals("human value", state.data.blocks.first { it.label == "human" }.value)
        }
    }

    @Test
    fun `loadAgent sets Error on failure`() = runTest {
        fakeAgentRepo.shouldFail = true
        viewModel.loadAgent()
        viewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
    }

    @Test
    fun `updateName changes only name`() = runTest {
        viewModel.loadAgent()
        viewModel.updateName("New Name")
        viewModel.uiState.test {
            assertEquals("New Name", (awaitItem() as UiState.Success).data.name)
        }
    }

    @Test
    fun `updateBlockValue changes matching block`() = runTest {
        viewModel.loadAgent()
        viewModel.updateBlockValue("persona", "New persona")
        viewModel.uiState.test {
            val state = (awaitItem() as UiState.Success).data
            assertEquals("New persona", state.blocks.first { it.label == "persona" }.value)
        }
    }

    @Test
    fun `saveAgent calls onSuccess`() = runTest {
        viewModel.loadAgent()
        var called = false
        viewModel.saveAgent { called = true }
        assertTrue(called)
    }

    @Test
    fun `addBlock forwards description and limit`() = runTest {
        viewModel.addBlock("memory", "value", "notes", 512)

        assertEquals("memory", fakeBlockRepo.lastCreatedParams?.label)
        assertEquals("value", fakeBlockRepo.lastCreatedParams?.value)
        assertEquals("notes", fakeBlockRepo.lastCreatedParams?.description)
        assertEquals(512, fakeBlockRepo.lastCreatedParams?.limit)
    }

    @Test
    fun `saveAgent sets Error on failure`() = runTest {
        viewModel.loadAgent()
        fakeAgentRepo.shouldFail = true
        viewModel.saveAgent {}
        viewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
    }

    private class FakeAgentRepo : AgentRepository(FakeAgentApi()) {
        private val _agents = MutableStateFlow<List<Agent>>(emptyList())
        override val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
        var shouldFail = false

        override fun getAgent(id: String): Flow<Agent> = flow {
            if (shouldFail) throw Exception("Load failed")
            emit(TestData.agent(
                id = "a1",
                name = "Test Agent",
                blocks = listOf(
                    TestData.block(label = "persona", value = "persona value"),
                    TestData.block(label = "human", value = "human value"),
                )
            ))
        }
        override suspend fun refreshAgents() {}
        override suspend fun createAgent(params: AgentCreateParams): Agent = TestData.agent()
        override suspend fun updateAgent(id: String, params: AgentUpdateParams): Agent {
            if (shouldFail) throw Exception("Update failed")
            return TestData.agent(id = id)
        }
        override suspend fun deleteAgent(id: String) {}
    }

    private class FakeBlockRepo : BlockRepository(FakeBlockApi()) {
        var lastCreatedParams: BlockCreateParams? = null

        override suspend fun createBlock(params: BlockCreateParams): Block {
            lastCreatedParams = params
            return TestData.block(id = "new-block", label = params.label, value = params.value)
        }

        override suspend fun updateBlock(agentId: String, blockLabel: String, value: String): Block {
            return TestData.block(label = blockLabel, value = value)
        }

        override suspend fun attachBlock(agentId: String, blockId: String): Block {
            return TestData.block(id = blockId, label = "attached", value = "value")
        }
    }
}
