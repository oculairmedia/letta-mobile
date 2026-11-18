package com.letta.mobile.ui.memory

import com.letta.mobile.data.memory.MemoryFleetInput
import com.letta.mobile.data.memory.MemoryFleetMapper
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphPoint
import com.letta.mobile.data.memory.graph.MemoryGraphTuning
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

/** A fleet graph starts from a seeded layout: agents on a ring, blocks by their agents, the same every time. */
class MemoryGraphSeedsTest {
    private val agents = (1..6).map { Agent(id = AgentId("a$it"), name = "Agent $it") }
    private val view = MemoryGraphViews.build(fleetGraph(), emptySet())
    private val tuning = MemoryGraphTuning()

    @Test
    fun theSameGraphSeedsTheSameWayEveryTime() {
        assertTrue(MemoryGraphSeeds.applies(view, tuning))
        assertEquals(MemoryGraphSeeds.initial(view, tuning), MemoryGraphSeeds.initial(view, tuning))
        assertEquals(view.nodes.map { it.id }.toSet(), MemoryGraphSeeds.initial(view, tuning).keys)
    }

    @Test
    fun agentsRingTheBackendAndBlocksStartByTheirAgents() {
        val seeds = MemoryGraphSeeds.initial(view, tuning)
        val backend = view.nodes.single { it.kind == MemoryGraphNodeKind.Backend }
        assertEquals(MemoryGraphPoint(0f, 0f), seeds[backend.id])
        val owners = MemoryGraphSeeds.ownersOf(view)
        val random = Random(SEED)
        val span = seeds.values.maxOf { maxOf(kotlin.math.abs(it.x), kotlin.math.abs(it.y)) }
        val owned = owners.keys.toList()
        val seeded = owned.map { id -> distanceToOwners(seeds.getValue(id), owners.getValue(id), seeds) }.average()
        val scattered = owned.map { id ->
            val point = MemoryGraphPoint(random.nextFloat() * 2 * span - span, random.nextFloat() * 2 * span - span)
            distanceToOwners(point, owners.getValue(id), seeds)
        }.average()
        assertTrue(seeded < scattered / 2, "seeded $seeded against scattered $scattered")
    }

    @Test
    fun oneAgentsStarDoesNotSeedThisWay() {
        val single = MemoryGraphViews.build(fleetGraph(agents.take(1)), emptySet())
        assertFalse(MemoryGraphSeeds.applies(single, tuning))
        assertFalse(MemoryGraphSeeds.applies(view, tuning.copy(seededLayout = false)))
    }

    private fun distanceToOwners(point: MemoryGraphPoint, owners: List<String>, seeds: Map<String, MemoryGraphPoint>): Double {
        val placed = owners.mapNotNull { seeds[it] }
        val cx = placed.map { it.x }.average()
        val cy = placed.map { it.y }.average()
        return hypot(point.x - cx, point.y - cy)
    }

    private fun fleetGraph(roster: List<Agent> = agents) = MemoryFleetMapper.build(
        MemoryFleetInput(
            agents = roster,
            blocks = roster.map { agent ->
                val id = agent.id.value
                agent.id to listOf(block("$id-persona", "persona")) + (1..OWN).map { block("$id-$it", "notes/$id/$it") }
            },
            backendDescriptor = backend,
            channelTransportState = ChannelTransportState.Idle,
        ),
    ).graph

    private fun block(id: String, label: String) = Block(id = BlockId(id), label = label, value = "value of $id")

    private val backend get() = BackendDescriptor(
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
        const val OWN = 8
        const val SEED = 7
    }
}
