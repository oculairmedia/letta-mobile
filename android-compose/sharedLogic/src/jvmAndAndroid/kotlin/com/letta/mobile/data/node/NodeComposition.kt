package com.letta.mobile.data.node

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.AgentChangeNotifier
import com.letta.mobile.data.controller.node.iroh.ControllerSubagentRegistrySource
import com.letta.mobile.data.controller.node.iroh.ConversationChangeNotifier
import com.letta.mobile.data.controller.node.iroh.FileTurnIdentityStore
import com.letta.mobile.data.controller.node.iroh.IrohEndpointBinding
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.controller.node.iroh.LateBoundConversationRows
import com.letta.mobile.data.controller.node.iroh.PendingApprovalsPublisher
import com.letta.mobile.data.controller.node.iroh.PermissionModePublisher
import com.letta.mobile.data.controller.node.iroh.SubagentRegistryPublisher
import com.letta.mobile.data.controller.node.iroh.feed.FeedJournalStore
import com.letta.mobile.data.controller.node.iroh.feed.FeedPusher
import com.letta.mobile.data.controller.node.iroh.feed.FeedRoutes
import com.letta.mobile.data.controller.node.iroh.pins.AgentPinsPublisher
import com.letta.mobile.data.controller.node.iroh.pins.AgentPinsRoutes
import com.letta.mobile.data.controller.node.iroh.servicecredentials.ServiceCredentialRoutes
import com.letta.mobile.data.controller.node.iroh.shellstate.ShellStatePublisher
import com.letta.mobile.data.controller.node.iroh.shellstate.ShellStateRoutes
import com.letta.mobile.data.controller.node.iroh.voice.VoiceEndpointRoutes
import com.letta.mobile.data.node.carrier.InProcessNodeCarrier
import com.letta.mobile.data.node.carrier.NodeAddress
import com.letta.mobile.data.node.carrier.NodePeerAcceptor
import com.letta.mobile.data.node.carrier.NodePeerIdentity
import com.letta.mobile.data.runtime.TurnIdentityLedger
import com.letta.mobile.data.runtime.host.LocalAppServerLease
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

/**
 * One Baken node: the runtime controller over its App Server, the admin_rpc router, the durable
 * turn journal and its feed, the publishers that push shared state to connected peers, pairing, and
 * the Iroh endpoint that serves all of it. Hosts (the Linux service, the desktop) build one per
 * process and differ only in their [NodePorts] and [NodeConfig].
 *
 * The node is assembled in order, because an Iroh endpoint's protocols are fixed when it binds and
 * the admin router needs the App Server client:
 * 1. [endpoint] builds the endpoint with every protocol handler; the host then binds it.
 * 2. [connect] builds the controller over the App Server (a [LocalAppServerLease] or an endpoint).
 * 3. [installAdminRpc] builds the router and its publishers and attaches them to the endpoint.
 * 4. [start] starts the tools socket and begins accepting peers.
 *
 * @property scope the node's lifetime; cancelling it stops everything the node launched.
 */
