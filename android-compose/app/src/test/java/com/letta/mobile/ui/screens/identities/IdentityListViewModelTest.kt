package com.letta.mobile.ui.screens.identities

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityUpdateParams
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.IdentityRepository
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeIdentityApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class IdentityListViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeApi: FakeIdentityApi
    private lateinit var fakeAgentApi: FakeAgentApi
    private lateinit var repository: IdentityRepository
    private lateinit var agentRepository: AgentRepository
    private lateinit var viewModel: IdentityListViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeApi = FakeIdentityApi()
        fakeAgentApi = FakeAgentApi()
        fakeApi.identities.addAll(
            listOf(
                sampleIdentity("identity-1", "user-1", "User One", "user"),
                sampleIdentity("identity-2", "org-1", "Org One", "org"),
            )
        )
        fakeAgentApi.agents.addAll(
            listOf(
                sampleAgent("agent-1", "Agent One"),
                sampleAgent("agent-2", "Agent Two"),
            )
        )
        repository = IdentityRepository(fakeApi)
        agentRepository = AgentRepository(fakeAgentApi, FakeAgentDao())
        viewModel = IdentityListViewModel(repository, agentRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadIdentities populates state`() = runTest {
        viewModel.loadIdentities()

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(2, state.data.identities.size)
    }

    @Test
    fun `updateSearchQuery filters identities locally`() = runTest {
        viewModel.loadIdentities()
        viewModel.updateSearchQuery("org")

        val filtered = viewModel.getFilteredIdentities()
        assertEquals(1, filtered.size)
        assertEquals("identity-2", filtered.first().id)
    }

    @Test
    fun `inspectIdentity loads details`() = runTest {
        viewModel.inspectIdentity("identity-1")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals("identity-1", state.data.selectedIdentity?.id)
    }

    @Test
    fun `createIdentity delegates to repository`() = runTest {
        viewModel.createIdentity(
            IdentityCreateParams(identifierKey = "user-2", name = "User Two", identityType = "user")
        )

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(3, state.data.identities.size)
    }

    @Test
    fun `updateIdentity updates state without clearing list`() = runTest {
        viewModel.loadIdentities()

        viewModel.updateIdentity(
            identityId = "identity-1",
            params = IdentityUpdateParams(name = "Updated User", blockIds = listOf("block-1")),
        )

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        val updated = state.data.identities.first { it.id == "identity-1" }
        assertEquals("Updated User", updated.name)
        assertEquals(listOf("block-1"), updated.blockIds)
    }

    @Test
    fun `deleteIdentity removes identity from state`() = runTest {
        viewModel.deleteIdentity("identity-1")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(1, state.data.identities.size)
        assertEquals("identity-2", state.data.identities.first().id)
    }

    @Test
    fun `attachIdentity refreshes selected identity relationships`() = runTest {
        fakeApi.identities[0] = fakeApi.identities[0].copy(agentIds = emptyList())
        viewModel.inspectIdentity("identity-1")

        viewModel.attachIdentity("agent-1", "identity-1")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(listOf("agent-1"), state.data.selectedIdentity?.agentIds)
        assertEquals(2, state.data.knownAgents.size)
    }

    @Test
    fun `detachIdentity refreshes selected identity relationships`() = runTest {
        fakeApi.identities[0] = fakeApi.identities[0].copy(agentIds = listOf("agent-1"))
        viewModel.inspectIdentity("identity-1")

        viewModel.detachIdentity("agent-1", "identity-1")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(emptyList<String>(), state.data.selectedIdentity?.agentIds)
    }

    @Test
    fun `inspectIdentity still loads identity when agent refresh fails`() = runTest {
        fakeAgentApi.shouldFail = true

        viewModel.inspectIdentity("identity-1")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals("identity-1", state.data.selectedIdentity?.id)
        assertEquals(emptyList<Agent>(), state.data.knownAgents)
        assertEquals(null, state.data.operationError)
    }
}

private fun sampleIdentity(id: String, identifierKey: String, name: String, type: String) = Identity(
    id = id,
    identifierKey = identifierKey,
    name = name,
    identityType = type,
)

private fun sampleAgent(id: String, name: String) = Agent(
    id = AgentId(id),
    name = name,
)

private class FakeAgentDao : AgentDao {
    private var agents: List<AgentEntity> = emptyList()

    override fun getAll(): Flow<List<AgentEntity>> = flowOf(agents)

    override suspend fun getAllOnce(): List<AgentEntity> = agents

    override suspend fun insertAll(agents: List<AgentEntity>) {
        this.agents = agents
    }

    override suspend fun deleteExcept(keepIds: List<String>) {
        agents = agents.filter { it.id in keepIds }
    }

    override suspend fun deleteAll() {
        agents = emptyList()
    }
}
