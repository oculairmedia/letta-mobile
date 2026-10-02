package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineSyncEvent
import com.letta.mobile.data.timeline.timelineNow
import com.letta.mobile.ui.chat.render.ConversationState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-o4ygk.4.5: the commonMain timeline port over a fake loop. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineChatSessionPortTest {
    private val target = TimelineChatTarget(agentId = "agent-1", agentName = "Nora", conversationId = "conv-1")

    @Test
    fun loadsTheConversationAndNamesItsAgent() = runTest {
        val session = FakeTimelineChatSession()
        val port = port(session)
        runCurrent()

        val state = port.uiState.value
        assertEquals(1, session.hydrations)
        assertEquals(ConversationState.Ready("conv-1"), state.conversationState)
        assertFalse(state.isLoadingMessages)
        assertEquals("Nora", state.agentName)
        assertEquals("agent-1", state.agentId)
    }

    @Test
    fun aFailedLoadShowsTheErrorAndRetryLoadsAgain() = runTest {
        val session = FakeTimelineChatSession(hydrateFailure = IllegalStateException("offline"))
        val port = port(session)
        runCurrent()
        assertEquals(ConversationState.Error("offline"), port.uiState.value.conversationState)

        session.hydrateFailure = null
        port.actions.retryLoad()
        runCurrent()

        assertEquals(ConversationState.Ready("conv-1"), port.uiState.value.conversationState)
        assertEquals(2, session.hydrations)
    }

    @Test
    fun sendHandsTheDraftToTheLoopAndEmptiesTheComposer() = runTest {
        val session = FakeTimelineChatSession()
        val port = port(session)
        runCurrent()

        port.actions.updateComposerText("  hello  ")
        port.actions.attachImage(image())
        runCurrent()
        assertTrue(port.composer.value.canSend)

        port.actions.send()
        runCurrent()

        assertEquals(listOf("hello" to 1), session.sends.map { (text, images) -> text to images.size })
        assertEquals("", port.composer.value.text)
        assertTrue(port.composer.value.attachments.isEmpty())
    }

    @Test
    fun aPromptStillSendingIsARunInFlightAndBlocksTheNextSend() = runTest {
        val session = FakeTimelineChatSession()
        val port = port(session)
        runCurrent()

        session.timeline.value = timelineWith(pendingPrompt("first"))
        port.actions.updateComposerText("second")
        runCurrent()

        assertTrue(port.uiState.value.isRunInFlight)
        assertFalse(port.composer.value.canSend)
        port.actions.send()
        runCurrent()
        assertTrue(session.sends.isEmpty())
        assertEquals("second", port.composer.value.text)
    }

    @Test
    fun aFailedSendIsShownOnceAndKeepsTheRunFailed() = runTest {
        val session = FakeTimelineChatSession(sendFailure = IllegalStateException("socket closed"))
        val port = port(session)
        runCurrent()

        port.actions.sendText("hi")
        runCurrent()
        assertEquals("socket closed", port.uiState.value.error)
        assertTrue(port.uiState.value.runFailed)

        port.actions.clearError()
        runCurrent()
        assertNull(port.uiState.value.error)
        assertTrue(port.uiState.value.runFailed)
    }

    @Test
    fun aStreamErrorFromTheLoopReachesThePage() = runTest {
        val session = FakeTimelineChatSession()
        val port = port(session)
        runCurrent()

        session.events.emit(TimelineSyncEvent.StreamError("stream", "lost the stream"))
        runCurrent()

        assertEquals("lost the stream", port.uiState.value.error)
    }

    @Test
    fun stopAsksTheTransportOnlyWhileARunIsInFlight() = runTest {
        val session = FakeTimelineChatSession()
        var stops = 0
        val port = port(session, TimelineChatRunControls(stopRun = { stops++; true }))
        runCurrent()

        port.actions.stopRun()
        runCurrent()
        assertEquals(0, stops)

        session.timeline.value = timelineWith(pendingPrompt("long task"))
        runCurrent()
        port.actions.stopRun()
        runCurrent()

        assertEquals(1, stops)
        assertTrue(port.uiState.value.isCancellingRun)
    }

    @Test
    fun anApprovalIsAnsweredOnceWhileItsAnswerIsInFlight() = runTest {
        val session = FakeTimelineChatSession()
        val answers = mutableListOf<ChatApprovalAnswer>()
        val release = CompletableDeferred<Unit>()
        val port = port(session, TimelineChatRunControls(answerApproval = { answers += it; release.await() }))
        runCurrent()

        port.actions.submitApproval(ChatApprovalAnswer("req-1", listOf("call-1"), approve = true, reason = null))
        port.actions.submitApproval(ChatApprovalAnswer("req-1", listOf("call-1"), approve = true, reason = null))
        runCurrent()
        assertEquals(1, answers.size)
        assertEquals(ChatApprovalAnswer("req-1", listOf("call-1"), approve = true, reason = null), answers.single())

        release.complete(Unit)
        runCurrent()
        port.actions.submitApproval(ChatApprovalAnswer("req-1", listOf("call-1"), approve = false, reason = "no"))
        runCurrent()
        assertEquals(2, answers.size)
    }

    @Test
    fun capabilitiesFollowTheControlsAndHideWhatTheWebCannotDo() = runTest {
        val bare = port(FakeTimelineChatSession()).capabilities.value
        assertFalse(bare.approvals)
        assertTrue(bare.attachImages)
        assertFalse(bare.modelSwitch)
        assertFalse(bare.workingDirectory)
        assertFalse(bare.pagedHistory)
        assertFalse(bare.rerun)
        assertFalse(bare.goals)

        val withApprovals = port(FakeTimelineChatSession(), TimelineChatRunControls(answerApproval = {})).capabilities.value
        assertTrue(withApprovals.approvals)
    }

    @Test
    fun anOverLimitAttachmentIsRejectedWithAComposerError() = runTest {
        val port = port(FakeTimelineChatSession())
        runCurrent()

        repeat(port.composer.value.maxAttachments + 1) { port.actions.attachImage(image()) }
        runCurrent()

        assertEquals(port.composer.value.maxAttachments, port.composer.value.attachments.size)
        assertIs<String>(port.composer.value.error)
        port.actions.clearComposerError()
        runCurrent()
        assertNull(port.composer.value.error)
    }

    private fun TestScope.port(
        session: FakeTimelineChatSession,
        controls: TimelineChatRunControls = TimelineChatRunControls(),
    ): TimelineChatSessionPort {
        val port = TimelineChatSessionPort(session, target, backgroundScope, TimelineChatSessionOptions(controls = controls))
        // The page's flows share while collected, as ChatSurface collects them.
        backgroundScope.launch { port.uiState.collect {} }
        backgroundScope.launch { port.composer.collect {} }
        return port
    }

    private fun image() = MessageContentPart.Image(base64 = "AAAA", mediaType = "image/png")

    private fun pendingPrompt(text: String) = TimelineEvent.Local(
        position = 1.0,
        otid = "otid-$text",
        content = text,
        sentAt = timelineNow(),
        deliveryState = DeliveryState.SENDING,
    )

    private fun timelineWith(vararg events: TimelineEvent) =
        Timeline(conversationId = "conv-1", events = persistentListOf(*events))
}

private class FakeTimelineChatSession(
    var hydrateFailure: Exception? = null,
    private val sendFailure: Exception? = null,
) : TimelineChatSession {
    override val timeline = MutableStateFlow(Timeline(conversationId = "conv-1"))
    override val events = MutableSharedFlow<TimelineSyncEvent>()
    var hydrations = 0
    val sends = mutableListOf<Pair<String, List<MessageContentPart.Image>>>()

    override suspend fun hydrate() {
        hydrations++
        hydrateFailure?.let { throw it }
    }

    override suspend fun send(text: String, attachments: List<MessageContentPart.Image>) {
        sendFailure?.let { throw it }
        sends += text to attachments
    }
}
