package com.letta.mobile.cli.commands

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
import com.letta.mobile.data.transport.iroh.IrohAgentAddress
import com.letta.mobile.data.transport.iroh.IrohAgentIdentity
import com.letta.mobile.data.transport.iroh.IrohAgentMessage
import com.letta.mobile.data.transport.iroh.DeliveryOutcome
import com.letta.mobile.data.transport.iroh.IrohAgentMessageReceiver
import com.letta.mobile.data.transport.iroh.IrohDiagnostics
import com.letta.mobile.util.Telemetry
import computer.iroh.EndpointAddr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
 * letta-mobile-by4vm.112: the [receiver] is an
 * [com.letta.mobile.data.controller.node.iroh.IrohNodeProtocolHandler] the wrapper
 * registers on its ONE host [com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint],
 * which demultiplexes `/letta/a2a/0` next to the App Server, canvas and notebook ALPNs.
 * The wiring used to bind a SECOND endpoint with the same secret key; the relay keeps
 * one registration per node id (the newest), so relay-routed App Server dials landed
 * on the a2a-only twin and failed TLS with `no_application_protocol`.
 *
 * Build via [buildA2aWiring] before the host endpoint (its ALPNs are fixed at bind),
 * then [publishHost] the bound endpoint's address.
 */
class A2aWiring internal constructor(
    val receiver: IrohAgentMessageReceiver,
    val router: IrohAgentMessageRouter,
    val addressStore: HostEndpointAddressStore,
    val identityDir: File,
)

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
    /** Per-agent identity directory (mode 0700). Created on demand. */
    val identityDir: File,
    /** Address-book kv file (parent dir must exist with mode 0700). */
    val addressBook: File,
)

/**
 * Build the production a2a wiring. The receiver's `onDeliver` lands a body as a
 * user message via the App Server client [client] returns at delivery time (the
 * same one the wrapper uses for everything else; it is created after the host
 * endpoint, so it is read late — the endpoint accepts nothing before then).
 *
 * **CreateAndDeliver** — the router returns CreateAndDeliver when the target
 * agent has NO interactive conversation; the wrapper creates one through the
 * client, or drops with `no_conversation_create_path` when it cannot.
 */
fun buildA2aWiring(
    config: A2aWiringConfig,
    client: () -> AppServerClient?,
    /**
     * Optional letta-code local-backend root. When non-null, conversations
     * for the target agentId are read from `<root>/conversations/...` and
     * busy state from `<root>/runs/...`. Null = no candidates supplied
     * (the router falls through to CreateAndDeliver).
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
    // M3 (PR #1125): ONE [HostEndpointAddressStore] instance per bind, shared by
    // publishHost and the receiver, so the kv file has one in-memory owner.
    val store = HostEndpointAddressStore.withBackend(config.addressBook, localBackendDir)
    // M4 (PR #1125): construct the [LocalBackendAdminStore] exactly once, not per
    // inbound message. Without a client or a backend dir there are no candidates
    // (router falls through to CreateAndDeliver).
    val backendStore = localBackendDir?.let { LocalBackendAdminStore(it) }
    val conversationsFor: suspend (String) -> List<IrohAgentMessageRouter.ConversationState> = { agentId ->
        if (client() == null || backendStore == null) emptyList()
        else listConversationsForAgent(backendStore, AgentIdNamespace.normalizeToBareId(agentId))
    }
    val router = IrohAgentMessageRouter(ownAgentId = ownAgentId)
    val receiver = IrohAgentMessageReceiver(
        router = router,
        conversationsFor = conversationsFor,
        onDeliver = { message, decision -> handleDecision(client(), message, decision) },
    )
    return A2aWiring(
        receiver = receiver,
        router = router,
        addressStore = store,
        identityDir = config.identityDir,
    )
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
    addr: EndpointAddr,
    store: HostEndpointAddressStore = HostEndpointAddressStore(config.addressBook),
): HostEndpointRecord {
    val nodeHex = IrohDiagnostics.endpointIdHex(addr.id())
    val direct = sortDirectAddresses(addr.directAddresses())
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
        val dropAttrs = mutableListOf(
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
