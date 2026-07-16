package com.letta.mobile.data.controller

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-kyqdt: authoritative busy ownership across DEVICES/CONNECTIONS.
 *
 * The Iroh node server hands ONE shared [AppServerController] (hence ONE
 * [com.letta.mobile.data.runtime.AppServerTurnEngine] with ONE `activeTurn`
 * mutex) to EVERY [com.letta.mobile.data.controller.node.iroh.IrohNodeConnection].
 * So two devices are two connections calling [AppServerController.runTurn] on the
 * SAME engine. The regression: a completed run held that mutex indefinitely (its
 * terminal quiet-timer was re-armed by later matching frames), so the second
 * device's send was rejected as "An App Server turn is already active ..." ~18.6s
 * after the prior run was already terminal.
 *
 * These tests drive the REAL controller + REAL engine (only the wire [client] is
 * faked) exactly as the two connections would, and assert:
 *   - cross-device sequential turn after a completed terminal is accepted (no false busy),
 *   - a post-terminal frame trickle (late fanout / cross-device viewer traffic)
 *     cannot defer busy release (delayed cleanup),
 *   - a "reconnect" (fresh runTurn call, as a redialed connection issues) after a
 *     completed terminal is accepted and preserves the OTID exactly once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerControllerCrossDeviceBusyTest {

    @Test
    fun secondDeviceSendAfterCompletedTerminalIsAccepted() = runTest {
        val client = FakeWireClient()
        val controller = DefaultAppServerController(client = client)

        // Device A runs a turn to a completed terminal.
        val deviceA = launch { controller.runTurn(commandA).collect() }
        runCurrent()
        client.emit(stopReason("run-A"))
        advanceUntilIdle()
        deviceA.join()

        // Device B (different connection, SAME shared engine) sends next.
        var deviceBAccepted = false
        val deviceB = launch { controller.runTurn(commandB).collect { deviceBAccepted = true } }
        runCurrent()
        client.emit(stopReason("run-B"))
        advanceUntilIdle()
        deviceB.join()

        assertTrue(deviceBAccepted, "second device must be able to send immediately after A's terminal")
        assertEquals(
            listOf("otid-A", "otid-B"),
            client.userClientMessageIds,
            "each device's send issues exactly one input, OTID preserved once",
        )
    }

    @Test
    fun postTerminalFrameTrickleDoesNotBlockCrossDeviceSend() = runTest {
        val client = FakeWireClient()
        val controller = DefaultAppServerController(client = client)

        val deviceA = launch { controller.runTurn(commandA).collect() }
        runCurrent()
        // A completes...
        client.emit(stopReason("run-A"))
        runCurrent()
        // ...then a steady trickle of later matching frames (delayed cleanup /
        // cross-device viewer traffic) arrives. The regression re-armed the
        // completed-terminal quiet timer on each, deferring busy release.
        repeat(20) {
            client.emit(assistantMessage("run-A"))
            runCurrent()
        }
        advanceUntilIdle()
        deviceA.join()

        var deviceBAccepted = false
        val deviceB = launch { controller.runTurn(commandB).collect { deviceBAccepted = true } }
        runCurrent()
        client.emit(stopReason("run-B"))
        advanceUntilIdle()
        deviceB.join()

        assertTrue(
            deviceBAccepted,
            "a post-terminal frame trickle must not keep the shared engine busy",
        )
    }

    @Test
    fun reconnectResendAfterCompletedTerminalPreservesOtidExactlyOnce() = runTest {
        val client = FakeWireClient()
        val controller = DefaultAppServerController(client = client)

        // Original connection: runTurn to completed terminal.
        val original = launch { controller.runTurn(commandA).collect() }
        runCurrent()
        client.emit(stopReason("run-A"))
        advanceUntilIdle()
        original.join()

        // Reconnect: a redialed connection re-issues runTurn for the SAME
        // conversation (fresh send, new OTID). It must be accepted and must not
        // duplicate the prior OTID.
        var reconnectAccepted = false
        val reconnect = launch { controller.runTurn(commandReconnect).collect { reconnectAccepted = true } }
        runCurrent()
        client.emit(stopReason("run-reconnect"))
        advanceUntilIdle()
        reconnect.join()

        assertTrue(reconnectAccepted, "reconnect re-send after a completed terminal must be accepted")
        assertEquals(
            listOf("otid-A", "otid-reconnect"),
            client.userClientMessageIds,
            "reconnect send preserves its OTID exactly once; no duplication of the prior turn",
        )
    }

    private companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-be818444")

        fun command(otid: String, text: String) = TurnCommand(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:agent-1:conv-be818444"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-be818444"),
            input = TurnInput.UserMessage(localMessageId = otid, text = text),
        )

        val commandA = command("otid-A", "first")
        val commandB = command("otid-B", "second from device B")
        val commandReconnect = command("otid-reconnect", "after reconnect")

        fun stopReason(runId: String) = streamDelta("stop_reason", runId)
        fun assistantMessage(runId: String) = streamDelta("assistant_message", runId)

        fun streamDelta(messageType: String, runId: String): AppServerInboundFrame.StreamDelta =
            AppServerInboundFrame.StreamDelta(
                runtime = runtime,
                eventSeq = 1,
                emittedAt = "2026-07-11T00:00:00Z",
                idempotencyKey = "evt-$messageType-$runId",
                delta = buildJsonObject {
                    put("message_type", messageType)
                    put("run_id", runId)
                },
            )
    }

    private class FakeWireClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 64)
        val userClientMessageIds = mutableListOf<String>()

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
            (command.payload as? AppServerInputPayload.CreateMessage)
                ?.messages
                ?.filter { it.role == "user" }
                ?.mapNotNull { it.clientMessageId }
                ?.let { userClientMessageIds += it }
        }

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("sync unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("abort unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        fun emit(frame: AppServerInboundFrame) {
            (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Stream,
                    frame = frame,
                    raw = buildJsonObject {
                        put("type", frame.type ?: "unknown")
                        put("idempotency_key", "evt-1")
                        if (frame is AppServerInboundFrame.StreamDelta) put("delta", frame.delta)
                    },
                ),
            )
        }
    }
}
