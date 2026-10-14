package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.chat.recompile.ConversationRecompileRequest
import com.letta.mobile.data.chat.recompile.IrohAdminRpcLink
import com.letta.mobile.data.repository.iroh.AdminRpcCall
import com.letta.mobile.data.chat.recompile.IrohConversationRecompileClient
import com.letta.mobile.data.chat.recompile.IrohConversationRecompileRpc
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConversationRecompile
import com.letta.mobile.data.transport.appserver.AppServerConversationRecompileResponse
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * letta-mobile-by4vm.51: an Iroh client's [IrohConversationRecompileClient] against a host's real
 * admin_rpc stream server (frame codec, capability gate, router) and [ConversationRecompileHandlers],
 * with the host's App Server faked: the App Server command it sends, the answer mapped back, the
 * App Server's refusal surfaced, the capability gate, and discovery through the host's advertised
 * capabilities.
 */
class ConversationRecompileRelayRoundTripTest {
    private val request = ConversationRecompileRequest(conversationId = "conv-7", agentId = "agent-1")
    private val manage = setOf(IrohPeerCapabilities.CONVERSATION_MANAGE, IrohPeerCapabilities.CHAT_READ)

    @Test
    fun aRecompileReachesTheAppServerAsConversationRecompileAndAnswersWithThePrompt() = runTest {
        val appServer = RecompilingAppServer(prompt = "You are agent-1. <memory_blocks>…")
        val client = IrohConversationRecompileClient(relayed(appServer, manage))

        assertTrue(client.supported, "a host with an App Server advertises the relay")
        val prompt = client.recompile(request, dryRun = false)

        assertEquals("You are agent-1. <memory_blocks>…", prompt)
        val sent = appServer.wire.single()
        assertEquals("conversation_recompile", sent.getValue("type").jsonPrimitive.content)
        assertEquals("conv-7", sent.getValue("conversation_id").jsonPrimitive.content)
        val body = sent.getValue("body").jsonObject
        assertEquals("agent-1", body.getValue("agent_id").jsonPrimitive.content)
        assertFalse("dry_run" in body, "an unset dry_run is not sent: $body")
    }

    @Test
    fun aDryRunTravelsToTheAppServer() = runTest {
        val appServer = RecompilingAppServer(prompt = "preview")
        val client = IrohConversationRecompileClient(relayed(appServer, manage))

        assertEquals("preview", client.recompile(request, dryRun = true))

        val body = appServer.wire.single().getValue("body").jsonObject
        assertEquals("true", body.getValue("dry_run").jsonPrimitive.content)
    }

    @Test
    fun theAppServersRefusalReachesTheClientWithItsReason() = runTest {
        val appServer = RecompilingAppServer(refusal = "Prompt recompile is not supported by this backend yet")
        val client = IrohConversationRecompileClient(relayed(appServer, manage))

        val error = assertFailsWith<IllegalStateException> { client.recompileConversation(request) }

        assertEquals("recompile_failed: Prompt recompile is not supported by this backend yet", error.message)
        assertEquals(1, appServer.wire.size)
    }

    @Test
    fun anAppServerThatDoesNotAnswerIsAFixedSentence() = runTest {
        val appServer = RecompilingAppServer(timesOut = true)
        val client = IrohConversationRecompileClient(relayed(appServer, manage))

        val error = assertFailsWith<IllegalStateException> { client.recompile(request, dryRun = false) }

        assertEquals("recompile_failed: the App Server did not answer", error.message)
    }

    @Test
    fun aPeerWithoutConversationManageIsRefusedBeforeTheAppServer() = runTest {
        val appServer = RecompilingAppServer(prompt = "never")
        val client = IrohConversationRecompileClient(relayed(appServer, setOf(IrohPeerCapabilities.CHAT_READ)))

        assertFailsWith<IllegalStateException> { client.recompile(request, dryRun = false) }

        assertTrue(appServer.wire.isEmpty(), "the capability gate refuses before the handler runs")
    }

    @Test
    fun aHostWithoutAnAppServerAdvertisesNothingAndTheClientDoesNotAsk() = runTest {
        val bare = AdminRpcRouter().also { ConversationRecompileHandlers.register(it, nativeClient = null) }
        assertFalse(IrohConversationRecompileRpc.CAPABILITY in IrohNodeConnection.advertisedCapabilities(bare))
        val transport = RelayTransport(hostCall(bare, manage), IrohNodeConnection.advertisedCapabilities(bare).toSet())
        val client = IrohConversationRecompileClient(transport)

        assertFalse(client.supported)
        val error = assertFailsWith<UnsupportedOperationException> { client.recompile(request, dryRun = false) }
        assertEquals(IrohConversationRecompileClient.HOST_NEEDS_UPDATE, error.message)
        assertTrue(transport.calls.isEmpty(), "an outdated host is never sent a method it would refuse")

        val response = hostCall(bare, manage)(IrohConversationRecompileRpc.METHOD, IrohConversationRecompileRpc.params(request, false))
        assertFalse(response.success)
        assertTrue(response.error.orEmpty().contains("capability_unavailable"), "${response.error}")
    }

