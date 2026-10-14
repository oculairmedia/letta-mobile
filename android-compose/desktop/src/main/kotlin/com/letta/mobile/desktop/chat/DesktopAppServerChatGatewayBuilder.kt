package com.letta.mobile.desktop.chat

import com.letta.mobile.data.runtime.answerApprovalReplaysFrom
import com.letta.mobile.data.canvas.CanvasExternalTools
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.meridian.AgentToolsModePolicy
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.controller.fanout.StreamIntegrityBinding
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.desktop.canvas.DesktopNotebookCanvasStore
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.PermissionModeRegistry
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.data.transport.appserver.AppServerTransport
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransport
import com.letta.mobile.data.transport.iroh.IrohAppServerTransportAdapter
import com.letta.mobile.desktop.security.DesktopIrohIdentity
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * Builds the desktop chat gateway backed by the App Server controller.
 *
 * Wires the controller stack (transport -> client -> turn engine) for either an
 * iroh:// backend (QUIC, peer to the Android path) or a WebSocket App Server,
 * authenticates the iroh session, and returns the [DesktopAppServerChatGateway] that routes
 * send/stream through the controller. Admin reads/writes share that same protocol session for
 * every backend (letta-mobile-ckmub.4: there is no HTTP admin path).
 *
 * Desktop does not use Hilt; callers inject this builder (or a test fake of
 * [DesktopAppServerChatGatewayFactory]) instead of constructing the stack inline.
 */
