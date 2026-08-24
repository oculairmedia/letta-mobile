package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun IrohObserverIngestor(
    scope: TestScope,
    turnRegistry: IrohTurnRegistry,
    connectionGeneration: () -> Long,
    emitBoth: suspend (ServerFrame) -> Unit,
    adminRpc: suspend (String, String, String?) -> AppServerInboundFrame.AdminRpcResponse,
    recordFrameOwnership: (String, IrohActiveTurn?) -> Unit,
): IrohObserverIngestor = IrohObserverIngestor(
    IrohObserverIngestor.Dependencies(
        scope = scope,
        turnRegistry = turnRegistry,
        connectionGeneration = connectionGeneration,
        emit = emitBoth,
        adminRpc = { request -> adminRpc(request.method, request.path, request.body) },
        recordFrameOwnership = { observation -> recordFrameOwnership(observation.conversationId, observation.localTurn) },
    ),
)

@OptIn(ExperimentalCoroutinesApi::class)
class IrohObserverIngestorTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun streamDelta(
        agentId: String,
        conversationId: String,
        seq: Long,
        delta: String,
    ): AppServerReceivedFrame {
        val body = """
            {
              "type": "stream_delta",
              "runtime": {"agent_id": "$agentId", "conversation_id": "$conversationId"},
              "event_seq": $seq,
              "emitted_at": "2026-07-09T00:00:0${seq}Z",
              "idempotency_key": "obs-evt-$conversationId-$seq",
              "delta": $delta
            }
        """.trimIndent()
        return AppServerProtocol.decodeFrame(body, AppServerChannel.Stream)
    }

    @Test
    fun testResubscribeUsesOnlyCurrentGeneration() = testScope.runTest {
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        val generation = AtomicLong(1L)
        val ingestor = observerIngestor(generation, calls)
        val path = "/v1/conversations/conv-123/messages?limit=50"
        ingestor.observeAdminRequest(AdminRpcRequest("message.list", path, null))
        assertEquals("conv-123", ingestor.viewedConversationId)
        assertEquals(path, ingestor.viewedMessageListPath)

        ingestor.reSubscribeViewedConversation(generation = 1L)
        assertEquals(listOf("message.list" to path), calls)

        generation.set(2L)
        ingestor.reSubscribeViewedConversation(generation = 1L)
        assertEquals(1, calls.size, "Stale resubscribe must not issue an RPC")
    }

    private fun observerIngestor(
        generation: AtomicLong,
        calls: CopyOnWriteArrayList<Pair<String, String>>,
    ) = IrohObserverIngestor(
        scope = testScope,
        turnRegistry = IrohTurnRegistry(),
        connectionGeneration = { generation.get() },
        emitBoth = { },
        adminRpc = { method, path, _ ->
            calls.add(method to path)
            AppServerInboundFrame.AdminRpcResponse("req-1", true, buildJsonObject { }, null)
        },
        recordFrameOwnership = { _, _ -> },
    )

    @Test
    fun testDualIngestGuardSkipsEngineOwnedFrameAndProjectsTerminal() = testScope.runTest {
        val emittedFrames = CopyOnWriteArrayList<ServerFrame>()
        val turnRegistry = IrohTurnRegistry()
        val connectionGeneration = AtomicLong(1L)

        val startResult = turnRegistry.tryStart(
            IrohTurnStartRequest(IrohTurnToken("conv-1", 1L, "turn-1"), "run-1", "agent-1"),
        )
        val activeTurn = (startResult as IrohTryStartResult.Started).turn

        val ingestor = IrohObserverIngestor(
            scope = testScope,
            turnRegistry = turnRegistry,
            connectionGeneration = { connectionGeneration.get() },
            emitBoth = { emittedFrames.add(it) },
            adminRpc = { _, _, _ -> error("unexpected") },
            recordFrameOwnership = { _, _ -> },
        )

        // 1. Normal streaming frame for engine-owned conversation -> dropped from observer emission
        val streamFrame = streamDelta(
            agentId = "agent-1",
            conversationId = "conv-1",
            seq = 1L,
            delta = """{"message_type": "assistant_message", "content": "hello from engine"}""",
        )
        ingestor.ingestObserverFrame(streamFrame, expectedGeneration = 1L)
        assertTrue(emittedFrames.isEmpty(), "Engine-owned non-terminal delta must not be emitted by observer")

        // 2. Terminal TurnDone frame for engine-owned conversation -> claims terminal and publishes
        val terminalDelta = streamDelta(
            agentId = "agent-1",
            conversationId = "conv-1",
            seq = 2L,
            delta = """{"message_type": "stop_reason", "stop_reason": "end_turn"}""",
        )
        ingestor.ingestObserverFrame(terminalDelta, expectedGeneration = 1L)
        assertEquals(1, emittedFrames.size)
        assertTrue(emittedFrames[0] is ServerFrame.TurnDone)
        assertEquals("completed", (emittedFrames[0] as ServerFrame.TurnDone).status)
        assertTrue(activeTurn.hasTerminal)
    }

    @Test
    fun testPassiveObserverFrameProjectedWhenNoLocalTurn() = testScope.runTest {
        val emittedFrames = CopyOnWriteArrayList<ServerFrame>()
        val turnRegistry = IrohTurnRegistry()
        val connectionGeneration = AtomicLong(1L)

        val ingestor = IrohObserverIngestor(
            scope = testScope,
            turnRegistry = turnRegistry,
            connectionGeneration = { connectionGeneration.get() },
            emitBoth = { emittedFrames.add(it) },
            adminRpc = { _, _, _ -> error("unexpected") },
            recordFrameOwnership = { _, _ -> },
        )

        val assistantFrame = streamDelta(
            agentId = "agent-1",
            conversationId = "conv-passive",
            seq = 1L,
            delta = """{"message_type": "assistant_message", "id": "m1", "content": "passive hello"}""",
        )
        ingestor.ingestObserverFrame(assistantFrame, expectedGeneration = 1L)

        assertFalse(emittedFrames.isEmpty(), "Passive observer frame must be projected and emitted")
    }

    @Test
    fun testRetiredRunIdIsSkipped() = testScope.runTest {
        val emittedFrames = CopyOnWriteArrayList<ServerFrame>()
        val turnRegistry = IrohTurnRegistry()
        val connectionGeneration = AtomicLong(1L)

        val startResult = turnRegistry.tryStart(
            IrohTurnStartRequest(IrohTurnToken("conv-1", 1L, "turn-1"), "run-retired", "agent-1"),
        )
        val turn = (startResult as IrohTryStartResult.Started).turn
        turnRegistry.publishTerminal(IrohTerminalPublication(turn, "completed", "engine"))

        val ingestor = IrohObserverIngestor(
            scope = testScope,
            turnRegistry = turnRegistry,
            connectionGeneration = { connectionGeneration.get() },
            emitBoth = { emittedFrames.add(it) },
            adminRpc = { _, _, _ -> error("unexpected") },
            recordFrameOwnership = { _, _ -> },
        )

        val delta = streamDelta(
            agentId = "agent-1",
            conversationId = "conv-1",
            seq = 1L,
            delta = """{"message_type": "assistant_message", "run_id": "run-retired", "content": "afterlife message"}""",
        )
        ingestor.ingestObserverFrame(delta, expectedGeneration = 1L)

        assertTrue(emittedFrames.isEmpty(), "Retired run frame must be skipped")
    }

    @Test
    fun testSubagentCorrelationDispatchAndReturn() = testScope.runTest {
        val emittedFrames = CopyOnWriteArrayList<ServerFrame>()
        val turnRegistry = IrohTurnRegistry()
        val connectionGeneration = AtomicLong(1L)

        val ingestor = IrohObserverIngestor(
            scope = testScope,
            turnRegistry = turnRegistry,
            connectionGeneration = { connectionGeneration.get() },
            emitBoth = { emittedFrames.add(it) },
            adminRpc = { _, _, _ -> error("unexpected") },
            recordFrameOwnership = { _, _ -> },
        )

        // 1. Agent tool call dispatch
        val dispatchDelta = streamDelta(
            agentId = "parent-agent",
            conversationId = "conv-parent",
            seq = 1L,
            delta = """
                {
                  "message_type": "tool_call_message",
                  "run_id": "parent-run-1",
                  "tool_call": {
                    "name": "Agent",
                    "tool_call_id": "call-sub-1",
                    "arguments": "{\"subagent_id\":\"sub-1\",\"instruction\":\"do task\"}"
                  }
                }
            """.trimIndent(),
        )
        ingestor.ingestObserverFrame(dispatchDelta, expectedGeneration = 1L)

        val subagentUpdate1 = emittedFrames.filterIsInstance<ServerFrame.SubagentsUpdated>().firstOrNull()
        assertNotNull(subagentUpdate1, "SubagentsUpdated must be emitted on Agent dispatch")
        assertEquals(IrohObserverIngestor.SUBAGENT_REASON_STARTED, subagentUpdate1.reason)

        // 2. Agent tool return
        val returnDelta = streamDelta(
            agentId = "parent-agent",
            conversationId = "conv-parent",
            seq = 2L,
            delta = """
                {
                  "message_type": "tool_return_message",
                  "tool_call_id": "call-sub-1",
                  "run_id": "parent-run-1",
                  "status": "success"
                }
            """.trimIndent(),
        )
        ingestor.ingestObserverFrame(returnDelta, expectedGeneration = 1L)

        val subagentUpdate2 = emittedFrames.filterIsInstance<ServerFrame.SubagentsUpdated>().lastOrNull()
        assertNotNull(subagentUpdate2, "SubagentsUpdated must be emitted on Agent return")
        assertEquals(IrohObserverIngestor.SUBAGENT_REASON_COMPLETED, subagentUpdate2.reason)
    }

    @Test
    fun testResetClearsState() = testScope.runTest {
        val turnRegistry = IrohTurnRegistry()
        val connectionGeneration = AtomicLong(1L)

        val ingestor = IrohObserverIngestor(
            scope = testScope,
            turnRegistry = turnRegistry,
            connectionGeneration = { connectionGeneration.get() },
            emitBoth = { },
            adminRpc = { _, _, _ -> error("unexpected") },
            recordFrameOwnership = { _, _ -> },
        )

        val dispatchDelta = streamDelta(
            agentId = "parent-agent",
            conversationId = "conv-parent",
            seq = 1L,
            delta = """
                {
                  "message_type": "tool_call_message",
                  "run_id": "parent-run-1",
                  "tool_call": {
                    "name": "Agent",
                    "tool_call_id": "call-sub-1",
                    "arguments": "{\"subagent_id\":\"sub-1\"}"
                  }
                }
            """.trimIndent(),
        )
        ingestor.ingestObserverFrame(dispatchDelta, expectedGeneration = 1L)
        assertTrue(ingestor.subagentCorrelator.revision > 0L)
        assertTrue(ingestor.subagentCorrelator.snapshot().isNotEmpty())

        ingestor.reset()
        assertEquals(0L, ingestor.subagentCorrelator.revision)
        assertTrue(ingestor.subagentCorrelator.snapshot().isEmpty())
    }
}
