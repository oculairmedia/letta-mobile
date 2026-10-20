package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class IrohObserverFixture {
    protected val testDispatcher = UnconfinedTestDispatcher()
    protected val testScope = TestScope(testDispatcher)

    protected fun streamDelta(spec: StreamDeltaSpec): AppServerReceivedFrame {
        val body = """
            {
              "type": "stream_delta",
              "runtime": {"agent_id": "${spec.agentId}", "conversation_id": "${spec.conversationId}"},
              "event_seq": ${spec.seq},
              "emitted_at": "2026-07-09T00:00:0${spec.seq}Z",
              "idempotency_key": "obs-evt-${spec.conversationId}-${spec.seq}",
              ${spec.subagentId?.let { "\"subagent_id\": \"$it\"," } ?: ""}
              "delta": ${spec.delta}
            }
        """.trimIndent()
        return AppServerProtocol.decodeFrame(body, AppServerChannel.Stream)
    }

    protected data class StreamDeltaSpec(
        val agentId: String,
        val conversationId: String,
        val seq: Long,
        val delta: String,
        val subagentId: String? = null,
    )

    protected fun streamDelta(
        agentId: String,
        conversationId: String,
        seq: Long,
        delta: String,
    ): AppServerReceivedFrame = streamDelta(StreamDeltaSpec(agentId, conversationId, seq, delta))

    protected fun engineOwnedTurn(registry: IrohTurnRegistry): IrohActiveTurn =
        assertIs<IrohTryStartResult.Started>(
            registry.tryStart(
                IrohTurnRequest(
                    IrohTurnToken(IrohConversationId("conv-1"), 1L, IrohTurnId("turn-1")),
                    IrohRunId("local-run-55"),
                    IrohAgentId("agent-1"),
                ),
            ),
        ).turn

    protected fun observerIngestor(registry: IrohTurnRegistry, emitted: MutableList<ServerFrame>) = IrohObserverIngestor(
        scope = testScope,
        turnRegistry = registry,
        connectionGeneration = { 1L },
        emitBoth = { emitted.add(it) },
        adminRpc = { _, _, _ -> error("unexpected") },
        recordFrameOwnership = { _, _ -> },
    )

}
