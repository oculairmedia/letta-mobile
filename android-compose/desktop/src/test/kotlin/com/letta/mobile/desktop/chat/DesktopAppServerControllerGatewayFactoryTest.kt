package com.letta.mobile.desktop.chat

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

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
    fun auth_failureWithBlankTokenIsTolerated() = runTest {
        val client = FakeAppServerClient(response = { command ->
            AppServerInboundFrame.AuthResponse(requestId = command.requestId, success = false, error = "no auth required")
        })

        // Servers without a required token still ack+record capabilities even
        // when they report success = false — must not throw for a blank token.
        authenticateDesktopIrohAppServer(client = client, accessToken = null)
        authenticateDesktopIrohAppServer(client = client, accessToken = "   ")
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
