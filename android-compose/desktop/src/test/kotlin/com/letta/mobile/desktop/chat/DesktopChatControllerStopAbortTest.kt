package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.chat.runtime.NowActiveStatus
import com.letta.mobile.data.presence.ActiveTurnIdentity
import com.letta.mobile.data.presence.ActiveTurnPhase
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolName
import com.letta.mobile.ui.shell.nowactive.toNowActiveStatus
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-lgns8.19 — desktop stop must abort the SERVER turn.
 *
 * Before this bead `stopActiveRun` only cancelled the local send job, so a long
 * tool call ran to completion and its output later surfaced as a ghost resume.
 * These tests assert the abort is dispatched to the gateway and that the UI
 * resolves on the stream's terminal, not on job cancellation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatControllerStopAbortTest {

    @Test
    fun stopActiveRunDispatchesServerAbortAndHoldsCancellingUntilTerminal() = runTest {
        val gateway = AbortingDesktopChatGateway()
        val loop = SuspendingSendLoop("conv-1")
        val controller = testController(gateway, loop)

        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        controller.updateComposerText("run a long tool")
        controller.send()
        runCurrent()
        assertEquals("conv-1", controller.streamingConversationId.value)

        controller.stopActiveRun("conv-1")
        runCurrent()

        // (a) the abort actually reached the gateway
        assertEquals(listOf("conv-1"), gateway.abortedConversations)
        // (b) the UI is still busy — the server turn has not confirmed yet
        assertEquals("conv-1", controller.cancellingConversationId.value)
        assertEquals("conv-1", controller.streamingConversationId.value)
        assertTrue(controller.replyPresence.value.isStreaming)

        // The terminal frame (stream end) is what settles the turn.
        loop.releaseSend()
        runCurrent()

        assertNull(controller.cancellingConversationId.value)
        assertNull(controller.streamingConversationId.value)
        assertFalse(controller.replyPresence.value.isStreaming)

        controller.close()
    }

    @Test
    fun sendIsRejectedWhileTheAbortIsUnconfirmed() = runTest {
        val gateway = AbortingDesktopChatGateway()
        val loop = SuspendingSendLoop("conv-1")
        val controller = testController(gateway, loop)

        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        controller.updateComposerText("run a long tool")
        controller.send()
        runCurrent()
        controller.stopActiveRun("conv-1")
        runCurrent()

        controller.updateComposerText("sneaky interleaved message")
        controller.send()
        runCurrent()

        assertEquals(1, loop.sentMessages.size)
        assertEquals(STOPPING_SEND_BLOCKED_MESSAGE, controller.state.value.errorMessage)

        controller.close()
    }

    @Test
    fun secondStopPressForceClearsLocallyWithoutASecondAbort() = runTest {
        val gateway = AbortingDesktopChatGateway()
        val loop = SuspendingSendLoop("conv-1")
        val controller = testController(gateway, loop)

        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        controller.updateComposerText("run a long tool")
        controller.send()
        runCurrent()
        controller.stopActiveRun("conv-1")
        runCurrent()
        controller.stopActiveRun("conv-1")
        runCurrent()

        assertEquals(listOf("conv-1"), gateway.abortedConversations)
        assertNull(controller.cancellingConversationId.value)
        assertNull(controller.streamingConversationId.value)

        controller.close()
    }

    @Test
    fun aGatewayThatCannotAbortFallsBackToTheLocalClear() = runTest {
        // FakeDesktopChatGateway is not a DesktopTurnAborter.
        val loop = SuspendingSendLoop("conv-1")
        val controller = testController(FakeDesktopChatGateway(), loop)

        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        controller.updateComposerText("run a long tool")
        controller.send()
        runCurrent()
        assertNotNull(controller.streamingConversationId.value)

        controller.stopActiveRun("conv-1")
        runCurrent()

        assertNull(controller.cancellingConversationId.value)
        assertNull(controller.streamingConversationId.value)

        controller.close()
    }

    @Test
    fun stopForAnotherConversationNeverAborts() = runTest {
        val gateway = AbortingDesktopChatGateway()
        val loop = SuspendingSendLoop("conv-1")
        val controller = testController(gateway, loop)

        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        controller.updateComposerText("run a long tool")
        controller.send()
        runCurrent()

        controller.stopActiveRun("conv-other")
        runCurrent()

        assertTrue(gateway.abortedConversations.isEmpty())
        assertEquals("conv-1", controller.streamingConversationId.value)

        controller.close()
    }

    @Test
    fun reconnectAndPortRecreationRetainUnconfirmedActiveTurn() = runTest {
        val gateway = AbortingDesktopChatGateway()
        val loop = SuspendingSendLoop("conv-1")
        val controller = testController(gateway, loop)
        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        controller.updateComposerText("run a long tool")
        controller.send()
        runCurrent()
        assertTrue(DesktopChatSessionPort(controller, backgroundScope).uiState.value.isStreaming)

        controller.retryConnection()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()

        // Losing the local collector is not a terminal from the server. Recreating the
        // page (as on resize) must retain the turn until exact reconciliation closes it.
        val recreated = DesktopChatSessionPort(controller, backgroundScope)
        assertTrue(recreated.uiState.value.isStreaming, "Reconnect must retain unconfirmed turn presence")
        assertTrue(gateway.abortedConversations.isEmpty(), "Restoration must not replay abort")
        controller.close()
    }

    @Test
    fun stopDeadlineClearsRetainedTurnAfterReconnectWithoutReplayingAbort() = runTest {
        val gateway = AbortingDesktopChatGateway()
        val loop = SuspendingSendLoop("conv-1")
        val controller = testController(gateway, loop)
        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        controller.updateComposerText("long tool")
        controller.send()
        runCurrent()
        controller.stopActiveRun("conv-1")
        runCurrent()
        controller.retryConnection()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        assertTrue(DesktopChatSessionPort(controller, backgroundScope).uiState.value.isCancelling)
        advanceTimeBy(30_000)
        runCurrent()
        val recreated = DesktopChatSessionPort(controller, backgroundScope)
        assertFalse(recreated.uiState.value.isStreaming)
        assertFalse(recreated.uiState.value.isCancelling)
        controller.stopActiveRun("conv-1")
        runCurrent()
        assertEquals(listOf("conv-1"), gateway.abortedConversations)
        controller.close()
    }

    @Test
    fun toolAndStopAreSharedAcrossRecreatedPortsAndLateOldTerminal() = runTest {
        val gateway = AbortingDesktopChatGateway()
        val controller = testController(gateway, SuspendingSendLoop("conv-1"))
        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        gateway.emit("run-old", RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Started))
        gateway.emit("run-new", RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Started))
        gateway.emit("run-new", RuntimeEventPayload.ToolCallObserved(ToolCallId("call-1"), ToolName("Bash")))
        gateway.emit("run-old", RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Completed))
        runCurrent()
        val turn = controller.activeTurnStates.value.getValue("conv-1")
        assertEquals("run-new", turn.runId)
        assertEquals("Bash", turn.activeTool)
        assertEquals(ActiveTurnPhase.ToolExecuting, turn.phase)
        assertEquals(NowActiveStatus.Thinking, turn.toNowActiveStatus())
        val resized = DesktopChatSessionPort(controller, backgroundScope)
        assertTrue(resized.uiState.value.isStreaming)
        resized.actions.stopRun()
        runCurrent()
        assertEquals(NowActiveStatus.Stopping, controller.activeTurnStates.value.getValue("conv-1").toNowActiveStatus())
        assertTrue(DesktopChatSessionPort(controller, backgroundScope).uiState.value.isCancelling)
        DesktopChatSessionPort(controller, backgroundScope).actions.stopRun()
        runCurrent()
        assertEquals(listOf("conv-1"), gateway.abortedConversations)
        assertEquals(NowActiveStatus.Idle, controller.activeTurnStates.value.getValue("conv-1").toNowActiveStatus())
        assertFalse(resized.uiState.value.isStreaming)
        controller.close()
    }

    @Test
    fun reconnectReconcilesTheServerRunWithoutResettingAnUnconfirmedStop() = runTest {
        val gateway = AbortingDesktopChatGateway()
        val controller = testController(gateway, SuspendingSendLoop("conv-1"))
        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        gateway.emit("local-command", RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Started), "server-run")
        runCurrent()
        controller.stopActiveRun("conv-1")
        runCurrent()
        controller.retryConnection()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        // The authoritative active-run query after reconnect names the server run,
        // while the original send observer captured the local command identity.
        gateway.emit("server-run", RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Started))
        runCurrent()
        assertEquals("conv-1", controller.cancellingConversationId.value)
        assertTrue(DesktopChatSessionPort(controller, backgroundScope).uiState.value.isCancelling)
        assertEquals(listOf("conv-1"), gateway.abortedConversations)
        gateway.emit("server-run", RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Cancelled))
        runCurrent()
        assertNull(controller.cancellingConversationId.value)
        assertFalse(DesktopChatSessionPort(controller, backgroundScope).uiState.value.isStreaming)
        controller.close()
    }

    @Test
    fun selectedCancellationIsProjectedEvenWhenAnotherConversationIsAlsoStopping() = runTest {
        val gateway = FakeDesktopChatGateway(conversationIds = listOf("conv-1", "conv-2"))
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), backgroundScope, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        listOf("conv-1", "conv-2").forEach { id ->
            val owner = assertNotNull(controller.activeTurnCoordinator(id))
            val turn = ActiveTurnIdentity("run-$id", "turn-$id")
            owner.start(turn)
            owner.interrupt(turn, "stop-$id") { kotlinx.coroutines.awaitCancellation() }
        }
        controller.selectConversation("conv-2")
        runCurrent()
        val port = DesktopChatSessionPort(controller, backgroundScope)
        assertTrue(port.uiState.value.isCancelling)
        port.actions.sendText("must wait for this conversation's stop")
        assertEquals(STOPPING_SEND_BLOCKED_MESSAGE, controller.state.value.errorMessage)
        controller.close()
    }

    private fun TestScope.testController(
        gateway: DesktopChatGateway,
        loop: DesktopTimelineLoop,
    ): DesktopChatController {
        if (gateway is AbortingDesktopChatGateway && loop is SuspendingSendLoop) {
            loop.onSend = { gateway.activeTurn = true }
            loop.onTerminal = { gateway.activeTurn = false }
        }
        return DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            rosterProvider = TEST_ROSTER,
            gatewayFactory = { gateway },
            loopFactory = { _, _, _ -> loop },
        )
    }
}

