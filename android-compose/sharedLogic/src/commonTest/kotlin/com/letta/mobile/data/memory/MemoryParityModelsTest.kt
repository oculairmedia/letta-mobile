package com.letta.mobile.data.memory

import com.letta.mobile.data.channel.ChannelDisplayStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class MemoryParityModelsTest {
    
    @Test
    fun testMemoryChannelStatusAccentRole() {
        assertEquals(MemoryAccentRole.Tertiary, MemoryChannelStatus.Connected.accentRole)
        assertEquals(MemoryAccentRole.Primary, MemoryChannelStatus.Connecting.accentRole)
        assertEquals(MemoryAccentRole.Neutral, MemoryChannelStatus.Idle.accentRole)
        assertEquals(MemoryAccentRole.Error, MemoryChannelStatus.Disconnected.accentRole)
    }

    @Test
    fun testMemoryParityItemAccentRole() {
        val skill = MemoryParityItem.Skill(
            id = "s1", title = "", subtitle = "", detailText = "", metadataLabels = emptyList(), links = emptyList(), type = "", tags = emptyList()
        )
        assertEquals(MemoryAccentRole.Primary, skill.accentRole)

        val memoryBlock = MemoryParityItem.MemoryBlock(
            id = "m1", title = "", subtitle = "", detailText = "", metadataLabels = emptyList(), links = emptyList(), preview = "", limit = null, readOnly = false
        )
        assertEquals(MemoryAccentRole.Secondary, memoryBlock.accentRole)

        val schedule = MemoryParityItem.Schedule(
            id = "sch1", title = "", subtitle = "", detailText = "", metadataLabels = emptyList(), links = emptyList(), scheduleType = "", nextRunLabel = ""
        )
        assertEquals(MemoryAccentRole.Tertiary, schedule.accentRole)

        val channel = MemoryParityItem.Channel(
            id = "c1", title = "", subtitle = "", detailText = "", metadataLabels = emptyList(), links = emptyList(), status = MemoryChannelStatus.Connected
        )
        assertEquals(MemoryAccentRole.Tertiary, channel.accentRole)
    }
    
    @Test
    fun testMemoryTextLinkParser_ValidLinks() {
        val text = "Check out http://example.com and ask @letta"
        val links = MemoryTextLinkParser.parse(text)
        assertEquals(2, links.size)
        
        val urlLink = links.find { it.kind == MemoryTextLinkKind.Url }
        assertTrue(urlLink != null)
        assertEquals("http://example.com", urlLink.target)
        
        val mentionLink = links.find { it.kind == MemoryTextLinkKind.Mention }
        assertTrue(mentionLink != null)
        assertEquals("letta", mentionLink.target)
    }

    @Test
    fun testMemoryTextLinkParser_EntityLinks() {
        val text = "Use tool:search or agent:my-agent or memory:core"
        val links = MemoryTextLinkParser.parse(text)
        assertEquals(3, links.size)
        
        val toolLink = links.find { it.kind == MemoryTextLinkKind.Skill }
        assertTrue(toolLink != null)
        assertEquals("search", toolLink.target)
        
        val agentLink = links.find { it.kind == MemoryTextLinkKind.Agent }
        assertTrue(agentLink != null)
        assertEquals("my-agent", agentLink.target)
        
        val memoryLink = links.find { it.kind == MemoryTextLinkKind.Memory }
        assertTrue(memoryLink != null)
        assertEquals("core", memoryLink.target)
    }

    @Test
    fun testMemoryTextLinkParser_OverlappingLinks() {
        val text = "http://example.com/tool:search"
        val links = MemoryTextLinkParser.parse(text)
        assertEquals(1, links.size)
        assertEquals(MemoryTextLinkKind.Url, links[0].kind)
    }

    @Test
    fun testSegmentsForText() {
        val text = "Hello @user check https://test.com"
        val segments = MemoryTextLinkParser.parse(text).segmentsForText(text)
        assertEquals(4, segments.size)
        assertEquals("Hello ", segments[0].text)
        assertEquals("@user", segments[1].text)
        assertEquals(MemoryTextLinkKind.Mention, segments[1].link?.kind)
        assertEquals(" check ", segments[2].text)
        assertEquals("https://test.com", segments[3].text)
        assertEquals(MemoryTextLinkKind.Url, segments[3].link?.kind)
    }

    @Test
    fun testMemoryParityState_isEmpty() {
        val emptyState = MemoryParityState()
        assertTrue(emptyState.isEmpty)

        val nonEmptyState = MemoryParityState(
            sections = listOf(
                MemoryParitySection(
                    kind = MemoryParitySectionKind.Skills,
                    title = "Skills",
                    subtitle = "Test",
                    emptyMessage = "None",
                    items = listOf(
                        MemoryParityItem.Skill(
                            id = "s1", title = "Skill 1", subtitle = "", detailText = "",
                            metadataLabels = emptyList(), links = emptyList(), type = "", tags = emptyList()
                        )
                    )
                )
            )
        )
        assertFalse(nonEmptyState.isEmpty)
    }

    @Test
    fun testMemoryParityState_scopeSubtitle() {
        val stateWithoutName = MemoryParityState()
        assertEquals("Skills, memory, schedules, and channels for the active backend.", stateWithoutName.scopeSubtitle)

        val stateWithName = MemoryParityState(selectedAgentName = "Test Agent")
        assertEquals("Skills, memory, schedules, and channels for Test Agent.", stateWithName.scopeSubtitle)
    }

    @Test
    fun testMemoryParitySummary_contextUsageLabel() {
        val summary1 = MemoryParitySummary(contextWindowUsed = 100, contextWindowLimit = 500, contextLoaded = true)
        assertEquals("100 / 500", summary1.contextUsageLabel)

        val summary2 = MemoryParitySummary(contextWindowUsed = 100, contextLoaded = true)
        assertEquals("100", summary2.contextUsageLabel)

        val summary3 = MemoryParitySummary(totalMemoryTokens = 200, contextLoaded = true)
        assertEquals("200 tokens", summary3.contextUsageLabel)

        val summary4 = MemoryParitySummary()
        assertEquals("—", summary4.contextUsageLabel)
    }

    @Test
    fun `unknown and measured empty remain distinct`() {
        val unknown = MemoryParityMapper.build(
            agents = emptyList(),
            selectedAgentId = null,
            allTools = emptyList(),
            schedules = emptyList(),
            backendDescriptor = testBackend(),
            channelTransportState = com.letta.mobile.data.transport.ChannelTransportState.Idle,
            availability = MemoryParityAvailability(
                skillsLoaded = false,
                memoryBlocksLoaded = false,
                schedulesLoaded = false,
                contextLoaded = false,
            ),
        )
        val measuredEmpty = MemoryParityMapper.build(
            agents = emptyList(),
            selectedAgentId = null,
            allTools = emptyList(),
            schedules = emptyList(),
            backendDescriptor = testBackend(),
            channelTransportState = com.letta.mobile.data.transport.ChannelTransportState.Idle,
        )

        assertEquals("—", unknown.summary.metrics.first { it.kind == MemorySummaryMetricKind.Skills }.value)
        assertEquals("0", measuredEmpty.summary.metrics.first { it.kind == MemorySummaryMetricKind.Skills }.value)
        assertEquals(MemoryParitySectionStatus.Unavailable, unknown.sections.first().status)
        assertEquals("Could not load skills.", unknown.sections.first().messageWhenEmpty)
        assertEquals(MemoryParitySectionStatus.Loaded, measuredEmpty.sections.first().status)
        assertEquals("No skills attached.", measuredEmpty.sections.first().messageWhenEmpty)
    }

    @Test
    fun testMemoryParitySummary_SerializationRoundTrips() {
        val summary = MemoryParitySummary(
            skillCount = 5,
            memoryBlockCount = 2,
            scheduleCount = 1,
            channelCount = 3,
            totalMemoryTokens = 1500,
            contextWindowUsed = 500,
            contextWindowLimit = 8000,
            contextLoaded = true,
        )
        
        val json = kotlinx.serialization.json.Json.encodeToString(summary)
        val decoded = kotlinx.serialization.json.Json.decodeFromString<MemoryParitySummary>(json)
        
        assertEquals(summary, decoded)
    }

    private fun testBackend() = com.letta.mobile.runtime.BackendDescriptor(
        backendId = com.letta.mobile.runtime.BackendId("backend"),
        runtimeId = com.letta.mobile.runtime.RuntimeId("runtime"),
        kind = com.letta.mobile.runtime.BackendKind.RemoteLetta,
        label = "Remote",
        capabilities = com.letta.mobile.runtime.BackendCapabilities(
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
