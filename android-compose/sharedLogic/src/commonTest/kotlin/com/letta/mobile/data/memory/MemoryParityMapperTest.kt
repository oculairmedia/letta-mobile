package com.letta.mobile.data.memory

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.RuntimeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class MemoryParityMapperTest {
    @Test
    fun buildCreatesSkillsMemorySchedulesAndChannelsSections() {
        val state = MemoryParityMapper.build(
            agents = listOf(sampleAgent()),
            selectedAgentId = "agent-1",
            allTools = listOf(sampleTool("tool-fallback")),
            schedules = listOf(sampleSchedule()),
            backendDescriptor = sampleBackend(),
            channelTransportState = ChannelTransportState.Connected(
                serverId = "server",
                sessionId = "session",
                deviceId = "device",
                canonicalLiveTransport = "websocket",
            ),
            contextWindowOverview = ContextWindowOverview(
                contextWindowSizeCurrent = 512,
                numTokensCoreMemory = 100,
                numTokensExternalMemorySummary = 25,
                numTokensMemoryFilesystem = 50,
                numTokensSummaryMemory = 10,
            ),
        )

        assertEquals("agent-1", state.selectedAgentId)
        assertEquals("Ada", state.selectedAgentName)
        assertEquals("Skills, memory, schedules, and channels for Ada.", state.scopeSubtitle)
        assertFalse(state.isEmpty)
        assertEquals(
            listOf(
                MemoryParitySectionKind.Skills,
                MemoryParitySectionKind.Memory,
                MemoryParitySectionKind.Schedules,
                MemoryParitySectionKind.Channels,
            ),
            state.sections.map { it.kind },
        )
        assertEquals(1, state.summary.skillCount)
        assertEquals(1, state.summary.memoryBlockCount)
        assertEquals(1, state.summary.scheduleCount)
        assertEquals(1, state.summary.channelCount)
        assertEquals(185, state.summary.totalMemoryTokens)
        assertEquals(512, state.summary.contextWindowUsed)
        assertEquals(
            listOf("Skills" to "1", "Blocks" to "1", "Schedules" to "1", "Channels" to "1", "Context" to "512 / 8000"),
            state.summary.metrics.map { it.label to it.value },
        )
        assertEquals("5 nodes / 4 links", state.graph.summaryLabel)
        assertEquals(
            listOf(
                "agent:agent-1",
                "skills:tool-1",
                "memory:block-1",
                "schedules:schedule-1",
                "channels:backend",
            ),
            state.graph.nodes.map { it.id },
        )
        assertEquals(
            listOf("uses", "remembers", "runs", "delivers"),
            state.graph.edges.map { it.label },
        )

        val skill = assertIs<MemoryParityItem.Skill>(state.section(MemoryParitySectionKind.Skills).items.single())
        assertEquals("search_docs", skill.title)
        assertEquals(listOf("research"), skill.tags)
        assertEquals(listOf("python", "research"), skill.metadataLabels)
        assertEquals(MemoryAccentRole.Primary, skill.accentRole)
        assertEquals(MemoryAccentRole.Primary, state.section(MemoryParitySectionKind.Skills).kind.accentRole)

        val memory = assertIs<MemoryParityItem.MemoryBlock>(state.section(MemoryParitySectionKind.Memory).items.single())
        assertEquals("persona", memory.title)
        assertEquals("Keeps a concise research voice.", memory.preview)
        assertEquals("Keeps a concise research voice.", memory.detailText)
        assertEquals(listOf("Limit 2000"), memory.metadataLabels)
        assertEquals(emptyList(), memory.links)
        assertEquals(MemoryAccentRole.Secondary, memory.accentRole)
        assertEquals(MemoryAccentRole.Secondary, state.section(MemoryParitySectionKind.Memory).kind.accentRole)

        val schedule = assertIs<MemoryParityItem.Schedule>(state.section(MemoryParitySectionKind.Schedules).items.single())
        assertEquals("Summarize the latest project memory", schedule.title)
        assertEquals("recurring", schedule.scheduleType)
        assertEquals(listOf("recurring", "2026-06-14T08:00:00Z"), schedule.metadataLabels)
        assertEquals(MemoryAccentRole.Tertiary, schedule.accentRole)
        assertEquals(MemoryAccentRole.Tertiary, state.section(MemoryParitySectionKind.Schedules).kind.accentRole)

        val channel = assertIs<MemoryParityItem.Channel>(state.section(MemoryParitySectionKind.Channels).items.single())
        assertEquals(MemoryChannelStatus.Connected, channel.status)
        assertEquals("Connected via websocket", channel.subtitle)
        assertEquals(listOf("Connected", "websocket", "Device device"), channel.metadataLabels)
        assertEquals(MemoryAccentRole.Tertiary, channel.accentRole)
        assertEquals(MemoryAccentRole.Tertiary, MemoryGraphNodeKind.Channel.accentRole(channel.status))
        assertEquals(MemoryAccentRole.Neutral, state.section(MemoryParitySectionKind.Channels).kind.accentRole)

        state.sections.flatMap { it.items }.forEach { item ->
            item.links.forEach { link ->
                assertEquals(true, link.start >= 0)
                assertEquals(true, link.end <= item.detailText.length)
            }
        }
    }

    @Test
    fun buildKeepsSelectedAgentSkillsScopedWhenAgentHasNoAttachedTools() {
        val state = MemoryParityMapper.build(
            agents = listOf(Agent(id = AgentId("agent-1"), name = "No tools")),
            selectedAgentId = "agent-1",
            allTools = listOf(sampleTool("fallback")),
            schedules = emptyList(),
            backendDescriptor = sampleBackend(),
            channelTransportState = ChannelTransportState.Idle,
        )

        assertEquals(0, state.summary.skillCount)
        assertEquals(emptyList(), state.section(MemoryParitySectionKind.Skills).items)
        assertEquals(MemoryChannelStatus.Idle, (state.section(MemoryParitySectionKind.Channels).items.single() as MemoryParityItem.Channel).status)
    }

    @Test
    fun buildUsesAllToolsOnlyWhenNoAgentIsSelected() {
        val state = MemoryParityMapper.build(
            agents = emptyList(),
            selectedAgentId = null,
            allTools = listOf(sampleTool("fallback")),
            schedules = emptyList(),
            backendDescriptor = sampleBackend(),
            channelTransportState = ChannelTransportState.Idle,
        )

        val skill = assertIs<MemoryParityItem.Skill>(state.section(MemoryParitySectionKind.Skills).items.single())
        assertEquals("fallback", skill.id)
    }

    @Test
    fun buildDoesNotFallbackToAnotherAgentWhenRequestedAgentIsMissing() {
        val state = MemoryParityMapper.build(
            agents = listOf(sampleAgent()),
            selectedAgentId = "missing-agent",
            allTools = listOf(sampleTool("fallback")),
            schedules = emptyList(),
            backendDescriptor = sampleBackend(),
            channelTransportState = ChannelTransportState.Idle,
        )

        assertEquals(null, state.selectedAgentId)
        assertEquals(null, state.selectedAgentName)
        assertEquals(0, state.summary.skillCount)
        assertEquals(0, state.summary.memoryBlockCount)
        assertEquals(emptyList(), state.section(MemoryParitySectionKind.Skills).items)
        assertEquals(emptyList(), state.section(MemoryParitySectionKind.Memory).items)
    }

    @Test
    fun parsesMemoryTextLinksInCommonCode() {
        val links = MemoryTextLinkParser.parse(
            "Ask @Ada to refresh tool:search_docs from https://docs.example/test and schedule:daily.",
        )

        assertEquals(
            listOf(
                MemoryTextLinkKind.Mention,
                MemoryTextLinkKind.Skill,
                MemoryTextLinkKind.Url,
                MemoryTextLinkKind.Schedule,
            ),
            links.map { it.kind },
        )
        assertEquals("@Ada", links[0].label)
        assertEquals("search_docs", links[1].target)
        assertEquals("https://docs.example/test", links[2].target)
        assertEquals("daily", links[3].target)
    }

    @Test
    fun validatesAndSegmentsMemoryTextLinksInCommonCode() {
        val text = "Open https://example.com and ask @Ada"
        val urlStart = text.indexOf("https://example.com")
        val mentionStart = text.indexOf("@Ada")
        val url = MemoryTextLink(
            start = urlStart,
            end = urlStart + "https://example.com".length,
            target = "https://example.com",
            label = "https://example.com",
            kind = MemoryTextLinkKind.Url,
        )
        val mention = MemoryTextLink(
            start = mentionStart,
            end = mentionStart + "@Ada".length,
            target = "Ada",
            label = "@Ada",
            kind = MemoryTextLinkKind.Mention,
        )
        val partialOverlap = url.copy(end = url.start + 8)
        val outOfBounds = mention.copy(end = text.length + 20)

        val links = listOf(outOfBounds, partialOverlap, mention, url)

        assertEquals(listOf(url, mention), links.validForText(text))
        assertEquals(
            listOf("Open ", "https://example.com", " and ask ", "@Ada"),
            links.segmentsForText(text).map { it.text },
        )
        assertEquals(
            listOf(null, MemoryTextLinkKind.Url, null, MemoryTextLinkKind.Mention),
            links.segmentsForText(text).map { it.link?.kind },
        )
    }

    @Test
    fun buildWithNoMemoryDataIsEmptyButStillReportsChannelStatus() {
        val state = MemoryParityMapper.build(
            agents = emptyList(),
            selectedAgentId = null,
            allTools = emptyList(),
            schedules = emptyList(),
            backendDescriptor = sampleBackend(),
            channelTransportState = ChannelTransportState.Disconnected(code = 1006, reason = "Network unavailable"),
        )

        assertEquals(null, state.selectedAgentId)
        assertEquals("Skills, memory, schedules, and channels for the active backend.", state.scopeSubtitle)
        assertEquals(0, state.summary.skillCount)
        assertEquals(0, state.summary.memoryBlockCount)
        assertEquals(0, state.summary.scheduleCount)
        assertEquals(0, state.summary.totalMemoryTokens)
        assertEquals(null, state.summary.contextWindowUsed)
        // The memory-data sections are empty when there is nothing to show.
        assertEquals(true, state.section(MemoryParitySectionKind.Skills).items.isEmpty())
        assertEquals(true, state.section(MemoryParitySectionKind.Memory).items.isEmpty())
        assertEquals(true, state.section(MemoryParitySectionKind.Schedules).items.isEmpty())
        // The channels section always renders one descriptor row, so the overall
        // state is NOT isEmpty - it still reports live channel status.
        assertFalse(state.isEmpty)
        val channel = assertIs<MemoryParityItem.Channel>(
            state.section(MemoryParitySectionKind.Channels).items.single(),
        )
        assertEquals(MemoryChannelStatus.Disconnected, channel.status)
        assertEquals(MemoryAccentRole.Error, channel.accentRole)
        assertEquals("Network unavailable", channel.subtitle)
    }

    @Test
    fun channelAccentRolesRepresentTransportStateInCommonCode() {
        assertEquals(MemoryAccentRole.Tertiary, MemoryChannelStatus.Connected.accentRole)
        assertEquals(MemoryAccentRole.Primary, MemoryChannelStatus.Connecting.accentRole)
        assertEquals(MemoryAccentRole.Neutral, MemoryChannelStatus.Idle.accentRole)
        assertEquals(MemoryAccentRole.Error, MemoryChannelStatus.Disconnected.accentRole)
        assertEquals(MemoryAccentRole.Neutral, MemoryGraphNodeKind.Channel.accentRole(null))
    }

    @Test
    fun buildLabelsOneTimeSchedulesWithTheirNextRunTime() {
        val oneTime = ScheduledMessage(
            id = "schedule-once",
            agentId = "agent-1",
            message = SchedulePayload(
                messages = listOf(ScheduleMessage(content = "Ping me later", role = "user")),
            ),
            schedule = ScheduleDefinition(type = "one_time"),
            nextScheduledTime = "2026-06-20T12:00:00Z",
        )

        val state = MemoryParityMapper.build(
            agents = listOf(sampleAgent()),
            selectedAgentId = "agent-1",
            allTools = emptyList(),
            schedules = listOf(oneTime),
            backendDescriptor = sampleBackend(),
            channelTransportState = ChannelTransportState.Idle,
        )

        val schedule = assertIs<MemoryParityItem.Schedule>(
            state.section(MemoryParitySectionKind.Schedules).items.single(),
        )
        assertEquals("Ping me later", schedule.title)
        assertEquals("one_time", schedule.scheduleType)
        assertEquals("One-time: 2026-06-20T12:00:00Z", schedule.subtitle)
        assertEquals("2026-06-20T12:00:00Z", schedule.nextRunLabel)
    }

    private fun MemoryParityState.section(kind: MemoryParitySectionKind): MemoryParitySection =
        sections.first { it.kind == kind }

    private fun sampleAgent() = Agent(
        id = AgentId("agent-1"),
        name = "Ada",
        blocks = listOf(
            Block(
                id = BlockId("block-1"),
                label = "persona",
                value = "Keeps a concise research voice.\nUse tool:search_docs when grounded research is needed.",
                limit = 2_000,
            ),
        ),
        tools = listOf(sampleTool("tool-1")),
        contextWindowLimit = 8_000,
    )

    private fun sampleTool(id: String) = Tool(
        id = ToolId(id),
        name = if (id == "fallback") "fallback" else "search_docs",
        description = "Search indexed docs",
        sourceType = "python",
        tags = listOf("research"),
    )

    private fun sampleSchedule() = ScheduledMessage(
        id = "schedule-1",
        agentId = "agent-1",
        message = SchedulePayload(
            messages = listOf(ScheduleMessage(content = "Summarize the latest project memory", role = "user")),
        ),
        schedule = ScheduleDefinition(type = "recurring", cronExpression = "0 8 * * *"),
        nextScheduledTime = "2026-06-14T08:00:00Z",
    )

    private fun sampleBackend() = BackendDescriptor(
        backendId = BackendId("backend"),
        runtimeId = RuntimeId("runtime"),
        kind = BackendKind.RemoteLetta,
        label = "https://api.letta.com",
        capabilities = BackendCapabilities(
            supportsStreaming = true,
            supportsMemFs = true,
            supportsTools = true,
            supportsApprovals = true,
            supportsAgentFileImport = true,
            supportsAgentFileExport = true,
        ),
    )
}
