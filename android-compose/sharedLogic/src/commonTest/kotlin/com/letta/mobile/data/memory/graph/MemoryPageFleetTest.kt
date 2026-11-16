package com.letta.mobile.data.memory.graph

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryPageFleetTest {
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
