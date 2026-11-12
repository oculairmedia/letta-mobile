package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.CanvasAcl
import com.letta.mobile.data.canvas.CanvasRelayProtocol
import com.letta.mobile.data.canvas.HostCanvasDirectory
import com.letta.mobile.data.canvas.HostCanvasEntry
import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
import com.letta.mobile.data.canvas.mount.AuthorizedCanvasMounts
import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.canvas.mount.FileCanvasMountStore
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.ConversationViewers
import com.letta.mobile.data.controller.node.iroh.ViewerHandle
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.hoststate.DurableHostFileHooks
import com.letta.mobile.data.hoststate.HostDirectoryDurability
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.HostId
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val TEST_HOST = HostId("host-under-test")
internal val AGENT_A = AgentId("agent-a")
internal val AGENT_B = AgentId("agent-b")
internal val CONV_A = ConversationId("conv-a")
internal val CONV_A2 = ConversationId("conv-a-second")
internal val CONV_B = ConversationId("conv-b")

/** The fixed request id: identical requests must produce byte-identical responses. */
internal const val REQUEST_ID = "req-1"

/** A verified connection: endpoint identity plus the scopes the connection itself established. */
internal data class Peer(
    val endpoint: String,
    val agents: Set<String>? = null,
    val conversations: Set<String>? = null,
) {
    fun context(): AdminRpcRequestContext = AdminRpcRequestContext(
        authenticated = true,
        authorizedConversationIds = conversations,
        authorizedAgentIds = agents,
        authenticatedEndpointId = endpoint,
    )

    companion object {
        /** A 64-hex endpoint made of one repeated digit. */
        fun of(digit: Char): Peer = Peer(digit.toString().repeat(ENDPOINT_HEX_CHARS))
    }
}

private const val ENDPOINT_HEX_CHARS = 64

internal val PEER = Peer.of('a')
internal val OTHER_PEER = Peer.of('b')

/** The host's conversation/agent records, scriptable to fail or hang. */
internal class FakeDirectory : CanvasMountDirectory {
    private val owners = ConcurrentHashMap(mapOf(CONV_A to AGENT_A, CONV_A2 to AGENT_A, CONV_B to AGENT_B))

    @Volatile var failure: Throwable? = null

    @Volatile var hang: Boolean = false

    override suspend fun ownerOf(conversation: ConversationId): AgentId? {
        script()
        return owners[conversation]
    }

    override suspend fun hasAgent(agent: AgentId): Boolean {
        script()
        return agent == AGENT_A || agent == AGENT_B
    }

    private suspend fun script() {
        if (hang) awaitCancellation()
        failure?.let { throw it }
    }
}

/** The host's agent canvas directory, scriptable to fail or hang from its [failFromCall]th / [hangFromCall]th lookup. */
internal class ScriptedCanvases(private val delegate: HostCanvasDirectory = InMemoryHostCanvasDirectory()) :
    HostCanvasDirectory by delegate {
    private val calls = AtomicInteger()

    @Volatile var failure: Throwable? = null

    @Volatile var failFromCall: Int = 1

    @Volatile var hangFromCall: Int = Int.MAX_VALUE

    override suspend fun get(canvasId: CanvasId): HostCanvasEntry? {
        val call = calls.incrementAndGet()
        if (call >= hangFromCall) awaitCancellation()
        failure?.takeIf { call >= failFromCall }?.let { throw it }
        return delegate.get(canvasId)
    }
}

/** A conversation viewer that records the frames it is sent; [context] is what its connection established. */
internal class FakeViewer(private val context: AdminRpcRequestContext?) : ViewerHandle {
    override val connectionId: String = "viewer-${System.identityHashCode(this)}"
    val frames: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun writeFrame(frame: String): Boolean = frames.add(frame)

    override fun adminContextFor(method: String): AdminRpcRequestContext? = context
}

/** Host records shared by every harness on one root, as two host processes share one host's files. */
internal class HarnessRecords {
    val canvases = ScriptedCanvases()
    val relay = InMemoryCanvasRelayStore()
    val directory = FakeDirectory()
    val viewers: MutableMap<ConversationId, Set<ViewerHandle>> = ConcurrentHashMap()

    /** Registers [canvas] on the host: in the agent directory owned by [owner], or relay-only (no known owner). */
    suspend fun canvas(canvas: CanvasId, owner: AgentId?) {
        if (owner == null) {
            relay.bind(CanvasRelayProtocol.canvasTopic(canvas), canvas)
            return
        }
        val acl = CanvasAcl(ownerUserId = "local-user", writerAgentIds = setOf(owner.value), readerAgentIds = setOf(owner.value))
        canvases.putIfAbsent(HostCanvasEntry(canvas.value, CanvasRelayProtocol.canvasTopic(canvas), "Board", acl = acl))
    }
}

