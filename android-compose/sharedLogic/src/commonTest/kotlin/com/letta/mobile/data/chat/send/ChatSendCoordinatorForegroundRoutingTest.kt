package com.letta.mobile.data.chat.send

import com.letta.mobile.data.transport.WsTimelineEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** Foreground UI follows the current selection; timeline ownership follows the frame. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChatSendCoordinatorForegroundRoutingTest : ChatSendCoordinatorConversationFixture() {
    @Test
    fun blankInitialSelectionAdoptsCreatedConversationBeforePublishingSend() = runTest(UnconfinedTestDispatcher()) {
        val ui = RecordingUiSink()
        val transport = FakeChannelTransport(mutableListOf())
        val coordinator = coordinator(
            timeline = RecordingTimelineWriter(),
            ui = ui,
            transport = transport,
            scope = backgroundScope,
        ) { selectedConversations.lastOrNull() ?: "" }
        coordinator.send("first message")
        assertEquals(listOf("conv-created"), selectedConversations)
        assertEquals(listOf("conv-created"), observedConversations)
        assertEquals(listOf("conv-created"), transport.sentConversationIds)
        assertEquals(1, composerClears)
        assertTrue(ui.isStreaming())
    }

    @Test
    fun backgroundUsageDoesNotReplaceForegroundCounters() = runTest(UnconfinedTestDispatcher()) {
        val ui = RecordingUiSink()
        var visible: String? = CONV_A
        val coordinator = coordinator(
            timeline = RecordingTimelineWriter(),
            ui = ui,
            transport = FakeChannelTransport(mutableListOf()),
            scope = backgroundScope,
        ) { visible }
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-a", AGENT_ID, CONV_A, "run-a"))
        visible = CONV_B
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-b", AGENT_ID, CONV_B, "run-b"))
        coordinator.handleEvent(WsTimelineEvent.UsageStatistics("turn-a", "run-a", 10, 5, 15, 0, 0))
        coordinator.handleEvent(WsTimelineEvent.UsageStatistics("turn-b", "run-b", 20, 8, 28, 0, 0))
        assertEquals(listOf(Triple(20, 8, 28)), ui.usages)
    }

    @Test
    fun backgroundCursorRepairSuccessDoesNotClearForegroundError() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val ui = RecordingUiSink(error = "B error")
        val coordinator = coordinator(
            timeline = timeline,
            ui = ui,
            transport = FakeChannelTransport(mutableListOf()),
            scope = backgroundScope,
        ) { CONV_B }
        coordinator.handleEvent(cursorError(CONV_A))
        assertEquals(listOf(CONV_A), timeline.cursorRepairs)
        assertEquals("B error", ui.currentError())
    }

    @Test
    fun backgroundCursorRepairFailureDoesNotReplaceForegroundError() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter().apply {
            cursorRepairFailure = IllegalStateException("A repair failed")
        }
        val ui = RecordingUiSink(error = "B error")
        val coordinator = coordinator(
            timeline = timeline,
            ui = ui,
            transport = FakeChannelTransport(mutableListOf()),
            scope = backgroundScope,
        ) { CONV_B }
        coordinator.handleEvent(cursorError(CONV_A))
        assertEquals(listOf(CONV_A), timeline.cursorRepairs)
        assertEquals("B error", ui.currentError())
    }

    @Test
    fun cursorRepairRechecksSelectionAfterItsSuspension() = runTest(UnconfinedTestDispatcher()) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val timeline = RecordingTimelineWriter().apply {
            cursorRepairStarted = started
            cursorRepairRelease = release
            cursorRepairFailure = IllegalStateException("A repair failed")
        }
        val ui = RecordingUiSink()
        var visible: String? = CONV_A
        val coordinator = coordinator(
            timeline = timeline,
            ui = ui,
            transport = FakeChannelTransport(mutableListOf()),
            scope = backgroundScope,
        ) { visible }
        val repair = backgroundScope.launch { coordinator.handleEvent(cursorError(CONV_A)) }
        started.await()
        visible = CONV_B
        ui.onError("B error")
        release.complete(Unit)
        repair.join()
        assertEquals("B error", ui.currentError())
    }

    @Test
    fun noActiveConversationPreservesPermissiveCursorUiPolicy() = runTest(UnconfinedTestDispatcher()) {
        val ui = RecordingUiSink(error = "old error")
        val coordinator = coordinator(
            timeline = RecordingTimelineWriter(),
            ui = ui,
            transport = FakeChannelTransport(mutableListOf()),
            scope = backgroundScope,
        ) { null }
        coordinator.handleEvent(cursorError(CONV_A))
        assertNull(ui.currentError())
    }

    @Test
    fun backgroundTurnStartedCannotSelectAnotherConversationsScreen() = runTest(UnconfinedTestDispatcher()) {
        val ui = RecordingUiSink(error = "B error")
        val coordinator = coordinator(
            timeline = RecordingTimelineWriter(),
            ui = ui,
            transport = FakeChannelTransport(mutableListOf()),
            scope = backgroundScope,
        ) {
            selectedConversations.lastOrNull() ?: CONV_B
        }
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-a", AGENT_ID, CONV_A, "run-a"))
        assertTrue(selectedConversations.isEmpty())
        assertTrue(observedConversations.isEmpty())
        assertEquals("B error", ui.currentError())
        assertFalse(ui.isStreaming())
    }

    @Test
    fun acceptedSendDoesNotMutateNewSelectionAfterTimelineSuspends() = runTest(UnconfinedTestDispatcher()) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val timeline = RecordingTimelineWriter().apply { appendStarted = started; appendRelease = release }
        val ui = RecordingUiSink()
        var visible: String? = CONV_A
        val transport = FakeChannelTransport(mutableListOf(true))
        val coordinator = coordinator(
            timeline = timeline,
            ui = ui,
            transport = transport,
            scope = backgroundScope,
        ) { visible }
        val send = coordinator.send("send A")
        started.await()
        visible = CONV_B
        ui.onError("B error")
        release.complete(Unit)
        send.join()
        assertEquals(listOf(CONV_A), transport.sentConversationIds)
        assertEquals(0, composerClears)
        assertEquals("B error", ui.currentError())
        assertFalse(ui.isStreaming())
    }

    @Test
    fun queuedSendRechecksSelectionAfterQueuePublication() = runTest(UnconfinedTestDispatcher()) {
        val ui = RecordingUiSink()
        var visible: String? = CONV_A
        val transport = FakeChannelTransport(mutableListOf(false), activeChatTurn = true)
        val coordinator = coordinator(
            timeline = RecordingTimelineWriter(),
            ui = ui,
            transport = transport,
            scope = backgroundScope,
        ) { visible }
        val switched = backgroundScope.launch {
            coordinator.sendQueue.state.first { queues -> queues.values.any { !it.isEmpty } }
            visible = CONV_B
            ui.onError("B error")
        }
        coordinator.send("queue A").join()
        switched.join()
        assertEquals(0, composerClears)
        assertEquals("B error", ui.currentError())
        assertFalse(ui.isStreaming())
    }

    @Test
    fun globalTransientDisconnectKeepsOnlyVisibleConversationsSendPresence() = runTest(UnconfinedTestDispatcher()) {
        val ui = RecordingUiSink()
        var visible: String? = CONV_A
        val coordinator = coordinator(
            timeline = RecordingTimelineWriter(),
            ui = ui,
            transport = FakeChannelTransport(mutableListOf(true)),
            scope = backgroundScope,
        ) { visible }
        coordinator.send("send A").join()
        visible = CONV_B
        ui.onTurnFinished("B error")
        coordinator.handleEvent(WsTimelineEvent.Disconnected(1006, "retrying", willReconnect = true))
        assertNull(ui.currentError(), "the transport-wide transient callback still clears the visible error")
        assertFalse(ui.isStreaming(), "A's send must not become B's presence")
    }

    @Test
    fun terminalDisconnectRemainsGlobalWhenOnlyBackgroundWorkIsTracked() = runTest(UnconfinedTestDispatcher()) {
        val ui = RecordingUiSink()
        var visible: String? = CONV_A
        val coordinator = coordinator(
            timeline = RecordingTimelineWriter(),
            ui = ui,
            transport = FakeChannelTransport(mutableListOf(true)),
            scope = backgroundScope,
        ) { visible }
        coordinator.send("send A").join()
        visible = CONV_B
        coordinator.handleEvent(WsTimelineEvent.Disconnected(1006, "transport lost"))
        assertEquals("transport lost", ui.currentError())
        assertFalse(ui.isStreaming())
    }

    @Test
    fun turnStartedStillAdoptsWhenNoConversationIsSelected() = runTest(UnconfinedTestDispatcher()) {
        val ui = RecordingUiSink()
        val coordinator = coordinator(
            timeline = RecordingTimelineWriter(),
            ui = ui,
            transport = FakeChannelTransport(mutableListOf()),
            scope = backgroundScope,
        ) { selectedConversations.lastOrNull() }
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("turn-a", AGENT_ID, CONV_A, "run-a"))
        assertEquals(listOf(CONV_A), selectedConversations)
        assertEquals(listOf(CONV_A), observedConversations)
        assertTrue(ui.isStreaming())
    }

    @Test
    fun anotherAgentsTurnStartedDoesNotSelectOrMutateThisChat() = runTest(UnconfinedTestDispatcher()) {
        val ui = RecordingUiSink(error = "B error")
        val coordinator = coordinator(
            timeline = RecordingTimelineWriter(),
            ui = ui,
            transport = FakeChannelTransport(mutableListOf()),
            scope = backgroundScope,
        ) { CONV_B }
        coordinator.handleEvent(WsTimelineEvent.TurnStarted("child-turn", "child-agent", CONV_A, "child-run"))
        assertTrue(selectedConversations.isEmpty())
        assertEquals("B error", ui.currentError())
        assertFalse(ui.isStreaming())
    }

    private fun cursorError(conversationId: String) = WsTimelineEvent.Error(
        code = "cursor_expired",
        message = "expired",
        conversationId = conversationId,
        afterSeq = 4,
        lastSeq = 8,
    )
    private companion object {
        const val AGENT_ID = "agent-1"
        const val CONV_A = "conv-a"
        const val CONV_B = "conv-b"
    }
}