// Compatibility default; production injects its window-owned scope. The client scope below is a
// child of whichever scope that is, so it is not detached either.
@Suppress("NoDetachedCoroutineLifecycle")
class DesktopAppServerChatGatewayBuilder(
    /** Coroutine scope for the controller and transport. */
    private val controllerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * Stable client identity source (d6e8g.4): the desktop dials with the same
     * vault-protected NodeId across restarts instead of an ephemeral keypair.
     */
    private val irohIdentity: () -> ByteArray = { DesktopIrohIdentity.loadOrCreate() },
    /** Must be the registry the canvas UI registers into, or agent edits miss the open session. */
    private val canvasSessions: CanvasSessionRegistry = CanvasSessionRegistry(),
    /** HTTP `/app-server-info` probe run before every remote WebSocket dial (letta-mobile-bzvro.2). */
    private val preflightProbe: DesktopAppServerProbe = defaultDesktopAppServerProbe,
    /** letta-mobile-bzvro.13: the app's one permission-mode settings; null runs every runtime Unrestricted, with no chip. */
    permissionModeSettings: PermissionModeSettings? = null,
) : DesktopAppServerChatGatewayFactory {
    private val permissionModes = permissionModeSettings?.modes

    override suspend fun create(
        lettaConfig: LettaConfig,
        appServerConfig: DesktopAppServerRuntimeConfig,
    ): DesktopAppServerChatGateway = withContext(Dispatchers.IO) {
        val serverUrl = appServerConfig.serverUrl
            ?: throw IllegalArgumentException(
                "An App Server address (ws://, wss:// or iroh://) is required. Set the Remote host address in " +
                    "Settings, or ${DesktopAppServerRuntimeConfig.SERVER_URL_PROPERTY} / " +
                    "${DesktopAppServerRuntimeConfig.SERVER_URL_ENV}.",
            )

        val isIroh = IrohChannelTransport.isIrohUrl(serverUrl)
        // The bundled local child was just started by us and is checked by the readiness handshake.
        if (!isIroh && lettaConfig.mode != LettaConfig.Mode.LOCAL) {
            preflightDesktopAppServer(serverUrl, lettaConfig.accessToken, preflightProbe)
        }
        val (transport, transportResources) = if (isIroh) {
            buildIrohTransport(serverUrl, lettaConfig)
        } else {
            buildWebSocketTransport(serverUrl, lettaConfig)
        }

        val clientScope = CoroutineScope(
            controllerScope.coroutineContext + SupervisorJob(controllerScope.coroutineContext[Job]),
        )
        val client = DefaultAppServerClient(transport, parentScope = clientScope)
        var localClientLease: AutoCloseable? = null
        var directClientLease: AutoCloseable? = null
        var eventRouter: AppServerRuntimeEventRouter? = null
        try {
            if (transport is KtorAppServerWebSocketTransport) {
                awaitDesktopAppServerReadiness(
                    DesktopAppServerReadinessProbe(
                        connectionState = transport.connectionState,
                        client = client,
                        expectation = readinessExpectationFor(lettaConfig),
                    ),
                )
            }
            if (lettaConfig.mode == LettaConfig.Mode.LOCAL) {
                localClientLease = DesktopLocalAppServerClientRegistry.shared.install(client)
            }
            if (!isIroh) {
                directClientLease = DesktopLocalAppServerClientRegistry.direct.install(client)
            }
            val router = AppServerRuntimeEventRouter()
            eventRouter = router
            startStreamIntegrity(router, client, clientScope, (transport as? IrohAppServerTransport)?.serverCapabilities)
            val turnEngine = buildDesktopAppServerTurnEngine(
                client = client,
                scope = controllerScope,
                externalToolRegistry = desktopCanvasToolRegistry(isIroh, canvasSessions),
                config = DesktopAppServerEngineConfig(
                    eventRouter = router,
                    // Over Iroh the node sets its own mode and the chip is not offered: the engine runs
                    // Unrestricted, as it always did, and the persisted default does not reach it.
                    permissionModes = permissionModes.takeUnless { isIroh },
                ),
            )
            DesktopAppServerChatGateway(
                turnEngine = turnEngine,
                client = client,
                transportResources = transportResources,
                permissionModes = permissionModes.takeUnless { isIroh },
                onClose = {
                    eventRouter.detach()
                    localClientLease?.close()
                    directClientLease?.close()
                    client.failPendingRequests("Desktop App Server gateway closed")
                    clientScope.cancel()
                },
            )
        } catch (error: Throwable) {
            eventRouter?.detach()
            localClientLease?.close()
            directClientLease?.close()
            client.failPendingRequests("Desktop App Server gateway creation failed")
            clientScope.cancel()
            transportResources.close()
            throw error
        }
    }

    /**
     * letta-mobile-bzvro.6: on a direct App Server socket every frame of the connection reaches
     * this client, so an `event_seq` gap is a lost frame: resync, and keep the watched runtimes on
     * the busy/idle sync cadence. letta-mobile-by4vm.50: an Iroh dial ([irohHostCapabilities] not
     * null) gets the same only when the host advertises ordered event_seq; an older host's relay
     * can put its seq on the wire out of order, so it is left to the reconnect path. Desktop has no
     * sync budget: the cadence always runs.
     */
    private fun startStreamIntegrity(
        router: AppServerRuntimeEventRouter,
        client: AppServerClient,
        scope: CoroutineScope,
        irohHostCapabilities: Set<String>?,
    ) {
        val binding = StreamIntegrityBinding(client, { "desktop-sync-${UUID.randomUUID()}" })
        if (irohHostCapabilities == null) {
            binding.bindAndStart(router, detectGaps = true, scope = scope)
        } else {
            binding.bindIfHostOrdersEventSeq(router, irohHostCapabilities, scope)
        }
    }

    private fun readinessExpectationFor(lettaConfig: LettaConfig): DesktopAppServerReadinessExpectation =
        if (lettaConfig.mode == LettaConfig.Mode.LOCAL) localDesktopAppServerExpectation else DesktopAppServerReadinessExpectation()

    /**
     * iroh://<ticket> — bind a local iroh endpoint, dial the backend over QUIC,
     * wait for the connection, and run the auth/capability handshake. Any
     * failure between bind and the authed hand-off tears the endpoint and
     * transport down before rethrowing (mirrors IrohChannelTransport.dial).
     */
    private suspend fun buildIrohTransport(
        serverUrl: String,
        lettaConfig: LettaConfig,
    ): Pair<IrohAppServerTransport, DesktopTransportResources> {
        val normalizedAddress = IrohChannelTransport.normalizeIrohAddress(serverUrl)
        val irohEndpoint = Endpoint.bind(
            EndpointOptions(relayMode = RelayMode.defaultMode(), secretKey = irohIdentity()),
        )
        var resources: DesktopTransportResources? = null
        try {
            val irohTransport = IrohAppServerTransportAdapter(irohEndpoint).createTransport(
                endpoint = AppServerEndpoint(scheme = "iroh", address = normalizedAddress),
                scope = controllerScope,
            ) as IrohAppServerTransport
            resources = DesktopTransportResources(irohEndpoint, irohTransport)
            irohTransport.awaitConnectionReady()
            authenticateDesktopIrohAppServer(
                client = DefaultAppServerClient(irohTransport),
                accessToken = lettaConfig.accessToken,
            )
            return irohTransport to resources
        } catch (t: Throwable) {
            resources?.close() ?: run {
                runCatching { irohEndpoint.shutdown() }
                runCatching { irohEndpoint.close() }
            }
            throw t
        }
    }

    private fun buildWebSocketTransport(
        serverUrl: String,
        lettaConfig: LettaConfig,
    ): Pair<AppServerTransport, DesktopTransportResources> {
        val endpoint = AppServerEndpoint.fromWebSocketUrl(url = serverUrl, bearerToken = lettaConfig.accessToken)
        val httpClient = createDesktopAppServerHttpClient()
        val transport = KtorAppServerWebSocketTransport(
            httpClient = httpClient,
            baseUrl = endpoint.address,
            scope = controllerScope,
            bearerToken = endpoint.bearerToken,
        )
        return transport to DesktopTransportResources.forWebSocket(transport, httpClient)
    }
}

