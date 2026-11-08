package com.letta.mobile.feature.chat.session
import com.letta.mobile.feature.chat.render.*
import com.letta.mobile.ui.chat.render.*

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.feature.chat.coordination.ChatConversationCoordinator
import com.letta.mobile.feature.chat.coordination.ChatRunExpansionState
import com.letta.mobile.feature.chat.coordination.ChatSessionResolver
import com.letta.mobile.feature.chat.coordination.ChatComposerController
import com.letta.mobile.feature.chat.state.ChatBannerController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionInitializerTest {
    @Test
    fun `blank agent marks ui error and skips ready-session bootstrap`() = runTest {
        val harness = Harness(scope = this, agentId = "")

        harness.initializer.run()
        advanceUntilIdle()

        assertEquals("No agent selected", harness.uiState.value.error)
        assertFalse(harness.seededAgentName)
        assertFalse(harness.observedAgentName)
        verify(exactly = 0) { harness.runExpansionState.hydrateUiState() }
    }

    @Test
    fun `project context bootstraps project data and resolves conversation`() = runTest {
        val projectContext = ProjectChatContext(
            identifier = "project-1",
            name = "Project 1",
            filesystemPath = "/workspace/project-1",
        )
        val harness = Harness(
            scope = this,
            projectContext = projectContext,
        )

        harness.initializer.run()
        advanceUntilIdle()

        assertEquals(1, harness.resolveRequests)
        assertEquals(1, harness.loadProjectAgentsCount)
        assertEquals(1, harness.loadProjectBriefCount)
        assertEquals(1, harness.loadRecentBugReportsCount)
    }

    @Test
    fun `initializer cannot clear visibility or independently resume a conversation`() = runTest {
        val harness = Harness(
            scope = this,
            resumeRecentEnabled = true,
        )
        coEvery {
            harness.sessionResolver.resolveMostRecentConversation("agent-1", 123L)
        } returns "conv-recent"

        harness.currentConversationTracker.setCurrent("owner-visible")
        harness.initializer.run()
        advanceUntilIdle()

        assertEquals("owner-visible", harness.currentConversationTracker.current)
        assertEquals(1, harness.resolveRequests)
        coVerify(exactly = 0) {
            harness.sessionResolver.resolveMostRecentConversation("agent-1", 123L)
        }
        verify(exactly = 0) { harness.conversationCoordinator.setActiveConversationId(any()) }
    }

    private class Harness(
        scope: kotlinx.coroutines.CoroutineScope,
        val agentId: String = "agent-1",
        val projectContext: ProjectChatContext? = null,
        resumeRecentEnabled: Boolean? = null,
    ) {
        val settingsRepository: SettingsRepository = mockk(relaxed = true)
        val sessionResolver: ChatSessionResolver = mockk(relaxed = true)
        val conversationCoordinator: ChatConversationCoordinator = mockk(relaxed = true)
        val uiState = MutableStateFlow(ChatUiState())
        val runExpansionState = mockk<ChatRunExpansionState>(relaxed = true)
        val bannerController = ChatBannerController(uiState, ChatComposerController())
        val currentConversationTracker = CurrentConversationTracker()
        var refreshedAgentsCount = 0
        var seededAgentName = false
        var observedAgentName = false
        var loadProjectAgentsCount = 0
        var loadProjectBriefCount = 0
        var loadRecentBugReportsCount = 0
        var resolveRequests = 0

        val initializer = ChatSessionInitializer(
            scope = scope,
            agentId = agentId,
            settingsRepository = settingsRepository,
            bannerController = bannerController,
            runExpansionState = runExpansionState,
            refreshAvailableAgents = { refreshedAgentsCount++ },
            prepareChat = {
                seededAgentName = true
                observedAgentName = true
            },
            loadProject = {
                if (projectContext != null) {
                    loadProjectAgentsCount++
                    loadProjectBriefCount++
                    loadRecentBugReportsCount++
                }
            },
            openConversation = { conversationCoordinator.recordOpenRequested(); resolveRequests++ },
        )

        init {
            every { settingsRepository.activeConfigChanges } returns emptyFlow()
            every { settingsRepository.observeResumeRecentConversation() } returns when (resumeRecentEnabled) {
                null -> emptyFlow()
                else -> flowOf(resumeRecentEnabled)
            }
            coEvery { sessionResolver.resolveMostRecentConversation(any(), any()) } returns null
            every { runExpansionState.hydrateUiState() } returns Unit
        }
    }
}
