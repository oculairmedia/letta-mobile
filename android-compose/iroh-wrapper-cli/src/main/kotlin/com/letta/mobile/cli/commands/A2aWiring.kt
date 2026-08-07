package com.letta.mobile.cli.commands

import com.letta.mobile.data.controller.node.iroh.FileIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationClass
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInputMessage
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.FileIrohAgentAddressStore
import com.letta.mobile.data.transport.iroh.IrohAgentAddress
import com.letta.mobile.data.transport.iroh.IrohAgentAddressResolver
import com.letta.mobile.data.transport.iroh.IrohAgentIdentity
import com.letta.mobile.data.transport.iroh.IrohAgentMessage
import com.letta.mobile.data.transport.iroh.IrohAgentMessageReceiver
import com.letta.mobile.util.Telemetry
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import computer.iroh.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * letta-mobile-bn008.6: production wiring for the a2a (direct agent-to-agent)
 * receiver on the live wrapper.
 *
 * Layer-1: a SECOND [Endpoint] bound to the a2a ALPN, co-located with the
 * wrapper's app-server endpoint. Re-uses the wrapper's stable host identity
 * (the same Ed25519 secret-key file) so the node id matches across both
 * endpoints — demux is by `IrohAgentMessage.toAgentId`, not by node id. This
 * avoids widening blast radius (no router changes, no dual-ALPN accept-loop
 * discrimination) at the cost of one extra bound UDP socket.
 *
 * Build via [buildA2aWiring] (headless / testable). The receiver is started
 * by [A2aWiring.start].
 */
class A2aWiring internal constructor(
    val endpoint: Endpoint,
    val receiver: IrohAgentMessageReceiver,
    val router: IrohAgentMessageRouter,
    val addressStore: FileIrohAgentAddressStore,
    val identityDir: File,
) {
    /** Start the receiver's accept loop on [scope]; returns the accept-loop Job. */
    fun start(scope: CoroutineScope): Job = receiver.start(scope)

    /** The a2a node id (hex, 64 chars). Equal to the app-server node id when the
     *  same secret-key file is shared between both endpoints. */
    val nodeIdHex: String get() {
        val id = endpoint.addr().id()
        return runBlocking { id.use { it.toBytes().joinToString("") { b -> "%02x".format(b) } } }
    }

    /** Best-effort shutdown: closes the underlying endpoint. */
    fun close() {
        runCatching { endpoint.close() }
    }
}

/**
 * Configuration for [buildA2aWiring]. Knobs are the ones the CLI exposes
 * (see `AppServerServeIrohCommand` --a2a-* options).
 */
data class A2aWiringConfig(
    /** UDP port for the a2a endpoint. 0 = OS-assigned random. */
    val port: Int,
    /** File holding the wrapper's 32-byte Iroh secret key (mode 0600). */
    val secretKeyPath: String?,
    /** Per-agent identity directory (mode 0700). Created on demand. */
    val identityDir: File,
    /** Address-book kv file (parent dir must exist with mode 0700). */
    val addressBook: File,
    /**
     * Comma-separated agentIds to publish on bind. Each one writes its entry
     * into [addressBook] pointing at THIS node id + direct addrs.
     */
    val publishAgents: List<String>,
)

/**
 * Build the production a2a wiring. Returns an [A2aWiring] the caller starts on
 * its scope. The receiver's `onDeliver` lands a body as a user message via
 * the supplied [client] (the same one the wrapper uses for everything else).
 *
 * **CreateAndDeliver** — the router returns CreateAndDeliver when the target
 * agent has NO interactive conversation. Implementing this requires the full
 * `runtime_start` + `create_conversation` orchestration, which the wrapper
 * does NOT have a clean layer-1 path for. Per the dispatch brief, this is
 * downgraded to `Dropped("no_conversation_create_path")` at the wrapper layer
 * with the gap flagged on the bead (non-blocking finding).
 */
