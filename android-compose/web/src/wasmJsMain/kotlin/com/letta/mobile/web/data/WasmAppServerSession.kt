package com.letta.mobile.web.data

import com.letta.mobile.data.canvas.CanvasRelayDial
import com.letta.mobile.data.chat.runtime.EnginePermissionModes
import com.letta.mobile.data.chat.runtime.PermissionModeGateway
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.IrohServerAddress
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.isIrohBackendUrl
import com.letta.mobile.data.repository.appserver.AppServerLocalAdminGateway
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.DurableTurnDelivery
import com.letta.mobile.data.runtime.PermissionModeRegistry
import com.letta.mobile.data.runtime.RuntimePermissionDefaults
import com.letta.mobile.data.runtime.permissionGovernedTurnStackSpec
import com.letta.mobile.data.runtime.attachTurnStack
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerWebSocketDial
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.openAppServerWebSocket
import com.letta.mobile.web.iroh.IrohWasmAppServerTransport
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

internal class WasmAppServerSession(
    private val link: WasmAppServerLink,
    services: WasmAppServerServices,
    private val scope: CoroutineScope,
) {
    val client: DefaultAppServerClient get() = link.client
    val isConnected: Flow<Boolean> get() = link.isConnected
    val failureReason: () -> String? get() = link.failureReason
    val label: String get() = link.label

    /** How this session reaches its host's canvas relay; null for a backend with none (ws://). */
    val canvasRelay: CanvasRelayDial? get() = link.canvasRelay
    val engine: AppServerTurnEngine = services.engine
    val router: AppServerRuntimeEventRouter = services.router
    val appServerAdmin: AppServerLocalAdminGateway = services.appServerAdmin
    val permissionModes: PermissionModeGateway? = services.permissionModes

    val conversationByAgent = mutableMapOf<String, String>()
    private val conversationInFlight = mutableMapOf<String, Deferred<String>>()

    suspend fun ensureConversation(agentId: String, nextRequestId: (String) -> String): String {
        conversationByAgent[agentId]?.let { return it }
        val pending = conversationInFlight[agentId] ?: scope.async {
            resolveConversation(agentId, nextRequestId)
        }.also { conversationInFlight[agentId] = it }
        return try {
            pending.await().also { conversationByAgent[agentId] = it }
        } finally {
            if (pending.isCompleted && conversationInFlight[agentId] === pending) {
                conversationInFlight -= agentId
            }
        }
    }

    private suspend fun resolveConversation(agentId: String, nextRequestId: (String) -> String): String {
        val listed = admin(
            method = "conversation.list",
            params = buildJsonObject {
                put("limit", "100")
                put("order", "desc")
                put("order_by", "last_message_at")
            },
            nextRequestId = nextRequestId,
        ) as? JsonArray ?: JsonArray(emptyList())
        val existing = AppServerProtocol.json
            .decodeFromJsonElement(ListSerializer(Conversation.serializer()), listed)
            .firstOrNull { it.agentId.value == agentId }
        val conversation = existing ?: AppServerProtocol.json.decodeFromJsonElement(
            Conversation.serializer(),
            admin(
                method = "conversation.create",
                params = buildJsonObject { put("agent_id", agentId) },
                nextRequestId = nextRequestId,
            ) ?: error("Conversation create returned no result"),
        )
        return conversation.id.value
    }

    suspend fun admin(
        method: String,
        params: JsonObject,
        nextRequestId: (String) -> String,
    ): JsonElement? = bounded(REQUEST_TIMEOUT_MS, "$method timed out") {
        val response = client.adminRpc(
            AppServerCommand.AdminRpc(
                requestId = nextRequestId(method.substringAfterLast('.')),
                method = method,
                params = params,
            ),
        )
        check(response.success) { response.error ?: "$method failed" }
        response.result
    }

    suspend fun close() {
        withContext(NonCancellable) {
            router.detach()
            conversationInFlight.values.forEach { it.cancel() }
            conversationInFlight.clear()
            conversationByAgent.clear()
            link.close()
        }
    }

    internal suspend fun onTransportDisconnected() {
        withContext(NonCancellable) {
            router.detach()
            conversationInFlight.values.forEach { it.cancel() }
            conversationInFlight.clear()
            conversationByAgent.clear()
        }
    }
}

/** One open connection to an App Server: its client, whether it is up, and how to end it. */
internal class WasmAppServerLink(
    val client: DefaultAppServerClient,
    val isConnected: Flow<Boolean>,
    /** Why the connection ended, when the transport knows (the Iroh binding does). */
    val failureReason: () -> String?,
    val label: String,
    val close: suspend () -> Unit,
    /** letta-mobile-ckmub.17.9: the host's canvas relay, from this connection's endpoint; Iroh only. */
    val canvasRelay: CanvasRelayDial? = null,
)

/** What a session serves on top of its [WasmAppServerLink]: its turn engine and the reads and writes it makes. */
internal class WasmAppServerServices(
    val engine: AppServerTurnEngine,
    val router: AppServerRuntimeEventRouter,
    /** The session's own App Server reads and writes (models, a conversation's record), as desktop's gateway makes them. */
    val appServerAdmin: AppServerLocalAdminGateway,
    /** letta-mobile-ckmub.17.2: the composer's mode chip; null over Iroh, whose host sets its own mode (as on desktop). */
    val permissionModes: PermissionModeGateway? = null,
)

