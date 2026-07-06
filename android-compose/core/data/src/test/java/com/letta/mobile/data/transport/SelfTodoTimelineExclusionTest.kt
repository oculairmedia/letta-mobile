package com.letta.mobile.data.transport

import com.letta.mobile.data.repository.SelfTodoRepository
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.testutil.FakeChannelTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * letta-mobile-y70m0: the shim's synthesized self-todo frame
 * (constant per-conversation `selftodo-` sentinel ids) is CHIP DATA ONLY.
 * It must update the [SelfTodoRepository] chip source but must NOT enter
 * the chat timeline as a [WsTimelineEvent.MessageDelta] / run block —
 * otherwise the re-emitted constant run id collides with itself in the
 * LazyColumn and hard-crashes with a duplicate-key
 * IllegalArgumentException.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelfTodoTimelineExclusionTest {

    private val conv = "conv-1"

    private fun selfTodoFrame(): ServerFrame.ToolCallMessage = ServerFrame.ToolCallMessage(
        id = "toolcall-selftodo-$conv",
        ts = "2026-06-05T00:00:00Z",
        agentId = "agent-1",
        conversationId = conv,
        turnId = "selftodo-turn-$conv",
        runId = "selftodo-run-$conv",
        toolCall = ToolCallPayload(
            toolCallId = "selftodo-$conv",
            name = "TodoWrite",
            arguments = """{"todos":[{"content":"do it","status":"in_progress","activeForm":"Doing it"}]}""",
        ),
    )

    private fun realToolCallFrame(): ServerFrame.ToolCallMessage = ServerFrame.ToolCallMessage(
        id = "toolcall-real-1",
        ts = "2026-06-05T00:00:01Z",
        agentId = "agent-1",
        conversationId = conv,
        turnId = "turn-real-1",
        runId = "run-real-1",
        toolCall = ToolCallPayload(
            toolCallId = "tc-real-1",
            name = "Bash",
            arguments = """{"command":"ls"}""",
        ),
    )

    @Test
    fun `self-todo frame does not produce a timeline MessageDelta but a real tool call does`() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeChannelTransport(
            initialState = ChannelTransportState.Connected(
                serverId = "srv", sessionId = "sess", deviceId = "dev",
            ),
        )
        val bridge = WsChatBridge(transport, injectedShareScope = backgroundScope)

        val collected = mutableListOf<WsTimelineEvent>()
        val job = launch { bridge.events.toList(collected) }
        advanceUntilIdle()

        // Re-emit the self-todo frame several times (mimicking TaskUpdate /
        // resubscribe bursts that previously caused the duplicate-key crash).
        // WsChatBridge consumes transport.frameEvents (replay-aware stream,
        // letta-mobile-ktm2b/#516), so timeline assertions must emit there.
        repeat(3) { transport.frameEvents.emit(TransportFrameEvent(selfTodoFrame())) }
        transport.frameEvents.emit(TransportFrameEvent(realToolCallFrame()))
        advanceUntilIdle()

        val deltas = collected.filterIsInstance<WsTimelineEvent.MessageDelta>()
        // Exactly one timeline message — the real tool call. None of the
        // three self-todo emissions folded into the timeline.
        assertEquals(1, deltas.size)
        assertEquals("toolcall-real-1", deltas.single().message.id)

        job.cancel()
    }

    @Test
    fun `self-todo frame still updates the chip via SelfTodoRepository`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = FakeChannelTransport(
                initialState = ChannelTransportState.Connected(
                    serverId = "srv", sessionId = "sess", deviceId = "dev",
                ),
            )
            // backgroundScope (auto-cancelled at test end) re-dispatched onto
            // an UnconfinedTestDispatcher so the repository's init-launched
            // observer subscribes EAGERLY to the (replay=0) SharedFlow before
            // we emit — otherwise the frame would be dropped.
            val repoScope = CoroutineScope(
                backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler),
            )
            val repo = SelfTodoRepository(transport, repoScope)

            transport.events.emit(selfTodoFrame())
            advanceUntilIdle()

            val todos = repo.latestFor(conv)
            assertEquals(1, todos.size)
            assertEquals("do it", todos.single().content)
            assertEquals("in_progress", todos.single().status)
        }
}