suspend fun buildA2aWiring(
    config: A2aWiringConfig,
    client: AppServerClient?,
    /**
     * Optional letta-code local-backend root. When non-null, conversations
     * for the target agentId are read from `<root>/conversations/...` and
     * busy state from `<root>/runs/...`. Null = no candidates supplied
     * (the router falls through to CreateAndDeliver, which the wrapper
     * downgrades to Dropped).
     */
    localBackendDir: File?,
    /**
     * Own agentId for the ping-pong guard. The router drops inbound messages
     * from this id (or its siblings). The wrapper's own identity = the host's
     * primary agent; for layer-1 we leave it unset (loop-safety still active
     * via the per-sender cap + seen-msgId dedupe).
     */
    ownAgentId: String = "",
): A2aWiring {
    require(config.publishAgents.isNotEmpty() || config.addressBook.exists()) {
        "publishAgents is empty AND addressBook ${config.addressBook} does not exist; nothing to bind"
    }
    val store = FileIrohAgentAddressStore(config.addressBook)
    val secretKeyBytes = loadSecretKey(config.secretKeyPath)  // suspend (FileIrohSecretKeyStore.loadOrCreate)
    val bindAddr = "0.0.0.0:${config.port}"
    val endpoint = Endpoint.bind(
        EndpointOptions(
            bindAddr = bindAddr,
            secretKey = secretKeyBytes,
            alpns = listOf(IrohAgentMessage.ALPN),
            relayMode = RelayMode.defaultMode(),
        ),
    ).also { runCatching { it.online() } }

    publishLocalAgents(config, endpoint)

    val conversationsFor: suspend (String) -> List<IrohAgentMessageRouter.ConversationState> = { agentId ->
        if (client == null || localBackendDir == null) emptyList()
        else listConversationsForAgent(localBackendDir, agentId)
    }

    val onDeliver: suspend (IrohAgentMessage, IrohAgentMessageRouter.RoutingDecision) -> Unit = { message, decision ->
        handleDecision(client, message, decision)
    }

    val router = IrohAgentMessageRouter(
        ownAgentId = ownAgentId,
    )

    val receiver = IrohAgentMessageReceiver(
        endpoint = endpoint,
        router = router,
        conversationsFor = conversationsFor,
        onDeliver = onDeliver,
    )

    return A2aWiring(
        endpoint = endpoint,
        receiver = receiver,
        router = router,
        addressStore = store,
        identityDir = config.identityDir,
    )
}

/**
 * Publish the given agentIds into the kv file pointed at [config.addressBook]
 * using the host's own node id + dialable direct addrs. Mirrors the
 * `IrohAgentAddressResolver.publish(...)` write pattern so the wire format
 * matches what the seed script and the sender expect.
 *
 * Returns the list of agents actually published (skips empty ids).
 */
fun publishLocalAgents(
    config: A2aWiringConfig,
    endpoint: Endpoint,
): List<String> {
    val store = FileIrohAgentAddressStore(config.addressBook)
    val resolver = IrohAgentAddressResolver(store)
    val addr = endpoint.addr()
    val nodeHex = runBlocking { addr.id().use { id -> id.toBytes().joinToString("") { b -> "%02x".format(b) } } }
    val direct = addr.directAddresses()
    val published = mutableListOf<String>()
    config.publishAgents.forEach { agentId ->
        if (agentId.isBlank()) return@forEach
        // Touch the per-agent identity dir so a future dial can load-or-create
        // its IrohAgentIdentity file (the seed only writes the README + dir).
        runCatching {
            IrohAgentIdentity.loadOrCreate(agentId, config.identityDir)
        }
        resolver.publish(IrohAgentAddress(agentId, nodeHex, direct))
        published += agentId
        Telemetry.event(
            "A2aHost",
            "agent.published",
            "agentId" to agentId,
            "nodeId" to nodeHex,
            "directAddrs" to direct.joinToString(","),
        )
    }
    return published
}

/**
 * Resolve a list of interactive conversations for the target agent. The
 * `busy` flag is computed from the local `runs/` store: a conversation is
 * busy iff there's at least one run with status="running" referencing it.
 *
 * Best-effort: every read is wrapped in runCatching so a missing dir or
 * corrupt record yields an empty list (router falls through to
 * CreateAndDeliver, which the wrapper downgrades).
 */
private fun listConversationsForAgent(
    localBackendDir: File,
    agentId: String,
): List<IrohAgentMessageRouter.ConversationState> = runCatching {
    val store = LocalBackendAdminStore(localBackendDir)
    val convsArray = store.listConversationsProjected(
        agentId = agentId,
        archiveStatus = "active",
        limit = 200,
        offset = 0,
    ) ?: return@runCatching emptyList<IrohAgentMessageRouter.ConversationState>()
    val activeConvs = store.activeConversationIds(agentId)

    convsArray.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val conv = decodeConversation(obj, agentId) ?: return@mapNotNull null
        IrohAgentMessageRouter.ConversationState(conv, conv.id.value in activeConvs)
    }.sortedByDescending { it.conversation.lastMessageAt ?: it.conversation.updatedAt ?: it.conversation.createdAt ?: "" }
}.getOrElse { emptyList() }

private fun JsonPrimitive.contentIfString(): String? = if (isString) content else null

private fun JsonElement?.stringOrNullSafe(): String? = (this as? JsonPrimitive)?.let { if (it.isString) it.content else null }

