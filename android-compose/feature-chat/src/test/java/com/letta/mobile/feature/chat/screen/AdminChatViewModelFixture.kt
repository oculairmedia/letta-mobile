package com.letta.mobile.feature.chat.screen

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.health.ShimBackendDetector
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.feature.chat.route.ChatRouteArgs
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.RuntimeId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * An [AdminChatViewModel] opened on one conversation, with every collaborator it reads on the way
 * up stubbed. The ViewModel opens its conversation from its own constructor, so a test that wants
 * to observe what happens *after* the conversation is open has to get all of this right before it
 * can assert anything — which is why it is shared rather than written out per test.
 *
 * Only [pagingHost] is the test's: it is the seam that decides which presentation the conversation
 * opens with, and how many times it is asked to open one.
 */
internal fun openedChatViewModel(
    pagingHost: ChatPagingHost,
    agent: Agent,
    conversationId: String,
    tag: String,
): AdminChatViewModel = AdminChatViewModel(
    routeArgs = ChatRouteArgs(
        SavedStateHandle(mapOf("agentId" to agent.id.value, "conversationId" to conversationId)),
    ),
    messageRepository = mockk(relaxed = true),
    timelineRepository = mockk(relaxed = true),
    externalTimelineWriter = mockk(relaxed = true),
    agentRepository = stubAgentRepository(agent),
    blockRepository = mockk(relaxed = true),
    bugReportRepository = mockk(relaxed = true),
    conversationRepository = mockk(relaxed = true),
    settingsRepository = stubSettingsRepository(),
    sessionManager = stubSessionManager(tag),
    runtimeEventOutbox = mockk(relaxed = true),
    currentConversationTracker = mockk(relaxed = true),
    shimBackendDetector = mockk<ShimBackendDetector>(relaxed = true) {
        every { activeUsesChannelTransport } returns MutableStateFlow(false)
        every { activeBackendKind } returns MutableStateFlow(BackendKind.REST)
    },
    wsChatBridge = stubChatBridge(),
    subagentRepository = mockk(relaxed = true),
    slashCommandRepository = mockk(relaxed = true) {
        coEvery { listForAgent(any()) } returns Result.success(emptyList())
        coEvery { listGlobal() } returns Result.success(emptyList())
        coEvery { getGoalStatus(any()) } returns Result.failure(IllegalStateException("Unsupported"))
    },
    clientVersionProvider = mockk(relaxed = true),
    selfTodoRepository = mockk(relaxed = true),
    modelRepository = mockk(relaxed = true) {
        every { llmModels } returns MutableStateFlow(emptyList())
    },
    pagingHost = pagingHost,
)

/** Cached, so the ViewModel resolves the agent without suspending during construction. */
private fun stubAgentRepository(agent: Agent) = mockk<IAgentRepository>(relaxed = true) {
    every { agents } returns MutableStateFlow(listOf(agent))
    every { getCachedAgent(agent.id) } returns agent
    every { getAgent(agent.id) } returns flowOf(agent)
}

private fun stubSettingsRepository() = mockk<ISettingsRepository>(relaxed = true) {
    every { activeConfig } returns MutableStateFlow(null)
    every { activeConfigChanges } returns emptyFlow()
    every { favoriteAgentId } returns MutableStateFlow(null)
    every { getChatBackgroundKey() } returns flowOf("default")
    every { getChatFontScale() } returns flowOf(1f)
    every { getHapticsEnabled() } returns flowOf(false)
    every { getPinnedAgentIds() } returns flowOf(emptySet())
}

private fun stubChatBridge() = mockk<WsChatBridge>(relaxed = true) {
    every { connection } returns emptyFlow()
    every { state } returns MutableStateFlow(mockk(relaxed = true))
    every { events } returns emptyFlow()
    every { a2uiEvents } returns emptyFlow()
}

private fun stubSessionManager(tag: String) = mockk<SessionManager>(relaxed = true) {
    every { current.localRuntimeBackend } returns null
    every { current.backendDescriptor.backendId } returns BackendId(tag)
    every { current.backendDescriptor.runtimeId } returns RuntimeId(tag)
}