class NodeComposition(
    private val config: NodeConfig,
    private val ports: NodePorts,
    val scope: CoroutineScope,
) {
    /** Conversation rows for the turn identity ledger, bound to the App Server client once it exists. */
    val identityRows = LateBoundConversationRows()

    /** The node's stream-to-stored identity ledger, persisted under `turn-identity/` in its state directory. */
    val turnIdentity = TurnIdentityLedger(
        store = FileTurnIdentityStore.inHostState(config.hostStateDirectory),
        rows = identityRows,
        clock = System::currentTimeMillis,
    )

    /**
     * The node's durable turn journal, beside its other state so a restart keeps finished turns. The
     * endpoint's turns write to it and `feed.subscribe` reads it.
     */
    val feedJournal: FeedJournalStore by lazy {
        FeedJournalStore(config.hostStateDirectory.toPath().resolve("conversation-feed"))
    }

    private val connector = NodeAppServerConnector(ports, config.requestTimeoutMs, scope)

    /** The node's Iroh endpoint with every protocol handler in [parts]; the host creates (binds) it. */
    fun endpoint(parts: NodeEndpointParts): IrohNodeEndpoint = IrohNodeEndpoint(
        scope = scope,
        binding = IrohEndpointBinding(bindAddr = config.endpoint.bindAddr, secretKeyPath = config.endpoint.secretKeyPath),
        authPolicy = parts.authPolicy,
        pairingService = parts.pairingService,
        canvasRelay = parts.canvasRelay,
        protocolHandlers = parts.protocolHandlers,
        turnIdentity = turnIdentity,
        turnJournal = feedJournal,
    )

    /** The controller over the App Server this node supervises, advertising [tools] to its runtimes. */
    fun connect(lease: LocalAppServerLease, tools: ExternalToolRegistry): NodeAppServerConnection =
        connect(AppServerEndpoint.fromWebSocketUrl(lease.serverUrl, lease.bearerToken), tools)

    /** The controller over [appServer]; null runs the node without an App Server (Iroh-only stub mode). */
    fun connect(appServer: AppServerEndpoint?, tools: ExternalToolRegistry): NodeAppServerConnection =
        connector.connect(appServer, tools)

    /**
     * Builds the admin_rpc router with the node's host-held state routes and [extras], wires every
     * publisher to [endpoint]'s peers, and serves the router on it.
     */
    fun installAdminRpc(endpoint: IrohNodeEndpoint, connection: NodeAppServerConnection, extras: NodeAdminExtras) {
        connection.client?.let(identityRows::bind)
        val publishers = NodePublishers(scope)
        var subagentPublisher: SubagentRegistryPublisher? = null
        val router = adminRouterSpec(connection, extras, publishers) { source ->
            SubagentRegistryPublisher(scope, source).also { subagentPublisher = it }
        }.build()
        extras.hostRoutes.forEach { it.register(router) }
        connection.client?.let(endpoint::attachToolsetStatuses)
        endpoint.attachPermissionModePublisher(publishers.permissionModes)
        endpoint.adminRpcRouter.copyHandlersFrom(router)
        subagentPublisher?.let(endpoint::attachSubagentPublisher)
        // Parked approvals (rules, blocked path, diffs) reach the viewers.
        endpoint.attachPendingApprovalsPublisher(PendingApprovalsPublisher(scope, connection.controller.pendingApprovals))
        publishers.attach(endpoint)
        // The journal's writes reach the connections viewing their conversation, as feed_delta frames; it registers itself on the journal.
        FeedPusher(scope, feedJournal, endpoint.conversationViewers(), endpoint::isConversationViewed)
        ports.log.info(
            "admin_rpc handlers registered " +
                "(methods: ${router.methodCount}, " +
                "subagent_registry_v1: ${AdminRpcRegistry.subagentMethods.all { it in router.registeredMethods }})",
        )
        ports.log.info("local_backend_store: " + (config.admin.localBackendDir ?: "UNSET (block.list/agent.context fail closed)"))
    }

    /** Starts the host's tools socket over [connection]'s client, then accepts peers on [endpoint]. */
    suspend fun start(endpoint: IrohNodeEndpoint, connection: NodeAppServerConnection) {
        ports.toolsSocket.start(scope, connection.client)
        endpoint.start(connection.controller)
    }

    /**
     * The node's end of a carrier link from anywhere but this process: each link is served as one node connection on
     * [endpoint], with the same router, auth, gates, viewers, turns and feed as an Iroh peer accepted there, and every
     * peer meets the endpoint's policy, whatever identity its link reports. Links are served once [start] has run; one
     * that arrives earlier is closed.
     */
    internal fun peerAcceptor(endpoint: IrohNodeEndpoint): NodePeerAcceptor = acceptor(endpoint, localShell = false)

    /** Like [peerAcceptor], but every link is the in-process shell's, admitted as an admin peer by construction. */
    private fun localShellAcceptor(endpoint: IrohNodeEndpoint): NodePeerAcceptor = acceptor(endpoint, localShell = true)

    private fun acceptor(endpoint: IrohNodeEndpoint, localShell: Boolean) = NodePeerAcceptor { link ->
        coroutineScope { endpoint.serveWire(NodePeerLinkWire(link, this, localShell)) }
    }

    /**
     * The carrier the shell in this process attaches to [endpoint]'s node with, at [IN_PROCESS_ADDRESS]. The shell
     * sees [node] as the node's identity: by default the endpoint's own Iroh id, so it sees the same node over either
     * carrier (the endpoint must be bound for the default).
     */
    internal fun inProcessCarrier(
        endpoint: IrohNodeEndpoint,
        node: NodePeerIdentity = NodePeerIdentity(endpoint.nodeIdHex()),
    ): InProcessNodeCarrier = InProcessNodeCarrier(IN_PROCESS_NAME, node, localShellAcceptor(endpoint), scope)

    companion object {
        private const val IN_PROCESS_NAME = "node"

        /** The address the shell dials its own process's node at over the in-process carrier. */
        internal val IN_PROCESS_ADDRESS: NodeAddress = NodeAddress(InProcessNodeCarrier.SCHEME + IN_PROCESS_NAME)
    }

    private fun adminRouterSpec(
        connection: NodeAppServerConnection,
        extras: NodeAdminExtras,
        publishers: NodePublishers,
        subagentPublisher: (ControllerSubagentRegistrySource) -> SubagentRegistryPublisher,
    ): NodeAdminRouterSpec {
        val admin = config.admin
        val stateDir = config.hostStateDirectory.toPath()
        return NodeAdminRouterSpec(
            controller = connection.controller,
            pairingService = extras.pairingService,
            nativeClient = connection.client,
            vibesyncBaseUrl = admin.vibesyncBaseUrl,
            beadsBaseUrl = admin.beadsBaseUrl,
            beadsTokenFile = admin.beadsTokenFile,
            subagentRegistryFile = admin.subagentRegistryFile,
            appServerProcessIdentity = ports.processIdentity,
            skillsDir = admin.skillsDir,
            localBackendDir = admin.localBackendDir,
            eventScope = scope,
            agentChanges = publishers.agentChanges,
            conversationChanges = publishers.conversationChanges,
            modelExposureFile = config.modelExposureFile,
            runRecordsFile = admin.runRecordsFile,
            turnIdentity = turnIdentity,
            pairingQr = extras.pairingQr,
            subagentPublisher = subagentPublisher,
            permissionModes = publishers.permissionModes,
            pluginRoutes = extras.pluginRoutes,
            canvasMountRoutes = extras.canvasMountRoutes,
            // The shared speech setup lives beside the model exposure decisions, its key in an owner-only
            // file; each agent's voice core is read from its own `voice` memory block.
            voiceRoutes = VoiceEndpointRoutes.forHost(stateDir, extras.voiceMemoryDir),
            pinsRoutes = AgentPinsRoutes.forHost(stateDir, publishers.pins),
            shellStateRoutes = ShellStateRoutes.forHost(stateDir, publishers.shellState),
            // Non-LLM service credentials live beside them too; the secrets in an owner-only file.
            serviceCredentialRoutes = ServiceCredentialRoutes.forHost(stateDir),
            feedRoutes = FeedRoutes.forHost(feedJournal, pushes = true),
        )
    }
}

/** The publishers that push the node's shared state to every connected peer. */
private class NodePublishers(scope: CoroutineScope) {
    val agentChanges = AgentChangeNotifier(scope)
    val conversationChanges = ConversationChangeNotifier(scope)

    /** A confirmed permission_mode.set reaches every viewer's chip. */
    val permissionModes = PermissionModePublisher(scope)

    /** The pinned agents every device shares. */
    val pins = AgentPinsPublisher(scope)

    /** The shell state document every device shares. */
    val shellState = ShellStatePublisher(scope)

    fun attach(endpoint: IrohNodeEndpoint) {
        agentChanges.attach(endpoint.agentChangeTarget())
        conversationChanges.attach(endpoint.conversationChangeTarget())
        pins.attach(endpoint::broadcastPinsUpdated)
        shellState.attach(endpoint::broadcastShellStateUpdated)
    }
}
