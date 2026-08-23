package com.letta.mobile.desktop.chat

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.model.LettaConfig
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the iroh auth/capability handshake [authenticateDesktopIrohAppServer]
 * runs before the desktop App Server controller stack is handed a live
 * connection (finding 6: factory auth wiring).
 */
class DesktopAppServerChatGatewayBuilderTest {

    @Test
    fun websocketGatewayIsNotPublishedUntilAppServerInfoCompletes() = runBlocking {
        RawAppServerWebSocket().use { server ->
            val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val buildScope = CoroutineScope(coroutineContext + SupervisorJob())
            val build = buildScope.async {
                DesktopAppServerChatGatewayBuilder(controllerScope = controllerScope).create(
                    lettaConfig = localConfig(),
                    appServerConfig = DesktopAppServerRuntimeConfig(enabled = true, serverUrl = server.url),
                )
            }

            try {
                val requestId = server.awaitAppServerInfoRequestId()
                assertFalse(build.isCompleted, "gateway was published before app_server_info completed")

                server.sendAppServerInfo(AppServerInfoReply(requestId = requestId))
                val gateway = withTimeout(5.seconds) { build.await() }
                (gateway as AutoCloseable).close()
                server.awaitPeerClose()
            } finally {
                build.cancel()
                buildScope.cancel()
                controllerScope.cancel()
            }
        }
    }

    @Test
    fun failedAppServerInfoRejectsGatewayAndClosesWebSocketResources() = runBlocking {
        RawAppServerWebSocket().use { server ->
            val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val buildScope = CoroutineScope(coroutineContext + SupervisorJob())
            val build = buildScope.async {
                DesktopAppServerChatGatewayBuilder(controllerScope = controllerScope).create(
                    lettaConfig = localConfig(),
                    appServerConfig = DesktopAppServerRuntimeConfig(enabled = true, serverUrl = server.url),
                )
            }

            try {
                val requestId = server.awaitAppServerInfoRequestId()
                server.sendAppServerInfo(
                    AppServerInfoReply(
                        requestId = requestId,
                        success = false,
                        protocolVersion = null,
                        runtimeStart = false,
                        error = "unsupported command",
                    ),
                )

                assertFailsWith<IllegalStateException> { withTimeout(5.seconds) { build.await() } }
                server.awaitPeerClose()
            } finally {
                build.cancel()
                buildScope.cancel()
                controllerScope.cancel()
            }
        }
    }

    @Test
    fun incompatibleAppServerInfoRejectsGatewayAndClosesWebSocketResources() = runBlocking {
        RawAppServerWebSocket().use { server ->
            val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val buildScope = CoroutineScope(coroutineContext + SupervisorJob())
            val build = buildScope.async {
                DesktopAppServerChatGatewayBuilder(controllerScope = controllerScope).create(
                    lettaConfig = localConfig(),
                    appServerConfig = DesktopAppServerRuntimeConfig(enabled = true, serverUrl = server.url),
                )
            }

            try {
                val requestId = server.awaitAppServerInfoRequestId()
                server.sendAppServerInfo(AppServerInfoReply(requestId = requestId, runtimeStart = false))

                assertFailsWith<IllegalStateException> { withTimeout(5.seconds) { build.await() } }
                server.awaitPeerClose()
            } finally {
                build.cancel()
                buildScope.cancel()
                controllerScope.cancel()
            }
        }
    }

    private fun localConfig() = LettaConfig(
        id = "desktop-handshake-test",
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = "local://bundled",
    )

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
        val eventRouter = AppServerRuntimeEventRouter()
        val engine = buildDesktopAppServerTurnEngine(
            client = client,
            scope = this,
            eventRouter = eventRouter,
        )

        try {
            runConversationTurnToCompletion(engine, client, "conv-1")
            runConversationTurnToCompletion(engine, client, "conv-1")

            val start = client.runtimeStarts.single()
            assertEquals(AppServerPermissionMode.Unrestricted, start.mode)
            assertEquals("conv-1", start.conversationId)
            assertEquals(2, client.agentRetrieveCount, "context-window preflight must run on each user turn")
        } finally {
            eventRouter.detach()
        }
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
        var agentRetrieveCount = 0
            private set
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

        // Healthy context so AppServerContextWindowPreflight does not mutate or
        // invalidate the cached runtime between the two turns under test.
        override suspend fun agentRetrieve(
            command: AppServerCommand.AgentRetrieve,
        ): AppServerInboundFrame.AgentRetrieveResponse {
            agentRetrieveCount += 1
            return AppServerInboundFrame.AgentRetrieveResponse(
                requestId = command.requestId,
                success = true,
                agent = buildJsonObject { put("context_window_limit", 200_000) },
            )
        }

        override suspend fun conversationRetrieve(
            command: AppServerCommand.ConversationRetrieve,
        ): AppServerInboundFrame.ConversationRetrieveResponse =
            AppServerInboundFrame.ConversationRetrieveResponse(
                requestId = command.requestId,
                success = true,
                conversation = buildJsonObject {},
            )

