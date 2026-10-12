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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-bzvro.40: the permission-mode chip of an Iroh client against a host's real admin_rpc
 * stream server (frame codec, capability gate, router) and [PermissionModeAdminHandlers], with the
 * host's controller faked. Proves the chip moves only on the host's confirmation, that another
 * client's change is adopted on open, and that a peer without conversation.manage cannot change it.
 */
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

    private fun router(controller: AppServerController) = AdminRpcRouter().also { PermissionModeAdminHandlers.register(it, controller) }

    private fun CoroutineScope.relayed(controller: ModeController, capabilities: Set<String>): RelayedPermissionModes {
        val call = hostCall(router(controller), capabilities)
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
    private class ModeController(initial: AppServerPermissionMode? = null) : AppServerController by FakeAppServerController() {
        private var mode: AppServerPermissionMode? = initial
        val changes = mutableListOf<Pair<AppServerRuntimeScope, AppServerPermissionMode>>()

        override suspend fun permissionModeOf(runtime: AppServerRuntimeScope): AppServerPermissionMode? = mode

        override suspend fun changePermissionMode(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode): Boolean {
            changes += runtime to mode
            this.mode = mode
            return true
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
