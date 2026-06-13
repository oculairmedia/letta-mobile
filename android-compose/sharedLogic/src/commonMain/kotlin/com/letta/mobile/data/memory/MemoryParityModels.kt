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
) {
    val isEmpty: Boolean
        get() = sections.all { it.items.isEmpty() }
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
)

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
sealed interface MemoryParityItem {
    val id: String
    val title: String
    val subtitle: String

    @Immutable
    data class Skill(
        override val id: String,
        override val title: String,
        override val subtitle: String,
        val type: String,
        val tags: List<String>,
    ) : MemoryParityItem

    @Immutable
    data class MemoryBlock(
        override val id: String,
        override val title: String,
        override val subtitle: String,
        val preview: String,
        val limit: Int?,
        val readOnly: Boolean,
    ) : MemoryParityItem

    @Immutable
    data class Schedule(
        override val id: String,
        override val title: String,
        override val subtitle: String,
        val scheduleType: String,
        val nextRunLabel: String,
    ) : MemoryParityItem

    @Immutable
    data class Channel(
        override val id: String,
        override val title: String,
        override val subtitle: String,
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
        val selectedAgent = agents.firstOrNull { it.id.value == selectedAgentId }
            ?: agents.firstOrNull()
        val selectedTools = selectedAgent?.tools?.takeIf { it.isNotEmpty() } ?: allTools
        val selectedBlocks = selectedAgent?.blocks.orEmpty()

        return MemoryParityState(
            selectedAgentId = selectedAgent?.id?.value,
            selectedAgentName = selectedAgent?.name,
            sections = listOf(
                skillsSection(selectedTools),
                memorySection(selectedBlocks),
                schedulesSection(schedules),
                channelsSection(backendDescriptor, channelTransportState),
            ),
            summary = MemoryParitySummary(
                skillCount = selectedTools.size,
                memoryBlockCount = selectedBlocks.size,
                scheduleCount = schedules.size,
                channelCount = 1,
                totalMemoryTokens = contextWindowOverview.totalMemoryTokens(),
                contextWindowUsed = contextWindowOverview?.contextWindowSizeCurrent,
                contextWindowLimit = selectedAgent?.contextWindowLimit,
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
                MemoryParityItem.Skill(
                    id = tool.id.value,
                    title = tool.name,
                    subtitle = tool.description?.takeIf { it.isNotBlank() }
                        ?: tool.sourceType?.takeIf { it.isNotBlank() }
                        ?: "Skill",
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
                MemoryParityItem.MemoryBlock(
                    id = block.id.value,
                    title = block.label?.takeIf { it.isNotBlank() } ?: "Memory block",
                    subtitle = block.description?.takeIf { it.isNotBlank() }
                        ?: block.limit?.let { "Limit $it chars" }
                        ?: "Core memory",
                    preview = block.value.lineSequence().firstOrNull { it.isNotBlank() }
                        ?.take(MAX_MEMORY_PREVIEW_CHARS)
                        ?: "",
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
                MemoryParityItem.Schedule(
                    id = schedule.id,
                    title = message.ifBlank { "Scheduled message" },
                    subtitle = when (schedule.schedule.type) {
                        "recurring" -> schedule.schedule.cronExpression?.let { "Recurring: $it" } ?: "Recurring"
                        else -> schedule.nextScheduledTime?.let { "One-time: $it" }
                            ?: schedule.schedule.scheduledAt?.let { "One-time: $it" }
                            ?: "One-time"
                    },
                    scheduleType = schedule.schedule.type,
                    nextRunLabel = schedule.nextScheduledTime
                        ?: schedule.schedule.scheduledAt?.toString()
                        ?: "Not scheduled",
                )
            },
        )

    private fun channelsSection(
        backendDescriptor: BackendDescriptor,
        channelTransportState: ChannelTransportState,
    ): MemoryParitySection =
        MemoryParitySection(
            kind = MemoryParitySectionKind.Channels,
            title = "Channels",
            subtitle = "Live channel and backend delivery status.",
            emptyMessage = "No channels available.",
            items = listOf(
                MemoryParityItem.Channel(
                    id = backendDescriptor.backendId.value,
                    title = backendDescriptor.label,
                    subtitle = channelTransportState.describe(),
                    status = channelTransportState.toMemoryStatus(),
                ),
            ),
        )

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
}