    @Test
    fun theHostRequiresTheAgentSoAScopedPeersGuardAlwaysSeesIt() = runTest {
        val appServer = RecompilingAppServer(prompt = "p")
        val call = hostCall(router(appServer), manage)

        val response = call(IrohConversationRecompileRpc.METHOD, IrohConversationRecompileRpc.params(request.copy(agentId = ""), false))

        assertFalse(response.success)
        assertEquals("agent_id required", response.error)
        assertTrue(appServer.wire.isEmpty())
    }

    private fun router(appServer: AppServerClient) =
        AdminRpcRouter().also { ConversationRecompileHandlers.register(it, appServer) }

    /** The client's transport, connected to a host whose router relays to [appServer]. */
    private fun CoroutineScope.relayed(appServer: AppServerClient, capabilities: Set<String>): RelayTransport {
        val router = router(appServer)
        return RelayTransport(hostCall(router, capabilities), IrohNodeConnection.advertisedCapabilities(router).toSet())
    }

    private fun interface HostCall {
        suspend operator fun invoke(method: String, params: JsonObject): AppServerInboundFrame.AdminRpcResponse
    }

    /** The host end of one Iroh connection: its admin_rpc stream server with the real capability gate. */
    private fun CoroutineScope.hostCall(router: AdminRpcRouter, capabilities: Set<String>): HostCall {
        val server = AdminRpcStreamServer(
            router = router,
            authenticated = AtomicBoolean(true),
            peerSupportsFrameParts = { true },
            capabilityGate = { method ->
                val required = IrohPeerCapabilities.forAdminMethod(method)
                if (IrohPeerCapabilities.isAllowed(capabilities, required)) null else required
            },
        )
        val scope = this
        return HostCall { method, params ->
            val stream = FakeBiStream()
            val job = with(server) { scope.launchHandler(stream) }
            val request = AppServerProtocol.encodeCommand(AppServerCommand.AdminRpc(requestId = "admin-1", method = method, params = params))
            stream.recv.chunks.send(IrohFrameCodec.encodeFrame(request))
            stream.recv.chunks.send(ByteArray(0))
            job.join()
            val raw = IrohFrameCodec.Decoder().let { decoder -> stream.send.writes.flatMap { decoder.feed(it) } }.single()
            AppServerProtocol.decodeFrame(raw, AppServerChannel.Control).frame as AppServerInboundFrame.AdminRpcResponse
        }
    }

    /** The host's App Server: answers `conversation_recompile` as letta-code's listener does. */
    private class RecompilingAppServer(
        private val prompt: String? = null,
        private val refusal: String? = null,
        private val timesOut: Boolean = false,
    ) : AppServerClient {
        /** Each command as it would go on the App Server wire. */
        val wire = mutableListOf<JsonObject>()

        override suspend fun conversationRecompile(command: AppServerConversationRecompile): AppServerConversationRecompileResponse {
            wire += Json.parseToJsonElement(AppServerProtocol.encodeCommand(command)).jsonObject
            if (timesOut) throw AppServerRequestTimeoutException(command.requestId, 1L, IllegalStateException("no answer"))
            return AppServerConversationRecompileResponse(command.requestId, success = refusal == null, error = refusal, result = prompt)
        }

        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
    }

    /** The client's end of the connection: admin_rpc goes to [host]; [hostCapabilities] are what the host advertised. */
    private class RelayTransport(private val host: HostCall, override val hostCapabilities: Set<String>) : IrohAdminRpcLink {
        val calls = mutableListOf<String>()

        override suspend fun adminRpc(call: AdminRpcCall): AppServerInboundFrame.AdminRpcResponse {
            calls += call.method
            val params = call.body?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
            return host(call.method, params)
        }
    }

    private class FakeBiStream : AdminRpcBiStream {
        val recv = FakeRecvStream()
        val send = FakeSendStream()
        override fun recv(): AdminRpcRecvStream = recv
        override fun send(): AdminRpcSendStream = send
    }

    private class FakeRecvStream : AdminRpcRecvStream {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        override suspend fun read(maxBytes: UInt): ByteArray = chunks.receive()
    }

    private class FakeSendStream : AdminRpcSendStream {
        val writes = mutableListOf<ByteArray>()
        override suspend fun writeAll(bytes: ByteArray) {
            writes += bytes
        }

        override suspend fun finish() = Unit
    }
}
