package com.letta.mobile.ui.memory

import com.letta.mobile.data.memory.MemoryFleetInput
import com.letta.mobile.data.memory.MemoryFleetMapper
import com.letta.mobile.data.memory.MemoryParityState
import com.letta.mobile.data.memory.graph.MemoryGraphLayoutConfig
import com.letta.mobile.data.memory.graph.MemoryGraphLayoutEngine
import com.letta.mobile.data.memory.graph.MemoryGraphMotion
import com.letta.mobile.data.memory.graph.MemoryGraphView
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The fleet memory graph opens out live like one agent's, then comes to rest and stays there. */
class MemoryFleetGraphLayoutTest {
    /** A dense fleet: 13 agents sharing "persona" and "human", each with 20 labels of its own. */
    private val agents = (1..13).map { Agent(id = AgentId("a$it"), name = "Agent $it") }

    @Test
    fun aDenseFleetComesToRestWithinAFewSecondsAndStaysThere() {
        val memory = fleet(agents)
        val view = MemoryGraphViews.build(memory.graph, emptySet())
        assertTrue(view.nodes.size in 200..MemoryGraphPhysics.LIVE_NODE_LIMIT, "a fleet the live view simulates")
        val sim = physics(view, memory)
        assertTrue(sim.isHot, "the fleet graph opens out like one agent's")

        var frames = 0
        while (sim.tick()) frames++
        assertTrue(frames <= REST_WITHIN_FRAMES, "came to rest in $frames frames")
        assertTrue(sim.lastSpeed < REST_SPEED, "still moving at ${sim.lastSpeed} per frame when it stopped")

        val rested = sim.snapshot()
        repeat(STAY_FRAMES) { assertFalse(sim.tick(), "a rested graph stays still") }
        assertEquals(rested, sim.snapshot())
    }

    @Test
    fun oneAgentsStarKeepsItsOwnMotion() {
        val config = MemoryGraphLayoutConfig()
        assertEquals(
            MemoryGraphMotion(config.damping, config.maxStep, alphaDecay = 0f, clampVelocity = false),
            MemoryGraphMotion.forGraph(nodes = 12, edges = 11),
        )
        val crowded = MemoryGraphMotion.forGraph(nodes = 300, edges = 600)
        assertTrue(crowded.damping < config.damping && crowded.maxSpeed < config.maxStep && crowded.alphaDecay > 0f)
    }

    @Test
    fun theSameFleetTwiceAndAnotherAgentsBlocksStartFromWhatIsShown() {
        val live = MemoryGraphLiveLayout()
        show(live, fleet(agents.take(3)))
        val shown = live.layout.positions

        show(live, fleet(agents.take(3)))
        assertEquals(shown, live.layout.positions, "the same fleet again moves nothing")
        assertTrue(live.isSettled, "nor sets it moving")

        show(live, fleet(agents.take(4)))
        val grown = live.layout.positions
        assertTrue(grown.keys.containsAll(shown.keys), "existing nodes keep their ids")
        shown.forEach { (id, point) -> assertEquals(point, grown[id], "$id starts where it was shown") }
        assertTrue(grown.size > shown.size)
    }

    private fun physics(view: MemoryGraphView, memory: MemoryParityState): MemoryGraphPhysics {
        val looks = MemoryNodeLooks.build(view, memory)
        return MemoryGraphPhysics.create(
            view = view,
            target = MemoryGraphLayoutEngine.layout(view),
            radiusOf = { id -> looks[id]?.radius ?: MemoryNodeLooks.SMALL_RADIUS },
            start = MemoryGraphPhysicsStart(previous = null, bloom = true, motion = true),
        )
    }

    private fun show(live: MemoryGraphLiveLayout, memory: MemoryParityState) {
        val view = MemoryGraphViews.build(memory.graph, emptySet())
        live.retarget(view, MemoryGraphLayoutEngine.layout(view), MemoryNodeLooks.build(view, memory), motion = true)
    }

    private fun fleet(roster: List<Agent>): MemoryParityState = MemoryFleetMapper.build(
        MemoryFleetInput(
            agents = roster,
            blocks = roster.map { agent ->
                val id = agent.id.value
                val own = (1..OWN_LABELS).map { n -> block("$id-$n", "notes/$id/$n") }
                agent.id to listOf(block("$id-persona", "persona"), block("$id-human", "human")) + own
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

    private companion object {
        const val OWN_LABELS = 20

        /** About three seconds at 60 frames per second. */
        const val REST_WITHIN_FRAMES = 180
        const val REST_SPEED = 1f
        const val STAY_FRAMES = 120
    }
}
