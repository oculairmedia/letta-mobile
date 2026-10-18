package com.letta.mobile.desktop.chat

import com.letta.mobile.data.canvas.CanvasExternalTools
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ToolAudience
import com.letta.mobile.data.controller.reconnect.ReconnectingClientListener
import com.letta.mobile.data.controller.reconnect.SessionSurvivingReconnectListener
import com.letta.mobile.data.controller.reconnect.reattachRuntimes
import com.letta.mobile.data.meridian.AgentToolsModePolicy
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.controller.fanout.StreamIntegrityBinding
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.desktop.canvas.DesktopNotebookCanvasStore
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.AppServerTurnStackSpec
import com.letta.mobile.data.runtime.DurableTurnDelivery
import com.letta.mobile.data.runtime.attachTurnStack
import com.letta.mobile.data.runtime.permissionGovernedTurnStackSpec
import com.letta.mobile.data.runtime.withWorkingDirectoryProvider
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AgentDefaultsRepository
import com.letta.mobile.data.runtime.PermissionModeRegistry
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.runtime.cwd
import com.letta.mobile.data.transport.appserver.AppServerReadinessExpectation
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerProbeResult
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.data.transport.appserver.AppServerTerminalConsumerId
import com.letta.mobile.desktop.runtime.DesktopRuntimeLaunchPreference
import com.letta.mobile.desktop.security.DesktopIrohIdentity
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
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
    /**
     * HTTP `/app-server-info` probe, handed to the shared reconnect owner as the preflight hook it
     * runs before every remote WebSocket dial (letta-mobile-bzvro.2, by4vm.9).
     */
    private val preflightProbe: suspend (String, String?) -> AppServerProbeResult? = ::probeDesktopAppServer,
    /** letta-mobile-bzvro.13: the app's one permission-mode settings; null runs every runtime Unrestricted, with no chip. */
    permissionModeSettings: PermissionModeSettings? = null,
    /** letta-mobile-k5nbq.9: per-agent default cwd carried on a new conversation's `runtime_start`. */
    private val agentDefaults: AgentDefaultsRepository? = null,
    /**
     * letta-mobile-by4vm.56: where an iroh:// session's generations come from, given the address,
     * the access token and the session's client scope. Tests substitute generations that need no
     * QUIC endpoint.
     */
    private val irohGenerations: (serverUrl: String, accessToken: String?, scope: CoroutineScope) -> DesktopIrohGenerationSource =
        { serverUrl, accessToken, scope -> DesktopIrohGenerationMinter(serverUrl, accessToken, irohIdentity, scope) },
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
        if (IrohChannelTransport.isIrohUrl(serverUrl)) {
            createIrohGateway(serverUrl, lettaConfig)
        } else {
            createDirectGateway(serverUrl, appServerConfig.bearerToken, lettaConfig)
        }
    }

    private fun childScope(): CoroutineScope =
        CoroutineScope(controllerScope.coroutineContext + SupervisorJob(controllerScope.coroutineContext[Job]))

    /**
     * iroh:// — the shared reconnect owner mints one QUIC connection per generation
     * (letta-mobile-by4vm.56), exactly as it mints sockets for ws://, so a dropped connection is
     * redialled behind the same client and a turn in flight survives it. Over Iroh the node sets
     * its own mode and the chip is not offered: the engine runs Unrestricted and the persisted
     * default does not reach it.
     */
    private suspend fun createIrohGateway(serverUrl: String, lettaConfig: LettaConfig): DesktopAppServerChatGateway {
        return createSessionGateway(
            SessionGatewaySpec(
                isIroh = true,
                local = lettaConfig.mode == LettaConfig.Mode.LOCAL,
                permissionModes = null,
                open = { scope, listener ->
                    val source = irohGenerations(serverUrl, lettaConfig.accessToken, scope)
                    DesktopDirectAppServerSession.openWith(
                        serverId = serverUrl,
                        scope = scope,
                        listener = listener,
                        dial = SessionDial(mint = source::mint, preflight = null, telemetryComponent = "DesktopIrohReconnect"),
                    )
                },
            ),
        )
    }

    /**
     * ws:// — the shared [com.letta.mobile.data.controller.reconnect.ReconnectingAppServerClient]
     * owns the socket (letta-mobile-by4vm.9).
     */
    private suspend fun createDirectGateway(
        serverUrl: String,
        leaseToken: String?,
        lettaConfig: LettaConfig,
    ): DesktopAppServerChatGateway {
        val local = lettaConfig.mode == LettaConfig.Mode.LOCAL
        // The bundled runtime's lease token (letta-mobile-by4vm.110.4), else the config's own.
        val bearerToken = leaseToken ?: lettaConfig.accessToken
        return createSessionGateway(
            SessionGatewaySpec(
                isIroh = false,
                local = local,
                permissionModes = permissionModes,
                open = { scope, listener ->
                    DesktopDirectAppServerSession.open(
                        dial = DesktopDirectDial(
                            serverUrl = serverUrl,
                            bearerToken = bearerToken,
                            readiness = readinessExpectationFor(lettaConfig),
                            // The bundled local child was just started by us and is checked by the readiness handshake.
                            preflight = if (local) null else ({ preflightProbe(serverUrl, bearerToken) ?: notProbeable() }),
                        ),
                        scope = scope,
                        listener = listener,
                    )
                },
            ),
        )
    }

    /** What differs between the ws:// and iroh:// self-reconnecting gateways. */
    private class SessionGatewaySpec(
        val isIroh: Boolean,
        val local: Boolean,
        val permissionModes: PermissionModeRegistry?,
        val open: suspend (scope: CoroutineScope, listener: ReconnectingClientListener) -> DesktopDirectAppServerSession,
    )

    /**
     * The router and turn engine are built once over the session's stable client and outlive each
     * generation, so a turn in flight across a brief drop reconnects instead of failing
     * ([SessionSurvivingReconnectListener]).
     */
    private suspend fun createSessionGateway(spec: SessionGatewaySpec): DesktopAppServerChatGateway {
        val clientScope = childScope()
        val router = AppServerRuntimeEventRouter()
        val toolRegistry = desktopCanvasToolRegistry(isIroh = spec.isIroh, canvasSessions)
        var engine: AppServerTurnEngine? = null
        var session: DesktopDirectAppServerSession? = null
        val leases = mutableListOf<AutoCloseable>()
        try {
            val opened = spec.open(
                clientScope,
                SessionSurvivingReconnectListener(
                    router = router,
                    onSessionLost = { engine?.invalidateRuntime(notifyHost = false) },
                    reattach = { client, runtimes ->
                        client.reattachRuntimes(runtimes) { desktopReattachCommand(it, toolRegistry, spec.permissionModes) }
                    },
                ),
            )
            session = opened
            if (spec.local) leases += DesktopLocalAppServerClientRegistry.shared.install(opened.client)
            if (!spec.isIroh) leases += DesktopLocalAppServerClientRegistry.direct.install(opened.client)
            startStreamIntegrity(router, opened.client, clientScope)
            // lgns8.22.3: one inbound collector per desktop gateway.
            val turnEngine = router.attachTurnStack(
                opened.client,
                controllerScope,
                desktopTurnStackSpec(toolRegistry, spec.permissionModes)
                    .withWorkingDirectoryProvider { command ->
                        agentDefaults?.cwd(AgentId(command.agentId.value))
                    },
            )
            engine = turnEngine
            return DesktopAppServerChatGateway(
                turnEngine = turnEngine,
                client = opened.client,
                transportResources = opened.resources(),
                permissionModes = spec.permissionModes,
                session = opened,
                onClose = {
                    router.detach()
                    leases.forEach { it.close() }
                    clientScope.cancel()
                },
            )
        } catch (error: Throwable) {
            router.detach()
            leases.forEach { it.close() }
            session?.resources()?.close()
            clientScope.cancel()
            throw error
        }
    }

    /**
     * letta-mobile-bzvro.6: an `event_seq` gap is a lost frame (a direct socket sees every frame;
     * the Iroh host draws its seq in wire order, by4vm.50): resync, and keep the watched runtimes on
     * the busy/idle sync cadence. Desktop has no sync budget: the cadence always runs.
     */
    private fun startStreamIntegrity(
        router: AppServerRuntimeEventRouter,
        client: AppServerClient,
        scope: CoroutineScope,
    ) {
        StreamIntegrityBinding(client, { "desktop-sync-${UUID.randomUUID()}" }).bindAndStart(router, detectGaps = true, scope = scope)
    }

    private fun readinessExpectationFor(lettaConfig: LettaConfig): AppServerReadinessExpectation =
        if (lettaConfig.mode == LettaConfig.Mode.LOCAL) AppServerReadinessExpectation.LocalBackend else AppServerReadinessExpectation()
}

