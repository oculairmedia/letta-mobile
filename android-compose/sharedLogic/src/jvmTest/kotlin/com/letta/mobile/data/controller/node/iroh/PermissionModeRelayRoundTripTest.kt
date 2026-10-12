package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.chat.runtime.MapSettingsStore
import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.node.FakeAppServerController
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelControlException
import com.letta.mobile.data.runtime.PermissionModeRpc
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.runtime.RelayedPermissionModes
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode.AcceptEdits
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode.Standard
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * letta-mobile-bzvro.40: the permission-mode chip of an Iroh client against a host's real admin_rpc
 * stream server (frame codec, capability gate, router) and [PermissionModeAdminHandlers], with the
 * host's controller faked. Proves the chip moves only on the host's confirmation, that another
 * client's change is adopted on open, and that a peer without conversation.manage cannot change it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionModeRelayRoundTripTest {
    private val runtime = AppServerRuntimeScope("agent-1", "conv-1")

    @Test
    fun aChangeReachesTheHostsControllerAndConfirmsTheChip() = runTest {
        val controller = ModeController()
        val modes = relayed(controller, setOf(IrohPeerCapabilities.CONVERSATION_MANAGE, IrohPeerCapabilities.CHAT_READ))

        assertTrue(modes.set(runtime, Standard))

        assertEquals(listOf(runtime to Standard), controller.changes)
        assertEquals(Standard, modes.observe(runtime).first().mode)
    }

    @Test
    fun openingAConversationAdoptsTheHostsMode() = runTest {
        val controller = ModeController(initial = AcceptEdits)
        val modes = relayed(controller, setOf(IrohPeerCapabilities.CHAT_READ))

        modes.sync(runtime)

        assertEquals(AcceptEdits, modes.observe(runtime).first().mode)
        assertEquals(emptyList(), controller.changes)
    }

    @Test
    fun aReadOnlyPeerCannotChangeTheMode() = runTest {
        val controller = ModeController(initial = AppServerPermissionMode.Unrestricted)
        val modes = relayed(controller, setOf(IrohPeerCapabilities.CHAT_READ))
        modes.sync(runtime)

        assertFalse(modes.set(runtime, Standard))

        assertEquals(emptyList(), controller.changes, "the capability gate refuses before the handler runs")
        assertEquals(Standard, modes.observe(runtime).first().unconfirmed)
    }

    @Test
    fun anUnknownModeIsRefusedWithoutReachingTheController() = runTest {
        val controller = ModeController()
        val call = hostCall(router(controller), setOf(IrohPeerCapabilities.ADMIN_FULL))

        val response = call(
            PermissionModeRpc.SET,
            kotlinx.serialization.json.buildJsonObject {
                put(PermissionModeRpc.AGENT_ID, kotlinx.serialization.json.JsonPrimitive("agent-1"))
                put(PermissionModeRpc.CONVERSATION_ID, kotlinx.serialization.json.JsonPrimitive("conv-1"))
                put(PermissionModeRpc.MODE, kotlinx.serialization.json.JsonPrimitive("yolo"))
            },
        )

        assertFalse(response.success)
        assertEquals(emptyList(), controller.changes)
    }

    @Test
    fun aHostWithoutAControllerAdvertisesNothingAndFailsClosed() = runTest {
        val bare = AdminRpcRouter().also { PermissionModeAdminHandlers.register(it, null) }
        assertFalse(PermissionModeRpc.CAPABILITY in IrohNodeConnection.advertisedCapabilities(bare))
        assertTrue(PermissionModeRpc.CAPABILITY in IrohNodeConnection.advertisedCapabilities(router(ModeController())))

        val response = hostCall(bare, setOf(IrohPeerCapabilities.ADMIN_FULL))(PermissionModeRpc.GET, PermissionModeRpc.params("a", "c"))
        assertFalse(response.success)
        assertNull(PermissionModeRpc.modeOf(response.result))
    }

    /** One connection viewing a conversation: what the host wrote to it, decoded as the client does. */
    private class Viewer(val conversationId: String) {
        val raw = java.util.concurrent.CopyOnWriteArrayList<String>()
        val frames = MutableSharedFlow<ServerFrame>(replay = 16)

        fun write(frame: String) {
            raw += frame
            frames.tryEmit(Json { ignoreUnknownKeys = true }.decodeFromString(ServerFrameSerializer, frame))
        }
    }

    /** letta-mobile-a4tm7: the endpoint's fan-out, as `viewersFor(conversationId)` does it. */
    private fun PermissionModePublisher.over(viewers: List<Viewer>) = also { publisher ->
        publisher.attach { conversationId, frame ->
            viewers.filter { it.conversationId == conversationId }.onEach { it.write(frame) }.size
        }
    }

    @Test
    fun aConfirmedChangeIsPushedOnceToEachViewerAndMovesTheOtherClientsChip() = runTest {
        val controller = ModeController(initial = AppServerPermissionMode.Unrestricted)
        val phone = Viewer("conv-1")
        val desktop = Viewer("conv-1")
        val elsewhere = Viewer("conv-2")
        val publisher = PermissionModePublisher(backgroundScope) { Instant.EPOCH }.over(listOf(phone, desktop, elsewhere))
        val caps = setOf(IrohPeerCapabilities.CONVERSATION_MANAGE, IrohPeerCapabilities.CHAT_READ)
        val phoneModes = relayed(controller, caps, publisher).also { it.bind(backgroundScope, phone.frames) }
        val desktopModes = relayed(controller, caps, publisher).also { it.bind(backgroundScope, desktop.frames) }
        phoneModes.sync(runtime)
        desktopModes.sync(runtime)

        assertTrue(phoneModes.set(runtime, Standard))
        runCurrent()

        assertEquals(Standard, desktopModes.observe(runtime).first().mode, "the desktop's chip follows without reopening")
        assertEquals(Standard, phoneModes.observe(runtime).first().mode)
        assertEquals(1, desktop.raw.size)
        assertEquals(1, phone.raw.size, "the sender gets the push once, beside its own confirmed result")
        assertEquals(0, elsewhere.raw.size, "only the conversation's viewers")
        assertEquals(listOf(runtime to Standard), controller.changes, "the push is never sent back to the host")
        val push = Json { ignoreUnknownKeys = true }.decodeFromString(ServerFrameSerializer, desktop.raw.single())
        assertTrue(push is ServerFrame.PermissionModeUpdated)
        assertEquals(listOf("agent-1", "conv-1", "standard"), listOf(push.agentId, push.conversationId, push.mode))
        assertEquals(Instant.EPOCH.toString(), push.at)
    }

    @Test
    fun anUnconfirmedChangeIsNotPushed() = runTest {
        val controller = ModeController(initial = AppServerPermissionMode.Unrestricted, confirm = false)
        val desktop = Viewer("conv-1")
        val publisher = PermissionModePublisher(backgroundScope) { Instant.EPOCH }.over(listOf(desktop))
        val phoneModes = relayed(controller, setOf(IrohPeerCapabilities.CONVERSATION_MANAGE, IrohPeerCapabilities.CHAT_READ), publisher)
        phoneModes.sync(runtime)

        assertFalse(phoneModes.set(runtime, Standard))
        runCurrent()

        assertEquals(emptyList(), desktop.raw.toList())
    }

    private fun router(controller: AppServerController, publisher: PermissionModePublisher? = null) =
        AdminRpcRouter().also { PermissionModeAdminHandlers.register(it, controller, publisher) }

    private fun CoroutineScope.relayed(
        controller: ModeController,
        capabilities: Set<String>,
        publisher: PermissionModePublisher? = null,
    ): RelayedPermissionModes {
        val call = hostCall(router(controller, publisher), capabilities)
        val rpc = AdminRpcInvoker { method, params ->
            val response = call(method, params)
            if (!response.success) throw ModelControlException(response.error ?: "$method failed")
            response.result
        }
        return RelayedPermissionModes(PermissionModeSettings(MapSettingsStore()).modes, rpc) { setOf(PermissionModeRpc.CAPABILITY) }
    }

    private fun interface HostCall {
        suspend operator fun invoke(method: String, params: kotlinx.serialization.json.JsonObject): AppServerInboundFrame.AdminRpcResponse
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

    /** The host's controller: confirms every change, as an App Server echoing `update_device_status` does. */
    private class ModeController(
        initial: AppServerPermissionMode? = null,
        private val confirm: Boolean = true,
    ) : AppServerController by FakeAppServerController() {
        private var mode: AppServerPermissionMode? = initial
        val changes = mutableListOf<Pair<AppServerRuntimeScope, AppServerPermissionMode>>()

        override suspend fun permissionModeOf(runtime: AppServerRuntimeScope): AppServerPermissionMode? = mode

        override suspend fun changePermissionMode(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode): Boolean {
            changes += runtime to mode
            if (confirm) this.mode = mode
            return confirm
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
