package com.letta.mobile.feature.chat
import com.letta.mobile.ui.chat.render.*

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.testutil.TestData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.feature.chat.coordination.TimelineSendCoordinator

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineSendCoordinatorTest {

    @Test
    fun `fresh timeline send creates conversation and starts observer`() = runTest {
        val harness = Harness(scope = this, isFreshRoute = true)
        coEvery { harness.conversationRepository.createConversation(AgentId("agent-1"), "hello") } returns
            TestData.conversation(id = "new-conv", agentId = "agent-1")

        harness.coordinator.send("hello")
        advanceUntilIdle()

        assertEquals("new-conv", harness.activeConversationId)
        assertEquals(ConversationState.Ready("new-conv"), harness.uiState.value.conversationState)
        assertEquals(listOf("new-conv"), harness.startedObservers)
        coVerify(exactly = 1) { harness.timelineRepository.sendWithOtid("agent-1", "new-conv", "hello", any(), any()) }
        // letta-mobile-mxwtn: optimistic insert must be paired with the
        // transport call so the user bubble appears in state BEFORE the
        // server echo arrives.
        coVerify(exactly = 1) { harness.timelineRepository.appendOptimisticLocal("agent-1", "new-conv", any(), "hello", any()) }
    }

    @Test
    fun `existing timeline send writes summary only once`() = runTest {
        val harness = Harness(scope = this, activeConversationId = "conv-1")

        harness.coordinator.send("first")
        advanceUntilIdle()
        harness.coordinator.send("second")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            harness.conversationRepository.updateConversation(ConversationId("conv-1"), AgentId("agent-1"), "first")
        }
        coVerify(exactly = 1) { harness.timelineRepository.sendWithOtid("agent-1", "conv-1", "first", any(), any()) }
        coVerify(exactly = 1) { harness.timelineRepository.sendWithOtid("agent-1", "conv-1", "second", any(), any()) }
    }

    @Test
    fun `timeline send uses attachment overload and clears composer`() = runTest {
        val harness = Harness(scope = this, activeConversationId = "conv-1")
        val image = MessageContentPart.Image(base64 = "abc", mediaType = "image/png")

        harness.coordinator.send("see image", listOf(image))
        advanceUntilIdle()

        assertEquals(1, harness.composerClearCount)
        coVerify(exactly = 1) { harness.timelineRepository.sendWithOtid("agent-1", "conv-1", "see image", any(), listOf(image)) }
        coVerify(exactly = 1) { harness.timelineRepository.appendOptimisticLocal("agent-1", "conv-1", any(), "see image", listOf(image)) }
    }

    @Test
    fun `stale existing conversation creates replacement and retries send`() = runTest {
        val harness = Harness(scope = this, activeConversationId = "stale-conv")
        coEvery { harness.conversationRepository.createConversation(AgentId("agent-1"), "hello") } returns
            TestData.conversation(id = "replacement-conv", agentId = "agent-1")
        coEvery { harness.timelineRepository.sendWithOtid("agent-1", "stale-conv", "hello", any(), any()) } throws
            ApiException(404, "Conversation not found with id='stale-conv'")

        harness.coordinator.send("hello")
        advanceUntilIdle()

        assertEquals("replacement-conv", harness.activeConversationId)
        assertEquals(ConversationState.Ready("replacement-conv"), harness.uiState.value.conversationState)
        assertEquals(listOf("stale-conv", "replacement-conv"), harness.startedObservers)
        coVerify(exactly = 1) { harness.timelineRepository.sendWithOtid("agent-1", "replacement-conv", "hello", any(), any()) }
    }

    @Test
    fun `timeline send failure clears streaming flags`() = runTest {
        val harness = Harness(scope = this, activeConversationId = "conv-1")
        coEvery { harness.timelineRepository.sendWithOtid("agent-1", "conv-1", "boom", any(), any()) } throws IllegalStateException("offline")

        harness.coordinator.send("boom")
        advanceUntilIdle()

        assertFalse(harness.uiState.value.isStreaming)
        assertFalse(harness.uiState.value.isAgentTyping)
        assertTrue(harness.uiState.value.error.orEmpty().contains("offline"))
    }

    @Test
    fun `send failure marks optimistic local failed_letmamxwtn`() = runTest {
        // letta-mobile-mxwtn: when the transport call throws, the optimistic
        // Local bubble (already in state) must be flipped to FAILED so the
        // user sees a retry affordance instead of a permanent SENDING
        // spinner. The otid that was inserted optimistically is the one to
        // fail — not a fresh one minted by the transport.
        val harness = Harness(scope = this, activeConversationId = "conv-1")
        val otidSlot = slot<String>()
        coEvery { harness.timelineRepository.appendOptimisticLocal("agent-1", "conv-1", capture(otidSlot), "boom", any()) } returns true
        coEvery { harness.timelineRepository.sendWithOtid("agent-1", "conv-1", "boom", any(), any()) } throws IllegalStateException("offline")

        harness.coordinator.send("boom")
        advanceUntilIdle()

        val insertedOtid = otidSlot.captured
        assertNotNull(insertedOtid)
        coVerify(exactly = 1) {
            harness.timelineRepository.markOptimisticLocalFailed("agent-1", "conv-1", insertedOtid)
        }
    }

    @Test
    fun `optimistic insert uses the same otid as the transport call_letmamxwtn`() = runTest {
        // letta-mobile-mxwtn: the otid threaded into the optimistic insert
        // and the transport call must match so the server's Confirmed echo
        // collapses the Local into the Confirmed via the existing
        // replaceByOtid reconcile. Different otids would leave a stranded
        // Local row in state.
        val harness = Harness(scope = this, activeConversationId = "conv-1")
        val insertOtidSlot = slot<String>()
        val sendOtidSlot = slot<String>()
        coEvery { harness.timelineRepository.appendOptimisticLocal("agent-1", "conv-1", capture(insertOtidSlot), "hello", any()) } returns true
        coEvery { harness.timelineRepository.sendWithOtid("agent-1", "conv-1", "hello", capture(sendOtidSlot), any()) } returns Unit

        harness.coordinator.send("hello")
        advanceUntilIdle()

        assertEquals(insertOtidSlot.captured, sendOtidSlot.captured)
    }

    @Test
    fun `failed optimistic append does not proceed to transport send_letmamxwtn`() = runTest {
        // Regression: appendOptimisticLocalSafely used to wrap the Local
        // insert in runCatching and continue with sendWithOtid
        // (appendLocal=false). A failed insert must abort the send — same
        // user-visible outcome as awaiting LocalSendAppend ack on the old
        // path — rather than shipping with no Local bubble.
        val harness = Harness(scope = this, activeConversationId = "conv-1")
        coEvery {
            harness.timelineRepository.appendOptimisticLocal("agent-1", "conv-1", any(), "hello", any())
        } throws IllegalStateException("timeline torn down")

        harness.coordinator.send("hello")
        advanceUntilIdle()

        coVerify(exactly = 0) {
            harness.timelineRepository.sendWithOtid(any(), any(), any(), any(), any())
        }
        assertTrue(harness.startedObservers.isEmpty())
        assertFalse(harness.uiState.value.isStreaming)
        assertFalse(harness.uiState.value.isAgentTyping)
        assertTrue(harness.uiState.value.error.orEmpty().contains("timeline torn down"))
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
            coEvery { timelineRepository.sendWithOtid(any<String>(), any<String>(), any<String>(), any<String>(), any<List<MessageContentPart.Image>>()) } returns Unit
            coEvery { timelineRepository.appendOptimisticLocal(any<String>(), any<String>(), any<String>(), any<String>(), any<List<MessageContentPart.Image>>()) } returns true
            coEvery { timelineRepository.markOptimisticLocalFailed(any<String>(), any<String>(), any<String>()) } returns Unit
            coEvery { conversationRepository.updateConversation(any<ConversationId>(), any<AgentId>(), any()) } returns Unit
            coEvery { conversationRepository.createConversation(any<AgentId>(), any<String>()) } returns
                TestData.conversation(id = "new-conv", agentId = "agent-1")
        }
    }
}
