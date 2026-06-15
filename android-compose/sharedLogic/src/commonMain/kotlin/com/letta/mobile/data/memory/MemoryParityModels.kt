package com.letta.mobile.data.memory

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.runtime.BackendDescriptor

@Immutable
data class MemoryParityState(
    val selectedAgentId: String? = null,
    val selectedAgentName: String? = null,
    val sections: List<MemoryParitySection> = emptyList(),
    val summary: MemoryParitySummary = MemoryParitySummary(),
    val graph: MemoryParityGraph = MemoryParityGraph(),
) {
    val isEmpty: Boolean
        get() = sections.all { it.items.isEmpty() }

    val scopeSubtitle: String
        get() = selectedAgentName
            ?.let { "Skills, memory, schedules, and channels for $it." }
            ?: "Skills, memory, schedules, and channels for the active backend."
}

@Immutable
data class MemoryParitySummary(
    val skillCount: Int = 0,
    val memoryBlockCount: Int = 0,
    val scheduleCount: Int = 0,
    val channelCount: Int = 0,
    val totalMemoryTokens: Int = 0,
    val contextWindowUsed: Int? = null,
    val contextWindowLimit: Int? = null,
) {
    val contextUsageLabel: String = when {
        contextWindowUsed != null && contextWindowLimit != null -> "$contextWindowUsed / $contextWindowLimit"
        contextWindowUsed != null -> contextWindowUsed.toString()
        totalMemoryTokens > 0 -> "$totalMemoryTokens tokens"
        else -> "Not loaded"
    }

    val metrics: List<MemorySummaryMetric> = listOf(
        MemorySummaryMetric(MemorySummaryMetricKind.Skills, "Skills", skillCount.toString()),
        MemorySummaryMetric(MemorySummaryMetricKind.Blocks, "Blocks", memoryBlockCount.toString()),
        MemorySummaryMetric(MemorySummaryMetricKind.Schedules, "Schedules", scheduleCount.toString()),
        MemorySummaryMetric(MemorySummaryMetricKind.Channels, "Channels", channelCount.toString()),
        MemorySummaryMetric(MemorySummaryMetricKind.Context, "Context", contextUsageLabel),
    )
}

@Immutable
data class MemorySummaryMetric(
    val kind: MemorySummaryMetricKind,
    val label: String,
    val value: String,
)

enum class MemorySummaryMetricKind {
    Skills,
    Blocks,
    Schedules,
    Channels,
    Context,
}

@Immutable
data class MemoryParitySection(
    val kind: MemoryParitySectionKind,
    val title: String,
    val subtitle: String,
    val emptyMessage: String,
    val items: List<MemoryParityItem>,
)

enum class MemoryParitySectionKind {
    Skills,
    Memory,
    Schedules,
    Channels,
}

@Immutable
data class MemoryParityGraph(
    val nodes: List<MemoryGraphNode> = emptyList(),
    val edges: List<MemoryGraphEdge> = emptyList(),
) {
    val isEmpty: Boolean
        get() = nodes.isEmpty()

    val summaryLabel: String
        get() = "${nodes.size} nodes / ${edges.size} links"
}

@Immutable
data class MemoryGraphNode(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: MemoryGraphNodeKind,
    val sourceItemId: String? = null,
    val status: MemoryChannelStatus? = null,
)

@Immutable
data class MemoryGraphEdge(
    val id: String,
    val fromId: String,
    val toId: String,
    val label: String,
    val kind: MemoryGraphEdgeKind,
)

enum class MemoryGraphNodeKind {
    Agent,
    Backend,
    Skill,
    Memory,
    Schedule,
    Channel,
}

enum class MemoryGraphEdgeKind {
    Uses,
    Remembers,
    Runs,
    Delivers,
}

@Immutable
data class MemoryTextLink(
    val start: Int,
    val end: Int,
    val target: String,
    val label: String,
    val kind: MemoryTextLinkKind,
)

enum class MemoryTextLinkKind {
    Url,
    Mention,
    Agent,
    Skill,
    Memory,
    Schedule,
    Channel,
}