internal suspend fun connectWasmAppServerSession(
    config: LettaConfig,
    scope: CoroutineScope,
    httpClient: HttpClient,
    nextRequestId: (String) -> String,
): WasmAppServerSession {
    val iroh = isIrohBackendUrl(config.serverUrl)
    val link = if (iroh) openIrohLink(config, scope) else openWebSocketLink(config, httpClient, scope)
    val router = AppServerRuntimeEventRouter()
    try {
        val services = attachServices(link, router, scope, TurnStackOptions(governModes = !iroh, nextRequestId))
        return WasmAppServerSession(link, services, scope)
    } catch (error: Throwable) {
        withContext(NonCancellable) {
            router.detach()
            link.close()
        }
        throw error
    }
}

/**
 * letta-mobile-ckmub.8: the address is read here (a ticket, a short URL or a bare id) so a bad one
 * fails with a message that says what is expected, and the browser binding only ever gets the
 * endpoint id it can dial (through the relay; direct UDP is not available in a browser).
 */
private suspend fun openIrohLink(config: LettaConfig, scope: CoroutineScope): WasmAppServerLink {
    val address = IrohServerAddress.parse(config.serverUrl)
    val transport = IrohWasmAppServerTransport.connect(address, scope)
    try {
        bounded(CONNECT_TIMEOUT_MS, "Connection timed out") { transport.isConnected.first { it } }
    } catch (error: Throwable) {
        withContext(NonCancellable) { transport.close() }
        throw error
    }
    return WasmAppServerLink(
        client = DefaultAppServerClient(transport, parentScope = scope),
        isConnected = transport.isConnected,
        failureReason = { transport.failureReason.value },
        label = "Iroh",
        close = transport::close,
        canvasRelay = CanvasRelayDial { transport.openCanvasRelay(HostId(address.endpointIdHex)) },
    )
}

/**
 * letta-mobile-by4vm.110.6: the ws:// open desktop and Android share; it fails at once with the
 * socket's reason instead of waiting out the timeout.
 */
private suspend fun openWebSocketLink(config: LettaConfig, httpClient: HttpClient, scope: CoroutineScope): WasmAppServerLink {
    val connection = openAppServerWebSocket(
        AppServerWebSocketDial(resolveWebSocketUrl(config.serverUrl), config.accessToken),
        httpClient,
        scope,
    )
    return WasmAppServerLink(
        client = connection.client,
        isConnected = connection.connectionState.map { it.isReady },
        failureReason = { null },
        label = "WebSocket",
        close = { connection.close("closed") },
    )
}

/** How a session's turn stack is built: [governModes] gives the composer a mode chip (ws:// only, as on desktop). */
private class TurnStackOptions(val governModes: Boolean, val nextRequestId: (String) -> String)

/** The turn stack over [link], routed by [router]. */
private fun attachServices(
    link: WasmAppServerLink,
    router: AppServerRuntimeEventRouter,
    scope: CoroutineScope,
    options: TurnStackOptions,
): WasmAppServerServices {
    val nextRequestId = options.nextRequestId
    // Per tab: the web has no settings store for the choices yet (letta-mobile-ckmub.17.7).
    val modes = PermissionModeRegistry(MutableStateFlow(RuntimePermissionDefaults.DEFAULT_MODE)).takeIf { options.governModes }
    val engine = router.attachTurnStack(
        link.client,
        scope,
        permissionGovernedTurnStackSpec(
            modes,
            requestIdFactory = { nextRequestId("turn") },
            durableDelivery = DurableTurnDelivery.chat(webTabTerminalConsumerId()),
        ),
    )
    return WasmAppServerServices(
        engine = engine,
        router = router,
        appServerAdmin = AppServerLocalAdminGateway(link.client, nextRequestId),
        permissionModes = modes?.let { EnginePermissionModes(engine, it) },
    )
}

internal suspend fun <T> bounded(timeoutMs: Long, message: String, block: suspend () -> T): T = try {
    withTimeout(timeoutMs.milliseconds) { block() }
} catch (timeout: TimeoutCancellationException) {
    throw IllegalStateException(message, timeout)
}

/** Auth capability the browser Iroh binding honours by reassembling `frame_part` sequences. */
internal const val FRAME_PART_CAPABILITY = "frame_part"

/**
 * The transport capabilities to advertise on auth for [serverUrl] (letta-mobile-yhq16). Over Iroh a
 * reply larger than 1 MiB, such as a full agent list, only arrives when the server may split it into
 * parts, which the browser binding reassembles. The WebSocket transport has no such framing.
 */
internal fun authCapabilitiesFor(serverUrl: String): List<String>? =
    if (isIrohBackendUrl(serverUrl)) listOf(FRAME_PART_CAPABILITY) else null

internal fun resolveWebSocketUrl(serverUrl: String): String {
    val trimmed = serverUrl.trim().removeSuffix("/")
    return when {
        trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> {
            val pathStart = trimmed.indexOf('/', startIndex = trimmed.indexOf("://") + 3)
            if (pathStart < 0) "$trimmed/ws" else trimmed
        }
        trimmed.startsWith("http://") -> "ws://${trimmed.removePrefix("http://")}/ws"
        trimmed.startsWith("https://") -> "wss://${trimmed.removePrefix("https://")}/ws"
        else -> error("Server URL must use iroh, http, https, ws, or wss")
    }
}

private const val CONNECT_TIMEOUT_MS = 15_000L
internal const val REQUEST_TIMEOUT_MS = 20_000L
