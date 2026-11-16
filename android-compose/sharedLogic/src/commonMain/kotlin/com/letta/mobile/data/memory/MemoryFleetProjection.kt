package com.letta.mobile.data.memory

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.visibleInLists
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.util.AgentFleetReads

/** What the fleet memory view is built from: the roster and each agent's blocks, in any order. */
@Immutable
data class MemoryFleetInput(
    /** The roster's visible agents. */
    val agents: List<Agent>,
    /** Each agent's core-memory blocks, for the agents whose blocks were read. */
    val blocks: List<Pair<AgentId, List<Block>>>,
    /** The backend the graph is rooted at. */
    val backendDescriptor: BackendDescriptor,
    /** The live channel the Channels section reports. */
    val channelTransportState: ChannelTransportState,
    /** False when no agent's blocks could be read. */
    val blocksLoaded: Boolean = true,
)

/**
 * The Memory page with no agent focused: every agent's core-memory blocks grouped by label (agents
 * hold their own block under a shared label such as "persona", and only a backend that shares one
 * block id across agents proves two of them are the same block), each group naming the agents that
 * hold it. The graph is rooted at the backend, with the agents as its children and each label group
 * linked to the agents holding it.
 */
object MemoryFleetMapper {
    /** The fleet memory overview: label groups, the backend-rooted graph and the channel. */
    fun build(input: MemoryFleetInput): MemoryParityState = buildOrdered(input.stablyOrdered())

    private fun buildOrdered(input: MemoryFleetInput): MemoryParityState {
        val names = input.agents.associate { it.id.value to it.name }
        val groups = groups(input.blocks)
        val memory = MemoryParitySection(
            kind = MemoryParitySectionKind.Memory,
            title = "Memory",
            subtitle = "Core memory blocks across every agent, grouped by label.",
            emptyMessage = "No memory blocks on any agent.",
            status = if (input.blocksLoaded) MemoryParitySectionStatus.Loaded else MemoryParitySectionStatus.Unavailable,
            items = groups.map { it.toItem(names) },
        )
        val channels = MemoryParityMapper.channelsSection(input.backendDescriptor, input.channelTransportState)
        return MemoryParityState(
            sections = listOf(memory, channels),
            summary = MemoryParitySummary(
                memoryBlockCount = input.blocks.sumOf { it.second.size }.takeIf { input.blocksLoaded },
                channelCount = 1,
            ),
            graph = graph(input, memory, channels),
            fleet = true,
        )
    }

    /**
     * Agents by name (then id), and their blocks in that order, so the graph's nodes and edges come
     * out the same however the host orders its roster or its replies: an unchanged fleet keeps its
     * topology, and so its layout, across reloads.
     */
    private fun MemoryFleetInput.stablyOrdered(): MemoryFleetInput {
        val ordered = agents.distinctBy { it.id }.sortedWith(compareBy({ it.name.lowercase() }, { it.id.value }))
        val rank = ordered.withIndex().associate { (index, agent) -> agent.id to index }
        return copy(agents = ordered, blocks = blocks.sortedBy { rank[it.first] ?: Int.MAX_VALUE })
    }

    /** Label groups sorted by label; a block with no label is a group of its own. */
    private fun groups(blocks: List<Pair<AgentId, List<Block>>>): List<LabelGroup> {
        val byKey = LinkedHashMap<String, LabelGroup>()
        blocks.forEach { (agent, agentBlocks) ->
            agentBlocks.forEach { block ->
                val label = block.label?.takeIf { it.isNotBlank() }
                val key = label?.let { "label:$it" } ?: "id:${block.id.value}"
                byKey.getOrPut(key) { LabelGroup(key, label) }.entries += agent to block
            }
        }
        return byKey.values.sortedWith(compareBy<LabelGroup>({ it.label == null }, { it.key.lowercase() }, { it.key }))
    }