/** A gateway that records server-side aborts, like the real App Server gateway. */
private class AbortingDesktopChatGateway(
    private val abortResult: Boolean = true,
) : FakeDesktopChatGateway(), DesktopTurnAborter, DesktopRuntimeEventSource {
    private val relay = DesktopRuntimeEventRelay()
    override val runtimeEvents = relay.runtimeEvents
    var activeTurn = false
    suspend fun emit(runId: String, payload: RuntimeEventPayload, serverRunId: String? = null) {
        if (payload is RuntimeEventPayload.RunLifecycleChanged && payload.status == RuntimeRunStatus.Started) activeTurn = true
        relay.emit(ScopedDesktopRuntimeEvent("conv-1", "agent-0", payload, runId, serverRunId))
    }
    val abortedConversations = mutableListOf<String>()

    override suspend fun abortConversationTurn(conversationId: String): Boolean {
        if (conversationId != "conv-1" || !activeTurn) return false
        abortedConversations += conversationId
        return abortResult
    }
}

/**
 * A loop whose `send` (which spans the whole reply stream) stays suspended until
 * [releaseSend] — releasing it stands in for the server's terminal frame.
 */
private class SuspendingSendLoop(conversationId: String) : DesktopTimelineLoop {
    override val state = MutableStateFlow(Timeline(conversationId))
    private val sendGate = CompletableDeferred<Unit>()
    var onSend: () -> Unit = {}
    var onTerminal: () -> Unit = {}
    val sentMessages = mutableListOf<String>()
    val sentAttachments = mutableListOf<List<MessageContentPart.Image>>()

    override suspend fun hydrate(request: DesktopTimelineHydrateRequest) = Unit

    override suspend fun send(request: DesktopTimelineSendRequest): String {
        onSend()
        sentMessages += request.content.value
        sentAttachments += request.attachments
        sendGate.await()
        return "client-stop-abort"
    }

    fun releaseSend() {
        onTerminal()
        sendGate.complete(Unit)
    }

    override fun close() {
        sendGate.cancel(CancellationException("closed"))
    }
}