/**
 * Desktop runs every turn Unrestricted unless a permission mode was chosen (letta-mobile-bzvro.13:
 * the composer chip per conversation, the settings card for the default), through the shared
 * [permissionGovernedTurnStackSpec] (no eager controller.startRuntime, no double runtime_start on
 * first send, #831 Codex P2).
 *
 * Context management is letta-code's (letta-mobile-4rxis): the engine never stamps a context
 * window or compacts before a turn. letta-code compacts from the agent's own compaction settings
 * against the model's real window.
 */
internal fun desktopTurnStackSpec(
    externalToolRegistry: ExternalToolRegistry? = null,
    permissionModes: PermissionModeRegistry? = null,
    installId: String? = DesktopRuntimeLaunchPreference.instanceId,
): AppServerTurnStackSpec = permissionGovernedTurnStackSpec(
    modes = permissionModes,
    clientInfo = DESKTOP_CLIENT_INFO,
    fallback = AppServerPermissionMode.Unrestricted,
    externalToolRegistry = externalToolRegistry,
    // letta-mobile-zxk2n.2: the per-install id makes this desktop a durable terminal consumer.
    durableDelivery = DurableTurnDelivery.chat(
        installId?.let { AppServerTerminalConsumerId.forClient(DESKTOP_CLIENT_INFO.name, it) },
    ),
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

private val DESKTOP_CLIENT_INFO = AppServerRuntimeStartClientInfo(
    name = "letta-desktop",
    title = "Letta Desktop",
    version = "0.2.0",
)

private fun notProbeable() = AppServerProbeResult.Unavailable("the App Server address cannot be probed")

/**
 * letta-mobile-by4vm.9: the `runtime_start` that reattaches [runtime] to a new socket after a
 * reconnect: the same client, mode and external tools the engine's own runtime_start carries, with
 * approvals and device status recovered.
 */
internal fun desktopReattachCommand(
    runtime: AppServerRuntimeScope,
    externalToolRegistry: ExternalToolRegistry,
    permissionModes: PermissionModeRegistry?,
    requestId: String = "desktop-reattach-${UUID.randomUUID()}",
): AppServerCommand.RuntimeStart = AppServerCommand.RuntimeStart(
    requestId = requestId,
    agentId = runtime.agentId,
    conversationId = runtime.conversationId,
    mode = permissionModes?.modeFor(runtime) ?: AppServerPermissionMode.Unrestricted,
    clientInfo = DESKTOP_CLIENT_INFO,
    recoverApprovals = true,
    forceDeviceStatus = true,
    externalTools = externalToolRegistry.advertisedToolsCommandGroups(audience = ToolAudience(runtime.agentId)),
)

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
        throw DesktopIrohAuthRefusedException("Desktop iroh App Server auth failed: ${auth.error ?: "unknown error"}")
    }
}

/** The iroh host refused this desktop's auth: terminal, never redialled (letta-mobile-by4vm.56). */
internal class DesktopIrohAuthRefusedException(message: String) : IllegalStateException(message)
