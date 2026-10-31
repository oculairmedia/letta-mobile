@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.memory

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.letta.mobile.data.memory.MemoryChannelStatus
import com.letta.mobile.data.memory.MemoryGraphEdge
import com.letta.mobile.data.memory.MemoryGraphEdgeKind
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityAgentOption
import com.letta.mobile.data.memory.MemoryParityControllerState
import com.letta.mobile.data.memory.MemoryParityGraph
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.MemoryParitySection
import com.letta.mobile.data.memory.MemoryParitySectionKind
import com.letta.mobile.data.memory.MemoryParityState
import com.letta.mobile.data.memory.MemoryParitySummary
import com.letta.mobile.data.memory.graph.MemoryGraphLayoutEngine
import com.letta.mobile.data.memory.graph.MemoryGraphViews
import com.letta.mobile.data.memory.graph.MemoryNodeContent
import com.letta.mobile.data.memory.graph.MemoryNodeDetails
import com.letta.mobile.data.memory.graph.MemoryNodeSelection
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The memory page in the dark theme as the owner reviews it: a phone (360dp), either side of the
 * 720dp compact/wide breakpoint (700dp, 760dp) and a wide desktop (1200dp), each with the agent
 * selected and with a block selected, after the graph has bloomed and settled. Writes PNGs to
 * build/memory-graph-render/ for review; it asserts only that each rendered.
 */
class MemoryGraphRenderTest {
    private class Shot(val widthDp: Int, val heightDp: Int, val scale: Int)

    private val shots = listOf(Shot(360, 780, 2), Shot(700, 860, 1), Shot(760, 860, 1), Shot(1200, 780, 1))

    @Test
    fun rendersTheAgentAndABlockAtEveryWidth() {
        shots.forEach { shot ->
            render(shot, AGENT)
            render(shot, HUMAN_BLOCK)
        }
    }

