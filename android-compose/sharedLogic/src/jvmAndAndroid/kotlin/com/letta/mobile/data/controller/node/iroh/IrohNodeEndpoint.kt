package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId

import com.letta.mobile.util.attr
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.extras.relay.AppToolRelayHost
import com.letta.mobile.data.controller.extras.relay.AppToolRelayLink
import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.transport.iroh.IrohDiagnostics
import com.letta.mobile.data.transport.iroh.IrohFailureLog
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.Incoming
import computer.iroh.EndpointAddr
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import computer.iroh.Preset
import computer.iroh.EndpointBuilder
import computer.iroh.AddrChangeCallback
import computer.iroh.HomeRelayCallback
import computer.iroh.NetworkChangeCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import com.letta.mobile.data.controller.node.IrohRelayConfig
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.controller.node.link.NodeConnectionWire

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Suppress("LongParameterList")
class IrohNodeEndpoint(
    binding: IrohEndpointBinding = IrohEndpointBinding(),
    private val scope: CoroutineScope,
    /**
     * REQUIRED authentication policy. There is no default: anonymous
     * operation must be selected explicitly via
     * [IrohAuthPolicy.InsecureAnonymousForTestOnly].
     */
    private val authPolicy: IrohAuthPolicy,
    /**
     * REQUIRED: serves one App Server connection this endpoint accepted (admitted, on the App Server ALPN) as a node
     * carrier link, through this endpoint's [serveWire], and returns when it has been served. The node's composition
     * supplies the Iroh carrier's node end (`com.letta.mobile.data.node.serveIrohCarrierLink`), so every peer is
     * served by one protocol code path whatever carrier it came over. The endpoint releases the connection afterwards.
     */
    private val appServerLinks: suspend (connection: Connection, endpoint: IrohNodeEndpoint) -> Unit,
    /** Optional pairing service; shared across every accepted connection. */
    private val pairingService: IrohPairingService? = null,
    /**
     * Relays shared canvases between the apps connected here. When set, the endpoint also
     * accepts the canvas ALPNs, from peers with an authenticated App Server connection only.
     */
    private val canvasRelay: com.letta.mobile.data.transport.iroh.IrohCanvasRelay? = null,
    /** Additional protocols on this host-owned endpoint; handlers own their connection lifecycle. */
    private val protocolHandlers: List<IrohNodeProtocolHandler> = emptyList(),
    /** The host's stream-to-stored identity join, shared by every connection. */
    private val turnIdentity: com.letta.mobile.data.runtime.TurnIdentityLedger? = null,
    private val connectionRegistry: ConnectionRegistry = ConnectionRegistry(),
    /** The host's durable turn journal; the default keeps none, so a redial replays nothing. */
    private val turnJournal: TurnJournal = NoTurnJournal,
) {
    private val alpn = binding.alpn
    private val relayConfig = binding.relayConfig
    private val bindAddr = binding.bindAddr
    private val secretKey = binding.secretKey
    private val secretKeyPath = binding.secretKeyPath
    private val secretKeyStore = binding.secretKeyStore

    /** Live App Server connections by peer id, to gate that peer's canvas connections. */
    private val appServerConnections =
        java.util.concurrent.ConcurrentHashMap<String, MutableSet<IrohNodeConnection>>()

    /** How many peers hold an entry in the live App Server connection table; for tests. */
    internal val appServerPeerCount: Int get() = appServerConnections.size

    // d6e8g.3: ONE verifier across every connection this endpoint accepts, so
    // per-NodeId auth-failure rate limiting survives redials.
    private val authVerifier = IrohBearerAuthVerifier(authPolicy)

    /** A redialling peer repeats the same failure; count it, log it sparsely. */
    private val handshakeFailures = IrohFailureLog("IrohNode", "incoming.handshake.failed")

    init {
        val alpns = listOf(alpn) + canvasRelay?.alpns.orEmpty() + protocolHandlers.map { it.alpn }
        require(alpns.map { it.toList() }.distinct().size == alpns.size) { "Duplicate Iroh protocol ALPN" }
        // letta-mobile-dpv7m.1: revoking a paired device ends its live sessions
        // now, instead of leaving them authenticated until the peer redials.
        pairingService?.addRevocationListener(::closeRevokedPeer)
    }

    private fun closeRevokedPeer(remoteId: String) {
        appServerConnections[remoteId].orEmpty().forEach { it.revokeSession() }
    }

    private var endpoint: Endpoint? = null
    private var acceptJob: Job? = null
    private var _adminRpcRouter: AdminRpcRouter? = null

    /**
     * One registry shared across every connection this endpoint
     * accepts. Maps conversationId -> the set of live viewer handles, enabling
     * a turn on one connection to fan its frames out to all connections viewing
     * the same conversation.
     */

    /**
     * ONE node-owned home for every connection's live turns, a child of
     * the endpoint scope and of no connection, so a peer dropping mid-turn detaches its turn
     * instead of cancelling the server turn under it.
     *
     * One host per endpoint generation: [start] creates it and [shutdown] stops its turns, so
     * detached turns never outlive the endpoint and a restarted endpoint gets a live host.
     */
    private var turnHost: NodeTurnHost? = null

    /**
     * The handled-turn set every connection of this endpoint shares, so a peer's redial (a new
     * connection to the same endpoint) re-sending a turn is recognised. The wrapper binds one
     * endpoint for its whole process; across a restart the durable [turnJournal] recognises it.
     */
    private val clientMessageDedupe = ProcessScopedClientMessageDedupe()

    /** The controller this endpoint generation serves, set with its [turnHost]; links served before that are refused. */
    @Volatile
    private var servingController: AppServerController? = null

    /** The cut of the shutdown drain [beginShutdownDrain] began; null before it or after the generation ended. */
    @Volatile
    private var shutdownDrainCut: (() -> Unit)? = null

    init {
        // letta-mobile-6vf4r (S3): a connection that starts viewing a conversation mid-turn learns
        // the running turn from its `update_loop_status`, next to the subagent/approval replays.
        connectionRegistry.addViewerJoinedListener { conversationId, viewer ->
            turnHost?.replayLoopStatus(ConversationId(conversationId), viewer)
        }
    }

    /**
     * Delivers device-wide `agent_updated` frames to every live connection allowed to read agents
     * ([IrohPeerCapabilities.CHAT_READ], the `agent.list` capability), on its stream channel.
     */
    fun agentChangeTarget(): AgentChangeTarget = AgentChangeTarget { frame ->
        val result = connectionRegistry.broadcast(frame) { it.receivesAgentEvents() }
        Telemetry.event("IrohNode", "agent_updated.broadcast", "recipients" attr result.recipients, "delivered" attr result.delivered)
    }

    /**
     * Delivers device-wide `conversation_updated` frames to every live connection allowed to read
     * every conversation (see [ViewerHandle.receivesConversationEvents]), on its stream channel.
     */
    fun conversationChangeTarget(): ConversationChangeTarget = ConversationChangeTarget { frame ->
        // letta-mobile-wisqe.2: the frame names a conversation and its agent; agent-scoped peers get only their agents'.
        val owner = runCatching {
            (Json.parseToJsonElement(frame) as? JsonObject)?.get("agent_id")?.let { it as? JsonPrimitive }?.content
        }.getOrNull()?.let(::AgentId)
        val result = connectionRegistry.broadcast(frame) { it.receivesConversationUpdateOf(owner) }
        Telemetry.event(
            "IrohNode",
            "conversation_updated.broadcast",
            "recipients" attr result.recipients,
            "delivered" attr result.delivered,
        )
    }

    /**
     * Delivers the host's `pins_updated` frames (the whole pinned-agents list) to every live connection
     * allowed to read agents across the whole host ([ViewerHandle.receivesPinsEvents]); agent-scoped peers get none.
     */
    suspend fun broadcastPinsUpdated(frame: String) {
        val result = connectionRegistry.broadcast(frame) { it.receivesPinsEvents() }
        Telemetry.event("IrohNode", "pins_updated.broadcast", "recipients" attr result.recipients, "delivered" attr result.delivered)
    }

    /**
     * Delivers the host's `shell_state_updated` frames (the whole shell state document) to every live connection
     * allowed to read it ([ViewerHandle.receivesShellStateEvents]); agent-scoped peers get none.
     */
    suspend fun broadcastShellStateUpdated(frame: String) {
        val result = connectionRegistry.broadcast(frame) { it.receivesShellStateEvents() }
        Telemetry.event(
            "IrohNode", "shell_state_updated.broadcast", "recipients" attr result.recipients, "delivered" attr result.delivered,
        )
    }

    /** Broadcasts inbox activity without changing any viewer subscription. Old clients receive nothing. */
    fun conversationActivityTarget(): ConversationChangeTarget = ConversationChangeTarget { frame ->
        val activity = kotlinx.serialization.json.Json.decodeFromString(
            com.letta.mobile.data.transport.ServerFrame.ConversationActivity.serializer(), frame,
        )
        connectionRegistry.broadcast(frame) { it.receivesConversationActivity(AgentId(activity.agentId)) }
    }

    /** Whether any connection views [conversation] as registered, so a push to its viewers can be skipped when none do. */
    fun isConversationViewed(conversation: ConversationId): Boolean = connectionRegistry.isViewed(conversation)

    /** The live viewers of each conversation, for pushes that must reach only that conversation's viewers. */
    fun conversationViewers(): ConversationViewers = { conversation -> connectionRegistry.viewersFor(conversation.value) }

    /**
     * Wires [publisher] to this endpoint. Its
     * `subagents_updated` frames go to the connections viewing the frame's
     * conversation (the same audience as that conversation's turn stream), and
     * each connection that starts viewing a conversation gets its snapshot
     * replayed to it alone.
     */
    fun attachSubagentPublisher(publisher: SubagentRegistryPublisher) {
        publisher.attach { conversationId, frame ->
            connectionRegistry.viewersFor(conversationId).count { it.writeFrame(frame) }
        }
        connectionRegistry.addViewerJoinedListener { conversationId, viewer ->
            publisher.replayTo(conversationId, viewer)
        }
    }

    /**
     * Wires [publisher] to this endpoint. Its `pending_approvals` frames go to
     * the connections viewing the frame's conversation (the audience of that conversation's turn
     * stream), and each connection that starts viewing a conversation gets its parked requests
     * replayed to it alone.
     */
    fun attachPendingApprovalsPublisher(publisher: PendingApprovalsPublisher) {
        publisher.attach { conversationId, frame ->
            connectionRegistry.viewersFor(conversationId).count { it.writeFrame(frame) }
        }
        connectionRegistry.addViewerJoinedListener { conversationId, viewer ->
            publisher.replayTo(conversationId, viewer)
        }
    }

    /**
     * Wires [publisher] to this endpoint. Its `permission_mode` frames go to the
     * connections viewing the frame's conversation; a viewer that joins later reads the mode itself
     * (`permission_mode.get`), so nothing is replayed.
     */
    fun attachPermissionModePublisher(publisher: PermissionModePublisher) {
        publisher.attach { conversationId, frame ->
            connectionRegistry.viewersFor(conversationId).count { it.writeFrame(frame) }
        }
    }

    /** Forwards and replays host-confirmed toolsets to authorized conversation viewers. */
    fun attachToolsetStatuses(client: com.letta.mobile.data.transport.appserver.AppServerClient): Job =
        bindToolsetDeviceStatuses(scope, client, connectionRegistry)

    @Volatile
    private var appToolRelay: AppToolRelayHost? = null

    /**
     * Wires [relay] to this endpoint: every App Server connection accepted after this gets a relay
     * link, and a relayed call goes to the connections viewing the caller's conversation (the
     * audience of its turn stream). Call before [start].
     */
    fun attachAppToolRelay(relay: AppToolRelayHost) {
        appToolRelay = relay
        relay.bindViewers { conversation -> connectionRegistry.viewersFor(conversation).mapNotNull(::appToolRelayLinkOf) }
    }

    private fun appToolRelayLinkOf(viewer: ViewerHandle): AppToolRelayLink? =
        appServerConnections[viewer.connectionId]?.firstOrNull { it.isViewer(viewer) }?.appToolRelayLink

    /**
     * The admin RPC router for this endpoint. Created lazily so handlers can
     * register before [start] is called. Passed to every incoming connection.
     */
    val adminRpcRouter: AdminRpcRouter
        get() {
            val r = _adminRpcRouter
            if (r != null) return r
            val created = AdminRpcRouter()
            _adminRpcRouter = created
            return created
        }

    /**
     * Returns the full EndpointAddr (node ID + relay + direct addresses).
     * This is the canonical dialable address for this endpoint.
     */
    fun addr(): EndpointAddr {
        val ep = checkNotNull(endpoint) { "IrohNodeEndpoint not created yet" }
        return ep.addr()
    }

    /** Dial using this endpoint's stable host identity, not a second protocol-specific endpoint. */
    suspend fun connect(remote: EndpointAddr, protocolAlpn: ByteArray): Connection {
        require(protocolAlpn.contentEquals(alpn) ||
            canvasRelay?.handles(protocolAlpn) == true ||
            protocolHandlers.any { it.alpn.contentEquals(protocolAlpn) }) { "Unregistered Iroh protocol ALPN" }
        return checkNotNull(endpoint) { "IrohNodeEndpoint not created yet" }.connect(remote, protocolAlpn)
    }

    /**
     * Returns the node ID as a hex string (64 hex characters = 32 bytes).
     */
    fun nodeIdHex(): String {
        return nodeIdHex(addr())
    }

    /**
     * Returns a serialized iroh ticket string that encodes the full endpoint address
     * (node ID + relay URL + direct addresses). This can be parsed back into an
     * EndpointAddr for dialing.
     */
    fun ticketString(): String {
        val endpointAddr = addr()
        val ticket = EndpointTicket.fromAddr(endpointAddr)
        return ticket.toString()
    }

    suspend fun create() {
        require(endpoint == null) { "IrohNodeEndpoint already created" }
        val relayMode = IrohRelayConfigMapper.toRelayMode(relayConfig)
        endpoint = Endpoint.bind(
            EndpointOptions(
                preset = object : Preset {
                    override fun apply(builder: EndpointBuilder) {
                        builder.applyN0()
                    }
                },
                bindAddr = bindAddr,
                secretKey = resolveSecretKeyStore().loadOrCreate(),
                alpns = listOf(alpn) + canvasRelay?.alpns.orEmpty() + protocolHandlers.map { it.alpn },
                relayMode = relayMode,
            )
        ).also { ep ->
            awaitDialable(ep)
            attachWatchers(ep)
            emitEndpointStatus(ep, "online")
        }
    }

    /**
     * Waits until [ep] can be dialed. With a relay that is [Endpoint.online] (a home relay is
     * connected). A direct-only endpoint never gets a home relay, so `online()` would never return:
     * it is dialable once it reports a direct address, which its ticket then carries.
     */
    private suspend fun awaitDialable(ep: Endpoint) {
        when (relayConfig) {
            is IrohRelayConfig.Default, is IrohRelayConfig.Custom -> ep.online()
            is IrohRelayConfig.Disabled -> withTimeoutOrNull(DIRECT_ADDRESS_WAIT_MS.milliseconds) {
                while (ep.addr().directAddresses().isEmpty()) delay(DIRECT_ADDRESS_POLL_MS.milliseconds)
            }
        }
    }

    private fun resolveSecretKeyStore(): IrohSecretKeyStore = secretKeyStore ?: when {
        secretKey != null -> FixedIrohSecretKeyStore(secretKey)
        secretKeyPath != null -> FileIrohSecretKeyStore(secretKeyPath)
        else -> EphemeralIrohSecretKeyStore()
    }

    private fun attachWatchers(ep: Endpoint) {
        runCatching {
            ep.watchAddr(object : AddrChangeCallback {
                override suspend fun onChange(addr: EndpointAddr) {
                    Telemetry.event(
                        "IrohNode", "addr.changed",
                        "nodeId" attr nodeIdHex(addr),
                        "relay" attr (addr.relayUrl() ?: ""),
                        "directAddresses" attr addr.directAddresses().joinToString(","),
                    )
                }
            })
        }
        runCatching {
            ep.watchHomeRelay(object : HomeRelayCallback {
                override suspend fun onChange(relays: List<String>) {
                    Telemetry.event("IrohNode", "homeRelay.changed", "relays" attr relays.joinToString(","))
                }
            })
        }
        runCatching {
            ep.watchNetworkChange(object : NetworkChangeCallback {
                override suspend fun onChange() {
                    Telemetry.event("IrohNode", "network.changed")
                    emitEndpointStatus(ep, "network_changed")
                }
            })
        }
    }

    private fun emitEndpointStatus(ep: Endpoint, status: String) {
        val endpointAddr = ep.addr()
        Telemetry.event(
            "IrohNode", "endpoint.status",
            "status" attr status,
            "nodeId" attr nodeIdHex(endpointAddr),
            "relay" attr (endpointAddr.relayUrl() ?: ""),
            "directAddresses" attr endpointAddr.directAddresses().joinToString(","),
        )
    }

    private fun nodeIdHex(endpointAddr: EndpointAddr): String =
        IrohDiagnostics.endpointIdHex(endpointAddr.id())

    private val irohExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Telemetry.event(
            "IrohNode",
            "crash.caught",
            "error" attr (throwable.message ?: throwable.toString()),
            "class" attr throwable::class.simpleName,
        )
    }

    /**
     * Creates this endpoint generation's [NodeTurnHost] and starts its relay of the turns the App Server starts by
     * itself, so they reach the feed and the conversation's viewers like a client's turn.
     */
    internal fun startTurnHost(controller: AppServerController): NodeTurnHost {
        val host = NodeTurnHost.childOf(scope, turnJournal)
        turnHost = host
        servingController = controller
        controller.observeRuntimeFrames()?.let { frames ->
            host.relayServerStartedTurns(
                frames,
                TurnFanoutDelivery(
                    viewersFor = connectionRegistry::viewersFor,
                    unregisterViewer = connectionRegistry::unregister,
                    observerWrites = host.observerWrites,
                ),
            )
        }
        return host
    }

    fun start(controller: AppServerController) {
        val ep = checkNotNull(endpoint) { "IrohNodeEndpoint not created yet" }
        require(acceptJob == null) { "IrohNodeEndpoint already started" }
        startTurnHost(controller)

        acceptJob = scope.launch(irohExceptionHandler) {
            while (isActive) {
                try {
                    val incoming = withTimeout(ACCEPT_TIMEOUT_MS.milliseconds) {
                        ep.acceptNext()
                    }
                    if (incoming == null) {
                        // A null accept is NOT a terminal signal by itself: breaking
                        // here left the endpoint bound-but-deaf (UDP port alive,
                        // no accepts) until the wrapper was restarted. Only stop
                        // when the scope is cancelled (shutdown()); otherwise log
                        // and keep accepting.
                        Telemetry.event(
                            "IrohNode", "incoming.accept.null",
                            level = Telemetry.Level.WARN,
                        )
                        if (!isActive) break
                        delay(ACCEPT_NULL_RETRY_MS.milliseconds)
                        continue
                    }
                    Telemetry.event("IrohNode", "incoming.accepting")
                    // Handshake + serve run in a CHILD coroutine so a stalled
                    // client handshake (peer died mid-connect) can never block
                    // the accept loop for other clients — the exact wedge that
                    // made every subsequent dial time out.
                    launch { serveIncoming(incoming) }
                } catch (_: TimeoutCancellationException) {
                    continue
                } catch (e: CancellationException) {
                    // After the timeout case (a subclass): shutdown and any other cancellation end the loop.
                    throw e
                } catch (e: Exception) {
                    if (!isActive) break
                    Telemetry.event(
                        "IrohNode",
                        "incoming.accept.failed",
                        "error" attr (e.message ?: e.toString()),
                        "class" attr e::class.simpleName,
                    )
                    delay(ACCEPT_FAILURE_RETRY_MS.milliseconds)
                }
            }
        }
    }

    /** Handshake and serve one accepted connection; failures are logged, never thrown into the accept loop. */
    private suspend fun serveIncoming(incoming: Incoming) {
        var offeredAlpn = ""
        try {
            val accepting = incoming.accept()
            val peerAlpn = accepting.alpn()
            offeredAlpn = peerAlpn.decodeToString()
            val connection = withTimeout(HANDSHAKE_TIMEOUT_MS.milliseconds) { accepting.connect() }
            val remoteId = IrohDiagnostics.endpointIdHex(connection.remoteId())
            Telemetry.event("IrohNode", "incoming.connected", "remoteEndpointId" attr remoteId)
            val handler = protocolHandlers.firstOrNull { it.alpn.contentEquals(peerAlpn) }
            if (handler != null) {
                serveProtocol(handler, connection, remoteId)
                return
            }
            if (!isPeerAllowed(remoteId)) {
                Telemetry.event("IrohNode", "auth.failed", "remoteEndpointId" attr remoteId, "reason" attr "peer_not_allowed")
                runCatching { connection.close(4403L, "peer_not_allowed".encodeToByteArray()) }
                return
            }
            dispatchAcceptedConnection(AcceptedConnection(peerAlpn, connection, remoteId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handshakeFailures.record(e, alpn = offeredAlpn, origin = IrohFailureLog.incomingRemote(incoming))
        }
    }

    private class AcceptedConnection(
        val peerAlpn: ByteArray,
        val connection: Connection,
        val remoteId: String,
    )

    private suspend fun dispatchAcceptedConnection(accepted: AcceptedConnection) {
        val relay = canvasRelay
        if (relay != null && relay.handles(accepted.peerAlpn)) {
            serveCanvas(relay, accepted.peerAlpn, accepted.connection, accepted.remoteId)
            return
        }
        if (!accepted.peerAlpn.contentEquals(alpn)) {
            accepted.connection.close(4404L, "unknown_alpn".encodeToByteArray())
            return
        }
        serveAppServer(accepted.connection)
    }

    private suspend fun serveProtocol(
        handler: IrohNodeProtocolHandler,
        connection: Connection,
        remoteId: String,
    ) {
        if (!handler.authorize(remoteId)) {
            Telemetry.event("IrohNode", "protocol.rejected", "remoteEndpointId" attr remoteId)
            connection.close(4403L, "protocol_not_authorized".encodeToByteArray())
            return
        }
        handler.accept(connection, remoteId)
    }

    /** Serves an admitted App Server connection as a carrier link ([appServerLinks]), then releases its handle. */
    private suspend fun serveAppServer(connection: Connection) {
        try {
            appServerLinks(connection, this)
        } finally {
            // The QUIC connection was closed with the link; this frees the binding's native handle.
            runCatching { connection.close() }
        }
    }

    /**
     * Serves one carrier link as a node connection: an Iroh peer this endpoint accepted (through [appServerLinks]), or
     * the shell in this process over the in-process carrier. Every link gets the same router, viewers, turns and feed;
     * only the local shell's auth differs, which the carrier verified and which is admitted as an admin peer by
     * construction (a static allowlist of its identity: no token, no pairing record). Any other peer meets this
     * endpoint's own policy. A link that arrives before [start] (or after [shutdown]) is refused.
     */
    internal suspend fun serveWire(wire: NodeConnectionWire) {
        val controller = servingController
        val host = turnHost
        if (controller == null || host == null) {
            Telemetry.event("IrohNode", "link.refused", "remoteEndpointId" attr wire.peerId, "reason" attr "not_started")
            wire.closeAfterServe()
            return
        }
        if (!wire.localShell && !isPeerAllowed(wire.peerId)) {
            Telemetry.event("IrohNode", "auth.failed", "remoteEndpointId" attr wire.peerId, "reason" attr "peer_not_allowed")
            runCatching { wire.close(4403L, "peer_not_allowed") }
            return
        }
        val authentication = if (wire.localShell) {
            IrohConnectionAuthentication(
                authPolicy = IrohAuthPolicy.PeerAllowlist(setOf(wire.peerId)),
                remoteEndpointId = wire.peerId,
            )
        } else {
            // A remote peer: this endpoint's policy, its shared verifier (so redials keep their failure budget), pairing.
            IrohConnectionAuthentication(
                authPolicy = authPolicy,
                authVerifier = authVerifier,
                pairingService = pairingService,
                remoteEndpointId = wire.peerId,
            )
        }
        val nodeConnection = IrohNodeConnection(
            wire = wire,
            controller = controller,
            adminRpcRouter = adminRpcRouter,
            authentication = authentication,
            connectionRegistry = connectionRegistry,
            clientMessageDedupe = clientMessageDedupe,
            turnHost = host,
            turnIdentity = turnIdentity,
        )
        serveNodeConnection(nodeConnection)
    }

    /** Serves [nodeConnection] with its app tool relay link, tracked by peer for revocation and canvas admission. */
    private suspend fun serveNodeConnection(nodeConnection: IrohNodeConnection) {
        val remoteId = nodeConnection.remoteEndpointId
        val relayLink = appToolRelay?.link(send = nodeConnection::writeAppToolFrame, ready = { nodeConnection.acceptsAppToolCalls })
        nodeConnection.appToolRelayLink = relayLink
        // Add and remove inside compute, so a peer's entry lives exactly as long as one of its connections:
        // a peer that connects and leaves (any number of fresh NodeIds) keeps nothing here.
        appServerConnections.compute(remoteId) { _, live ->
            (live ?: java.util.concurrent.ConcurrentHashMap.newKeySet()).apply { add(nodeConnection) }
        }
        try {
            nodeConnection.serve()
        } finally {
            appServerConnections.computeIfPresent(remoteId) { _, live ->
                live.apply { remove(nodeConnection) }.takeIf { it.isNotEmpty() }
            }
            relayLink?.close()
        }
    }

    /**
     * A canvas connection is served only for a peer that is signed in on the App Server here: the
     * canvas protocols carry no credentials of their own, and the apps open them once their App
     * Server connection is ready.
     */
    private fun serveCanvas(
        relay: com.letta.mobile.data.transport.iroh.IrohCanvasRelay,
        alpn: ByteArray,
        connection: computer.iroh.Connection,
        remoteId: String,
    ) {
        val authenticated = appServerConnections[remoteId].orEmpty().any { it.isAuthenticated }
        if (!authenticated) {
            Telemetry.event("IrohNode", "canvas.rejected", "remoteEndpointId" attr remoteId, "reason" attr "not_authenticated")
            runCatching { connection.close(4401L, "not_authenticated".encodeToByteArray()) }
            return
        }
        Telemetry.event("IrohNode", "canvas.accepted", "remoteEndpointId" attr remoteId, "alpn" attr alpn.decodeToString())
        relay.accept(alpn, connection, origin = remoteId, hostNodeId = HostId(nodeIdHex()))
    }

    /**
     * Connection admission (not authentication). With a static allowlist, only
     * listed NodeIds connect — unless pairing is configured, in which case paired
     * NodeIds and unknown peers that come to redeem an invite are admitted too.
     * Admission never authenticates. In the auth frame ([PeerSessionAuth]):
     * a paired NodeId or a valid invite authenticates regardless of the
     * allowlist; the static bearer token and allowlist
     * pre-authentication ([IrohBearerAuthVerifier]) are honoured only for
     * listed NodeIds when an allowlist is set, so admitted but
     * unlisted peers cannot use a leaked token.
     */
    private fun isPeerAllowed(remoteId: String): Boolean =
        authPolicy.allowedPeerIds.isEmpty() || remoteId in authPolicy.allowedPeerIds || pairingService != null

    suspend fun shutdown() {
        acceptJob?.cancel()
        acceptJob = null
        servingController = null
        shutdownDrainCut = null
        turnHost?.shutdown()
        turnHost = null

        endpoint?.let {
            runCatching { it.shutdown() }
            runCatching { it.close() }
        }
        endpoint = null
    }

    /**
     * Begins this endpoint generation's shutdown drain: its [NodeTurnHost] refuses new turn inputs
     * while its live turns keep running and relaying, so the shutdown path can then wait for them
     * with [awaitShutdownDrain] before [shutdown] closes the App Server connection — instead of
     * cancelling every in-flight turn with cause "transport". Returns false when no generation
     * started or a drain already began (in both cases there is nothing to wait for).
     */
    fun beginShutdownDrain(bound: Duration, nowMs: () -> Long = System::currentTimeMillis): Boolean {
        val host = turnHost ?: return false
        shutdownDrainCut = host.beginDrain(bound, nowMs) ?: return false
        return true
    }

    /** Cuts an in-flight [awaitShutdownDrain] at once: a second shutdown signal, or an explicit skip. */
    fun cutShutdownDrainWait() {
        shutdownDrainCut?.invoke()
    }

    /** Waits for the drain begun by [beginShutdownDrain]: every live turn ended, its bound elapsed, or [cutShutdownDrainWait] cut it. */
    suspend fun awaitShutdownDrain() {
        turnHost?.awaitDrained()
    }

    companion object {
        val DEFAULT_ALPN = "/letta/appserver/0".toByteArray()
        private const val ACCEPT_TIMEOUT_MS = 120_000L
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val ACCEPT_NULL_RETRY_MS = 1_000L
        private const val ACCEPT_FAILURE_RETRY_MS = 500L
        private const val DIRECT_ADDRESS_WAIT_MS = 10_000L
        private const val DIRECT_ADDRESS_POLL_MS = 50L
    }
}

/** A peer-owned protocol accepted on the host endpoint, with its own authorization policy. */
interface IrohNodeProtocolHandler {
    val alpn: ByteArray
    /** Opt in each remote peer explicitly; an App Server login does not grant notebook access. */
    fun authorize(remoteEndpointId: String): Boolean
    suspend fun accept(connection: Connection, remoteEndpointId: String)
}
