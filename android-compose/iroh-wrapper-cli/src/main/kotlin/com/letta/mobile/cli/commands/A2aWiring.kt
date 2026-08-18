package com.letta.mobile.cli.commands

import com.letta.mobile.data.controller.node.iroh.FileIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import com.letta.mobile.data.messaging.AgentMessageClientId
import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentIdNamespace
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationClass
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInputMessage
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.HostEndpointAddressStore
import com.letta.mobile.data.transport.iroh.IdentityMigrationAction
import com.letta.mobile.data.transport.iroh.IrohAgentAddress
import com.letta.mobile.data.transport.iroh.IrohAgentAddressResolver
import com.letta.mobile.data.transport.iroh.IrohAgentIdentity
import com.letta.mobile.data.transport.iroh.IrohAgentMessage
import com.letta.mobile.data.transport.iroh.DeliveryOutcome
import com.letta.mobile.data.transport.iroh.IrohAgentMessageReceiver
import com.letta.mobile.util.Telemetry
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import computer.iroh.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

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
    val addressStore: HostEndpointAddressStore,
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
 *
 * letta-mobile-xmpqm: the previous `publishAgents` allowlist is gone. The host
 * record is written once per bind (in [publishHost]), and agent reachability
 * is resolved at dial time via [com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore.agentExists].
 * No enumeration of agents at boot — that was the O(agents²) bind-time cost
 * this phase deletes.
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
     *
     * letta-mobile-xmpqm: this is now also the membership oracle for the
     * host-level address book — [HostEndpointAddressStore] is wired to the
     * same [LocalBackendAdminStore] so an agent is addressable iff it exists
     * in the backend dir, regardless of whether it was published.
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
    // letta-mobile-xmpqm: no more `publishAgents` allowlist, no more
    // "nothing to bind" guard. The host record is written once at bind
    // (publishHost), and the membership gate at resolve() decides which
    // agents are addressable from the local backend dir.
    // M3 (PR #1125): ONE [HostEndpointAddressStore] instance per bind. Sharing
    // it between publishHost and the closures below prevents divergent
    // in-memory state in the kv file (each new instance re-reads the file).
    val store = HostEndpointAddressStore.withBackend(config.addressBook, localBackendDir)
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
    // but every subsequent step (endpointIdHex, publishHost,
    // LocalBackendAdminStore construction, A2aWiring wiring) can still throw.
    // Without this guard a post-bind failure would leak the bound Endpoint —
    // the receiver would never start, so A2aWiring.close() would never run,
    // and the address book entry that publishHost just wrote would point
    // at a dead node. Wrap the post-bind init in try/catch; on failure
    // shutdown() the endpoint (best-effort, runBlocking on the suspending call)
    // and rethrow so the AppServerServeIrohCommand.run wrapper can degrade
    // gracefully. The native-gated test `endpoint is released when post-bind
    // setup fails` (in A2aWiringTest) pins this contract.
    try {
        // M2: compute hex node id once at bind time (suspend id() call) and store it
        // on A2aWiring as a plain `val`.
        val nodeIdHex = endpointIdHex(endpoint)

        publishHost(config, endpoint, store)

        // M4 (PR #1125): construct the [LocalBackendAdminStore] exactly once at
        // bind time (sync file I/O had been running on the receiver's
        // connection-coroutine dispatcher — a layering bug). The receiver's
        // `conversationsFor` closure now captures it; if either client is null or
        // the backend dir is null we short-circuit to an empty list (router
        // falls through to CreateAndDeliver -> Dropped).
        //
        // letta-mobile-xmpqm: the same store instance backs the host endpoint
        // address book (`store`) above, so membership checks via
        // agentExists() and conversation reads share one file handle.
        val backendStore = localBackendDir?.let { LocalBackendAdminStore(it) }
        val conversationsFor: suspend (String) -> List<IrohAgentMessageRouter.ConversationState> = { agentId ->
            if (client == null || backendStore == null) emptyList()
            else listConversationsForAgent(backendStore, AgentIdNamespace.normalizeToBareId(agentId))
        }

        val onDeliver: suspend (IrohAgentMessage, IrohAgentMessageRouter.RoutingDecision) -> DeliveryOutcome = { message, decision ->
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
 * The single record the wrapper writes on bind (letta-mobile-xmpqm).
 *
 * One kv line: `host:<hostKey>=<wire>`. The previous shape wrote one row
 * per agent (`agentId=<wire>`); every row carried the SAME (nodeId,
 * directAddrs) pair, so the file grew O(agents) duplicates of O(1)
 * information. At bind that was `register() = readAll() + writeAll()` of the
 * WHOLE file per agent — O(agents²) bytes of I/O. With this call the kv file
 * stays at exactly one line regardless of how many agents share the host,
 * and rebind updates that one line in place.
 *
 * Reachability is no longer pinned to a per-agent row: the
 * [HostEndpointAddressStore.resolve] call gates on
 * [LocalBackendAdminStore.agentExists], so an agent is addressable iff it
 * exists in the local backend dir — no enumeration at bind, no allowlist
 * to scale (LETTA_A2A_PUBLISH_AGENTS is gone, replaced by backend
 * membership + gossip-cached peer roster in Phase 3).
 *
 * Returns the [HostEndpointRecord] actually written — the host key + the
 * wire — so callers can log or assert on what hit disk. The legacy
 * `publishLocalAgents(...): List<String>` returned the list of agents
 * published; that list no longer exists in this phase (no enumeration, no
 * return value that names agents).
 *
 * The legacy identity migration still runs here (the wrapper is the only
 * process guaranteed to own the identity dir, and there is no per-agent
 * pre-touch to fold it into). Per-agent identities are loaded lazily at
 * dial time by [IrohAgentIdentity.loadOrCreate] — no pre-touch at bind.
 */
suspend fun publishHost(
    config: A2aWiringConfig,
    endpoint: Endpoint,
    store: HostEndpointAddressStore = HostEndpointAddressStore(config.addressBook),
): HostEndpointRecord {
    val addr = endpoint.addr()
    val nodeHex = endpointIdHex(endpoint)
    val direct = sortDirectAddresses(addr.directAddresses())
    // Collapse any legacy per-agent rows on the first write — `register()`
    // rewrites the file with exactly one host record, evicting all of the
    // O(agents) duplicates the previous shape left behind.
    migrateLegacyIdentities(config.identityDir)
    val hostAddr = IrohAgentAddress(
        agentId = HOST_ONLY_AGENT_ID,
        nodeIdHex = nodeHex,
        directAddrs = direct,
    )
    store.register(hostAddr)
    Telemetry.event(
        "A2aHost",
        "host.published",
        "nodeId" to nodeHex,
        "directAddrs" to direct.joinToString(","),
        "addressBook" to config.addressBook.absolutePath,
    )
    return HostEndpointRecord(
        hostKey = hostAddr.nodeIdHex.take(HostEndpointAddressStore.HOST_KEY_LENGTH),
        nodeIdHex = nodeHex,
        directAddrs = direct,
    )
}

/** The single record the wrapper writes on bind (letta-mobile-xmpqm). */
data class HostEndpointRecord(
    val hostKey: String,
    val nodeIdHex: String,
    val directAddrs: List<String>,
)

/**
 * The "agentId" written into the host-record wire is an internal sentinel —
 * it is never used for membership; [HostEndpointAddressStore.resolve] calls
 * the membership oracle with the CALLER's agentId, not the one in the
 * wire. Keeping a placeholder means the wire stays shaped like the prior
 * kv format (still parses as `nodeIdHex@directAddrs`).
 */
private const val HOST_ONLY_AGENT_ID = "host"

/**
 * letta-mobile-oi147: collapse identity files left under the retired `letta_`
 * namespace onto their canonical (bare) names, once, at bind.
 *
 * Runs here because bind is the only moment the wrapper is guaranteed to own the
 * identity dir and no dial is in flight. Each action is emitted as telemetry: this
 * moves SECRET KEY MATERIAL, so it must be greppable after the fact rather than
 * happening silently. Failures are reported at WARN and do not abort the bind — a
 * stranded legacy file is a cleanup problem, not a reason to take messaging down.
 *
 * letta-mobile-xmpqm: still runs at bind, but is no longer coupled to
 * per-agent publishing — it is its own concern.
 */
internal fun migrateLegacyIdentities(identityDir: File) {
    val actions = runCatching { IrohAgentIdentity.migrateLegacyNamespacedFiles(identityDir) }
        .getOrElse { t ->
            Telemetry.event(
                "A2aHost", "identity.migration_failed",
                "reason" to (t.message ?: t::class.simpleName ?: "error"),
                level = Telemetry.Level.WARN,
            )
            return
        }
    actions.forEach { action ->
        when (action) {
            is IdentityMigrationAction.DeletedOrphan -> Telemetry.event(
                "A2aHost", "identity.orphan_deleted",
                "legacyAgentId" to action.legacyAgentId,
                "canonicalAgentId" to action.canonicalAgentId,
            )
            is IdentityMigrationAction.RenamedToCanonical -> Telemetry.event(
                "A2aHost", "identity.renamed_to_canonical",
                "legacyAgentId" to action.legacyAgentId,
                "canonicalAgentId" to action.canonicalAgentId,
            )
            is IdentityMigrationAction.Failed -> Telemetry.event(
                "A2aHost", "identity.migration_failed",
                "legacyAgentId" to action.legacyAgentId,
                "reason" to action.reason,
                level = Telemetry.Level.WARN,
            )
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
    rawAgentId: String,
): List<IrohAgentMessageRouter.ConversationState> = withContext(Dispatchers.IO) {
    val agentId = AgentIdNamespace.normalizeToBareId(rawAgentId)
    if (!store.agentExists(agentId)) {
        Telemetry.event(
            "A2aHost", "a2a.agent_missing",
            "agentId" to agentId,
            level = Telemetry.Level.WARN,
        )
        return@withContext emptyList()
    }
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

internal fun JsonElement?.stringOrNullSafe(): String? = (this as? JsonPrimitive)?.let { if (it.isString) it.content else null }

/**
 * Build the a2a envelope JSON for diagnostic / metadata use.
 *
 * Shape: {"envelope":"a2a","from_agent_id":...,"to_agent_id":...,"ts":...,"msg_id":...,"content":...}
 *
 * letta-mobile-8kbqd: this is NOT what gets written into the recipient's
 * conversation. Delivery uses [message.body] plain text so humans / chat UIs
 * see the sender's words, not a nested JSON blob. Wire identity for
 * at-most-once dedup stays on [IrohAgentMessage.msgId] → clientMessageId.
 */
internal fun wrapA2aEnvelope(message: IrohAgentMessage): String = buildJsonObject {
    put("envelope", "a2a")
    put("from_agent_id", message.fromAgentId)
    put("to_agent_id", message.toAgentId)
    put("ts", message.ts)
    put("msg_id", message.msgId)
    put("content", message.body)
}.toString()

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
): DeliveryOutcome {
    val normMessage = message.copy(
        fromAgentId = AgentIdNamespace.normalizeToBareId(message.fromAgentId),
        toAgentId = AgentIdNamespace.normalizeToBareId(message.toAgentId),
    )
    when (decision) {
        is IrohAgentMessageRouter.RoutingDecision.Deliver -> {
            Telemetry.event(
                "A2aHost", "a2a.deliver",
                "fromAgentId" to normMessage.fromAgentId,
                "toAgentId" to normMessage.toAgentId,
                "msgId" to normMessage.msgId,
                "conversationId" to decision.conversationId,
            )
            return inputOnConversation(client, normMessage, decision.conversationId)
        }
        is IrohAgentMessageRouter.RoutingDecision.Queue -> {
            // Same as Deliver but no second turn: the app-server already serializes
            // turns on the same runtime; Queue here means "the conv is busy with
            // another run, do not trigger another input". For layer-1 we still
            // input the message — the App Server's own turn queue takes it from
            // there — but we mark a different telemetry signal.
            Telemetry.event(
                "A2aHost", "a2a.route",
                "fromAgentId" to normMessage.fromAgentId,
                "toAgentId" to normMessage.toAgentId,
                "msgId" to normMessage.msgId,
                "decision" to "queue",
                "conversationId" to decision.conversationId,
            )
            return inputOnConversation(client, normMessage, decision.conversationId)
        }
        is IrohAgentMessageRouter.RoutingDecision.CreateAndDeliver -> {
            return handleCreateAndDeliver(client, normMessage)
        }
        is IrohAgentMessageRouter.RoutingDecision.Dropped -> {
            Telemetry.event(
                "A2aHost", "a2a.drop",
                "fromAgentId" to normMessage.fromAgentId,
                "toAgentId" to normMessage.toAgentId,
                "msgId" to normMessage.msgId,
                "reason" to decision.reason,
            )
            return DeliveryOutcome(false, decision.reason)
        }
    }
}

internal suspend fun handleCreateAndDeliver(
    client: AppServerClient?,
    message: IrohAgentMessage,
): DeliveryOutcome {
    if (client == null) {
        Telemetry.event(
            "A2aHost", "a2a.drop",
            "fromAgentId" to message.fromAgentId,
            "toAgentId" to message.toAgentId,
            "msgId" to message.msgId,
            "reason" to "no_app_server_client",
            level = Telemetry.Level.WARN,
        )
        return DeliveryOutcome(false, "application_enqueue_failure")
    }
    var appServerError: String? = null
    val createdId = runCatching {
        val response = client.conversationCreate(
            AppServerCommand.ConversationCreate(
                requestId = "conv-create-${UUID.randomUUID()}",
                body = buildJsonObject { put("agent_id", message.toAgentId) },
            ),
        )
        if (response.success) {
            response.conversation?.get("id")?.stringOrNullSafe()
        } else {
            appServerError = response.error
            null
        }
    }.onFailure { t ->
        appServerError = t.message ?: t::class.simpleName
    }.getOrNull()

    if (!createdId.isNullOrEmpty()) {
        Telemetry.event(
            "A2aHost", "a2a.create_and_deliver",
            "fromAgentId" to message.fromAgentId,
            "toAgentId" to message.toAgentId,
            "msgId" to message.msgId,
            "conversationId" to createdId,
        )
        return inputOnConversation(client, message, createdId)
    } else {
        val dropAttrs = mutableListOf<Pair<String, Any?>>(
            "fromAgentId" to message.fromAgentId,
            "toAgentId" to message.toAgentId,
            "msgId" to message.msgId,
            "reason" to "no_conversation_create_path",
        )
        if (!appServerError.isNullOrBlank()) {
            dropAttrs.add("error" to appServerError)
        }
        Telemetry.event(
            "A2aHost", "a2a.drop",
            *dropAttrs.toTypedArray(),
            level = Telemetry.Level.WARN,
        )
    }
    return DeliveryOutcome(false, "conversation_create_failure")
}

private suspend fun inputOnConversation(
    client: AppServerClient?,
    message: IrohAgentMessage,
    conversationId: String,
): DeliveryOutcome {
    if (client == null) {
        Telemetry.event(
            "A2aHost", "a2a.drop",
            "fromAgentId" to message.fromAgentId,
            "toAgentId" to message.toAgentId,
            "msgId" to message.msgId,
            "reason" to "no_app_server_client",
            level = Telemetry.Level.WARN,
        )
        return DeliveryOutcome(false, "application_enqueue_failure")
    }
    return runCatching {
        client.input(
            AppServerCommand.Input(
                runtime = AppServerRuntimeScope(
                    agentId = message.toAgentId,
                    conversationId = conversationId,
                ),
                payload = AppServerInputPayload.CreateMessage(
                    messages = listOf(
                        // letta-mobile-8kbqd: persist/render the plain body.
                        // Envelope metadata (from/to/ts/msg_id) stays on the
                        // wire + telemetry; the body text itself NEVER carries
                        // wrapA2aEnvelope(...) JSON — it must stay readable to
                        // both the human and the receiving agent's own turn.
                        //
                        // letta-mobile-slqfp: structural (non-heuristic)
                        // provenance instead rides the clientMessageId field,
                        // which already survives Local -> Confirmed
                        // reconciliation end to end as an opaque string (see
                        // AgentMessageClientId). The receiving client's chat
                        // render projects fromAgentId/toAgentId/msgId back out
                        // of this id — never by parsing the body above.
                        AppServerInputMessage(
                            role = "user",
                            content = JsonPrimitive(message.body),
                            clientMessageId = AgentMessageClientId.encode(
                                msgId = message.msgId,
                                fromAgentId = message.fromAgentId,
                                toAgentId = message.toAgentId,
                            ),
                        ),
                    ),
                ),
            ),
        )
    }.map {
        Telemetry.event(
            "A2aHost", "a2a.application_delivered",
            "msgId" to message.msgId,
            "toAgentId" to message.toAgentId,
            "conversationId" to conversationId,
        )
        DeliveryOutcome(true)
    }.getOrElse { t ->
        if (t is CancellationException) throw t
        Telemetry.event(
            "A2aHost", "a2a.application_failed",
            "msgId" to message.msgId,
            "toAgentId" to message.toAgentId,
            "conversationId" to conversationId,
            "reason" to "application_input_failure",
            level = Telemetry.Level.WARN,
        )
        DeliveryOutcome(false, "application_input_failure")
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
 * not pay for a `runBlocking` apiece. Also used by [publishHost] when
 * it writes the address book — one helper, two callers, zero divergence.
 */
private suspend fun endpointIdHex(endpoint: Endpoint): String {
    val id = endpoint.addr().id()
    return id.use { it.toBytes().joinToString("") { b -> "%02x".format(b) } }
}
