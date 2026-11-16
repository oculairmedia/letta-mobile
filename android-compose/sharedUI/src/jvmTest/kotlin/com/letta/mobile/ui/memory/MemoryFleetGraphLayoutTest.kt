package com.letta.mobile.ui.memory

import com.letta.mobile.data.memory.MemoryFleetInput
import com.letta.mobile.data.memory.MemoryFleetMapper
import com.letta.mobile.data.memory.MemoryParityState
import com.letta.mobile.data.memory.graph.MemoryGraphLayoutEngine
import com.letta.mobile.data.memory.graph.MemoryGraphViews
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.RuntimeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fleet memory graph rests: it is drawn where it is laid out, and an update keeps what is shown. */
class MemoryFleetGraphLayoutTest {
    private val agents = (1..4).map { Agent(id = AgentId("a$it"), name = "Agent $it") }

    @Test
    fun theSameFleetTwiceAndAnotherAgentsBlocksKeepEveryShownNodeWhereItIs() {
        val live = MemoryGraphLiveLayout()
        val first = fleet(agents.take(3))
        show(live, first)
        val shown = live.layout.positions
        assertTrue(live.isSettled, "the fleet graph does not drift after it opens")

        show(live, fleet(agents.take(3)))
        assertEquals(shown, live.layout.positions, "the same fleet again moves nothing")

        show(live, fleet(agents))
        val grown = live.layout.positions
        assertTrue(grown.keys.containsAll(shown.keys), "existing nodes keep their ids")
        shown.forEach { (id, point) -> assertEquals(point, grown[id], "$id keeps its position") }
        assertTrue(grown.size > shown.size)
        assertTrue(live.isSettled, "new blocks do not set the graph moving")
    }

    private fun show(live: MemoryGraphLiveLayout, memory: MemoryParityState) {
        val view = MemoryGraphViews.build(memory.graph, emptySet())
        live.retarget(view, MemoryGraphLayoutEngine.layout(view), MemoryNodeLooks.build(view, memory), motion = !memory.fleet)
    }

    private fun fleet(roster: List<Agent>): MemoryParityState = MemoryFleetMapper.build(
        MemoryFleetInput(
            agents = roster,
            blocks = roster.map { agent ->
                val id = agent.id.value
                agent.id to listOf(block("$id-persona", "persona"), block("$id-notes", "notes/$id"))
            },
            backendDescriptor = backend,
            channelTransportState = ChannelTransportState.Idle,
        ),
    )

    private fun block(id: String, label: String) = Block(id = BlockId(id), label = label, value = "value of $id")

    private val backend = BackendDescriptor(
        backendId = BackendId("backend"),
        runtimeId = RuntimeId("runtime"),
        kind = BackendKind.RemoteLetta,
        label = "Backend",
        capabilities = BackendCapabilities(
            supportsStreaming = true,
            supportsMemFs = true,
            supportsToolEvents = true,
            supportsToolExecution = true,
            supportsApprovals = true,
            supportsAgentFileImport = true,
            supportsAgentFileExport = true,
        ),
    )
}
