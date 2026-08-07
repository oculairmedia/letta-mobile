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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    /**
     * M2 (PR #1125): the a2a node id (hex, 64 chars). Equal to the app-server
     * node id when both endpoints share the same secret-key file. Computed
     * once at bind time (suspending; the underlying `Endpoint.addr().id()`
     * is async) and stored as a plain `val`, so callers can read it
     * synchronously without a `runBlocking` per access. */
    val nodeIdHex: String,
) {
    /** Start the receiver's accept loop on [scope]; returns the accept-loop Job. */
    fun start(scope: CoroutineScope): Job = receiver.start(scope)

    /** Best-effort shutdown: shuts down the underlying endpoint.
     *
     *  M1 (PR #1125): per the UniFFI Kotlin binding, `Endpoint::close()` is the
     *  `AutoCloseable.close()` shim and is BLOCKING, while the native iroh
     *  `Endpoint::close()` is async. UniFFI resolves the signature collision by
     *  exposing the async one as `shutdown()`. Calling `endpoint.close()` here
     *  would deadlock under load; call the suspend `shutdown()` via a
     *  `runBlocking` (best-effort, wrapped) so the helper stays synchronous. */
    fun close() {
        runCatching { runBlocking { endpoint.shutdown() } }
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
    // M3 (PR #1125): ONE [FileIrohAgentAddressStore] instance per bind. Sharing
    // it between publishLocalAgents and the closures below prevents divergent
    // in-memory state in the kv file (each new instance re-reads the file).
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
    ).also { ep ->
        // N2 (PR #1125): a relay-set-up failure no longer black-holes silently
        // — emit a typed warning. The receiver still proceeds (the endpoint
        // can serve direct addrs even when the relay is unavailable), but an
        // operator can grep the log for `a2a.online_failed` and see why
        // rendezvous dials keep timing out.
        runCatching { ep.online() }.onFailure { t ->
            Telemetry.event(
                "A2aHost", "a2a.online_failed",
                "reason" to (t::class.simpleName ?: "error"),
                "message" to (t.message ?: ""),
                level = Telemetry.Level.WARN,
            )
        }
    }
    // letta-mobile-bn008.6 sweep round 2 (PR #1125): the endpoint is now BOUND
    // but every subsequent step (endpointIdHex, publishLocalAgents,
    // LocalBackendAdminStore construction, A2aWiring wiring) can still throw.
    // Without this guard a post-bind failure would leak the bound Endpoint —
    // the receiver would never start, so A2aWiring.close() would never run,
    // and the address book entry that publishLocalAgents just wrote would point
    // at a dead node. Wrap the post-bind init in try/catch; on failure
    // shutdown() the endpoint (best-effort, runBlocking on the suspending call)
    // and rethrow so the AppServerServeIrohCommand.run wrapper can degrade
    // gracefully. The native-gated test `endpoint is released when post-bind
    // setup fails` (in A2aWiringTest) pins this contract.
    try {
        // M2: compute hex node id once at bind time (suspend id() call) and store it
        // on A2aWiring as a plain `val`.
        val nodeIdHex = endpointIdHex(endpoint)

        publishLocalAgents(config, endpoint, store)

        // M4 (PR #1125): construct the [LocalBackendAdminStore] exactly once at
        // bind time (sync file I/O had been running on the receiver's
        // connection-coroutine dispatcher — a layering bug). The receiver's
        // `conversationsFor` closure now captures it; if either client is null or
        // the backend dir is null we short-circuit to an empty list (router
        // falls through to CreateAndDeliver -> Dropped).
        val backendStore = localBackendDir?.let { LocalBackendAdminStore(it) }
        val conversationsFor: suspend (String) -> List<IrohAgentMessageRouter.ConversationState> = { agentId ->
            if (client == null || backendStore == null) emptyList()
            else listConversationsForAgent(backendStore, agentId)
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
            nodeIdHex = nodeIdHex,
        )
    } catch (t: Throwable) {
        // Best-effort release of the bound endpoint before propagating.
        // The endpoint is bound to a UDP socket on the OS, so leaking it
        // here would keep the port occupied until process exit — bad for
        // a wrapper that may restart on configuration changes.
        runCatching { runBlocking { endpoint.shutdown() } }
        throw t
    }
}

/**
 * Publish the given agentIds into the kv file pointed at [config.addressBook]
 * using the host's own node id + dialable direct addrs. Mirrors the
 * `IrohAgentAddressResolver.publish(...)` write pattern so the wire format
 * matches what the seed script and the sender expect.
 *
 * Returns the list of agents actually published (skips empty ids).
 *
 * M3 (PR #1125): accepts [store] as a parameter so callers can share one
 * [FileIrohAgentAddressStore] across publish + receiver wiring (preventing
 * divergent in-memory kv state). The default still constructs one for
 * direct callers that don't have a store handy (e.g. tests).
 *
 * M2 follow-on (PR #1125): this is `suspend` because it reads the host's
 * own node id via [endpointIdHex], which is `suspend` (the underlying
 * `Endpoint.addr().id()` is async). Both production callers (`buildA2aWiring`,
 * the test below) are already inside `runBlocking { ... }` so the suspend
 * marker does not change the call ergonomics — only makes the async I/O
 * visible in the type system.
 */