        override suspend fun conversationMessagesList(
            command: AppServerCommand.ConversationMessagesList,
        ): AppServerInboundFrame.ConversationMessagesListResponse =
            AppServerInboundFrame.ConversationMessagesListResponse(
                requestId = command.requestId,
                success = true,
                messages = kotlinx.serialization.json.JsonArray(emptyList()),
            )
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

/**
 * Minimal dependency-free WebSocket peer for exercising the production CIO
 * transport. It intentionally exposes the info response as a test-controlled
 * barrier so returning a gateway on socket-open alone is observable.
 */
private data class AppServerInfoReply(
    val requestId: String,
    val success: Boolean = true,
    val protocolVersion: Int? = 1,
    val runtimeStart: Boolean = true,
    val error: String? = null,
)

private class RawAppServerWebSocket : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var acceptedSocket: Socket? = null
    private val inputReady = CompletableDeferred<BufferedInputStream>()
    private val outputReady = CompletableDeferred<BufferedOutputStream>()

    val url: String = "ws://127.0.0.1:${server.localPort}"

    init {
        scope.async {
            val socket = server.accept()
            socket.tcpNoDelay = true
            socket.soTimeout = 5_000
            acceptedSocket = socket
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val headers = readHttpHeaders(input)
            val key = headers.lineSequence()
                .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                .substringAfter(':')
                .trim()
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WEB_SOCKET_GUID).toByteArray()),
            )
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
            )
            output.flush()
            inputReady.complete(input)
            outputReady.complete(output)
        }
    }

    suspend fun awaitAppServerInfoRequestId(): String {
        val frame = withTimeout(5.seconds) { readNextText(inputReady.await()) }
        val payload = Json.parseToJsonElement(frame).jsonObject
        assertEquals("app_server_info", payload["type"]?.jsonPrimitive?.content)
        return payload.getValue("request_id").jsonPrimitive.content
    }

    suspend fun sendAppServerInfo(reply: AppServerInfoReply) {
        val response = buildJsonObject {
            put("type", "app_server_info_response")
            put("request_id", reply.requestId)
            put("success", reply.success)
            reply.error?.let { put("error", it) }
            if (reply.protocolVersion != null) {
                put("letta_code_version", "0.29.12")
                put("protocol_version", reply.protocolVersion)
                put("backend", "local")
                put(
                    "capabilities",
                    buildJsonObject {
                        put("runtime_start", reply.runtimeStart)
                        put("split_channels", false)
                        put("agent_management", true)
                        put("conversation_management", true)
                        put("memory_management", true)
                    },
                )
            }
        }.toString()
        writeServerText(outputReady.await(), response)
    }

    suspend fun awaitPeerClose() {
        val input = inputReady.await()
        withTimeout(5.seconds) {
            while (true) {
                val opcode = readFrame(input)?.first ?: return@withTimeout
                if (opcode == CLOSE_OPCODE) return@withTimeout
            }
        }
    }

    override fun close() {
        runCatching { acceptedSocket?.close() }
        runCatching { server.close() }
        scope.cancel()
    }

    private fun readHttpHeaders(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (matched < terminator.size) {
            val value = input.read()
            check(value >= 0) { "socket closed during WebSocket upgrade" }
            val byte = value.toByte()
            bytes += byte
            matched = if (byte == terminator[matched]) matched + 1 else if (byte == terminator[0]) 1 else 0
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun readNextText(input: BufferedInputStream): String {
        while (true) {
            val (opcode, payload) = readFrame(input) ?: error("socket closed before app_server_info")
            if (opcode == TEXT_OPCODE) return payload.decodeToString()
            if (opcode == CLOSE_OPCODE) error("socket closed before app_server_info")
        }
    }

    private fun readFrame(input: BufferedInputStream): Pair<Int, ByteArray>? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) return null
        val opcode = first and 0x0f
        val masked = second and 0x80 != 0
        val length = when (val shortLength = second and 0x7f) {
            126 -> readUnsignedShort(input).toLong()
            127 -> readLong(input)
            else -> shortLength.toLong()
        }
        require(length <= Int.MAX_VALUE) { "test peer received an oversized frame" }
        val mask = if (masked) input.readExactly(4) else null
        val payload = input.readExactly(length.toInt())
        if (mask != null) payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        return opcode to payload
    }

    private fun writeServerText(output: BufferedOutputStream, text: String) {
        val payload = text.toByteArray()
        synchronized(output) {
            output.write(0x80 or TEXT_OPCODE)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size <= 0xffff -> {
                    output.write(126)
                    output.write((payload.size ushr 8) and 0xff)
                    output.write(payload.size and 0xff)
                }
                else -> {
                    output.write(127)
                    output.write(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(payload.size.toLong()).array())
                }
            }
            output.write(payload)
            output.flush()
        }
    }

    private fun readUnsignedShort(input: BufferedInputStream): Int =
        (input.readRequired() shl 8) or input.readRequired()

    private fun readLong(input: BufferedInputStream): Long =
        ByteBuffer.wrap(input.readExactly(Long.SIZE_BYTES)).long

    private fun BufferedInputStream.readRequired(): Int =
        read().also { check(it >= 0) { "unexpected WebSocket EOF" } }

    private fun BufferedInputStream.readExactly(size: Int): ByteArray = ByteArray(size).also { bytes ->
        var offset = 0
        while (offset < size) {
            val count = read(bytes, offset, size - offset)
            check(count >= 0) { "unexpected WebSocket EOF" }
            offset += count
        }
    }

    private companion object {
        const val WEB_SOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val TEXT_OPCODE = 1
        const val CLOSE_OPCODE = 8
    }
}
