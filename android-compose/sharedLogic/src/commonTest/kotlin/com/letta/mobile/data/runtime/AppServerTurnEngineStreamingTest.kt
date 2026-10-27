package com.letta.mobile.data.runtime



import app.cash.turbine.ReceiveTurbine
import com.letta.mobile.runtime.RuntimeEventDraft
import app.cash.turbine.test
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest


@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineStreamingTest : AppServerTurnEngineFixture() {
    @Test
    fun runTurnStartsRuntimeSendsInputAndCompletesOnStopReason() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = { "runtime-start-1" },
        )

        engine.runTurn(command).test {
            val started = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Started, started.status)

            val input = assertIs<AppServerCommand.Input>(client.sentCommands.single())
            assertEquals(runtime, input.runtime)
            val payload = assertIs<com.letta.mobile.data.transport.appserver.AppServerInputPayload.CreateMessage>(input.payload)
            assertEquals("local-1", payload.messages.single().clientMessageId)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))

            assertFrameType("stop_reason")
            assertTerminal(RuntimeRunStatus.Completed)
        }

        assertEquals("runtime-start-1", client.runtimeStartCommands.single().requestId)
    }

    @Test
    fun runTurnCompletesOnUsageAfterPostToolAssistantText() = runTest {
        withRunningTurn { client ->

            client.emit(streamDelta(messageType = "assistant_message", runId = "run-1"))
            assertFrameType("assistant_message")

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            runCurrent()
            expectNoEvents()

            // letta-mobile-qygvv.26: the turn went on, so round 1's stop_reason goes out at the boundary.
            client.emit(streamDelta(messageType = "client_tool_end", runId = "run-1"))
            assertFrameType("stop_reason")
            assertIs<RuntimeEventPayload.ToolReturnObserved>(awaitItem().payload)

            client.emit(streamDelta(messageType = "assistant_message", runId = "run-2"))
            assertFrameType("assistant_message")

            client.emit(streamDelta(messageType = "usage_statistics", runId = "run-2"))
            assertFrameType("usage_statistics")
            assertTerminal(RuntimeRunStatus.Completed)
        }
    }


    @Test
    fun multiRoundToolTurnContinuesAfterPostToolUsageInterRoundTail() = runTest {
        // letta-mobile-c4igq.6: a multi-step agentic turn emits a usage_statistics
        // frame BETWEEN tool rounds (tool_return -> assistant -> usage -> ANOTHER
        // tool_call -> ...). The post-tool usage-completion fallback must NOT treat
        // that inter-round usage tail as terminal and kill the turn before round 2.
        // Regression: on Iroh this manifested as "the turn stops after a tool call
        // and waits for the user to send a message to continue".
        //
        // Telemetry is a global accumulator; clear it so this turn's
        // activeTurn.released event does not pollute other tests that assert
        // on Telemetry.snapshot() (they select with .first{}).
        com.letta.mobile.util.Telemetry.clear()
        withRunningTurn { client ->

            // Round 1: tool return, assistant text, then an inter-round usage tail.
            client.emit(streamDelta(messageType = "client_tool_end", runId = "run-1", toolCallId = "call-1"))
            assertIs<RuntimeEventPayload.ToolReturnObserved>(awaitItem().payload)

            client.emit(streamDelta(messageType = "assistant_message", runId = "run-1", key = "evt-assistant-round-2"))
            assertFrameType("assistant_message")

            // Inter-round usage tail — the model is NOT done; another tool call follows.
            client.emit(streamDelta(messageType = "usage_statistics", runId = "run-1"))
            runCurrent()

            // Round 2: the model makes another tool call. If the engine prematurely
            // completed on the inter-round usage tail, this frame is never delivered
            // (the flow already terminated) and the awaitItem() below times out.
            client.emit(streamDelta(messageType = "client_tool_start", runId = "run-1", toolCallId = "call-2"))
            assertIs<RuntimeEventPayload.ToolCallObserved>(awaitItem().payload)

            client.emit(streamDelta(messageType = "client_tool_end", runId = "run-1", toolCallId = "call-2"))
            assertIs<RuntimeEventPayload.ToolReturnObserved>(awaitItem().payload)

            client.emit(streamDelta(messageType = "assistant_message", runId = "run-1", key = "evt-assistant-round-3"))
            assertFrameType("assistant_message")

            // Real terminal now completes the turn. The KEY assertion of this test
            // already passed: round 2's tool_call/return were delivered, proving the
            // turn continued past the inter-round usage tail instead of prematurely
            // completing. Drain remaining terminal frames without over-asserting
            // exact tail ordering (buffered stop + completion interplay is covered
            // by the dedicated single-round tests).
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1", stopReason = "end_turn"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun usageBeforeFinalAssistantTextEmitsAfterAssistantAtTail() = runTest {
        withRunningTurn { client ->

            client.emit(streamDelta(messageType = "usage_statistics", runId = "run-1"))
            runCurrent()
            expectNoEvents()

            client.emit(streamDelta(messageType = "assistant_message", runId = "run-1"))
            assertFrameType("assistant_message")

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertFrameType("usage_statistics")
            assertFrameType("stop_reason")
            assertTerminal(RuntimeRunStatus.Completed)
        }
    }

    @Test
    fun requiresApprovalStopReasonReachesClientAtRoundBoundary() = runTest {
        withRunningTurn { client ->

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1", stopReason = "requires_approval"))
            runCurrent()
            expectNoEvents()

            client.emit(streamDelta(messageType = "assistant_message", runId = "run-1"))
            assertEquals("requires_approval",
                 assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).body.jsonDeltaString("stop_reason"))
            assertFrameType("assistant_message")

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1", stopReason = "end_turn"))
            val stop = assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload)
            assertEquals("stop_reason", stop.messageType)
            assertEquals("end_turn", stop.body.jsonDeltaString("stop_reason"))
            assertTerminal(RuntimeRunStatus.Completed)
        }
    }

    @Test
    fun everyUsageFrameReachesTailInServerOrder() = runTest {
        withRunningTurn { client ->

            client.emit(streamDelta(messageType = "usage_statistics", runId = "run-1", totalTokens = 11))
            client.emit(streamDelta(messageType = "usage_statistics", runId = "run-1", totalTokens = 22))
            runCurrent()
            expectNoEvents()

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("11", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).body.jsonDeltaString("total_tokens"))
            assertEquals("22", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).body.jsonDeltaString("total_tokens"))
            assertFrameType("stop_reason")
            assertTerminal(RuntimeRunStatus.Completed)
        }
    }

    @Test
    fun failedTurnFlushesBufferedTailBeforeFailedTerminal() = runTest {
        withRunningTurn { client ->

            client.emit(streamDelta(messageType = "usage_statistics", runId = "run-1"))
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1", stopReason = "requires_approval"))
            runCurrent()
            expectNoEvents()

            client.emit(streamDelta(messageType = "error_message", runId = "run-1"))
            assertFrameType("usage_statistics")
            assertFrameType("stop_reason")
            assertTerminal(RuntimeRunStatus.Failed)
        }
    }
    private suspend fun withRunningTurn(block: suspend ReceiveTurbine<RuntimeEventDraft>.(FakeAppServerClient) -> Unit) {
        val client = FakeAppServerClient()
        AppServerTurnEngine(client = client).runTurn(command).test {
            assertStarted(client)
            block(client)
        }
    }

    private suspend fun ReceiveTurbine<RuntimeEventDraft>.assertFrameType(expected: String) {
        assertEquals(expected, assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
    }

    private suspend fun ReceiveTurbine<RuntimeEventDraft>.assertStarted(client: FakeAppServerClient) {
        assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
        assertIs<AppServerCommand.Input>(client.sentCommands.single())
    }

    private suspend fun ReceiveTurbine<RuntimeEventDraft>.assertTerminal(status: RuntimeRunStatus) {
        val terminal = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
        assertEquals(status, terminal.status)
        awaitComplete()
    }

}

