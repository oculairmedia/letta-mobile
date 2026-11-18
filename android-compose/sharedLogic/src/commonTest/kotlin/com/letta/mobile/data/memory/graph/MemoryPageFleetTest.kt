package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.home.SessionHomePageSourceTest
import com.letta.mobile.data.memory.MemoryFleetInput
import com.letta.mobile.data.memory.MemoryFleetMapper
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.transport.ChannelTransportState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryPageFleetTest {
    @Test
    fun aChangedFleetBlockClosesTheCardAndCancelsItsPendingLoad() = runTest {
        val ada = Agent(AgentId("a1"), "Ada")
        val bo = Agent(AgentId("a2"), "Bo")
        val backend = SessionHomePageSourceTest.HomeGraph().backendDescriptor
        fun memory(agent: Agent, id: String) = MemoryFleetMapper.build(MemoryFleetInput(
            agents = listOf(ada, bo),
            blocks = listOf(agent.id to listOf(Block(BlockId(id), label = "persona", value = "overview"))),
            backendDescriptor = backend,
            channelTransportState = ChannelTransportState.Idle,
        ))
        val initial = MemoryGraphFixtures.controllerState().copy(memory = memory(ada, "ada-block"))
        val source = FakeMemoryParitySource(initial)
        var cancelled = false
        val port = object : MemoryBlockContentPort {
            override fun canWrite(): Boolean = true
            override suspend fun load(ref: MemoryBlockRef): Block {
                assertEquals(MemoryBlockRef("a1", "persona", "ada-block"), ref)
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            override suspend fun save(ref: MemoryBlockRef, value: String): Block = error("unexpected write")
            override suspend fun create(agentId: String, label: String, value: String): Block = error("unexpected create")
            override suspend fun delete(ref: MemoryBlockRef): Unit = error("unexpected delete")
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val controller = MemoryPageController(source, port, backgroundScope, dispatcher)
        controller.start()
        runCurrent()
        controller.selectNode("memory:fleet:label:persona")
        runCurrent()
        assertEquals(MemoryNodeContent.Loading, controller.state.value.selection?.content)
        source.emit(initial.copy(memory = memory(bo, "bo-block")))
        runCurrent()
        assertNull(controller.state.value.selection)
        assertTrue(cancelled, "the old block load is cancelled on reference change")
        controller.close()
    }

    @Test
    fun selectingTheFleetClosesTheOpenCardAndAsksTheSourceForEveryAgent() = runTest {
        val source = FakeMemoryParitySource(MemoryGraphFixtures.controllerState())
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val controller = MemoryPageController(
            source = source,
            blocks = FakeMemoryBlockPort(initial = mapOf("persona" to "I am a helpful agent.")),
            scope = TestScope(dispatcher),
            layoutDispatcher = dispatcher,
        ).also { it.start() }
        controller.selectAgent("agent-1")
        controller.selectNode(MemoryGraphFixtures.blockNodeId(MemoryGraphFixtures.persona))

        controller.selectFleet()

        assertNull(controller.state.value.selection)
        assertNull(source.selectedAgent)
        assertEquals(1, source.fleetSelections)
    }
}