private fun decodeConversation(obj: JsonObject, fallbackAgentId: String): Conversation? = runCatching {
    val id = obj["id"]?.let { (it as? JsonPrimitive)?.contentIfString() } ?: return@runCatching null
    val agentIdStr = obj["agent_id"]?.let { (it as? JsonPrimitive)?.contentIfString() } ?: fallbackAgentId
    val convClass = when (obj["conversation_class"]?.let { (it as? JsonPrimitive)?.contentIfString() }) {
        "autonomous" -> ConversationClass.AUTONOMOUS
        else -> ConversationClass.INTERACTIVE
    }
    Conversation(
        id = ConversationId(id),
        agentId = AgentId(agentIdStr),
        summary = obj["summary"]?.stringOrNullSafe(),
        createdAt = obj["created_at"]?.stringOrNullSafe(),
        updatedAt = obj["updated_at"]?.stringOrNullSafe(),
        lastMessageAt = obj["last_message_at"]?.stringOrNullSafe(),
        conversationClass = convClass,
    )
}.getOrNull()

/**
 * Land one inbound a2a message as a user message on the chosen conversation.
 * Telemetry signals are emitted here (NOT in the receiver) so the wrapper
 * controls what gets logged — and what's NOT (body text, msgId payload,
 * agent secrets are NEVER logged).
 */
private suspend fun handleDecision(
    client: AppServerClient?,
    message: IrohAgentMessage,
    decision: IrohAgentMessageRouter.RoutingDecision,
) {
    when (decision) {
        is IrohAgentMessageRouter.RoutingDecision.Deliver -> {
            Telemetry.event(
                "A2aHost", "a2a.deliver",
                "fromAgentId" to message.fromAgentId,
                "toAgentId" to message.toAgentId,
                "conversationId" to decision.conversationId,
            )
            inputOnConversation(client, message, decision.conversationId)
        }
        is IrohAgentMessageRouter.RoutingDecision.Queue -> {
            // Same as Deliver but no second turn: the app-server already serializes
            // turns on the same runtime; Queue here means "the conv is busy with
            // another run, do not trigger another input". For layer-1 we still
            // input the message — the App Server's own turn queue takes it from
            // there — but we mark a different telemetry signal.
            Telemetry.event(
                "A2aHost", "a2a.route",
                "fromAgentId" to message.fromAgentId,
                "toAgentId" to message.toAgentId,
                "decision" to "queue",
                "conversationId" to decision.conversationId,
            )
            inputOnConversation(client, message, decision.conversationId)
        }
        is IrohAgentMessageRouter.RoutingDecision.CreateAndDeliver -> {
            // bn008.6 layer-1: no clean create-conversation path on this wrapper
            // (would require orchestrating runtime_start + create_conversation,
            // which the existing controller exposes only via runTurn()). Drop with
            // a typed reason — the gap is surfaced on the bead as a non-blocking
            // finding for layer-2.
            Telemetry.event(
                "A2aHost", "a2a.drop",
                "fromAgentId" to message.fromAgentId,
                "toAgentId" to message.toAgentId,
                "reason" to "no_conversation_create_path",
                level = Telemetry.Level.WARN,
            )
        }
        is IrohAgentMessageRouter.RoutingDecision.Dropped -> {
            Telemetry.event(
                "A2aHost", "a2a.drop",
                "fromAgentId" to message.fromAgentId,
                "toAgentId" to message.toAgentId,
                "reason" to decision.reason,
            )
        }
    }
}

private suspend fun inputOnConversation(
    client: AppServerClient?,
    message: IrohAgentMessage,
    conversationId: String,
) {
    if (client == null) {
        Telemetry.event(
            "A2aHost", "a2a.drop",
            "fromAgentId" to message.fromAgentId,
            "toAgentId" to message.toAgentId,
            "reason" to "no_app_server_client",
            level = Telemetry.Level.WARN,
        )
        return
    }
    runCatching {
        client.input(
            AppServerCommand.Input(
                runtime = AppServerRuntimeScope(
                    agentId = message.toAgentId,
                    conversationId = conversationId,
                ),
                payload = AppServerInputPayload.CreateMessage(
                    messages = listOf(
                        AppServerInputMessage(
                            role = "user",
                            content = JsonPrimitive(message.body),
                            clientMessageId = message.msgId,
                        ),
                    ),
                ),
            ),
        )
    }.onFailure { t ->
        Telemetry.event(
            "A2aHost", "a2a.drop",
            "fromAgentId" to message.fromAgentId,
            "toAgentId" to message.toAgentId,
            "reason" to "input_failed:${t::class.simpleName ?: "error"}",
            level = Telemetry.Level.WARN,
        )
    }
}

/**
 * Load the wrapper's 32-byte Iroh secret key from [path]. Re-uses the
 * existing `FileIrohSecretKeyStore` so the on-disk format matches the
 * `IrohNodeEndpoint` path byte-for-byte (and the a2a node id matches the
 * app-server node id when both reference the same file).
 */
private suspend fun loadSecretKey(path: String?): ByteArray {
    if (path == null) {
        // No path provided: generate ephemeral. Stable identity across restarts
        // is operator-driven (LETTA_IROH_SECRET_KEY_FILE); absent that, the
        // node id rotates every restart and dialers must use the ticket.
        return SecretKey.generate().use { it.toBytes() }
    }
    return FileIrohSecretKeyStore(path).loadOrCreate()
}
