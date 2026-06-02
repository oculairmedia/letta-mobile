package com.letta.mobile.feature.chat.session

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.feature.chat.coordination.ChatConversationCoordinator
import com.letta.mobile.feature.chat.coordination.ChatRunExpansionState
import com.letta.mobile.feature.chat.coordination.ChatSessionResolver
import com.letta.mobile.feature.chat.render.ChatUiState
import com.letta.mobile.feature.chat.render.ProjectChatContext
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
        assertFalse(harness.observedLastSelection)
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

        assertEquals(listOf(false), harness.resolveRequests)
        assertEquals(0, harness.refreshClientModeLocationCount)
        assertEquals(1, harness.loadProjectAgentsCount)
        assertEquals(1, harness.loadProjectBriefCount)
        assertEquals(1, harness.loadRecentBugReportsCount)
    }

    @Test
    fun `fresh non-explicit route resumes recent conversation when feature flag is enabled`() = runTest {
        val harness = Harness(
            scope = this,
            isFreshRoute = true,
            explicitNewChat = false,
            resumeRecentEnabled = true,
        )
        coEvery {
            harness.sessionResolver.resolveMostRecentConversation("agent-1", 123L)
        } returns "conv-recent"

        harness.initializer.run()
        advanceUntilIdle()

        assertEquals(null, harness.currentConversationTracker.current)
        coVerify(exactly = 1) {
            harness.sessionResolver.resolveMostRecentConversation("agent-1", 123L)
        }
        verify(exactly = 1) { harness.conversationCoordinator.setActiveConversationId("conv-recent") }
    }

    private class Harness(
        scope: kotlinx.coroutines.CoroutineScope,
        val agentId: String = "agent-1",
        val isFreshRoute: Boolean = false,
        val explicitNewChat: Boolean = false,
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
        var clientModeConversationId: String? = "conv-existing"
        var refreshedAgentsCount = 0
        var observedLastSelection = false
        var seededAgentName = false
        var observedAgentName = false
        var refreshClientModeLocationCount = 0
        var loadProjectAgentsCount = 0
        var loadProjectBriefCount = 0
        var loadRecentBugReportsCount = 0
        val resolveRequests = mutableListOf<Boolean>()

        val initializer = ChatSessionInitializer(
            scope = scope,
            agentId = agentId,
            isFreshRoute = isFreshRoute,
            explicitNewChat = explicitNewChat,
            resumeCacheMaxAgeMs = 123L,
            projectContext = projectContext,
            settingsRepository = settingsRepository,
            sessionResolver = sessionResolver,
            conversationCoordinator = conversationCoordinator,
            runExpansionState = runExpansionState,
            currentConversationTracker = currentConversationTracker,
            bannerController = bannerController,
            setClientModeConversationId = { clientModeConversationId = it },
            refreshAvailableAgents = { refreshedAgentsCount++ },
            observeLastChatSelection = { observedLastSelection = true },
            seedAgentNameFromMemoryCache = { seededAgentName = true },
            observeAgentNameCache = { observedAgentName = true },
            refreshClientModeLocation = { refreshClientModeLocationCount++ },
            loadProjectAgents = { loadProjectAgentsCount++ },
            loadProjectBrief = { loadProjectBriefCount++ },
            loadRecentBugReports = { loadRecentBugReportsCount++ },
            resolveConversationAndLoad = { resolveRequests += it },
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
