package com.letta.mobile.ui.screens.templates

import app.cash.turbine.test
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.testutil.FakeAgentApi
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
class TemplatesViewModelTest {

    private lateinit var fakeRepo: FakeAgentRepo
    private lateinit var viewModel: TemplatesViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeAgentRepo()
        viewModel = TemplatesViewModel(fakeRepo)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loadTemplates emits hardcoded templates`() = runTest {
        viewModel.loadTemplates()
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertTrue(state.data.templates.isNotEmpty())
            assertEquals(3, state.data.templates.size)
        }
    }

    @Test
    fun `createFromTemplate calls repo and invokes onSuccess`() = runTest {
        var capturedId = ""
        viewModel.createFromTemplate("default") { capturedId = it }
        assertTrue(capturedId.isNotEmpty())
    }

    @Test
    fun `createFromTemplate sets Error on failure`() = runTest {
        fakeRepo.shouldFail = true
        viewModel.createFromTemplate("default") {}
        viewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
    }

    private class FakeAgentRepo : AgentRepository(FakeAgentApi()) {
        private val _agents = MutableStateFlow<List<Agent>>(emptyList())
        override val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
        var shouldFail = false

        override suspend fun refreshAgents() {}
        override fun getAgent(id: String): Flow<Agent> = flow { emit(TestData.agent()) }
        override suspend fun createAgent(params: AgentCreateParams): Agent {
            if (shouldFail) throw Exception("Create failed")
            return TestData.agent(id = "new-1", name = params.name ?: "Unnamed")
        }
        override suspend fun updateAgent(id: String, params: AgentUpdateParams): Agent = TestData.agent()
        override suspend fun deleteAgent(id: String) {}
    }
}
