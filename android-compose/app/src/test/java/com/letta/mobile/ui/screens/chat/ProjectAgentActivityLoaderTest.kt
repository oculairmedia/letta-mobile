package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.bot.protocol.BotAgentInfo
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.FolderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class ProjectAgentActivityLoaderTest {
    private val internalBotClient: InternalBotClient = mockk(relaxed = true)
    private val agentRepository: AgentRepository = mockk(relaxed = true)
    private val folderRepository: FolderRepository = mockk(relaxed = true)
    private val loader = ProjectAgentActivityLoader(
        internalBotClient = internalBotClient,
        agentRepository = agentRepository,
        folderRepository = folderRepository,
    )

    @Test
    fun `missing folder id returns live status for current agent only`() = runTest {
        coEvery { internalBotClient.listAgents() } returns listOf(
            BotAgentInfo(id = "agent-1", name = "Coder", status = "running"),
            BotAgentInfo(id = "agent-2", name = "Other", status = "ready"),
        )

        val result = loader.load(ProjectChatContext(identifier = "project", name = "Project"), "agent-1")

        assertEquals(1, result.size)
        assertEquals("agent-1", result.single().id)
        assertEquals("Coder", result.single().name)
        assertEquals("Working", result.single().statusLabel)
        assertEquals(ProjectAgentStatusTone.Busy, result.single().statusTone)
        coVerify(exactly = 0) { folderRepository.listAgentsForFolder(any()) }
    }

    @Test
    fun `missing folder id returns empty when current agent is not live`() = runTest {
        coEvery { internalBotClient.listAgents() } returns listOf(
            BotAgentInfo(id = "agent-2", name = "Other", status = "ready"),
        )

        val result = loader.load(ProjectChatContext(identifier = "project", name = "Project"), "agent-1")

        assertEquals(emptyList<ProjectAgentActivity>(), result)
    }

    @Test
    fun `folder id loads folder agents and merges live statuses`() = runTest {
        coEvery { internalBotClient.listAgents() } returns listOf(
            BotAgentInfo(id = "agent-2", name = "Live Two", status = "error"),
        )
        coEvery { folderRepository.listAgentsForFolder("folder-1") } returns listOf("agent-1", "agent-2")
        coEvery { agentRepository.refreshAgentsIfStale(60_000) } returns true
        every { agentRepository.getCachedAgent("agent-1") } returns Agent(
            id = AgentId("agent-1"),
            name = "Cached One",
            model = "gpt-4.1",
            updatedAt = "2026-05-01T00:00:00Z",
        )
        every { agentRepository.getCachedAgent("agent-2") } returns null
        every { agentRepository.getAgent("agent-2") } returns flowOf(
            Agent(
                id = AgentId("agent-2"),
                name = "Fetched Two",
                model = "claude",
                lastRunCompletion = "Last completion",
            )
        )

        val result = loader.load(
            ProjectChatContext(identifier = "project", name = "Project", lettaFolderId = "folder-1"),
            "agent-ignored",
        )

        assertEquals(listOf("agent-1", "agent-2"), result.map { it.id })
        assertEquals("Disconnected", result[0].statusLabel)
        assertEquals("Error", result[1].statusLabel)
        assertEquals("gpt-4.1", result[0].model)
        assertEquals("claude", result[1].model)
        assertEquals("Last completion", result[1].detail)
    }
}