/**
 * Desktop runs every turn Unrestricted unless a permission mode was chosen (letta-mobile-bzvro.13:
 * the composer chip per conversation, the settings card for the default); the default stays
 * Unrestricted. Under Unrestricted a Standard-mode approval_request would stall the turn, so the
 * engine auto-allows instead (parity with the Android iroh engine). Baking the mode into the engine lets
 * ensureRuntime's single runtime_start carry it — no eager
 * controller.startRuntime, no double runtime_start on first send (#831 Codex P2).
 *
 * Context management is letta-code's (letta-mobile-4rxis): the engine never stamps a context
 * window or compacts before a turn. letta-code compacts from the agent's own compaction settings
 * against the model's real window.
 */
internal data class DesktopAppServerEngineConfig(
    val eventRouter: AppServerRuntimeEventRouter = AppServerRuntimeEventRouter(),
    val permissionModes: PermissionModeRegistry? = null,
)

/**
 * The desktop's own canvas_* tools, for an App Server it reaches directly. On Iroh the host answers
 * canvas_* for every runtime it serves (letta-mobile-aknkw), so the desktop offers none there.
 */
internal fun desktopCanvasToolRegistry(
    isIroh: Boolean,
    canvasSessions: com.letta.mobile.data.canvas.CanvasSessionRegistry,
    agentToolsModes: AgentToolsModePolicy = desktopAgentToolsModePolicy(),
): ExternalToolRegistry = agentToolsModes.withoutCli().apply(
    ExternalToolRegistry.hostTools(
        if (isIroh) emptyList() else CanvasExternalTools.all(DesktopNotebookCanvasStore.documents, canvasSessions),
    ),
)

/**
 * The desktop's `agent-tools-mode` (letta-mobile-jna0o.9): `-Dletta.agentToolsMode` or
 * `LETTA_AGENT_TOOLS_MODE` (native|cli|meta), with per-agent `agentId=mode` pairs in
 * `-Dletta.agentToolsModeOverrides` / `LETTA_AGENT_TOOLS_MODE_OVERRIDES`. Native unless set. A
 * direct App Server has no `meridian` CLI front door, so cli means native here
 * ([AgentToolsModePolicy.withoutCli]); a value that names no mode is reported and ignored.
 */
internal fun desktopAgentToolsModePolicy(
    setting: (property: String, env: String) -> String? = { property, env -> System.getProperty(property) ?: System.getenv(env) },
): AgentToolsModePolicy = AgentToolsModePolicy.parse(
    setting("letta.agentToolsMode", "LETTA_AGENT_TOOLS_MODE"),
    setting("letta.agentToolsModeOverrides", "LETTA_AGENT_TOOLS_MODE_OVERRIDES"),
).getOrElse { invalid ->
    System.err.println("[letta-desktop] ${invalid.message}; agent tools stay native")
    AgentToolsModePolicy()
}

internal fun buildDesktopAppServerTurnEngine(
    client: AppServerClient,
    scope: CoroutineScope,
    externalToolRegistry: ExternalToolRegistry? = null,
    config: DesktopAppServerEngineConfig = DesktopAppServerEngineConfig(),
): AppServerTurnEngine {
    // lgns8.22.3: one inbound collector per desktop gateway generation.
    val router = config.eventRouter
    router.attach(scope, client.events)
    return AppServerTurnEngine(
        client = client,
        clientInfo = AppServerRuntimeStartClientInfo(
            name = "letta-desktop",
            title = "Letta Desktop",
            version = "0.2.0",
        ),
        permissionMode = AppServerPermissionMode.Unrestricted,
        permissionModeProvider = { command ->
            config.permissionModes?.modeFor(AppServerRuntimeScope(command.agentId.value, command.conversationId.value))
                ?: AppServerPermissionMode.Unrestricted
        },
        onPermissionModeInForce = { runtime, mode -> config.permissionModes?.observed(runtime, mode) },
        eventRouter = router,
        externalToolRegistry = externalToolRegistry,
    ).also { engine -> engine.answerApprovalReplaysFrom(router, scope) }
}

/**
 * The iroh auth exchange doubles as the transport handshake: it advertises the
 * frame_part chunked-frame capability so the server may split >1MiB frames.
 * Sent even with a blank token — servers without a required token still ack
 * and record capabilities (mirrors IrohChannelTransport.kt dial() and the
 * CLI probe's tokenless handshake). A failure response is always fatal: the
 * in-repo server (IrohNodeConnection.handleAuth) returns success=true for
 * no-token servers, so success=false unambiguously means unauthenticated —
 * tolerating it would hand an unauthenticated transport onward and surface as
 * an opaque runtime_start timeout instead of a clear auth error.
 */
internal suspend fun authenticateDesktopIrohAppServer(
    client: AppServerClient,
    accessToken: String?,
    requestIdFactory: () -> String = { "desktop-auth-${UUID.randomUUID()}" },
) {
    val token = accessToken?.trim().orEmpty()
    val auth = client.auth(
        AppServerCommand.Auth(
            requestId = requestIdFactory(),
            token = token,
            capabilities = listOf(IrohFrameCodec.FRAME_PART_CAPABILITY),
        ),
    )
    if (!auth.success) {
        error("Desktop iroh App Server auth failed: ${auth.error ?: "unknown error"}")
    }
}