suspend fun publishLocalAgents(
    config: A2aWiringConfig,
    endpoint: Endpoint,
    store: FileIrohAgentAddressStore = FileIrohAgentAddressStore(config.addressBook),
): List<String> {
    val resolver = IrohAgentAddressResolver(store)
    val addr = endpoint.addr()
    val nodeHex = endpointIdHex(endpoint)
    val direct = addr.directAddresses()
    val published = mutableListOf<String>()
    val targetAgents = resolveTargetAgentsToPublish(config.publishAgents, config.addressBook)
    targetAgents.forEach { agentId ->
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
 * Resolve the list of target agent IDs to publish on bind by combining
 * [publishAgents] with seeded empty-wire entries from [addressBook] (if present).
 * Preserves order, removes duplicates, and filters out blank agent IDs.
 */
internal fun resolveTargetAgentsToPublish(
    publishAgents: List<String>,
    addressBook: File,
): List<String> {
    val seededEmptyAgents = findSeededEmptyAgents(addressBook)
    return (publishAgents.map { it.trim() } + seededEmptyAgents)
        .filterNot { it.isBlank() }
        .distinct()
}

/**
 * Read [addressBook] if it exists and find all agent IDs whose wire value is empty or blank
 * (e.g. `agentId=` or `agentId=  `).
 */
internal fun findSeededEmptyAgents(addressBook: File): List<String> {
    if (!addressBook.exists()) return emptyList()
    return addressBook.readLines().mapNotNull { line ->
        val eq = line.indexOf('=')
        if (eq <= 0) null else {
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (key.isNotEmpty() && value.isBlank()) key else null
        }
    }
}


/**
 * Resolve a list of interactive conversations for the target agent. The
 * `busy` flag is computed from the local `runs/` store: a conversation is
 * busy iff there's at least one run with status="running" referencing it.
 *
 * M4 (PR #1125): reuses a [LocalBackendAdminStore] built once at bind time
 * (no per-call re-construct or per-call file walk re-read), wraps the
 * synchronous readers in [withContext] so the file I/O does NOT run on the
 * receiver's connection-coroutine dispatcher, and is `suspend` (so callers
 * must wait for it before moving on — important for the conversation
 * ordering invariant the router depends on).
 *
 * N5 (PR #1125): visibility is `internal` so the JVM tests in the
 * `iroh-wrapper-cli` module can exercise it without bringing up the iroh
 * native binding (the default `:iroh-wrapper-cli:test` gate stays hermetic).
 *
 * Best-effort: every read is wrapped in runCatching so a missing dir or
 * corrupt record yields an empty list (router falls through to
 * CreateAndDeliver, which the wrapper downgrades).
 */
internal suspend fun listConversationsForAgent(
    store: LocalBackendAdminStore,
    agentId: String,
): List<IrohAgentMessageRouter.ConversationState> = withContext(Dispatchers.IO) {
    runCatching {
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
}

private fun JsonElement?.stringOrNullSafe(): String? = (this as? JsonPrimitive)?.let { if (it.isString) it.content else null }

/**
 * N5 (PR #1125): `internal` so the JVM tests in `iroh-wrapper-cli` can
 * exercise the decoder shape directly (the default gate is hermetic — it
 * does not bring up the iroh native binding).
 */
internal fun decodeConversation(obj: JsonObject, fallbackAgentId: String): Conversation? = runCatching {
    val id = obj["id"].stringOrNullSafe() ?: return@runCatching null
    val agentIdStr = obj["agent_id"].stringOrNullSafe() ?: fallbackAgentId
    val convClass = when (obj["conversation_class"].stringOrNullSafe()) {
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
 *
 * N5 (PR #1125): visibility is `internal` so the JVM tests in
 * `iroh-wrapper-cli` can exercise the decision / telemetry path directly.
 */
internal suspend fun handleDecision(
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

/**
 * M2 (PR #1125): file-scope helper that returns the bound endpoint's node id
 * as a hex string. `Endpoint.id()` itself is `suspend` (UniFFI bridges the
 * async FFI call); the subsequent `EndpointId.toBytes()` is not. Wrapping
 * here lets `buildA2aWiring` compute the hex once at bind time and store it
 * on [A2aWiring] as a plain `val`, so the many property reads elsewhere do
 * not pay for a `runBlocking` apiece. Also used by [publishLocalAgents] when
 * it writes the address book — one helper, two callers, zero divergence.
 */
private suspend fun endpointIdHex(endpoint: Endpoint): String {
    val id = endpoint.addr().id()
    return id.use { it.toBytes().joinToString("") { b -> "%02x".format(b) } }
}