    private fun render(shot: Shot, nodeId: String) =
        runDesktopComposeUiTest(width = shot.widthDp * shot.scale, height = shot.heightDp * shot.scale) {
            val state = pageState(nodeId)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(shot.scale.toFloat(), 1f)) {
                    SharedMaterialTheme(darkTheme = true) {
                        // The shell's page surface: content colour and background as the app provides them.
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            MemoryPage(state, actions())
                        }
                    }
                }
            }
            // Let the bloom run out and the simulation park.
            mainClock.advanceTimeBy(BLOOM_SETTLE_MILLIS)
            waitForIdle()
            val image = onRoot().captureToImage().toAwtImage()
            val name = if (nodeId == AGENT) "agent" else "block"
            val out = File("build/memory-graph-render").apply { mkdirs() }.resolve("memory-${shot.widthDp}dp-$name.png")
            ImageIO.write(image, "png", out)
            assertTrue(out.length() > 0)
        }

    private fun pageState(nodeId: String): MemoryPageState {
        val memory = memory()
        val view = MemoryGraphViews.build(memory.graph, emptySet())
        val detail = requireNotNull(MemoryNodeDetails.resolve(memory, nodeId))
        val content = if (detail.blockRef != null) MemoryNodeContent.Loaded(HUMAN_VALUE) else MemoryNodeContent.Static
        return MemoryPageState(
            parity = MemoryParityControllerState(
                memory = memory,
                agents = listOf(MemoryParityAgentOption("agent-1", "PM-letta-mobile")),
            ),
            view = view,
            layout = MemoryGraphLayoutEngine.layout(view),
            selection = MemoryNodeSelection(detail = detail, content = content, writable = true),
            canCreateBlock = true,
        )
    }

    private fun memory(): MemoryParityState {
        val blocks = listOf(
            block("persona", 1_840),
            block("human", HUMAN_VALUE.length),
            block("project_letta_mobile", 4_210),
        )
        val schedules = listOf(
            schedule("Emmanuel authorized check-ins on the release train every weekday", recurring = true),
            schedule("Summarise yesterday's merged PRs", recurring = false),
        )
        val channel = MemoryParityItem.Channel(
            id = "channel-1",
            title = "iroh://330415cc15c111a9f0e2b7d4c8a1e5f6",
            subtitle = "Iroh peer channel",
            detailText = "Delivering to the paired desktop",
            metadataLabels = emptyList(),
            links = emptyList(),
            status = MemoryChannelStatus.Connected,
        )
        val sections = listOf(
            section(MemoryParitySectionKind.Memory, blocks),
            section(MemoryParitySectionKind.Schedules, schedules),
            section(MemoryParitySectionKind.Channels, listOf(channel)),
        )
        return MemoryParityState(
            selectedAgentId = "agent-1",
            selectedAgentName = "PM-letta-mobile",
            sections = sections,
            summary = MemoryParitySummary(
                skillCount = 0, memoryBlockCount = 3, scheduleCount = 2, channelCount = 1,
                contextWindowUsed = 801, contextWindowLimit = 1_000_000, contextLoaded = true,
            ),
            graph = graph(sections),
        )
    }

    private fun graph(sections: List<MemoryParitySection>): MemoryParityGraph {
        val root = MemoryGraphNode(AGENT, "PM-letta-mobile", "Selected agent", MemoryGraphNodeKind.Agent, "agent-1")
        val leaves = sections.flatMap { section ->
            section.items.map { item ->
                MemoryGraphNode(
                    id = "${section.kind.name.lowercase()}:${item.id}",
                    title = item.title,
                    subtitle = item.subtitle,
                    kind = kindOf(section.kind),
                    sourceItemId = item.id,
                    status = (item as? MemoryParityItem.Channel)?.status,
                )
            }
        }
        val edges = leaves.map { MemoryGraphEdge("$AGENT->${it.id}", AGENT, it.id, "links", MemoryGraphEdgeKind.Remembers) }
        return MemoryParityGraph(listOf(root) + leaves, edges)
    }

    private fun kindOf(kind: MemoryParitySectionKind) = when (kind) {
        MemoryParitySectionKind.Skills -> MemoryGraphNodeKind.Skill
        MemoryParitySectionKind.Memory -> MemoryGraphNodeKind.Memory
        MemoryParitySectionKind.Schedules -> MemoryGraphNodeKind.Schedule
        MemoryParitySectionKind.Channels -> MemoryGraphNodeKind.Channel
    }

    private fun section(kind: MemoryParitySectionKind, items: List<MemoryParityItem>) =
        MemoryParitySection(kind = kind, title = kind.name, subtitle = "", emptyMessage = "", items = items)

    private fun block(label: String, length: Int) = MemoryParityItem.MemoryBlock(
        id = "block-$label",
        title = label,
        subtitle = "Limit 5000 chars",
        detailText = "",
        metadataLabels = emptyList(),
        links = emptyList(),
        preview = "The ${label.replace('_', ' ')} block",
        limit = 5_000,
        readOnly = false,
        label = label,
        length = length,
    )

    private fun schedule(message: String, recurring: Boolean): MemoryParityItem.Schedule {
        val subtitle = if (recurring) "Recurring: 0 9 * * 1-5" else "One-time: 2026-10-31T08:00:00Z"
        return MemoryParityItem.Schedule(
            id = "sched-${message.length}",
            title = message,
            subtitle = subtitle,
            detailText = subtitle,
            metadataLabels = emptyList(),
            links = emptyList(),
            scheduleType = if (recurring) "recurring" else "one-time",
            nextRunLabel = "2026-10-31T09:00:00Z",
        )
    }

    private fun actions(): MemoryPageActions =
        Proxy.newProxyInstance(MemoryPageActions::class.java.classLoader, arrayOf(MemoryPageActions::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "MemoryPageActions"
                else -> null
            }
        } as MemoryPageActions

    private companion object {
        const val AGENT = "agent:agent-1"
        const val HUMAN_BLOCK = "memory:block-human"
        const val BLOOM_SETTLE_MILLIS = 8_000L
        // Distinct paragraphs, so a render that drops or joins text would show it.
        val HUMAN_VALUE = (1..12).joinToString("\n\n") { n ->
            "Note $n. Emmanuel builds letta-mobile, a Kotlin Multiplatform client for Letta agents. " +
                "Prefers small, green PRs and reviews renders before merging; plans live in beads."
        }
    }
}