/** Calls of every harness run here, as a host's own scope would hold them. */
internal val TEST_CALL_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Default)

internal fun testDeadline(limit: Duration, grace: Duration) = CanvasMountDeadline(limit, grace, TEST_CALL_SCOPE)

/** Optional knobs of one [MountHarness]. */
internal class HarnessOptions(
    val policy: IrohAuthPolicy = IrohAuthPolicy.PairedPeersOnly,
    val hooks: DurableHostFileHooks = DurableHostFileHooks.NONE,
    val deadline: CanvasMountDeadline = testDeadline(10.seconds, 2.seconds),
)

/**
 * The real routes on a real [AdminRpcRouter] over a real file mount store in a temp root and the host's canvas
 * records ([HarnessRecords]). Every response is kept in [responses] so tests can scan them.
 */
internal class MountHarness(
    val root: Path = Files.createTempDirectory("canvas-mount-routes"),
    private val options: HarnessOptions = HarnessOptions(),
    val records: HarnessRecords = HarnessRecords(),
) {
    val directory: FakeDirectory get() = records.directory
    val canvases: ScriptedCanvases get() = records.canvases
    val router = AdminRpcRouter()
    val responses: MutableList<String> = Collections.synchronizedList(mutableListOf())

    init {
        val sources = CanvasMountSources(
            CanvasMountCatalog(records.canvases, records.relay),
            records.directory,
            ConversationViewers { conversation -> records.viewers[conversation].orEmpty() },
        )
        val host = CanvasMountHost(
            mounts = { authority -> AuthorizedCanvasMounts(store(), authority) },
            sources = sources,
            bounds = CanvasMountBounds(CanvasMountDurability.ofHost(), options.deadline, TEST_CALL_SCOPE),
        )
        CanvasMountRoutes(options.policy, host).register(router)
    }

    /** A raw store instance on this root, as a separate host process would open it. */
    fun store(): FileCanvasMountStore = FileCanvasMountStore(root, TEST_HOST, HostDirectoryDurability::syncMount, options.hooks)

    suspend fun canvas(canvas: CanvasId, owner: AgentId?) = records.canvas(canvas, owner)

    suspend fun call(route: CanvasMountRoute, params: JsonObject?, peer: Peer): Reply = call(route.method, params, peer.context())

    suspend fun call(method: String, params: JsonObject?, context: AdminRpcRequestContext): Reply {
        val raw = router.dispatch(AdminRpcInvocation(REQUEST_ID, method, params, context))
        responses += raw
        return Reply(raw)
    }
}

/** One decoded admin RPC response; [raw] is the exact encoded string. */
internal class Reply(val raw: String) {
    private val envelope = Json.parseToJsonElement(raw).jsonObject
    private val result = envelope["result"] as? JsonObject

    val success: Boolean get() = envelope.getValue("success").jsonPrimitive.booleanOrNull == true
    val error: String? get() = envelope["error"]?.jsonPrimitive?.contentOrNull
    val status: String? get() = result?.field(CanvasMountWire.STATUS)
    val canvas: String? get() = result?.field(CanvasMountWire.CANVAS_ID)
    val reason: String? get() = result?.field(CanvasMountWire.REASON)
    val source: String? get() = result?.field(CanvasMountWire.SOURCE)
    val durability: String? get() = result?.field(CanvasMountWire.DURABILITY)
    val won: Boolean? get() = result?.get(CanvasMountWire.WON)?.jsonPrimitive?.booleanOrNull

    override fun toString(): String = raw

    private fun JsonObject.field(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

internal fun bindingParams(agent: AgentId, conversation: ConversationId, canvas: CanvasId? = null): JsonObject = buildJsonObject {
    put(CanvasMountWire.AGENT_ID, agent.value)
    put(CanvasMountWire.CONVERSATION_ID, conversation.value)
    canvas?.let { put(CanvasMountWire.CANVAS_ID, it.value) }
}

internal fun defaultParams(agent: AgentId, canvas: CanvasId): JsonObject = buildJsonObject {
    put(CanvasMountWire.AGENT_ID, agent.value)
    put(CanvasMountWire.CANVAS_ID, canvas.value)
}

/** The params [route] needs to address agent A's [conversation] with [canvas]. */
internal fun paramsFor(route: CanvasMountRoute, conversation: ConversationId, canvas: CanvasId): JsonObject = when (route) {
    CanvasMountRoute.RESOLVE -> bindingParams(AGENT_A, conversation)
    CanvasMountRoute.SET_DEFAULT -> defaultParams(AGENT_A, canvas)
    else -> bindingParams(AGENT_A, conversation, canvas)
}

/** The exact denial every route answers, whichever check failed. */
internal val DENIED_RESPONSE = """{"type":"admin_rpc_response","request_id":"$REQUEST_ID","success":false,"error":"canvas.mount.denied"}"""
