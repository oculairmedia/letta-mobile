package com.letta.mobile.data.templates

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.repository.api.IAgentRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-ckmub.2.5 (S17): making an agent from a starter template through `agent_create`. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentTemplatesControllerTest {
    /** letta-mobile-by4vm.13: a session with no agents route offers no create, and never calls one. */
    @Test
    fun anAbsentAgentsRouteOffersNoCreate() = runTest {
        val controller = AgentTemplatesController(agentRepository = null, scope = backgroundScope)
        assertTrue(controller.state.value.createUnavailableReason != null)
        val opened = mutableListOf<String>()
        controller.create("coder") { opened += it }
        runCurrent()
        assertTrue(opened.isEmpty())
        assertNull(controller.state.value.creatingId)
        assertNull(controller.state.value.error)
    }

    @Test
    fun aTemplateBecomesAnAgentWithItsPromptAndTags() = runTest {
        val agents = FakeAgentRepository()
        val controller = AgentTemplatesController(agents, backgroundScope)
        val opened = mutableListOf<String>()
        controller.create("coder") { opened += it }
        runCurrent()
        val params = agents.created.single()
        assertEquals("Coding Assistant", params.name)
        assertEquals(listOf("starter", "coding"), params.tags?.toList())
        assertEquals(true, params.includeBaseTools)
        assertEquals(listOf("agent-new"), opened)
        assertNull(controller.state.value.creatingId)
    }

    @Test
    fun oneCreationAtATime() = runTest {
        val gate = CompletableDeferred<Unit>()
        val agents = FakeAgentRepository(gate = gate)
        val controller = AgentTemplatesController(agents, backgroundScope)
        controller.create("default") {}
        runCurrent()
        assertEquals("default", controller.state.value.creatingId)
        controller.create("writer") {}
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("Default Agent"), agents.created.map { it.name })
    }

    @Test
    fun aRefusedCreationIsShownAndCanBeDismissed() = runTest {
        val controller = AgentTemplatesController(FakeAgentRepository(failure = IllegalStateException("no model")), backgroundScope)
        val opened = mutableListOf<String>()
        controller.create("writer") { opened += it }
        runCurrent()
        assertEquals("no model", controller.state.value.error)
        assertEquals(emptyList<String>(), opened)
        controller.clearError()
        assertNull(controller.state.value.error)
    }

    @Test
    fun anUnknownTemplateDoesNothing() = runTest {
        val agents = FakeAgentRepository()
        AgentTemplatesController(agents, backgroundScope).create("missing") {}
        runCurrent()
        assertTrue(agents.created.isEmpty())
    }

    @Test
    fun aBackendThatCannotCreateGatesTheActionInsteadOfCallingIt() = runTest {
        // letta-mobile-by4vm.45: the bundled App Server's createAgent always throws; the page hides it.
        val agents = FakeAgentRepository(canCreate = false)
        val controller = AgentTemplatesController(agents, backgroundScope)
        assertEquals("This backend cannot create agents yet.", controller.state.value.createUnavailableReason)
        val opened = mutableListOf<String>()
        controller.create("default") { opened += it }
        runCurrent()
        assertTrue(agents.created.isEmpty())
        assertEquals(emptyList<String>(), opened)
        assertNull(controller.state.value.creatingId)
        assertNull(controller.state.value.error)
    }

    @Test
    fun aBackendThatCanCreateOffersTheAction() = runTest {
        assertNull(AgentTemplatesController(FakeAgentRepository(), backgroundScope).state.value.createUnavailableReason)
    }

    private class FakeAgentRepository(
        private val failure: Throwable? = null,
        private val gate: CompletableDeferred<Unit>? = null,
        private val canCreate: Boolean = true,
    ) : IAgentRepository {
        val created = mutableListOf<AgentCreateParams>()
        override val supportsCreateAgent: Boolean get() = canCreate
        override val agents: StateFlow<List<Agent>> = MutableStateFlow(emptyList())
        override val isRefreshing: StateFlow<Boolean> = MutableStateFlow(false)
        override val refreshError: StateFlow<Throwable?> = MutableStateFlow(null)
        override suspend fun countAgents(): Int = 0
        override suspend fun refreshAgents() = Unit
        override suspend fun listAgentSummaries(): List<AgentSummary> = emptyList()
        override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean = false
        override fun getCachedAgent(id: AgentId): Agent? = null
        override fun getAgent(id: AgentId): Flow<Agent> = error("unused")
        override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?): ContextWindowOverview = error("unused")
        override suspend fun checkpointAndRestoreConfig(agentId: AgentId, operation: suspend () -> Unit) = operation()
        override suspend fun createAgent(params: AgentCreateParams): Agent {
            gate?.await()
            failure?.let { throw it }
            created += params
            return Agent(id = AgentId("agent-new"), name = params.name.orEmpty())
        }
        override suspend fun updateAgent(id: AgentId, params: AgentUpdateParams): Agent = error("unused")
        override suspend fun deleteAgent(id: AgentId) = Unit
        override suspend fun exportAgent(id: AgentId): String = error("unused")
        override suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse = error("unused")
        override suspend fun attachArchive(agentId: AgentId, archiveId: String) = Unit
        override suspend fun detachArchive(agentId: AgentId, archiveId: String) = Unit
    }
}
