package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P3 client residuals over a fake in-process App Server engine (no QUIC):
 *  - real cancel: abort_message is sent, and a cancelled terminal is emitted
 *    exactly once whether the server answers or not;
 *  - canonical ids: the first server frame promotes the turn off its synthetic
 *    run id, re-emitting TurnStarted and carrying the real run id on the terminal.
 */
class IrohChannelTransportCancelTest {

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        clientScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun transportWith(client: ControllableClient): IrohChannelTransport {
        val engine = AppServerTurnEngine(client = client, requestIdFactory = { "runtime-start-1" })
        return IrohChannelTransport(
            scope = clientScope,
            activeConfigProvider = { IrohConnectConfig("iroh://ticket", "", "device", "test") },
            testDialer = { config ->
                IrohConnectionHandle(
                    config = config,
                    ticket = "ticket",
                    sessionId = "session",
                    turnEngine = engine,
                    close = {},
                )
            },
            serverTerminalWaitMs = 200L,
        )
    }

    @Test
    fun cancelSendsAbortAndSynthesizesExactlyOneCancelledTerminalWhenServerSilent() = runBlocking {
        val client = ControllableClient()
        val transport = transportWith(client)
        val frames = mutableListOf<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }
        try {
            delay(150) // let the SharedFlow collector subscribe before frames are emitted
            transport.send("agent-1", "conv-1", "hi", "otid-1", null, false)
            // Wait until the turn is streaming (TurnStarted observed) AND the
            // runtime is established so abort has a scope to address.
            withTimeout(3_000) { while (frames.none { it is ServerFrame.TurnStarted }) delay(10) }
            withTimeout(3_000) { while (!client.inputReceived) delay(10) }

            assertTrue(transport.cancel("conv-1"))

            // The server never emits a terminal, so the synthetic fallback fires.
            withTimeout(3_000) {
                while (frames.none { it is ServerFrame.TurnDone && it.status == "cancelled" }) delay(10)
            }
            // Let any (incorrect) duplicate terminal race in.
            delay(300)

            assertTrue(client.abortCommands.isNotEmpty(), "cancel must send an abort_message")
            assertEquals(
                1,
                frames.count { it is ServerFrame.TurnDone },
                "exactly one terminal per turn; got ${frames.filterIsInstance<ServerFrame.TurnDone>().map { it.status }}",
            )
            assertEquals("cancelled", frames.filterIsInstance<ServerFrame.TurnDone>().single().status)
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    @Test
    fun cancelPrefersServerTerminalOverSyntheticFallback() = runBlocking {
        // On abort, the server promptly streams its own terminal (stop_reason).
        val client = ControllableClient(emitTerminalOnAbort = true)
        val transport = transportWith(client)
        val frames = mutableListOf<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }
        try {
            delay(150) // let the SharedFlow collector subscribe before frames are emitted
            transport.send("agent-1", "conv-1", "hi", "otid-1", null, false)
            withTimeout(3_000) { while (frames.none { it is ServerFrame.TurnStarted }) delay(10) }
            withTimeout(3_000) { while (!client.inputReceived) delay(10) }

            assertTrue(transport.cancel("conv-1"))

            withTimeout(3_000) { while (frames.none { it is ServerFrame.TurnDone }) delay(10) }
            delay(400) // longer than serverTerminalWaitMs to expose a stray synthetic

            assertTrue(client.abortCommands.isNotEmpty())
            assertEquals(
                1,
                frames.count { it is ServerFrame.TurnDone },
                "server terminal must be the ONLY terminal; got ${frames.filterIsInstance<ServerFrame.TurnDone>().map { it.status }}",
            )
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    @Test
    fun firstServerFramePromotesSyntheticRunIdOntoTurnStartedAndTerminal() = runBlocking {
        val client = ControllableClient()
        val transport = transportWith(client)
        val frames = mutableListOf<ServerFrame>()
        val collector = clientScope.async { transport.events.collect { frames.add(it) } }
        try {
            delay(150) // let the SharedFlow collector subscribe before frames are emitted
            transport.send("agent-1", "conv-1", "hi", "otid-1", null, false)
            withTimeout(3_000) { while (frames.none { it is ServerFrame.TurnStarted }) delay(10) }

            // The first assistant frame carries the real server run id.
            client.emitAssistant(messageId = "letta-msg-1", content = "hello", runId = REAL_RUN_ID)
            withTimeout(3_000) {
                while (frames.none { it is ServerFrame.AssistantMessage && it.content.contains("hello") }) delay(10)
            }
            // Terminal so the turn resolves with the promoted run id.
            client.emitStopReason(runId = REAL_RUN_ID)
            withTimeout(3_000) { while (frames.none { it is ServerFrame.TurnDone }) delay(10) }

            val turnStarts = frames.filterIsInstance<ServerFrame.TurnStarted>()
            assertTrue(turnStarts.size >= 2, "expected a re-emitted TurnStarted after promotion, got ${turnStarts.size}")
            assertEquals(REAL_RUN_ID, turnStarts.last().runId, "re-emitted TurnStarted must carry the real run id")
            assertEquals(
                REAL_RUN_ID,
                frames.filterIsInstance<ServerFrame.TurnDone>().first().runId,
                "terminal must carry the promoted canonical run id, not the synthetic placeholder",
            )
        } finally {
            collector.cancel()
            transport.disconnect()
        }
    }

    private companion object {
        const val REAL_RUN_ID = "run-letta-real-1"
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
    }

    /**
     * Minimal App Server client whose stream can be driven from the test. `input`
     * suspends forever so the turn stays active until the test drives a terminal
     * or cancels.
     */
    private class ControllableClient(
        private val emitTerminalOnAbort: Boolean = false,
    ) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 32)
        val abortCommands = mutableListOf<AppServerCommand.AbortMessage>()

        // Set once the engine has started the runtime and sent input — i.e. the
        // turn is fully streaming and a runtime scope is cached, so abort has
        // something to address.
        @Volatile
        var inputReceived = false

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = requireNotNull(command.agentId),
                    conversationId = requireNotNull(command.conversationId),
                ),
            )

        override suspend fun input(command: AppServerCommand.Input) {
            inputReceived = true
            kotlinx.coroutines.awaitCancellation()
        }

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("sync unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse {
            abortCommands += command
            if (emitTerminalOnAbort) emitStopReason(runId = REAL_RUN_ID)
            return AppServerInboundFrame.AbortMessageResponse(
                requestId = command.requestId ?: "",
                runtime = command.runtime,
                aborted = true,
                success = true,
            )
        }

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        fun emitAssistant(messageId: String, content: String, runId: String) = emit(
            AppServerInboundFrame.StreamDelta(
                runtime = runtime,
                eventSeq = 1,
                emittedAt = "2026-07-01T00:00:00Z",
                idempotencyKey = "evt-$messageId",
                delta = buildJsonObject {
                    put("id", messageId)
                    put("message_type", "assistant_message")
                    put("content", content)
                    put("run_id", runId)
                },
            ),
        )

        fun emitStopReason(runId: String) = emit(
            AppServerInboundFrame.StreamDelta(
                runtime = runtime,
                eventSeq = 2,
                emittedAt = "2026-07-01T00:00:01Z",
                idempotencyKey = "evt-stop",
                delta = buildJsonObject {
                    put("message_type", "stop_reason")
                    put("run_id", runId)
                },
            ),
        )

        private fun emit(frame: AppServerInboundFrame.StreamDelta) {
            (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Stream,
                    frame = frame,
                    raw = buildJsonObject {
                        put("type", "stream_delta")
                        put("idempotency_key", frame.idempotencyKey)
                        put("delta", frame.delta.jsonObject)
                    },
                ),
            )
        }
    }
}
