package com.letta.mobile.cli.meridian

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import com.letta.mobile.data.meridian.MeridianExit
import com.letta.mobile.data.meridian.endpoint.MeridianCallerBindingMode
import com.letta.mobile.data.meridian.endpoint.MeridianToolsWire
import com.letta.mobile.data.meridian.endpoint.MeridianToolsWireRequest
import com.letta.mobile.data.meridian.endpoint.MeridianToolsWireResponse
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

class MeridianToolsEndpointTest {
    @TempDir
    lateinit var dir: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frames = MutableSharedFlow<AppServerReceivedFrame>(replay = 16)
    private val tool = EchoTool()
    private val logs = mutableListOf<String>()
    private val runtime = AppServerRuntimeScope("agent-a", "conv-a")

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun config(
        transport: MeridianToolsTransport = MeridianToolsTransport.UNIX,
        mode: AgentToolsMode = AgentToolsMode.CLI,
        socket: Path = dir.resolve("t.sock"),
    ) = MeridianToolsConfig(
        mode = mode,
        transport = transport,
        socketPath = socket.toString(),
        tcpPort = 0,
        endpointFile = dir.resolve("meridian/tools.endpoint").toString(),
        binding = MeridianCallerBindingMode.LIVE_CALL,
    )

    private fun endpoint(config: MeridianToolsConfig) =
        MeridianToolsEndpoint(config, ExternalToolRegistry.hostTools(listOf(tool)), frames, { synchronized(logs) { logs += it } })

    private fun request(vararg argv: String, token: String? = null) =
        MeridianToolsWireRequest(argv.toList(), agentId = "agent-a", conversationId = "conv-a", token = token)

    @Test
    fun `serves the router on the Unix socket and binds to the live shell call`() = runBlocking {
        frames.emit(toolStart("call-7", "meridian canvas list"))
        assertNotNull(endpoint(config()).start(scope))

        val response = awaitLiveBinding { exchange(unix(dir.resolve("t.sock")), request("canvas", "list")) }

        assertEquals(MeridianExit.OK, response.exitCode, response.stdout)
        assertEquals("call-7", tool.callers.single().toolCallId)
        assertEquals("conv-a", tool.callers.single().conversationId)
    }

    @Test
    fun `refuses callers with no live meridian shell call`() = runBlocking {
        assertNotNull(endpoint(config()).start(scope))

        val response = exchange(unix(dir.resolve("t.sock")), request("canvas", "list"))

        assertEquals(MeridianExit.DENIED, response.exitCode)
        assertTrue(tool.callers.isEmpty())
    }

    @Test
    fun `falls back to loopback TCP with a bearer token file`() = runBlocking {
        frames.emit(toolStart("call-1", "meridian canvas list"))
        val blocked = dir.resolve("not-a-socket")
        Files.writeString(blocked, "regular file")
        assertNotNull(endpoint(config(transport = MeridianToolsTransport.AUTO, socket = blocked)).start(scope))

        val descriptor = Json.parseToJsonElement(Files.readString(dir.resolve("meridian/tools.endpoint"))).jsonObject
        val port = descriptor["port"]!!.jsonPrimitive.int
        val token = descriptor["token"]!!.jsonPrimitive.content
        val withoutToken = exchange(tcp(port), request("canvas", "list"))
        val withToken = awaitLiveBinding { exchange(tcp(port), request("canvas", "list", token = token)) }

        assertEquals("tcp", descriptor["transport"]!!.jsonPrimitive.content)
        assertEquals(64, token.length)
        assertEquals(MeridianExit.DENIED, withoutToken.exitCode)
        assertEquals(MeridianExit.OK, withToken.exitCode, withToken.stdout)
        assertEquals("regular file", Files.readString(blocked))
    }

    @Test
    fun `native mode serves nothing`() = runBlocking {
        val job = endpoint(config(mode = AgentToolsMode.NATIVE)).start(scope)

        assertNull(job)
        assertTrue(Files.notExists(dir.resolve("t.sock")))
    }

    @Test
    fun `an oversized request line is refused unread`() {
        val tooLong = ByteArrayInputStream(ByteArray(32) { 'a'.code.toByte() })

        assertNull(MeridianToolsSocketServer.readLine(tooLong, maxBytes = 16))
        assertEquals("abc", MeridianToolsSocketServer.readLine(ByteArrayInputStream("abc\nrest".toByteArray())))
    }

    @Test
    fun `agent tools mode parses the three arms and rejects others`() {
        assertEquals(AgentToolsMode.CLI, AgentToolsMode.parse(" CLI "))
        assertEquals(AgentToolsMode.META, AgentToolsMode.parse("meta"))
        assertTrue(runCatching { AgentToolsMode.parse("shell") }.isFailure)
    }

    /** The frame collector runs concurrently with the first request; retry until it has caught up. */
    private suspend fun awaitLiveBinding(call: () -> MeridianToolsWireResponse): MeridianToolsWireResponse =
        withTimeout(10_000) {
            var response = call()
            while (response.exitCode == MeridianExit.DENIED && response.stdout.contains("no running meridian")) {
                kotlinx.coroutines.delay(20)
                response = call()
            }
            response
        }

    private fun unix(path: Path): SocketChannel = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
        connect(UnixDomainSocketAddress.of(path))
    }

    private fun tcp(port: Int): SocketChannel = SocketChannel.open(InetSocketAddress(InetAddress.getLoopbackAddress(), port))

    private fun exchange(channel: SocketChannel, request: MeridianToolsWireRequest): MeridianToolsWireResponse = channel.use {
        val bytes = ByteBuffer.wrap((MeridianToolsWire.encodeRequest(request) + "\n").toByteArray())
        while (bytes.hasRemaining()) it.write(bytes)
        val reply = MeridianToolsSocketServer.readLine(Channels.newInputStream(it))
        MeridianToolsWire.decodeResponse(requireNotNull(reply))
    }

    private fun toolStart(id: String, command: String) = AppServerReceivedFrame(
        channel = AppServerChannel.Stream,
        frame = AppServerInboundFrame.StreamDelta(
            runtime = runtime,
            eventSeq = 1,
            emittedAt = "2026-10-10T00:00:00Z",
            idempotencyKey = "stream_delta:$id",
            delta = buildJsonObject {
                put("message_type", "client_tool_start")
                put("tool_call_id", id)
                put("tool_name", "Bash")
                put("tool_args", buildJsonObject { put("command", command) })
            },
        ),
        raw = JsonObject(emptyMap()),
    )

    private class EchoTool : HostExternalTool {
        val callers = java.util.concurrent.CopyOnWriteArrayList<ExternalToolCaller>()
        override val name = "canvas_list"
        override val description = "List canvases."
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(emptyMap<String, JsonElement>()))
        }
        override val capability = Capability.ImageHydration

        override suspend fun invoke(input: JsonObject, agentId: String?) = invoke(input, ExternalToolCaller(agentId))

        override suspend fun invoke(input: JsonObject, caller: ExternalToolCaller): ExternalToolResult {
            callers += caller
            return ExternalToolResult.Success("""{"canvases":[]}""")
        }
    }
}
