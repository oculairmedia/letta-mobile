package com.letta.mobile.data.shellstate.appearance

import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.shellstate.ShellStatePublisher
import com.letta.mobile.data.controller.node.iroh.shellstate.ShellStateRoutes
import com.letta.mobile.data.shellstate.shared.ShellStateLink
import com.letta.mobile.data.shellstate.shared.ShellStateReply
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One real host: the production shell-state routes over a real state file, served through the real
 * [AdminRpcRouter] (capability matrix, allowlist, compare-and-set, push), shared by every [device] that pairs
 * with it. Nothing here accepts what the real host would refuse.
 */
internal class RouterShellStateHost(private val directory: Path = Files.createTempDirectory("shell-state-host")) {
    private val publisher = ShellStatePublisher(CoroutineScope(Dispatchers.Unconfined)) { Instant.EPOCH }
    private val router = AdminRpcRouter().also { ShellStateRoutes.forHost(directory, publisher).register(it) }
    private val devices = mutableListOf<Device>()

    /** Every propose call the host answered, as the keys it carried. */
    val proposals = mutableListOf<List<String>>()

    init {
        // The real endpoint broadcasts to every connection that may read; here, every device that is connected.
        publisher.attach { frame ->
            val parsed = Json.parseToJsonElement(frame).jsonObject
            devices.filter { it.connected.value }.forEach { it.pushFrames.tryEmit(parsed) }
        }
    }

    /** A paired device: a link to this host that the test can cut and restore. */
    fun device(endpoint: String): Device = Device(endpoint).also { devices += it }

    /** The host's own entries, read through the route like any client: key to value. */
    suspend fun entries(): Map<String, kotlinx.serialization.json.JsonElement> {
        val reply = dispatch(ShellStateWire.GET, JsonObject(emptyMap()), "reader")
        return reply.result!!.jsonObject.getValue("entries").jsonObject.mapValues { it.value.jsonObject.getValue("value") }
    }

    private suspend fun dispatch(method: String, params: JsonObject, endpoint: String): ShellStateReply {
        if (method == ShellStateWire.PROPOSE) proposals += keysOf(params)
        val context = AdminRpcRequestContext(authenticated = true, authenticatedEndpointId = endpoint)
        val response = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("r", method, params, context))).jsonObject
        val ok = response.getValue("success").jsonPrimitive.boolean
        return ShellStateReply(ok, response["result"], response["error"]?.jsonPrimitive?.content)
    }

    private fun keysOf(params: JsonObject): List<String> =
        (params[ShellStateWire.CHANGES] as? kotlinx.serialization.json.JsonArray)
            ?.map { it.jsonObject.getValue(ShellStateWire.KEY).jsonPrimitive.content }.orEmpty()

    /** One device's link: advertises what the real router advertises while connected, and nothing when cut. */
    inner class Device(private val endpoint: String) : ShellStateLink {
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
            return dispatch(method, params, endpoint)
        }
    }
}