    private fun graph(input: MemoryFleetInput, memory: MemoryParitySection, channels: MemoryParitySection): MemoryParityGraph {
        val root = "backend:${input.backendDescriptor.backendId.value}"
        val backendId = input.backendDescriptor.backendId.value
        val backend = MemoryGraphNode(root, input.backendDescriptor.label, "Active backend", MemoryGraphNodeKind.Backend, backendId)
        val nodes = mutableListOf(backend)
        val edges = mutableListOf<MemoryGraphEdge>()
        input.agents.forEach { agent ->
            val id = agentNodeId(agent.id)
            val subtitle = agent.description?.takeIf { it.isNotBlank() } ?: "Agent"
            nodes += MemoryGraphNode(id, agent.name, subtitle, MemoryGraphNodeKind.Agent, agent.id.value)
            edges += MemoryGraphEdge("$root->$id", root, id, "runs", MemoryGraphEdgeKind.Runs)
        }
        memory.items.filterIsInstance<MemoryParityItem.MemoryBlock>().forEach { item ->
            val id = "memory:${item.id}"
            nodes += MemoryGraphNode(id, item.title, item.subtitle, MemoryGraphNodeKind.Memory, item.id)
            item.owners.forEach { owner ->
                val from = agentNodeId(AgentId(owner.id))
                edges += MemoryGraphEdge("$from->$id", from, id, "remembers", MemoryGraphEdgeKind.Remembers)
            }
        }
        channels.items.filterIsInstance<MemoryParityItem.Channel>().forEach { item ->
            val id = "channels:${item.id}"
            nodes += MemoryGraphNode(id, item.title, item.subtitle, MemoryGraphNodeKind.Channel, item.id, item.status)
            edges += MemoryGraphEdge("$root->$id", root, id, "delivers", MemoryGraphEdgeKind.Delivers)
        }
        val ids = nodes.mapTo(HashSet()) { it.id }
        return MemoryParityGraph(
            nodes = nodes.distinctBy { it.id },
            edges = edges.filter { it.fromId in ids && it.toId in ids }.distinctBy { it.id },
        )
    }

    private fun agentNodeId(agent: AgentId) = "agent:${agent.value}"

    private class LabelGroup(val key: String, val label: String?) {
        val entries = mutableListOf<Pair<AgentId, Block>>()

        fun toItem(names: Map<String, String>): MemoryParityItem.MemoryBlock {
            val owners = entries.map { it.first.value }.distinct().map { MemoryParityAgentOption(it, names[it] ?: it) }
            val shared = entries.map { it.second }.distinctBy { it.id }.singleOrNull()
            val preview = shared?.let { MemoryParityMapper.previewOf(it.value) }.orEmpty()
            val subtitle = usedBy(owners)
            return MemoryParityItem.MemoryBlock(
                id = "fleet:$key",
                title = label ?: "Memory block",
                subtitle = subtitle,
                detailText = preview.ifBlank { subtitle },
                metadataLabels = listOfNotNull(
                    if (owners.size == 1) "1 agent" else "${owners.size} agents",
                    shared?.limit?.let { "Limit $it" },
                    "Read-only".takeIf { entries.any { it.second.readOnly == true } },
                ),
                links = MemoryTextLinkParser.parse(preview.ifBlank { subtitle }),
                preview = preview,
                limit = shared?.limit,
                readOnly = entries.any { it.second.readOnly == true },
                label = label,
                length = shared?.value?.length ?: 0,
                owners = owners,
                sharedBlockId = shared?.id?.value,
            )
        }
    }

    private fun usedBy(owners: List<MemoryParityAgentOption>): String {
        val shown = owners.take(NAMED_OWNERS).joinToString(", ") { it.name }
        val more = owners.size - NAMED_OWNERS
        return if (more > 0) "Used by $shown +$more" else "Used by $shown"
    }

    private const val NAMED_OWNERS = 3
}

/**
 * Every visible roster agent's core-memory blocks, read per agent a few at a time: one agent's
 * failure leaves the others' blocks, and only every agent failing makes the section unavailable.
 * Without a block repository the roster's embedded blocks stand in.
 */
internal suspend fun MemoryParitySectionReader.readFleetBlocks(
    graph: SessionRepositoryGraph,
    roster: MemoryParitySectionRead<List<Agent>>,
): MemoryParitySectionRead<List<Pair<AgentId, List<Block>>>> {
    val agents = roster.value?.visibleInLists()
        ?: return MemoryParitySectionRead.Unavailable(IllegalStateException("roster dependency unavailable"))
    val repository = graph.blockRepository ?: return MemoryParitySectionRead.Loaded(agents.map { it.id to it.coreBlocks })
    return read("blocks", SessionRoute.BLOCKS, graph.capabilities) {
        val fleet = AgentFleetReads.read(agents.map { it.id }) { agent -> repository.getBlocks(agent.value) }
        fleet.firstFailure?.takeIf { fleet.allFailed }?.let { throw it }
        fleet.values
    }
}
