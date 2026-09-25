package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.memory.MemoryGraphEdge
import com.letta.mobile.data.memory.MemoryGraphEdgeKind
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityControllerState
import com.letta.mobile.data.memory.MemoryParityGraph
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.MemoryParitySection
import com.letta.mobile.data.memory.MemoryParitySectionKind
import com.letta.mobile.data.memory.MemoryParitySource
import com.letta.mobile.data.memory.MemoryParityState
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Typed builders for a small agent-rooted memory graph (agent, blocks, one skill). */
internal object MemoryGraphFixtures {
    const val AGENT_ID = "agent-1"
    const val ROOT_ID = "agent:$AGENT_ID"

    data class BlockSpec(val id: String, val label: String, val preview: String, val readOnly: Boolean = false, val limit: Int? = null)

    data class SkillSpec(val id: String, val name: String, val description: String)

    val persona = BlockSpec(id = "block-persona", label = "persona", preview = "I am a helpful agent.")
    val human = BlockSpec(id = "block-human", label = "human", preview = "Name: Emmanuel", limit = 40)
    val locked = BlockSpec(id = "block-locked", label = "policy", preview = "Do not edit.", readOnly = true)
    val search = SkillSpec(id = "tool-search", name = "web_search", description = "Search the web")

    fun blockNodeId(spec: BlockSpec) = "memory:${spec.id}"

    fun skillNodeId(spec: SkillSpec) = "skills:${spec.id}"

    fun memory(
        blocks: List<BlockSpec> = listOf(persona, human, locked),
        skills: List<SkillSpec> = listOf(search),
    ): MemoryParityState {
        val sections = listOf(
            MemoryParitySection(MemoryParitySectionKind.Skills, "Skills", "", "", skills.map(::skillItem)),
            MemoryParitySection(MemoryParitySectionKind.Memory, "Memory", "", "", blocks.map(::blockItem)),
        )
        return MemoryParityState(
            selectedAgentId = AGENT_ID,
            selectedAgentName = "Ada",
            sections = sections,
            graph = graph(blocks, skills),
        )
    }

    fun controllerState(memory: MemoryParityState = memory()) = MemoryParityControllerState(memory = memory)

    fun view(memory: MemoryParityState = memory()): MemoryGraphView = MemoryGraphViews.build(memory.graph, emptySet())

    private fun graph(blocks: List<BlockSpec>, skills: List<SkillSpec>): MemoryParityGraph {
        val root = MemoryGraphNode(ROOT_ID, "Ada", "Selected agent", MemoryGraphNodeKind.Agent, AGENT_ID)
        val blockNodes = blocks.map { MemoryGraphNode(blockNodeId(it), it.label, it.preview, MemoryGraphNodeKind.Memory, it.id) }
        val skillNodes = skills.map { MemoryGraphNode(skillNodeId(it), it.name, it.description, MemoryGraphNodeKind.Skill, it.id) }
        val edges = blockNodes.map { edge(it, MemoryGraphEdgeKind.Remembers) } + skillNodes.map { edge(it, MemoryGraphEdgeKind.Uses) }
        return MemoryParityGraph(nodes = listOf(root) + blockNodes + skillNodes, edges = edges)
    }

    private fun edge(to: MemoryGraphNode, kind: MemoryGraphEdgeKind) =
        MemoryGraphEdge(id = "$ROOT_ID->${to.id}", fromId = ROOT_ID, toId = to.id, label = kind.name.lowercase(), kind = kind)

    private fun blockItem(spec: BlockSpec) = MemoryParityItem.MemoryBlock(
        id = spec.id,
        title = spec.label,
        subtitle = "Core memory",
        detailText = spec.preview,
        metadataLabels = emptyList(),
        links = emptyList(),
        preview = spec.preview,
        limit = spec.limit,
        readOnly = spec.readOnly,
        label = spec.label,
    )

    private fun skillItem(spec: SkillSpec) = MemoryParityItem.Skill(
        id = spec.id,
        title = spec.name,
        subtitle = spec.description,
        detailText = spec.description,
        metadataLabels = listOf("tool"),
        links = emptyList(),
        type = "tool",
        tags = emptyList(),
    )
}

/** Fake overview source: tests push states and count reloads. */
internal class FakeMemoryParitySource(initial: MemoryParityControllerState) : MemoryParitySource {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<MemoryParityControllerState> = flow
    var reloads = 0
        private set
    var selectedAgent: String? = null
        private set

    fun emit(next: MemoryParityControllerState) {
        flow.value = next
    }

    override fun start() = Unit

    override fun reload() {
        reloads += 1
    }

    override fun selectAgent(agentId: String) {
        selectedAgent = agentId
    }

    override fun close() = Unit
}

/** In-memory block store standing in for the committed write path. */
internal class FakeMemoryBlockPort(
    initial: Map<String, String>,
    private val writable: Boolean = true,
) : MemoryBlockContentPort {
    val values = initial.toMutableMap()
    val saves = mutableListOf<Pair<MemoryBlockRef, String>>()
    var failSaveWith: Throwable? = null
    var failLoadWith: Throwable? = null

    override fun canWrite(): Boolean = writable

    override suspend fun load(ref: MemoryBlockRef): Block {
        failLoadWith?.let { throw it }
        val value = values[ref.label] ?: throw NoSuchElementException("missing ${ref.label}")
        return Block(id = BlockId(ref.blockId), label = ref.label, value = value)
    }

    override suspend fun save(ref: MemoryBlockRef, value: String): Block {
        failSaveWith?.let { throw it }
        saves += ref to value
        values[ref.label] = value
        return Block(id = BlockId(ref.blockId), label = ref.label, value = value)
    }
}
