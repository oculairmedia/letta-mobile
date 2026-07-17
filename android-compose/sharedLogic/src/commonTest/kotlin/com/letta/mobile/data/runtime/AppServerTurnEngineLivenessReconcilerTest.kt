package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-c4igq.3: causal liveness reconciler backstop. When a send is
 * rejected because the engine is busy, the reconciler proves whether the owning
 * run is dead (run.get) before giving up. A stale lock over a DEAD run is
 * cleared so the next send proceeds; a LIVE (silently-thinking) run is left
 * alone so a legitimate multi-hour turn is never interrupted. No wall-clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineLivenessReconcilerTest {

    @Test
    fun busyRejectedSendReconcilesAndReleasesWhenOwnerRunIsProvablyDead() = runTest(UnconfinedTestDispatcher()) {
        val client = LivenessClient(runStatus = "failed")
        val engine = AppServerTurnEngine(client = client)

        // First turn: acquire the lock + promote the owner runId, then go silent
        // (no terminal frame) — the classic stuck-lock over a run that later dies.
        val first = backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        client.emit(streamDelta("assistant_message", "run-1"))
        runCurrent()
        assertTrue(engine.isBusy, "engine is busy with the stuck turn")

        // A second send is issued. It is busy-rejected, but the reconciler queries
        // run.get (which reports "failed" = dead) and releases the stale lock.
        var secondAccepted = false
        val second = backgroundScope.launch {
            runCatching { engine.runTurn(secondCommand).collect { secondAccepted = true } }
        }
        runCurrent()
        // The reconciled second turn is now the active turn; its started draft is emitted.
        client.emit(streamDelta("assistant_message", "run-2"))
        runCurrent()

        assertTrue(client.runGetQueried, "reconciler must query run.get for the owner run")
        assertTrue(secondAccepted, "a provably-dead owner run must release the stale lock so the next send proceeds")
    }

    @Test
    fun busyRejectedSendDoesNotReleaseWhenOwnerRunIsStillAlive() = runTest(UnconfinedTestDispatcher()) {
        val client = LivenessClient(runStatus = "in_progress")
        val engine = AppServerTurnEngine(client = client)

        val first = backgroundScope.launch { runCatching { engine.runTurn(command).collect() } }
        runCurrent()
        client.emit(streamDelta("assistant_message", "run-1"))
        runCurrent()
        assertTrue(engine.isBusy)

        // Second send: reconciler queries run.get (reports "in_progress" = ALIVE),
        // so the lock is NOT released — the silent long turn is protected.
        var secondAccepted = false
        val second = backgroundScope.launch {
            runCatching { engine.runTurn(secondCommand).collect { secondAccepted = true } }
        }
        runCurrent()

        assertTrue(client.runGetQueried, "reconciler must query run.get")
        assertTrue(engine.isBusy, "a live (in_progress) owner run must NOT be interrupted")
        assertFalse(secondAccepted, "the second send stays busy-rejected while the owner run is alive")
    }

    private companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val command = TurnCommand(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:agent-1:conv-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hey"),
        )
        val secondCommand = command.copy(input = TurnInput.UserMessage(localMessageId = "local-2", text = "second"))

        fun streamDelta(messageType: String, runId: String): AppServerInboundFrame.StreamDelta =
            AppServerInboundFrame.StreamDelta(
                runtime = runtime, eventSeq = 1, emittedAt = "2026-07-16T00:00:00Z",
                idempotencyKey = "evt-$messageType-$runId",
                delta = buildJsonObject { put("message_type", messageType); put("run_id", runId) },
            )
    }

    private class LivenessClient(private val runStatus: String) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 64)
        var runGetQueried = false

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId, success = true,
                runtime = AppServerRuntimeScope(requireNotNull(command.agentId), requireNotNull(command.conversationId)),
            )

        override suspend fun input(command: AppServerCommand.Input) = Unit
        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = error("sync unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse = error("abort unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse {
            if (command.method == "run.get") {
                runGetQueried = true
                return AppServerInboundFrame.AdminRpcResponse(
                    requestId = command.requestId, success = true,
                    result = buildJsonObject { put("status", runStatus) },
                )
            }
            return AppServerInboundFrame.AdminRpcResponse(requestId = command.requestId, success = false, error = "unexpected")
        }

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        fun emit(frame: AppServerInboundFrame) {
            (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Stream, frame = frame,
                    raw = buildJsonObject {
                        put("type", frame.type ?: "unknown"); put("idempotency_key", "evt-1")
                        if (frame is AppServerInboundFrame.StreamDelta) put("delta", frame.delta)
                    },
                ),
            )
        }
    }
}
