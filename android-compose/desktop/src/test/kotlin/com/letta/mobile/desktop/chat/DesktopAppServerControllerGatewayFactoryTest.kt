package com.letta.mobile.desktop.chat

import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Covers the iroh auth/capability handshake [authenticateDesktopIrohAppServer]
 * runs before the desktop App Server controller stack is handed a live
 * connection (finding 6: factory auth wiring).
 */
class DesktopAppServerControllerGatewayFactoryTest {

    @Test
    fun auth_advertisesFramePartCapabilityAndToken() = runTest {
        val client = FakeAppServerClient(response = { command ->
            AppServerInboundFrame.AuthResponse(requestId = command.requestId, success = true)
        })

        authenticateDesktopIrohAppServer(client = client, accessToken = "tok-123")

        val recorded = client.recordedAuth ?: error("auth() was not invoked")
        assertEquals("tok-123", recorded.token)
        assertEquals(listOf(IrohFrameCodec.FRAME_PART_CAPABILITY), recorded.capabilities)
        assertTrue(recorded.requestId.startsWith("desktop-auth-"), "requestId was: ${recorded.requestId}")
    }

    @Test
    fun auth_failureWithTokenThrows() = runTest {
        val client = FakeAppServerClient(response = { command ->
            AppServerInboundFrame.AuthResponse(requestId = command.requestId, success = false, error = "denied")
        })

        val error = assertFailsWith<IllegalStateException> {
            authenticateDesktopIrohAppServer(client = client, accessToken = "tok-123")
        }
        assertTrue(error.message.orEmpty().contains("denied"), "message was: ${error.message}")
    }

    @Test
    fun auth_failureAlwaysThrows() = runTest {
        val client = FakeAppServerClient(response = { command ->
            AppServerInboundFrame.AuthResponse(requestId = command.requestId, success = false, error = "no auth required")
        })

        // The in-repo server returns success=true for no-token servers, so
        // success=false unambiguously means unauthenticated — it must throw
        // regardless of whether the client sent a blank token.
        assertFailsWith<IllegalStateException> {
            authenticateDesktopIrohAppServer(client = client, accessToken = null)
        }
        assertFailsWith<IllegalStateException> {
            authenticateDesktopIrohAppServer(client = client, accessToken = "   ")
        }
    }

    /**
     * Finding 6 (#831 Codex P2): the desktop gateway used to eagerly call
     * controller.startRuntime(Unrestricted) AND let AppServerTurnEngine's own
     * ensureRuntime issue a second runtime_start on the first send — a double
     * runtime_start. Dropping the controller and baking the mode into the
     * engine (see [buildDesktopAppServerTurnEngine]) means ensureRuntime's
     * single cached-runtime path is the only place runtime_start is ever
     * issued, Unrestricted, once per conversation.
     *
     * Each turn is let run to a NATURAL completion (a terminal stop_reason
     * stream_delta) rather than truncated with take(1): the engine's
     * activeTurn mutex is only guaranteed unlocked once its channelFlow
     * producer finishes on its own, and a forced downstream cancellation
     * races that teardown. Driving a real terminal frame is what proves the
     * second runTurn legitimately reaches ensureRuntime's cached-runtime
     * branch instead of a scheduling artifact of a half-torn-down turn.
     */
    @Test
    fun desktopTurnEngine_singleUnrestrictedRuntimeStartPerConversation() = runTest {
        val client = RecordingAppServerClient()
        val engine = buildDesktopAppServerTurnEngine(client)

        runConversationTurnToCompletion(engine, client, "conv-1")
        runConversationTurnToCompletion(engine, client, "conv-1")

        val start = client.runtimeStarts.single()
        assertEquals(AppServerPermissionMode.Unrestricted, start.mode)
        assertEquals("conv-1", start.conversationId)
    }

    private suspend fun TestScope.runConversationTurnToCompletion(
        engine: AppServerTurnEngine,
        client: RecordingAppServerClient,
        conversationId: String,
    ) {
        val turn = launch { engine.runTurn(turnCommand(conversationId)).collect() }
        runCurrent()
        client.eventsFlow.emit(stopReasonFrame(conversationId))
        advanceUntilIdle()
        turn.join()
    }

    private fun turnCommand(conversationId: String) = TurnCommand(
        backendId = BackendId("desktop-app-server"),
        runtimeId = RuntimeId("desktop-app-server:$conversationId"),
        agentId = com.letta.mobile.data.model.AgentId("agent-1"),
        conversationId = ConversationId(conversationId),
        input = TurnInput.UserMessage(localMessageId = "m-1", text = "hi"),
    )

    /** A terminal `stream_delta`/`stop_reason` frame that completes a turn naturally. */
    private fun stopReasonFrame(conversationId: String, agentId: String = "agent-1"): AppServerReceivedFrame {
        val envelope = buildJsonObject {
            put("type", "stream_delta")
            put(
                "runtime",
                buildJsonObject {
                    put("agent_id", agentId)
                    put("conversation_id", conversationId)
                },
            )
            put("event_seq", 1)
            put("emitted_at", "2026-01-01T00:00:00Z")
            put("idempotency_key", "idem-stop-$conversationId")
            put("delta", buildJsonObject { put("message_type", "stop_reason") })
        }
        return AppServerProtocol.decodeFrame(envelope.toString(), AppServerChannel.Stream)
    }

    /**
     * Records every runtime_start issued so the test can assert it happens
     * exactly once (Unrestricted) even across two runTurn calls for the same
     * conversation — the second call must hit the engine's cached runtime,
     * not reissue runtime_start.
     */
    private class RecordingAppServerClient : AppServerClient {
        val runtimeStarts = mutableListOf<AppServerCommand.RuntimeStart>()
        val eventsFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)
        override val events: Flow<AppServerReceivedFrame> = eventsFlow

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse {
            runtimeStarts += command
            return AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = command.agentId.orEmpty(),
                    conversationId = command.conversationId.orEmpty(),
                ),
            )
        }

        override suspend fun input(command: AppServerCommand.Input) = Unit

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            TODO("not needed")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            TODO("not needed")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            TODO("not needed")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }

    private class FakeAppServerClient(
        private val response: (AppServerCommand.Auth) -> AppServerInboundFrame.AuthResponse,
    ) : AppServerClient {
        var recordedAuth: AppServerCommand.Auth? = null
            private set

        override val events: Flow<AppServerReceivedFrame> = emptyFlow()

        override suspend fun auth(command: AppServerCommand.Auth): AppServerInboundFrame.AuthResponse {
            recordedAuth = command
            return response(command)
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            TODO("not needed for auth handshake tests")

        override suspend fun input(command: AppServerCommand.Input) = TODO("not needed for auth handshake tests")

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            TODO("not needed for auth handshake tests")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            TODO("not needed for auth handshake tests")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            TODO("not needed for auth handshake tests")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) =
            TODO("not needed for auth handshake tests")
    }
}
