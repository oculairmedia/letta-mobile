package com.letta.mobile.data.plugin.wire

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginSecrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A host that records what plugins ask of it, answering deterministically (`el-1`, `el-2`, … and real asset hashes). */
class RecordingHost : LcpHostHandlers {
    val emits = mutableListOf<LcpEmit>()
    val assets = mutableListOf<LcpUploadedAsset>()
    /** Every log line, in order, as the host kept it. */
    val logged = Channel<LogParams>(Channel.UNLIMITED)
    private var placed = 0

    override suspend fun emit(emit: LcpEmit): EmitReceipt {
        emits += emit
        return EmitReceipt(placed = emit.place.map { "el-${++placed}" })
    }

    override suspend fun storeAsset(asset: LcpUploadedAsset): String {
        assets += asset
        return "sha256:${asset.sha256}"
    }

    override suspend fun readElements(query: LcpElementQuery): List<JsonObject> = listOf(
        buildJsonObject {
            put("id", "el-1")
            put("type", "ext:letta.example/widget")
        },
    )

    override suspend fun log(line: LogParams) {
        logged.send(line)
    }
}

/** The plugin side of the tests: what it answers to each host method. */
class TestPlugin {
    var spoken: List<Int> = listOf(LcpWire.CONTRACT_VERSION)
    var invoke: suspend (InvokeParams) -> InvokeResult = { InvokeResult("ran ${it.action}") }
    var health: suspend () -> HealthResult = { HealthResult(LcpHealthStatus.OK) }
    val events = mutableListOf<ElementEventParams>()
    val settings = mutableListOf<JsonObject>()
    var deactivated = false

    fun serveOn(session: LcpPluginSession) {
        val peer = session.peer
        peer.serve(LcpCalls.INITIALIZE) {
            InitializeResult(ok = true, LcpHandshake.choose(it.contractVersions, spoken), LcpPluginInfo("Example", "1.2.0"))
        }
        peer.serve(LcpCalls.ACTIVATE) { LcpEmpty() }
        peer.serve(LcpCalls.HEALTH) { health() }
        peer.serve(LcpCalls.DEACTIVATE) { LcpEmpty().also { deactivated = true } }
        peer.serve(LcpCalls.INVOKE) { invoke(it) }
        peer.serve(LcpCalls.ELEMENT_EVENT) { events += it }
        peer.serve(LcpCalls.SETTINGS_CHANGED) { settings += it.settings }
    }
}

/** A host session and a plugin session joined by an in-memory transport. */
class LcpTestRig(
    scope: CoroutineScope,
    capabilities: Set<PluginCapability> = PluginCapability.entries.toSet(),
    secrets: PluginSecrets = PluginSecrets(),
) {
    val host = RecordingHost()
    val plugin = TestPlugin()
    private val ends = LoopbackLcpTransport.pair()
    val hostSession = LcpHostSession(ends.first, LcpHostBinding(capabilities, secrets.scrubber(), host), scope)
    val pluginSession = LcpPluginSession(ends.second, scope).also {
        plugin.serveOn(it)
        it.start()
    }

    suspend fun initialize(settings: JsonObject = JsonObject(emptyMap())): InitializeResult = hostSession.initialize(initializeParams(settings))

    /** Initialized and activated. */
    suspend fun ready(): LcpTestRig = also {
        initialize()
        hostSession.activate()
    }

    companion object {
        fun initializeParams(settings: JsonObject = JsonObject(emptyMap()), offered: List<Int> = LcpWire.SUPPORTED_CONTRACT_VERSIONS) = InitializeParams(
            contractVersion = offered.max(),
            contractVersions = offered,
            pluginId = "letta.example",
            settings = settings,
            hostInfo = LcpHostInfo("letta-host", "test"),
        )

        fun agentInvoke(action: String, input: JsonObject = JsonObject(emptyMap())) =
            InvokeParams(action, input, LcpActionContext(LcpOrigin.Agent("agent-1", "conv-1", "call-1")))
    }
}
