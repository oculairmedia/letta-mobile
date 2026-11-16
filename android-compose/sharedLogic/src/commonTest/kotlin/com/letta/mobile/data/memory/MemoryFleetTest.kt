package com.letta.mobile.data.memory

import com.letta.mobile.data.home.SessionHomePageSourceTest
import com.letta.mobile.data.memory.graph.MemoryBlockRef
import com.letta.mobile.data.memory.graph.MemoryNodeDetails
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.repository.api.IAgentBlockRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import com.letta.mobile.data.transport.ChannelTransportState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryFleetTest {
    private val ada = Agent(id = AgentId("a1"), name = "Ada")
    private val bo = Agent(id = AgentId("a2"), name = "Bo")
    private val cy = Agent(id = AgentId("a3"), name = "Cy")
    private val backend = SessionHomePageSourceTest.HomeGraph().backendDescriptor

    @Test
    fun blocksGroupByLabelWithTheirAgentsInRosterOrder() {
        val memory = build(
            ada.id to listOf(block("a1-persona", "persona"), block("a1-human", "human")),
            bo.id to listOf(block("a2-persona", "persona")),
            cy.id to listOf(block("a3-persona", "persona"), block("a3-notes", "notes")),
        )
        val items = blockItems(memory)
        assertEquals(listOf("human", "notes", "persona"), items.map { it.label })
        assertEquals(listOf("Ada", "Bo", "Cy"), items.last().owners.map { it.name })
        assertEquals("Used by Ada, Bo, Cy", items.last().subtitle)
        assertEquals(5, memory.summary.memoryBlockCount)
        assertTrue(memory.fleet)
        assertNull(memory.selectedAgentId)
    }

    @Test
    fun anUnlabelledBlockNeverJoinsALabelThatLooksLikeItsId() {
        val unlabelled = Block(id = BlockId("b1"), label = null, value = "v")
        val memory = build(ada.id to listOf(unlabelled), bo.id to listOf(block("other", "id:b1")))
        val items = blockItems(memory)
        assertEquals(2, items.size)
        assertEquals(listOf(listOf("Bo"), listOf("Ada")), items.map { item -> item.owners.map { it.name } })
    }

    @Test
    fun theGraphIsRootedAtTheBackendWithAgentsAsChildren() {
        val memory = build(ada.id to listOf(block("p1", "persona")), bo.id to listOf(block("p2", "persona")))
        val graph = memory.graph
        val root = graph.nodes.first()
        assertEquals(MemoryGraphNodeKind.Backend, root.kind)
        assertEquals(
            listOf("agent:a1", "agent:a2", "agent:a3"),
            graph.edges.filter { it.fromId == root.id && it.kind == MemoryGraphEdgeKind.Runs }.map { it.toId },
        )
        val persona = graph.nodes.single { it.kind == MemoryGraphNodeKind.Memory }
        assertEquals(listOf("agent:a1", "agent:a2"), graph.edges.filter { it.toId == persona.id }.map { it.fromId })
    }

    @Test
    fun aLabelGroupIsEditableOnlyWhenItsOwnersHoldOneBlock() {
        val memory = build(
            ada.id to listOf(block("a1-persona", "persona"), block("shared", "team")),
            bo.id to listOf(block("a2-persona", "persona"), block("shared", "team")),
            cy.id to listOf(block("a3-notes", "notes")),
        )
        val ids = memory.graph.nodes.filter { it.kind == MemoryGraphNodeKind.Memory }.associate { it.title to it.id }
        assertNull(MemoryNodeDetails.resolve(memory, ids.getValue("persona"))?.blockRef, "distinct blocks under one label")
        assertEquals(MemoryBlockRef("a1", "team", "shared"), MemoryNodeDetails.resolve(memory, ids.getValue("team"))?.blockRef)
        assertEquals(MemoryBlockRef("a3", "notes", "a3-notes"), MemoryNodeDetails.resolve(memory, ids.getValue("notes"))?.blockRef)
    }

    @Test
    fun focusMovingFromNoneToAnAgentAndBackSwapsFleetAndAgentMemory() = runTest {
        val controller = MemoryParityController(provider(FleetBlocks()), this, sectionReader = MemoryParitySectionReader { _, _ -> })
        controller.selectFleet()
        advanceUntilIdle()
        assertTrue(controller.state.value.memory.fleet)
        assertEquals(listOf("human", "persona"), blockItems(controller.state.value.memory).map { it.label })

        controller.selectAgent("a2")
        assertTrue(controller.state.value.memory.sections.isEmpty(), "the fleet must not linger under an agent")
        advanceUntilIdle()
        assertFalse(controller.state.value.memory.fleet)
        assertEquals("a2", controller.state.value.memory.selectedAgentId)

        controller.selectFleet()
        assertNull(controller.state.value.memory.selectedAgentId, "the agent must not linger in the fleet")
        advanceUntilIdle()
        assertTrue(controller.state.value.memory.fleet)
        assertEquals(3, controller.state.value.agents.size)
    }

    @Test
    fun oneAgentsFailedBlockReadLeavesTheOthersBlocks() = runTest {
        val blocks = FleetBlocks().apply { failing += "a1" }
        val controller = MemoryParityController(provider(blocks), this, sectionReader = MemoryParitySectionReader { _, _ -> })
        controller.selectFleet()
        advanceUntilIdle()
        val items = blockItems(controller.state.value.memory)
        assertEquals(listOf("persona"), items.map { it.label })
        assertEquals(listOf("Bo", "Cy"), items.single().owners.map { it.name })
        assertNull(controller.state.value.errorMessage)
    }

    @Test
    fun everyAgentsFailedBlockReadMarksTheMemorySectionUnavailable() = runTest {
        val blocks = FleetBlocks().apply { failing += listOf("a1", "a2", "a3") }
        val controller = MemoryParityController(provider(blocks), this, sectionReader = MemoryParitySectionReader { _, _ -> })
        controller.selectFleet()
        advanceUntilIdle()
        val section = controller.state.value.memory.sections.first { it.kind == MemoryParitySectionKind.Memory }
        assertEquals(MemoryParitySectionStatus.Unavailable, section.status)
        assertTrue(controller.state.value.errorMessage != null)
    }

    private fun build(vararg blocks: Pair<AgentId, List<Block>>): MemoryParityState = MemoryFleetMapper.build(
        MemoryFleetInput(listOf(ada, bo, cy), blocks.toList(), backend, ChannelTransportState.Idle),
    )

    private fun blockItems(memory: MemoryParityState): List<MemoryParityItem.MemoryBlock> =
        memory.sections.first { it.kind == MemoryParitySectionKind.Memory }.items.filterIsInstance<MemoryParityItem.MemoryBlock>()

    private inner class FleetBlocks : IAgentBlockRepository {
        val failing = mutableSetOf<String>()

        override suspend fun getBlocks(agentId: String): List<Block> {
            if (agentId in failing) error("$agentId down")
            val persona = block("$agentId-persona", "persona")
            return if (agentId == "a1") listOf(persona, block("a1-human", "human")) else listOf(persona)
        }
    }

    private fun provider(blocks: IAgentBlockRepository): SessionRepositoryGraphProvider<SessionRepositoryGraph> {
        val roster = listOf(ada, bo, cy)
        val agents = object : IAgentRepository by SessionHomePageSourceTest.HomeGraph().agentRepository {
            override val agents: StateFlow<List<Agent>> = MutableStateFlow(roster)
            override suspend fun refreshAgentsIfStale(maxAgeMs: Long): Boolean = false
        }
        val graph = object : SessionRepositoryGraph by SessionHomePageSourceTest.HomeGraph() {
            override val agentRepository = agents
            override val blockRepository = blocks
        }
        return object : SessionRepositoryGraphProvider<SessionRepositoryGraph> {
            override val currentGraph = MutableStateFlow<SessionRepositoryGraph>(graph)
            override val sessionError: StateFlow<Throwable?> = MutableStateFlow(null)
            override val current: SessionRepositoryGraph get() = currentGraph.value
            override fun rebuild(): SessionRepositoryGraph = current
            override suspend fun <T> withCurrentSession(block: suspend (SessionRepositoryGraph) -> T): T = block(current)
        }
    }
}

private fun block(id: String, label: String) = Block(id = BlockId(id), label = label, value = "value of $id")
