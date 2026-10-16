package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
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
        controller.updateComposerText("run a long tool")
        controller.send()
        runCurrent()

        controller.stopActiveRun("conv-other")
        runCurrent()

        assertTrue(gateway.abortedConversations.isEmpty())
        assertEquals("conv-1", controller.streamingConversationId.value)

        controller.close()
    }

    private fun TestScope.testController(
        gateway: DesktopChatGateway,
        loop: DesktopTimelineLoop,
    ): DesktopChatController = DesktopChatController(
        bootstrapState = defaultDesktopBootstrapState(),
        scope = this,
        gatewayFactory = { gateway },
        loopFactory = { _, _, _ -> loop },
    )
}

/** A gateway that records server-side aborts, like the real App Server gateway. */
private class AbortingDesktopChatGateway(
    private val abortResult: Boolean = true,
) : FakeDesktopChatGateway(), DesktopTurnAborter {
    val abortedConversations = mutableListOf<String>()

    override suspend fun abortConversationTurn(conversationId: String): Boolean {
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
    val sentMessages = mutableListOf<String>()
    val sentAttachments = mutableListOf<List<MessageContentPart.Image>>()

    override suspend fun hydrate(request: DesktopTimelineHydrateRequest) = Unit

    override suspend fun send(request: DesktopTimelineSendRequest): String {
        sentMessages += request.content.value
        sentAttachments += request.attachments
        sendGate.await()
        return "client-stop-abort"
    }

    fun releaseSend() {
        sendGate.complete(Unit)
    }

    override fun close() {
        sendGate.cancel(CancellationException("closed"))
    }
}