@Immutable
sealed interface MemoryParityItem {
    val id: String
    val title: String
    val subtitle: String
    val detailText: String
    val metadataLabels: List<String>
    val links: List<MemoryTextLink>

    @Immutable
    data class Skill(
        override val id: String,
        override val title: String,
        override val subtitle: String,
        override val detailText: String,
        override val metadataLabels: List<String>,
        override val links: List<MemoryTextLink>,
        val type: String,
        val tags: List<String>,
    ) : MemoryParityItem

    @Immutable
    data class MemoryBlock(
        override val id: String,
        override val title: String,
        override val subtitle: String,
        override val detailText: String,
        override val metadataLabels: List<String>,
        override val links: List<MemoryTextLink>,
        val preview: String,
        val limit: Int?,
        val readOnly: Boolean,
    ) : MemoryParityItem

    @Immutable
    data class Schedule(
        override val id: String,
        override val title: String,
        override val subtitle: String,
        override val detailText: String,
        override val metadataLabels: List<String>,
        override val links: List<MemoryTextLink>,
        val scheduleType: String,
        val nextRunLabel: String,
    ) : MemoryParityItem

    @Immutable
    data class Channel(
        override val id: String,
        override val title: String,
        override val subtitle: String,
        override val detailText: String,
        override val metadataLabels: List<String>,
        override val links: List<MemoryTextLink>,
        val status: MemoryChannelStatus,
    ) : MemoryParityItem
}

enum class MemoryChannelStatus {
    Connected,
    Connecting,
    Idle,
    Disconnected,
}

object MemoryParityMapper {
    fun build(
        agents: List<Agent>,
        selectedAgentId: String?,
        allTools: List<Tool>,
        schedules: List<ScheduledMessage>,
        backendDescriptor: BackendDescriptor,
        channelTransportState: ChannelTransportState,
        contextWindowOverview: ContextWindowOverview? = null,
    ): MemoryParityState {
        val selectedAgent = if (selectedAgentId != null) {
            agents.firstOrNull { it.id.value == selectedAgentId }
        } else {
            agents.firstOrNull()
        }
        val selectedTools = selectedAgent?.tools
            ?: allTools.takeIf { selectedAgentId == null && agents.isEmpty() }
            ?: emptyList()
        val selectedBlocks = selectedAgent?.blocks.orEmpty()
        val channelSection = channelsSection(backendDescriptor, channelTransportState)
        val sections = listOf(
            skillsSection(selectedTools),
            memorySection(selectedBlocks),
            schedulesSection(schedules),
            channelSection,
        )

        return MemoryParityState(
            selectedAgentId = selectedAgent?.id?.value,
            selectedAgentName = selectedAgent?.name,
            sections = sections,
            summary = MemoryParitySummary(
                skillCount = selectedTools.size,
                memoryBlockCount = selectedBlocks.size,
                scheduleCount = schedules.size,
                channelCount = 1,
                totalMemoryTokens = contextWindowOverview.totalMemoryTokens(),
                contextWindowUsed = contextWindowOverview?.contextWindowSizeCurrent,
                contextWindowLimit = selectedAgent?.contextWindowLimit,
            ),
            graph = memoryGraph(
                selectedAgent = selectedAgent,
                backendDescriptor = backendDescriptor,
                sections = sections,
            ),
        )
    }

    private fun skillsSection(tools: List<Tool>): MemoryParitySection =
        MemoryParitySection(
            kind = MemoryParitySectionKind.Skills,
            title = "Skills",
            subtitle = "Tools and callable skills attached to the active agent.",
            emptyMessage = "No skills attached.",
            items = tools.map { tool ->
                val detailText = tool.description?.takeIf { it.isNotBlank() }
                    ?: tool.sourceType?.takeIf { it.isNotBlank() }
                    ?: "Skill"
                MemoryParityItem.Skill(
                    id = tool.id.value,
                    title = tool.name,
                    subtitle = tool.description?.takeIf { it.isNotBlank() }
                        ?: tool.sourceType?.takeIf { it.isNotBlank() }
                        ?: "Skill",
                    detailText = detailText,
                    metadataLabels = listOf(
                        tool.toolType?.takeIf { it.isNotBlank() }
                            ?: tool.sourceType?.takeIf { it.isNotBlank() }
                            ?: "tool",
                    ) + tool.tags.take(MAX_METADATA_TAGS),
                    links = MemoryTextLinkParser.parse(detailText),
                    type = tool.toolType?.takeIf { it.isNotBlank() }
                        ?: tool.sourceType?.takeIf { it.isNotBlank() }
                        ?: "tool",
                    tags = tool.tags,
                )
            },
        )

