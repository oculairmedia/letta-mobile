package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
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
import java.util.UUID
import kotlin.time.Duration

/**
 * letta-mobile-by4vm.110.6: one dial of a plain ws:// App Server that [IrohChannelTransport]
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
)

/** Where [IrohChannelTransport] gets each direct dial; a fresh one per (re)dial. */
fun interface DirectAppServerDialer {
    suspend fun dial(config: IrohConnectConfig): DirectAppServerDial

    /** How long a caller waits for a dial; the on-device runtime's first start is slower than a socket. */
    val readyTimeout: Duration get() = DEFAULT_READY_TIMEOUT
}

/**
 * Dials [target] over its own [directAppServerHttpClient] and serves its `admin_rpc` in process.
 * [localBackendDir] lets the router read the on-disk store the server writes (runs, context, memory
 * blocks); null for a server elsewhere. [release] runs after the socket closes.
 */
suspend fun openDirectAppServerDial(
    target: AppServerWebSocketDial,
    scope: CoroutineScope,
    localBackendDir: String? = null,
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
    val router = AdminRpcRegistry.buildRouter(nativeClient = connection.client, localBackendDir = localBackendDir)
    return DirectAppServerDial(
        client = connection.client,
        connectionState = connection.connectionState,
        streamFrames = connection.streamFrames,
        hostCapabilities = router.featureCapabilities,
        adminRpc = { method, path, body -> router.answer(method, appServerAdminRpcParams(path, body)) },
        close = { reason ->
            connection.close(reason)
            releaseAll()
        },
    )
}

/**
 * The HTTP client for direct App Server sockets on Android and the JVM: CIO, because the shared
 * frame ceiling needs an engine that honours `maxFrameSize` (OkHttp refuses it, letta-mobile-by4vm.117).
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
