package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes
import com.letta.mobile.data.controller.node.iroh.NativeSlashCommandCatalog
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerConnectionState
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerWebSocketDial
import com.letta.mobile.data.transport.appserver.appServerAdminRpcParams
import com.letta.mobile.data.transport.appserver.applyAppServerFrameLimits
import com.letta.mobile.data.transport.appserver.openAppServerWebSocket
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.time.Duration
import com.letta.mobile.data.controller.node.iroh.AdminRpcNativeSources

/**
 * One dial of a plain ws:// App Server that [IrohChannelTransport]
 * attaches to its turn engine exactly like an Iroh dial: the device's own loopback server (the
 * embedded runtime) or a Settings "Remote host" ws:// address. So every Android backend runs the
 * same channel, chat and repositories; only the dial differs.
 *
 * A stock App Server answers no `admin_rpc`, so [adminRpc] serves it in process with the router an
 * Iroh host runs ([AdminRpcRegistry.buildRouter]) over this dial's own client.
 */
class DirectAppServerDial(
    val client: AppServerClient,
    val connectionState: StateFlow<AppServerConnectionState>,
    /** The socket's stream deltas, for the passive observer of turns this client did not start. */
    val streamFrames: Flow<AppServerReceivedFrame>,
    /** What the in-process admin router serves beyond its methods, as an Iroh host advertises it. */
    val hostCapabilities: Set<String>,
    val adminRpc: suspend (method: String, path: String, body: String?) -> AppServerInboundFrame.AdminRpcResponse,
    /** Ends the socket, then whatever the dial holds for it (the on-device runtime's lease). */
    val close: suspend (reason: String) -> Unit,
    /**
     * A streamed `admin_rpc` (see [com.letta.mobile.data.transport.api.IChannelTransport.adminRpcStream]) when the
     * dial serves one, as a node session does on a call lane; null when it does not.
     */
    val adminRpcStream: ((method: String, params: JsonObject) -> Flow<JsonObject>)? = null,
)

/** Where [IrohChannelTransport] gets each direct dial; a fresh one per (re)dial. */
fun interface DirectAppServerDialer {
    suspend fun dial(config: IrohConnectConfig): DirectAppServerDial

    /** How long a caller waits for a dial; the on-device runtime's first start is slower than a socket. */
    val readyTimeout: Duration get() = DEFAULT_READY_TIMEOUT
}

/**
 * Builds the in-process admin router of one direct dial over its socket's client and command catalog.
 * [localBackendDir] lets the router read the on-disk store the server writes (runs, context, memory
 * blocks); null for a server elsewhere. [hostRoutes] are the host-held shared state routes the dialer's
 * host serves in process (the on-device runtime's pins); a server elsewhere gets none, since state kept
 * here would be this device's, not the host's.
 */
fun directDialRouter(
    localBackendDir: String? = null,
    hostRoutes: List<AdminRpcRoutes> = emptyList(),
): (AppServerClient, NativeSlashCommandCatalog) -> AdminRpcRouter = { client, commands ->
    AdminRpcRegistry.buildRouter(
        native = AdminRpcNativeSources(nativeClient = client, slashCommands = commands, localBackendDir = localBackendDir),
    ).also { router -> hostRoutes.forEach { it.register(router) } }
}

/**
 * Dials [target] over its own [directAppServerHttpClient] and serves its `admin_rpc` in process with the
 * router [router] builds (see [directDialRouter]). [release] runs after the socket closes.
 */
suspend fun openDirectAppServerDial(
    target: AppServerWebSocketDial,
    scope: CoroutineScope,
    router: (AppServerClient, NativeSlashCommandCatalog) -> AdminRpcRouter = directDialRouter(),
    release: () -> Unit = {},
): DirectAppServerDial {
    val httpClient = directAppServerHttpClient()
    val releaseAll = {
        httpClient.close()
        release()
    }
    val connection = runCatching { openAppServerWebSocket(target, httpClient, scope) }
        .onFailure { releaseAll() }
        .getOrThrow()
    val commandCatalog = NativeSlashCommandCatalog()
    val commandCatalogJob = commandCatalog.start(scope, connection.client.events)
    val adminRouter = router(connection.client, commandCatalog)
    return DirectAppServerDial(
        client = connection.client,
        connectionState = connection.connectionState,
        streamFrames = connection.streamFrames,
        hostCapabilities = adminRouter.featureCapabilities,
        adminRpc = { method, path, body -> adminRouter.answer(method, appServerAdminRpcParams(path, body)) },
        close = { reason ->
            commandCatalogJob.cancel()
            connection.close(reason)
            releaseAll()
        },
    )
}

/**
 * The HTTP client for direct App Server sockets on Android and the JVM: CIO, because the shared
 * frame ceiling needs an engine that honours `maxFrameSize` (OkHttp refuses it).
 */
fun directAppServerHttpClient(): HttpClient = HttpClient(CIO) {
    install(WebSockets) { applyAppServerFrameLimits() }
}

private suspend fun AdminRpcRouter.answer(
    method: String,
    params: kotlinx.serialization.json.JsonObject,
): AppServerInboundFrame.AdminRpcResponse {
    val raw = dispatch(AdminRpcInvocation(requestId = "direct-${UUID.randomUUID()}", method = method, params = params))
    return AppServerProtocol.decodeFrame(raw, AppServerChannel.Control).frame as? AppServerInboundFrame.AdminRpcResponse
        ?: error("admin_rpc $method answered with an unreadable response")
}