    private fun memorySection(blocks: List<Block>): MemoryParitySection =
        MemoryParitySection(
            kind = MemoryParitySectionKind.Memory,
            title = "Memory",
            subtitle = "Core memory blocks available to the selected agent.",
            emptyMessage = "No memory blocks attached.",
            items = blocks.map { block ->
                val preview = block.value.lineSequence().firstOrNull { it.isNotBlank() }
                    ?.take(MAX_MEMORY_PREVIEW_CHARS)
                    ?: ""
                val subtitle = block.description?.takeIf { it.isNotBlank() }
                    ?: block.limit?.let { "Limit $it chars" }
                    ?: "Core memory"
                MemoryParityItem.MemoryBlock(
                    id = block.id.value,
                    title = block.label?.takeIf { it.isNotBlank() } ?: "Memory block",
                    subtitle = subtitle,
                    detailText = preview.ifBlank { subtitle },
                    metadataLabels = listOfNotNull(
                        block.limit?.let { "Limit $it" },
                        "Read-only".takeIf { block.readOnly == true },
                    ),
                    links = MemoryTextLinkParser.parse(preview.ifBlank { subtitle }),
                    preview = preview,
                    limit = block.limit,
                    readOnly = block.readOnly == true,
                )
            },
        )

    private fun schedulesSection(schedules: List<ScheduledMessage>): MemoryParitySection =
        MemoryParitySection(
            kind = MemoryParitySectionKind.Schedules,
            title = "Memory schedules",
            subtitle = "Scheduled messages and recurring memory maintenance.",
            emptyMessage = "No memory schedules configured.",
            items = schedules.map { schedule ->
                val message = schedule.message.messages.firstOrNull()?.content.orEmpty()
                val subtitle = when (schedule.schedule.type) {
                    "recurring" -> schedule.schedule.cronExpression?.let { "Recurring: $it" } ?: "Recurring"
                    else -> schedule.nextScheduledTime?.let { "One-time: $it" }
                        ?: schedule.schedule.scheduledAt?.let { "One-time: $it" }
                        ?: "One-time"
                }
                val nextRunLabel = schedule.nextScheduledTime
                    ?: schedule.schedule.scheduledAt?.toString()
                    ?: "Not scheduled"
                MemoryParityItem.Schedule(
                    id = schedule.id,
                    title = message.ifBlank { "Scheduled message" },
                    subtitle = subtitle,
                    detailText = subtitle,
                    metadataLabels = listOf(schedule.schedule.type, nextRunLabel),
                    links = MemoryTextLinkParser.parse(subtitle),
                    scheduleType = schedule.schedule.type,
                    nextRunLabel = nextRunLabel,
                )
            },
        )

    private fun channelsSection(
        backendDescriptor: BackendDescriptor,
        channelTransportState: ChannelTransportState,
    ): MemoryParitySection =
        channelTransportState.describe().let { subtitle ->
            MemoryParitySection(
                kind = MemoryParitySectionKind.Channels,
                title = "Channels",
                subtitle = "Live channel and backend delivery status.",
                emptyMessage = "No channels available.",
                items = listOf(
                    MemoryParityItem.Channel(
                        id = backendDescriptor.backendId.value,
                        title = backendDescriptor.label,
                        subtitle = subtitle,
                        detailText = subtitle,
                        metadataLabels = listOf(channelTransportState.toMemoryStatus().name),
                        links = MemoryTextLinkParser.parse(subtitle),
                        status = channelTransportState.toMemoryStatus(),
                    ),
                ),
            )
        }

