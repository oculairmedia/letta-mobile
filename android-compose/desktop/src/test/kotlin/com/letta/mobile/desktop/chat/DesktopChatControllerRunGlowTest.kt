package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.parseTimelineInstant
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1: the full page's glow went out mid-run. It read the controller's
 * thinkingConversationId, which clears the moment the agent's first message lands, while the run
 * goes on through its Bash rounds with the Stop button up. The page state the Stop button and the
 * docked panel's glow read keeps the run in flight until the turn's terminal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatControllerRunGlowTest {
    @Test
    fun theRunStaysInFlightThroughToolRoundsAfterTheFirstReplyLands() = runTest {
        val loop = HeldSendLoop("conv-1")
        val controller = DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            gatewayFactory = { FakeDesktopChatGateway() },
            agentNamesByIdProvider = { emptyMap() },
            timelinePersistence = noOpDesktopTimelinePersistence,
            loopFactory = { _, _, _ -> loop },
        )
        controller.start()
        runCurrent()
        controller.updateComposerText("run the checks")
        controller.send()
        runCurrent()

        // Mid-run: the prompt, a line of narration, then Bash rounds, each under its own run id.
        loop.state.value = Timeline(
            conversationId = "conv-1",
            events = persistentListOf(
                MidRun.prompt(),
                MidRun.narration(Round(runId = "local-run-100", at = 2)),
                MidRun.bash(Round(runId = "local-run-100", at = 3)),
                MidRun.bash(Round(runId = "local-run-101", at = 4)),
            ),
        )
        runCurrent()

        // The old glow input is gone, though the turn is still running...
        assertNull(controller.thinkingConversationId.value)
        // ...and the page state (Stop button, docked glow, now the page glow) still says so.
        val page = pageStateOf(controller)
        assertTrue(page.isStreaming, "the Stop button is up")
        assertTrue(page.isRunInFlight, "the page glow runs while the turn does")

        loop.releaseSend()
        runCurrent()
        assertFalse(pageStateOf(controller).isRunInFlight, "the turn's terminal ends the run")

        controller.close()
    }

    private fun pageStateOf(controller: DesktopChatController) = desktopChatUiState(
        DesktopChatTimelineInputs(
            surface = controller.state.value,
            presence = controller.replyPresence.value,
            cancellingConversationId = null,
            sendQueue = ConversationSendQueue(),
            local = DesktopChatLocalTimelineState(),
        ),
        previous = null,
    )

    /** Where one message of a round sits: its run and its second within the minute. */
    private data class Round(val runId: String, val at: Int)

    /** The selected conversation's confirmed events of a coding agent's run. */
    private object MidRun {
        fun prompt() = event(TimelineMessageType.USER, "run the checks", Round(runId = "", at = 1))

        fun narration(round: Round) = event(TimelineMessageType.ASSISTANT, "Checking the build.", round)

        fun bash(round: Round) = event(TimelineMessageType.TOOL_CALL, "", round).copy(
            toolCalls = persistentListOf(ToolCall(id = "call-${round.at}", name = "Bash", arguments = """{"command":"ls"}""")),
        )

        private fun event(type: TimelineMessageType, content: String, round: Round) = TimelineEvent.Confirmed(
            position = round.at.toDouble(),
            otid = "server-${round.at}",
            content = content,
            serverId = "msg-${round.at}",
            messageType = type,
            date = parseTimelineInstant("2026-09-25T11:05:0${round.at}Z"),
            runId = round.runId.ifBlank { null },
            stepId = null,
        )
    }

    /** A loop whose send holds the turn open until [releaseSend], as a running turn does. */
    private class HeldSendLoop(conversationId: String) : DesktopTimelineLoop {
        override val state = MutableStateFlow(Timeline(conversationId))
        private val sendGate = CompletableDeferred<Unit>()

        override suspend fun hydrate(request: DesktopTimelineHydrateRequest) = Unit

        override suspend fun send(request: DesktopTimelineSendRequest): String {
            sendGate.await()
            return "client-held"
        }

        fun releaseSend() {
            sendGate.complete(Unit)
        }

        override fun close() {
            sendGate.cancel(CancellationException("closed"))
        }
    }
}
