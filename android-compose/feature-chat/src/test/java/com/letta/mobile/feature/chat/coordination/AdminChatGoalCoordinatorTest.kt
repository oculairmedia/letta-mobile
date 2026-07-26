package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.GoalStatus
import com.letta.mobile.data.model.GoalStatusResponse
import com.letta.mobile.data.repository.api.ISlashCommandRepository
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminChatGoalCoordinatorTest {
    @Test
    fun `late response after conversation switch is ignored`() = runTest {
        val response = CompletableDeferred<Result<GoalStatusResponse>>()
        val repository = mockk<ISlashCommandRepository>()
        val requestCount = AtomicInteger()
        coEvery { repository.getGoalStatus("agent-1") } coAnswers {
            if (requestCount.incrementAndGet() == 1) {
                withContext(NonCancellable) { response.await() }
            } else {
                Result.success(GoalStatusResponse(conversationId = "conversation-2"))
            }
        }
        val bridge = mockk<WsChatBridge>()
        every { bridge.events } returns MutableSharedFlow()
        val state = MutableStateFlow(
            ChatUiState(conversationState = ConversationState.Ready("conversation-1")),
        )
        val coordinator = AdminChatGoalCoordinator(
            scope = backgroundScope,
            agentId = AgentId("agent-1"),
            slashCommandRepository = repository,
            wsChatBridge = bridge,
            uiState = state,
            bannerController = mockk<ChatBannerController>(relaxed = true),
            isShimBackend = MutableStateFlow(false),
            localRuntimeRouting = { LocalRuntimeRouting.Remote },
            onGoalSlashCommandsDetected = {},
        )
        coordinator.startObserving()
        runCurrent()

        coordinator.refreshGoalStatus()
        runCurrent()
        state.value = state.value.copy(
            conversationState = ConversationState.Ready("conversation-2"),
        )
        runCurrent()

        response.complete(
            Result.success(
                GoalStatusResponse(
                    conversationId = "conversation-1",
                    goal = GoalStatus(objective = "Stale goal", status = "active"),
                ),
            ),
        )
        advanceUntilIdle()

        assertNull(state.value.goalStatus)
        assertFalse(state.value.isGoalStatusLoading)
    }
}
