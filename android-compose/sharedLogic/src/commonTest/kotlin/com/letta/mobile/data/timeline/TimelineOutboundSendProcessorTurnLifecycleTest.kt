package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Codex #902 review finding 1: the non-WS/REST send path
 * ([TimelineSendCoordinator] -> [TimelineRepository.sendMessage] ->
 * [TimelineOutboundSendProcessor.streamAndReconcile]) never called the
 * dangling-tool-call resolver's turnStarted/turnEnded hooks, unlike the
 * WS/iroh [com.letta.mobile.data.chat.send.ChatSendCoordinator] path — a
 * tool_call streamed here that never got a return left its card spinning
 * forever, with no sweep ever scheduled.
 *
 * Driving this through the full [TimelineSyncLoop.send] rendezvous (event
 * queue linearization, LocalSendAppend/MarkSent gateway round-trips,
 * ChatTimelineObserver-facing state) end-to-end in a unit test would mostly
 * be re-testing the plumbing already covered elsewhere. Instead this tests
 * the exact seam that was wired to fix the finding:
 * [TimelineOutboundSendProcessor]'s `onTurnStarted` / `onTurnEnded`
 * callbacks (now wired in [TimelineSyncLoop] to `::turnStarted` /
 * `::turnEnded`, the same methods the WS path drives via
 * [TimelineRepository]) fire around exactly the outbound stream, with
 * `clean` reflecting whether the stream finished without throwing.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TimelineOutboundSendProcessorTurnLifecycleTest {

    @Test
    fun `send stream completing cleanly signals turnStarted then turnEnded(clean = true)`() = runTest(UnconfinedTestDispatcher()) {
        val transport = SingleToolCallSendTransport(fail = false)
        val turnStartedCalls = mutableListOf<Unit>()
        val turnEndedCalls = mutableListOf<Boolean>()
        val harness = newProcessor(
            transport = transport,
            scope = backgroundScope,
            onTurnStarted = { turnStartedCalls += Unit },
            onTurnEnded = { clean -> turnEndedCalls += clean },
        )

        harness.processor.send("hello")
        runCurrent()

        assertEquals(1, turnStartedCalls.size)
        assertEquals(listOf(true), turnEndedCalls)
    }

    @Test
    fun `send stream throwing signals turnEnded(clean = false)`() = runTest(UnconfinedTestDispatcher()) {
        val transport = SingleToolCallSendTransport(fail = true)
        val turnStartedCalls = mutableListOf<Unit>()
        val turnEndedCalls = mutableListOf<Boolean>()
        val harness = newProcessor(
            transport = transport,
            scope = backgroundScope,
            onTurnStarted = { turnStartedCalls += Unit },
            onTurnEnded = { clean -> turnEndedCalls += clean },
        )

        harness.processor.send("hello")
        runCurrent()

        assertEquals(1, turnStartedCalls.size)
        assertEquals(listOf(false), turnEndedCalls)
    }

    @Test
    fun `a dangling tool call streamed via the send path is resolvable once turnEnded schedules a sweep`() = runTest(UnconfinedTestDispatcher()) {
        // Demonstrates the end-to-end payoff of finding 1's fix: once
        // TimelineOutboundSendProcessor's onTurnEnded fires (wired to
        // TimelineSyncLoop.turnEnded in production), a
        // DanglingToolCallResolver listening for that signal can schedule
        // and run its bounded sweep — exactly as it already does for the
        // WS/iroh path.
        val transport = SingleToolCallSendTransport(fail = false)
        val state = MutableStateFlow(Timeline("conv-send-dangle"))
        var reconcileCalls = 0
        val resolver = DanglingToolCallResolver(
            conversationId = "conv-send-dangle",
            state = state,
            writeMutex = Mutex(),
            scope = backgroundScope,
            reconcile = { _, _ -> reconcileCalls++; 0 },
        )
        val harness = newProcessor(
            transport = transport,
            scope = backgroundScope,
            state = state,
            onTurnStarted = { resolver.cancelPendingSweep() },
            onTurnEnded = { clean -> resolver.scheduleSweepIfUnresolved(clean) },
        )

        harness.processor.send("hello")
        runCurrent()

        // The stream landed a TOOL_CALL with no return; the send path's
        // onTurnEnded should have scheduled (but not yet run) a sweep.
        assertTrue(state.value.unresolvedToolCallIds().isNotEmpty())
        assertEquals(0, reconcileCalls)

        advanceTimeBy(2_000 + 5_000 + 15_000 + 30_000 + 1)
        runCurrent()

        assertEquals(4, reconcileCalls)
        val event = state.value.events.single() as TimelineEvent.Confirmed
        assertEquals("No tool result recorded", event.toolReturnContentByCallId["call-send-1"])
    }

    private class ProcessorHarness(
        val processor: TimelineOutboundSendProcessor,
    )

    private fun newProcessor(
        transport: SingleToolCallSendTransport,
        scope: kotlinx.coroutines.CoroutineScope,
        state: MutableStateFlow<Timeline> = MutableStateFlow(Timeline("conv-send-dangle")),
        onTurnStarted: () -> Unit,
        onTurnEnded: (Boolean) -> Unit,
    ): ProcessorHarness {
        val eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED)
        lateinit var processor: TimelineOutboundSendProcessor
        // The gateway event linearizer (TimelineSyncLoop.processEventQueue +
        // TimelineStateTransitionHandler) isn't present in this isolated
        // test; run a minimal stand-in that acks the events
        // TimelineOutboundSendProcessor actually emits and — critically —
        // forwards LocalSendAppend's pending send onto the processor's own
        // sendQueue, exactly as TimelineStateTransitionHandler.
        // applyLocalSendAppend does in production, since that's what
        // actually triggers streamAndReconcile. The state side-effects
        // those handlers separately own are covered by other tests — this
        // test is only about the turnStarted/turnEnded wiring.
        scope.launch {
            for (event in eventQueue) {
                when (event) {
                    is TimelineGatewayEvent.LocalSendAppend -> {
                        processor.sendQueue.send(event.pending)
                        event.ack.complete(Unit)
                    }
                    is TimelineGatewayEvent.MarkSent -> event.ack.complete(Unit)
                    is TimelineGatewayEvent.MarkFailed -> event.ack.complete(Unit)
                    else -> error("Unexpected gateway event in send-path test: $event")
                }
            }
        }
        processor = TimelineOutboundSendProcessor(
            conversationId = "conv-send-dangle",
            messageApi = transport,
            eventQueue = eventQueue,
            writeMutex = Mutex(),
            state = state,
            events = MutableSharedFlow(replay = 1, extraBufferCapacity = 64),
            pendingLocalStore = NoOpPendingLocalStore,
            logTag = "TestSend",
            scope = scope,
            ingestStreamEvent = { message ->
                if (message is ToolCallMessage) {
                    val toolCall = requireNotNull(message.toolCall)
                    state.value = state.value.append(
                        TimelineEvent.Confirmed(
                            position = (state.value.events.size + 1).toDouble(),
                            otid = "otc-send-1",
                            content = "tool call",
                            serverId = message.id,
                            messageType = TimelineMessageType.TOOL_CALL,
                            date = timelineNow(),
                            runId = message.runId,
                            stepId = null,
                            toolCalls = kotlinx.collections.immutable.persistentListOf(toolCall),
                            toolReturnContentByCallId = kotlinx.collections.immutable.persistentMapOf(),
                            toolReturnIsErrorByCallId = kotlinx.collections.immutable.persistentMapOf(),
                        )
                    )
                }
            },
            onSendStreamEnded = {},
            onTurnStarted = onTurnStarted,
            onTurnEnded = onTurnEnded,
        )
        return ProcessorHarness(processor)
    }

    private class SingleToolCallSendTransport(private val fail: Boolean) : TimelineTransport {
        override suspend fun sendConversationMessage(
            conversationId: String,
            request: MessageCreateRequest,
        ): Flow<LettaMessage> = flow {
            emit(
                ToolCallMessage(
                    id = "tc-send-1",
                    toolCall = ToolCall(id = "call-send-1", name = "search"),
                    runId = "run-send-1",
                )
            )
            if (fail) throw IllegalStateException("stream dropped")
        }

        override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = emptyFlow()

        // reconcileAfterSend's post-stream GET is irrelevant to the
        // turnStarted/turnEnded wiring under test here; an empty result
        // keeps its merge a no-op against the TOOL_CALL event this test
        // appends directly via ingestStreamEvent below.
        override suspend fun listConversationMessages(
            conversationId: String,
            limit: Int?,
            after: String?,
            order: String?,
        ): List<LettaMessage> = emptyList()

        override suspend fun listAgentMessages(
            agentId: String,
            limit: Int?,
            order: String?,
            conversationId: String?,
        ): List<LettaMessage> = emptyList()
    }
}
