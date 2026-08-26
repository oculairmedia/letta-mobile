package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.model.SubagentStatus

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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IrohObserverIngestorTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun streamDelta(
        agentId: String,
        conversationId: String,
        seq: Long,
        delta: String,
        subagentId: String? = null,
    ): AppServerReceivedFrame {
        val body = """
            {
              "type": "stream_delta",
              "runtime": {"agent_id": "$agentId", "conversation_id": "$conversationId"},
              "event_seq": $seq,
              "emitted_at": "2026-07-09T00:00:0${seq}Z",
              "idempotency_key": "obs-evt-$conversationId-$seq",
              ${subagentId?.let { "\"subagent_id\": \"$it\"," } ?: ""}
              "delta": $delta
            }
        """.trimIndent()
        return AppServerProtocol.decodeFrame(body, AppServerChannel.Stream)
    }

    private fun resubscribeFixture(currentGeneration: Long): ResubscribeFixture {
        val adminRpcCalls = CopyOnWriteArrayList<Pair<String, String>>()
        val connectionGeneration = AtomicLong(currentGeneration)
        val ingestor = IrohObserverIngestor(
            scope = testScope,
            turnRegistry = IrohTurnRegistry(),
            connectionGeneration = { connectionGeneration.get() },
            emitBoth = { },
            adminRpc = { method, path, _ ->
                adminRpcCalls.add(method to path)
                AppServerInboundFrame.AdminRpcResponse("req-1", true, buildJsonObject { }, null)
            },
            recordFrameOwnership = { _, _ -> },
        )
        return ResubscribeFixture(ingestor, connectionGeneration, adminRpcCalls)
    }

    private data class ResubscribeFixture(
        val ingestor: IrohObserverIngestor,
        val connectionGeneration: AtomicLong,
        val adminRpcCalls: CopyOnWriteArrayList<Pair<String, String>>,
    )

    @Test
    fun testResubscribeOnlyForCurrentGeneration() = testScope.runTest {
        val fixture = resubscribeFixture(currentGeneration = 1L)
        val path = "/v1/conversations/conv-123/messages?limit=50"

        fixture.ingestor.recordViewedConversationFrom(ViewedConversationRequest("message.list", path))
        assertEquals("conv-123", fixture.ingestor.viewedConversationId)
        assertEquals(path, fixture.ingestor.viewedMessageListPath)

        fixture.ingestor.reSubscribeViewedConversation(ObserverResubscribeRequest(1L))
        assertEquals(listOf("message.list" to path), fixture.adminRpcCalls)

        fixture.connectionGeneration.set(2L)
        fixture.ingestor.reSubscribeViewedConversation(ObserverResubscribeRequest(1L))
        assertEquals(1, fixture.adminRpcCalls.size, "Resubscribe on stale generation must not issue RPC")
    }

    @Test
    fun testDualIngestGuardSkipsEngineOwnedFrameAndProjectsTerminal() = testScope.runTest {
        val emittedFrames = CopyOnWriteArrayList<ServerFrame>()
        val turnRegistry = IrohTurnRegistry()
        val connectionGeneration = AtomicLong(1L)

        val startResult = turnRegistry.tryStart(
            IrohTurnRequest(
                IrohTurnToken(IrohConversationId("conv-1"), 1L, IrohTurnId("turn-1")),
                IrohRunId("run-1"),
                IrohAgentId("agent-1"),
            ),
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
        ingestor.ingestObserverFrame(ObserverFrameRequest(streamFrame, 1L))
        assertTrue(emittedFrames.isEmpty(), "Engine-owned non-terminal delta must not be emitted by observer")

        // 2. Terminal TurnDone frame for engine-owned conversation -> claims terminal and publishes
        val terminalDelta = streamDelta(
            agentId = "agent-1",
            conversationId = "conv-1",
            seq = 2L,
            delta = """{"message_type": "stop_reason", "stop_reason": "end_turn"}""",
        )
        ingestor.ingestObserverFrame(ObserverFrameRequest(terminalDelta, 1L))
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
        ingestor.ingestObserverFrame(ObserverFrameRequest(assistantFrame, 1L))

        assertFalse(emittedFrames.isEmpty(), "Passive observer frame must be projected and emitted")
    }

    @Test
    fun childAttributedFramesNeverProjectIntoParentTimeline() = testScope.runTest {
        val emitted = CopyOnWriteArrayList<ServerFrame>()
        val ingestor = IrohObserverIngestor(
            scope = testScope,
            turnRegistry = IrohTurnRegistry(),
            connectionGeneration = { 1L },
            emitBoth = { emitted.add(it) },
            adminRpc = { _, _, _ -> error("unexpected") },
            recordFrameOwnership = { _, _ -> },
        )

        listOf(
            """{"message_type":"assistant_message","content":"public child progress"}""",
            """{"message_type":"reasoning_message","reasoning":"hidden child reasoning"}""",
            """{"message_type":"tool_call_message","tool_call":{"name":"Bash","tool_call_id":"inner"}}""",
        ).forEachIndexed { index, delta ->
            ingestor.ingestObserverFrame(
                ObserverFrameRequest(streamDelta("parent", "conv-parent", index + 1L, delta, "child-1"), 1L),
            )
        }

        assertTrue(emitted.isEmpty(), "child trajectory must not append any parent timeline frame")
    }

    @Test
    fun activeChildTerminalShapesNeverCompleteParentTurn() = testScope.runTest {
        val emitted = CopyOnWriteArrayList<ServerFrame>()
        val registry = IrohTurnRegistry()
        val active = assertIs<IrohTryStartResult.Started>(
            registry.tryStart(
                IrohTurnRequest(
                    IrohTurnToken(IrohConversationId("conv-parent"), 1L, IrohTurnId("turn-parent")),
                    IrohRunId("run-parent"),
                    IrohAgentId("parent"),
                ),
            ),
        ).turn
        val ingestor = IrohObserverIngestor(
            scope = testScope,
            turnRegistry = registry,
            connectionGeneration = { 1L },
            emitBoth = { emitted.add(it) },
            adminRpc = { _, _, _ -> error("unexpected") },
            recordFrameOwnership = { _, _ -> },
        )

        listOf(
            """{"message_type":"stop_reason","stop_reason":"end_turn"}""",
            """{"message_type":"loop_error","message":"child failed"}""",
            """{"message_type":"error_message","message":"child failed"}""",
        ).forEachIndexed { index, delta ->
            ingestor.ingestObserverFrame(
                ObserverFrameRequest(streamDelta("parent", "conv-parent", index + 1L, delta, "child-1"), 1L),
            )
        }

        assertTrue(emitted.none { it is ServerFrame.TurnDone })
        assertFalse(active.hasTerminal)
    }

    @Test
    fun testRetiredRunIdIsSkipped() = testScope.runTest {
        val emittedFrames = CopyOnWriteArrayList<ServerFrame>()
        val turnRegistry = IrohTurnRegistry()
        val connectionGeneration = AtomicLong(1L)

        val startResult = turnRegistry.tryStart(
            IrohTurnRequest(
                IrohTurnToken(IrohConversationId("conv-1"), 1L, IrohTurnId("turn-1")),
                IrohRunId("run-retired"),
                IrohAgentId("agent-1"),
            ),
        )
        val turn = (startResult as IrohTryStartResult.Started).turn
        val publication = IrohTerminalPublication(
            turn = turn,
            status = IrohTerminalStatus("completed"),
            source = IrohTerminalSource.Engine,
        )
        assertTrue(turnRegistry.claimTerminal(publication))
        assertTrue(turnRegistry.retireClaimed(publication))

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
        ingestor.ingestObserverFrame(ObserverFrameRequest(delta, 1L))

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
        ingestor.ingestObserverFrame(ObserverFrameRequest(dispatchDelta, 1L))

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
        ingestor.ingestObserverFrame(ObserverFrameRequest(returnDelta, 1L))

        val updates = emittedFrames.filterIsInstance<ServerFrame.SubagentsUpdated>()
        assertEquals(1, updates.size, "dispatch acknowledgement must not emit a child terminal")
        assertEquals(SubagentStatus.RUNNING, updates.single().subagent?.status)
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
        ingestor.ingestObserverFrame(ObserverFrameRequest(dispatchDelta, 1L))
        assertTrue(ingestor.subagentCorrelator.revision > 0L)
        assertTrue(ingestor.subagentCorrelator.snapshot().isNotEmpty())

        ingestor.reset()
        assertEquals(0L, ingestor.subagentCorrelator.revision)
        assertTrue(ingestor.subagentCorrelator.snapshot().isEmpty())
    }
}
