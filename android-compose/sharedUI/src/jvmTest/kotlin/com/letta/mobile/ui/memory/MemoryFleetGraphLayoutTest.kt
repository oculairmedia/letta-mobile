package com.letta.mobile.ui.memory

import com.letta.mobile.data.memory.MemoryFleetInput
import com.letta.mobile.data.memory.MemoryFleetMapper
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityState
import com.letta.mobile.data.memory.graph.MemoryGraphLayout
import com.letta.mobile.data.memory.graph.MemoryGraphLayoutConfig
import com.letta.mobile.data.memory.graph.MemoryGraphLayoutEngine
import com.letta.mobile.data.memory.graph.MemoryGraphMotion
import com.letta.mobile.data.memory.graph.MemoryGraphTuning
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
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fleet memory graph opens out live like one agent's, then comes to rest round and centred on
 * the backend, and stays there: for a small fleet (13 agents), a real one (150 agents sharing
 * "persona", "human" and a few other labels) and one big enough for Barnes–Hut (150 agents with
 * many blocks of their own).
 */
class MemoryFleetGraphLayoutTest {
    @Test
    fun aSmallFleetRestsRoundAndCentred() = assertRestsRoundAndCentred(fleet(agents = 13, ownShare = 0.3, mostOwn = 3))

    @Test
    fun aRealFleetOfAHundredAndFiftyAgentsRestsRoundAndCentred() =
        assertRestsRoundAndCentred(fleet(agents = 150, ownShare = 0.3, mostOwn = 3))

    @Test
    fun aFleetBigEnoughForBarnesHutStillMovesAndRests() {
        val memory = fleet(agents = 150, ownShare = 0.9, mostOwn = 8)
        val nodes = memory.graph.nodes.size
        assertTrue(nodes > MemoryGraphTuning().barnesHutAbove && nodes <= MemoryGraphPhysics.LIVE_NODE_LIMIT, "$nodes nodes")
        assertRestsRoundAndCentred(memory)
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
        show(live, simpleFleet(3))
        val shown = live.layout.positions

        show(live, simpleFleet(3))
        assertEquals(shown, live.layout.positions, "the same fleet again moves nothing")
        assertTrue(live.isSettled, "nor sets it moving")

        show(live, simpleFleet(4))
        val grown = live.layout.positions
        assertTrue(grown.keys.containsAll(shown.keys), "existing nodes keep their ids")
        shown.forEach { (id, point) -> assertEquals(point, grown[id], "$id starts where it was shown") }
        assertTrue(grown.size > shown.size)
    }

    /**
     * Opens [memory] live and checks it comes to rest within [REST_WITHIN_FRAMES] frames, slower
     * than [REST_SPEED] per frame, stays still, and rests round and centred: the backend at the
     * centre, every node's centroid within [CENTRED] of the layout's radius of it, and at least
     * [FILLED] of the nodes inside half that radius (a ring with an empty middle has almost none).
     */
    private fun assertRestsRoundAndCentred(memory: MemoryParityState) {
        val view = MemoryGraphViews.build(memory.graph, emptySet())
        val sim = physics(view, memory)
        assertTrue(sim.isHot, "the fleet graph opens out like one agent's")
        var frames = 0
        while (sim.tick()) frames++
        assertTrue(frames <= REST_WITHIN_FRAMES, "came to rest in $frames frames")
        assertTrue(sim.lastSpeed < REST_SPEED, "still moving at ${sim.lastSpeed} per frame when it stopped")
        val rested = sim.snapshot()
        repeat(STAY_FRAMES) { assertFalse(sim.tick(), "a rested graph stays still") }
        assertEquals(rested, sim.snapshot())
        assertRoundAndCentred(view, rested)
    }

    private fun assertRoundAndCentred(view: MemoryGraphView, layout: MemoryGraphLayout) {
        val backend = checkNotNull(layout[view.nodes.single { it.kind == MemoryGraphNodeKind.Backend }.id])
        val points = layout.positions.values
        val radius = points.maxOf { hypot(it.x - backend.x, it.y - backend.y) }
        val cx = points.sumOf { it.x.toDouble() } / points.size - backend.x
        val cy = points.sumOf { it.y.toDouble() } / points.size - backend.y
        assertTrue(hypot(cx, cy) <= CENTRED * radius, "centroid ${hypot(cx, cy)} off the backend in a radius of $radius")
        val inside = points.count { hypot(it.x - backend.x, it.y - backend.y) < radius / 2f }.toDouble() / points.size
        assertTrue(inside >= FILLED, "only $inside of the nodes inside half the radius")
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

    /**
     * [agents] agents who all hold "persona" and "human", eight more labels each shared by a random
     * eighth to a third of them, and, for a [ownShare] of them, up to [mostOwn] blocks of their own.
     * Seeded, so every run builds the same fleet.
     */
    private fun fleet(agents: Int, ownShare: Double, mostOwn: Int): MemoryParityState {
        val random = Random(FLEET_SEED)
        val roster = (1..agents).map { Agent(id = AgentId("a$it"), name = "Agent $it") }
        val shared = (1..SHARED_LABELS).associate { n ->
            val holders = roster.shuffled(random).take(random.nextInt(maxOf(2, agents / 8), maxOf(3, agents / 3) + 1))
            "shared/$n" to holders.map { it.id }.toSet()
        }
        val blocks = roster.map { agent ->
            val id = agent.id.value
            val ownCount = if (random.nextDouble() < ownShare) random.nextInt(1, mostOwn + 1) else 0
            val own = (1..ownCount).map { block("$id-$it", "notes/$id/$it") }
            val sharedHeld = shared.filterValues { agent.id in it }.keys.map { label -> block("$id-$label", label) }
            agent.id to listOf(block("$id-persona", "persona"), block("$id-human", "human")) + sharedHeld + own
        }
        return MemoryFleetMapper.build(MemoryFleetInput(roster, blocks, backend, ChannelTransportState.Idle))
    }

    /** [agents] agents sharing "persona", each with one block of its own: a fleet that only grows. */
    private fun simpleFleet(agents: Int): MemoryParityState {
        val roster = (1..agents).map { Agent(id = AgentId("a$it"), name = "Agent $it") }
        val blocks = roster.map { agent ->
            val id = agent.id.value
            agent.id to listOf(block("$id-persona", "persona"), block("$id-notes", "notes/$id"))
        }
        return MemoryFleetMapper.build(MemoryFleetInput(roster, blocks, backend, ChannelTransportState.Idle))
    }

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
        const val SHARED_LABELS = 8
        const val FLEET_SEED = 1

        /** About three seconds at 60 frames per second. */
        const val REST_WITHIN_FRAMES = 180
        const val REST_SPEED = 1f
        const val STAY_FRAMES = 120
        const val CENTRED = 0.2
        const val FILLED = 0.2
    }
}