    private fun memoryGraph(
        selectedAgent: Agent?,
        backendDescriptor: BackendDescriptor,
        sections: List<MemoryParitySection>,
    ): MemoryParityGraph {
        val nodes = mutableListOf<MemoryGraphNode>()
        val edges = mutableListOf<MemoryGraphEdge>()
        val rootNodeId = selectedAgent?.id?.value?.let { "agent:$it" }
            ?: "backend:${backendDescriptor.backendId.value}"

        if (selectedAgent != null) {
            nodes += MemoryGraphNode(
                id = rootNodeId,
                title = selectedAgent.name,
                subtitle = selectedAgent.description?.takeIf { it.isNotBlank() } ?: "Selected agent",
                kind = MemoryGraphNodeKind.Agent,
                sourceItemId = selectedAgent.id.value,
            )
        } else {
            nodes += MemoryGraphNode(
                id = rootNodeId,
                title = backendDescriptor.label,
                subtitle = "Active backend",
                kind = MemoryGraphNodeKind.Backend,
                sourceItemId = backendDescriptor.backendId.value,
            )
        }

        sections.forEach { section ->
            section.items.forEach { item ->
                val node = item.toGraphNode(section.kind)
                nodes += node
                edges += MemoryGraphEdge(
                    id = "${rootNodeId}->${node.id}",
                    fromId = rootNodeId,
                    toId = node.id,
                    label = section.kind.edgeLabel(),
                    kind = section.kind.edgeKind(),
                )
            }
        }

        return MemoryParityGraph(
            nodes = nodes.distinctBy { it.id },
            edges = edges.distinctBy { it.id },
        )
    }

    private fun MemoryParityItem.toGraphNode(sectionKind: MemoryParitySectionKind): MemoryGraphNode =
        MemoryGraphNode(
            id = "${sectionKind.name.lowercase()}:$id",
            title = title,
            subtitle = subtitle,
            kind = sectionKind.graphNodeKind(),
            sourceItemId = id,
            status = (this as? MemoryParityItem.Channel)?.status,
        )

    private fun MemoryParitySectionKind.graphNodeKind(): MemoryGraphNodeKind = when (this) {
        MemoryParitySectionKind.Skills -> MemoryGraphNodeKind.Skill
        MemoryParitySectionKind.Memory -> MemoryGraphNodeKind.Memory
        MemoryParitySectionKind.Schedules -> MemoryGraphNodeKind.Schedule
        MemoryParitySectionKind.Channels -> MemoryGraphNodeKind.Channel
    }

    private fun MemoryParitySectionKind.edgeKind(): MemoryGraphEdgeKind = when (this) {
        MemoryParitySectionKind.Skills -> MemoryGraphEdgeKind.Uses
        MemoryParitySectionKind.Memory -> MemoryGraphEdgeKind.Remembers
        MemoryParitySectionKind.Schedules -> MemoryGraphEdgeKind.Runs
        MemoryParitySectionKind.Channels -> MemoryGraphEdgeKind.Delivers
    }

    private fun MemoryParitySectionKind.edgeLabel(): String = when (this) {
        MemoryParitySectionKind.Skills -> "uses"
        MemoryParitySectionKind.Memory -> "remembers"
        MemoryParitySectionKind.Schedules -> "runs"
        MemoryParitySectionKind.Channels -> "delivers"
    }

    private fun ContextWindowOverview?.totalMemoryTokens(): Int =
        this?.let { overview ->
            overview.numTokensCoreMemory +
                overview.numTokensExternalMemorySummary +
                overview.numTokensMemoryFilesystem +
                overview.numTokensSummaryMemory
        } ?: 0

    private fun ChannelTransportState.describe(): String = when (this) {
        ChannelTransportState.Idle -> "Idle"
        ChannelTransportState.Connecting -> "Connecting"
        is ChannelTransportState.Connected -> buildString {
            append("Connected")
            canonicalLiveTransport?.takeIf { it.isNotBlank() }?.let { append(" via $it") }
        }
        is ChannelTransportState.Disconnected -> reason.ifBlank { "Disconnected" }
    }

