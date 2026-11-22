package com.letta.mobile.data.shellstate.appearance

import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.shellstate.ShellStatePublisher
import com.letta.mobile.data.controller.node.iroh.shellstate.ShellStateRoutes
import com.letta.mobile.data.shellstate.shared.ShellStateLink
import com.letta.mobile.data.shellstate.shared.ShellStateReply
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.appServerAdminRpcParams
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A paired device's endpoint id, as the host's authenticated connection records it. */
@JvmInline
internal value class Endpoint(val id: String)

/** One request as the host receives it: what is asked, with which params, and by whom. */
private class HostCall(val method: String, val params: JsonObject, val from: Endpoint)

/**
 * One real host: the production shell-state routes over a real state file, served through the real
 * [AdminRpcRouter] (capability matrix, allowlist, compare-and-set, push), shared by every [device] that pairs
 * with it. Nothing here accepts what the real host would refuse.
 */
internal class RouterShellStateHost(private val directory: Path = Files.createTempDirectory("shell-state-host")) {
    private val publisher = ShellStatePublisher(CoroutineScope(Dispatchers.Unconfined)) { Instant.EPOCH }
    private val router = AdminRpcRouter().also { ShellStateRoutes.forHost(directory, publisher).register(it) }
    private val devices = mutableListOf<Device>()
    private val transports = mutableListOf<PairedTransport>()

    /** Every propose call the host answered, as the keys it carried. */
    val proposals = mutableListOf<List<String>>()

    init {
        // The real endpoint broadcasts to every connection that may read; here, every device that is connected.
        publisher.attach { frame ->
            val parsed = Json.parseToJsonElement(frame).jsonObject
            devices.filter { it.connected.value }.forEach { it.pushFrames.tryEmit(parsed) }
            transports.filter { it.isUp }.forEach { it.deliver(parsed) }
        }
    }

    /** A paired device: a link to this host that the test can cut and restore. */
    fun device(endpoint: Endpoint): Device = Device(endpoint).also { devices += it }

    /**
     * A paired device as a client sees its channel transport: it answers `admin_rpc` through the real router after the
     * same per-method capability gate the host's connection applies to the peer's [role] ([IrohPeerCapabilities]), builds
     * its params the way the real transports do ([appServerAdminRpcParams]), advertises what the router advertises while
     * connected, and receives the host's `shell_state_updated` pushes as the untyped host frames the real ingestors emit.
     */
    fun transport(endpoint: Endpoint, role: Set<String> = IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE): PairedTransport =
        PairedTransport(endpoint, role).also { transports += it }

    /** The host's own entries, read through the route like any client: key to value. */
    suspend fun entries(): Map<String, kotlinx.serialization.json.JsonElement> {
        val reply = dispatch(HostCall(ShellStateWire.GET, JsonObject(emptyMap()), Endpoint("reader")))
        return reply.result!!.jsonObject.getValue("entries").jsonObject.mapValues { it.value.jsonObject.getValue("value") }
    }

    private suspend fun dispatch(call: HostCall): ShellStateReply {
        if (call.method == ShellStateWire.PROPOSE) proposals += keysOf(call.params)
        val context = AdminRpcRequestContext(authenticated = true, authenticatedEndpointId = call.from.id)
        val invocation = AdminRpcInvocation("r", call.method, call.params, context)
        val response = Json.parseToJsonElement(router.dispatch(invocation)).jsonObject
        val ok = response.getValue("success").jsonPrimitive.boolean
        return ShellStateReply(ok, response["result"], response["error"]?.jsonPrimitive?.content)
    }

    private fun keysOf(params: JsonObject): List<String> =
        (params[ShellStateWire.CHANGES] as? kotlinx.serialization.json.JsonArray)
            ?.map { it.jsonObject.getValue(ShellStateWire.KEY).jsonPrimitive.content }.orEmpty()

    /** One device's link: advertises what the real router advertises while connected, and nothing when cut. */
    inner class Device(private val endpoint: Endpoint) : ShellStateLink {
        val connected = MutableStateFlow(true)
        val pushFrames = MutableSharedFlow<JsonObject>(extraBufferCapacity = 16)
        private var epoch = 1

        override val hostCapabilities: Set<String> get() = if (connected.value) router.featureCapabilities else emptySet()
        override val currentConnection: String? get() = "session#$epoch".takeIf { connected.value }
        override val connection: Flow<String?> get() = connected.map { up -> "session#$epoch".takeIf { up } }
        override val pushes: Flow<JsonObject> get() = pushFrames

        fun cut() {
            connected.value = false
        }

        fun restore() {
            epoch += 1
            connected.value = true
        }

        override suspend fun call(method: String, params: JsonObject): ShellStateReply {
            if (!connected.value) error("link is down")
            return dispatch(HostCall(method, params, endpoint))
        }
    }

    /** One paired device's channel transport to this host; [cut] and [restore] drop and redial it (a new connection epoch). */
    inner class PairedTransport(private val endpoint: Endpoint, private val role: Set<String>) : NoOpChannelTransport() {
        private var epoch = 1L
        private val frames = kotlinx.coroutines.flow.MutableSharedFlow<ServerFrame>(extraBufferCapacity = 16)
        private val published = MutableStateFlow<ChannelTransportState>(up())
        override val state: StateFlow<ChannelTransportState> = published
        override val events: SharedFlow<ServerFrame> = frames
        override val hostCapabilities: Set<String> get() = if (isUp) router.featureCapabilities else emptySet()

        val isUp: Boolean get() = published.value is ChannelTransportState.Connected

        private fun up() = ChannelTransportState.Connected(
            serverId = "iroh-app-server", sessionId = "host-session", deviceId = endpoint.id, connectionEpoch = epoch,
        )

        /** How many collectors this transport's events have: a frame delivered before there is one is lost, as on a real transport. */
        val listeners: StateFlow<Int> get() = frames.subscriptionCount

        /** Delivers a `shell_state_updated` [frame] to this transport as the host would push it. */
        fun deliver(frame: JsonObject) {
            frames.tryEmit(ServerFrame.Unknown(type = frame.getValue("type").jsonPrimitive.content, raw = frame))
        }

        fun cut() {
            published.value = DOWN
        }

        fun restore() {
            epoch += 1
            published.value = up()
        }

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            check(isUp) { "transport is down" }
            val required = IrohPeerCapabilities.forAdminMethod(method)
            if (!IrohPeerCapabilities.isAllowed(role, required)) {
                return AppServerInboundFrame.AdminRpcResponse(requestId = "r", success = false, error = "forbidden")
            }
            val reply = dispatch(HostCall(method, appServerAdminRpcParams(path, body), endpoint))
            return AppServerInboundFrame.AdminRpcResponse(
                requestId = "r", success = reply.success, result = reply.result, error = reply.error,
            )
        }
    }
}

private val DOWN = ChannelTransportState.Disconnected(code = 1006, reason = "cut")
