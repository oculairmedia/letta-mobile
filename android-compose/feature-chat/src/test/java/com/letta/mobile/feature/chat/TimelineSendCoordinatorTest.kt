package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.testutil.TestData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineSendCoordinatorTest {

    @Test
    fun `fresh timeline send creates conversation and starts observer`() = runTest {
        val harness = Harness(scope = this, isFreshRoute = true)
        coEvery { harness.conversationRepository.createConversation("agent-1", "hello") } returns
            TestData.conversation(id = "new-conv", agentId = "agent-1")

        harness.coordinator.send("hello")
        advanceUntilIdle()

        assertEquals("new-conv", harness.activeConversationId)
        assertEquals(ConversationState.Ready("new-conv"), harness.uiState.value.conversationState)
        assertEquals(listOf("new-conv"), harness.startedObservers)
        coVerify(exactly = 1) { harness.timelineRepository.sendMessage("new-conv", "hello") }
    }

    @Test
    fun `existing timeline send writes summary only once`() = runTest {
        val harness = Harness(scope = this, activeConversationId = "conv-1")

        harness.coordinator.send("first")
        advanceUntilIdle()
        harness.coordinator.send("second")
        advanceUntilIdle()

        coVerify(exactly = 1) { harness.conversationRepository.updateConversation("conv-1", "agent-1", "first") }
        coVerify(exactly = 1) { harness.timelineRepository.sendMessage("conv-1", "first") }
        coVerify(exactly = 1) { harness.timelineRepository.sendMessage("conv-1", "second") }
    }

    @Test
    fun `timeline send uses attachment overload and clears composer`() = runTest {
        val harness = Harness(scope = this, activeConversationId = "conv-1")
        val image = MessageContentPart.Image(base64 = "abc", mediaType = "image/png")

        harness.coordinator.send("see image", listOf(image))
        advanceUntilIdle()

        assertEquals(1, harness.composerClearCount)
        coVerify(exactly = 1) { harness.timelineRepository.sendMessage("conv-1", "see image", listOf(image)) }
    }

    @Test
    fun `timeline send failure clears streaming flags`() = runTest {
        val harness = Harness(scope = this, activeConversationId = "conv-1")
        coEvery { harness.timelineRepository.sendMessage("conv-1", "boom") } throws IllegalStateException("offline")

        harness.coordinator.send("boom")
        advanceUntilIdle()

        assertFalse(harness.uiState.value.isStreaming)
        assertFalse(harness.uiState.value.isAgentTyping)
        assertTrue(harness.uiState.value.error.orEmpty().contains("offline"))
    }

    private class Harness(
        scope: kotlinx.coroutines.CoroutineScope,
        isFreshRoute: Boolean = false,
        activeConversationId: String? = null,
    ) {
        val conversationRepository: ConversationRepository = mockk(relaxed = true)
        val timelineRepository: TimelineRepository = mockk(relaxed = true)
        val uiState = MutableStateFlow(ChatUiState())
        val startedObservers = mutableListOf<String>()
        var composerClearCount = 0
        var activeConversationId: String? = activeConversationId

        val coordinator = TimelineSendCoordinator(
            scope = scope,
            agentId = "agent-1",
            isFreshRoute = isFreshRoute,
            explicitConversationId = null,
            conversationRepository = conversationRepository,
            timelineRepository = timelineRepository,
            uiState = uiState,
            clearComposerAfterSend = { composerClearCount++ },
            activeConversationId = { this.activeConversationId },
            setActiveConversationId = { this.activeConversationId = it },
            startTimelineObserver = { startedObservers += it },
        )

        init {
            coEvery { timelineRepository.sendMessage(any(), any()) } returns "otid"
            coEvery { timelineRepository.sendMessage(any(), any(), any()) } returns "otid"
            coEvery { conversationRepository.updateConversation(any(), any(), any()) } returns Unit
            coEvery { conversationRepository.createConversation(any(), any()) } returns
                TestData.conversation(id = "new-conv", agentId = "agent-1")
        }
    }
}