    private fun ChannelTransportState.toMemoryStatus(): MemoryChannelStatus = when (this) {
        ChannelTransportState.Idle -> MemoryChannelStatus.Idle
        ChannelTransportState.Connecting -> MemoryChannelStatus.Connecting
        is ChannelTransportState.Connected -> MemoryChannelStatus.Connected
        is ChannelTransportState.Disconnected -> MemoryChannelStatus.Disconnected
    }

    private const val MAX_MEMORY_PREVIEW_CHARS = 160
    private const val MAX_METADATA_TAGS = 2
}

object MemoryTextLinkParser {
    fun parse(text: String): List<MemoryTextLink> =
        (urlLinks(text) + entityLinks(text) + mentionLinks(text))
            .sortedWith(compareBy<MemoryTextLink> { it.start }.thenBy { it.end })
            .dedupeOverlaps()

    private fun urlLinks(text: String): List<MemoryTextLink> =
        URL_REGEX.findAll(text).map { match ->
            val range = match.trimmedRange(text)
            val label = text.substring(range.first, range.second)
            MemoryTextLink(
                start = range.first,
                end = range.second,
                target = label,
                label = label,
                kind = MemoryTextLinkKind.Url,
            )
        }.toList()

    private fun entityLinks(text: String): List<MemoryTextLink> =
        ENTITY_REGEX.findAll(text).mapNotNull { match ->
            val range = match.trimmedRange(text)
            val label = text.substring(range.first, range.second)
            val target = match.groupValues[2].trimEndLinkPunctuation()
            val kind = when (match.groupValues[1]) {
                "agent" -> MemoryTextLinkKind.Agent
                "tool", "skill" -> MemoryTextLinkKind.Skill
                "block", "memory" -> MemoryTextLinkKind.Memory
                "schedule" -> MemoryTextLinkKind.Schedule
                "channel" -> MemoryTextLinkKind.Channel
                else -> return@mapNotNull null
            }
            MemoryTextLink(
                start = range.first,
                end = range.second,
                target = target,
                label = label,
                kind = kind,
            )
        }.toList()

    private fun mentionLinks(text: String): List<MemoryTextLink> =
        MENTION_REGEX.findAll(text).map { match ->
            val prefixLength = match.groupValues[1].length
            val start = match.range.first + prefixLength
            MemoryTextLink(
                start = start,
                end = match.range.last + 1,
                target = match.groupValues[2],
                label = text.substring(start, match.range.last + 1),
                kind = MemoryTextLinkKind.Mention,
            )
        }.toList()

    private fun List<MemoryTextLink>.dedupeOverlaps(): List<MemoryTextLink> {
        val accepted = mutableListOf<MemoryTextLink>()
        forEach { candidate ->
            if (accepted.none { it.overlaps(candidate) }) {
                accepted += candidate
            }
        }
        return accepted
    }

    private fun MemoryTextLink.overlaps(other: MemoryTextLink): Boolean =
        start < other.end && other.start < end

    private fun MatchResult.trimmedRange(text: String): Pair<Int, Int> {
        var endExclusive = range.last + 1
        while (endExclusive > range.first && text[endExclusive - 1] in TRAILING_LINK_PUNCTUATION) {
            endExclusive -= 1
        }
        return range.first to endExclusive
    }

    private fun String.trimEndLinkPunctuation(): String =
        trimEnd { it in TRAILING_LINK_PUNCTUATION }

    private val URL_REGEX = Regex("""https?://[^\s<>)"]+""")
    private val ENTITY_REGEX = Regex("""\b(agent|tool|skill|block|memory|schedule|channel):([A-Za-z0-9_.:-]+)""")
    private val MENTION_REGEX = Regex("""(^|[^\w@])@([A-Za-z][A-Za-z0-9_.-]{1,63})""")
    private val TRAILING_LINK_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?')
}
