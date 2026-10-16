package com.letta.mobile.web.data

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.IrohServerAddress
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.isIrohBackendUrl
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.AppServerTurnStackSpec
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
    val client: DefaultAppServerClient,
    val engine: AppServerTurnEngine,
    val router: AppServerRuntimeEventRouter,
    val isConnected: Flow<Boolean>,
    /** Why the connection ended, when the transport knows (the Iroh binding does). */
    val failureReason: () -> String?,
    val label: String,
    private val scope: CoroutineScope,
    private val closeTransport: suspend () -> Unit,
) {
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
            closeTransport()
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

internal suspend fun connectWasmAppServerSession(
    config: LettaConfig,
    scope: CoroutineScope,
    httpClient: HttpClient,
    nextRequestId: (String) -> String,
): WasmAppServerSession {
    val label: String
    val client: DefaultAppServerClient
    val isConnected: Flow<Boolean>
    val failureReason: () -> String?
    val closeTransport: suspend () -> Unit
    if (isIrohBackendUrl(config.serverUrl)) {
        // letta-mobile-ckmub.8: read the address here (a ticket, a short URL or a bare id) so a bad one
        // fails with a message that says what is expected, and the browser binding only ever gets
        // the endpoint id it can dial (through the relay; direct UDP is not available in a browser).
        val address = IrohServerAddress.parse(config.serverUrl)
        val iroh = IrohWasmAppServerTransport.connect(address.endpointUrl, scope)
        try {
            bounded(CONNECT_TIMEOUT_MS, "Connection timed out") { iroh.isConnected.first { it } }
        } catch (error: Throwable) {
            withContext(NonCancellable) { iroh.close() }
            throw error
        }
        label = "Iroh"
        client = DefaultAppServerClient(iroh, parentScope = scope)
        isConnected = iroh.isConnected
        failureReason = { iroh.failureReason.value }
        closeTransport = iroh::close
    } else {
        // letta-mobile-by4vm.110.6: the ws:// open desktop and Android share; it fails at once with
        // the socket's reason instead of waiting out the timeout.
        val connection = openAppServerWebSocket(
            AppServerWebSocketDial(resolveWebSocketUrl(config.serverUrl), config.accessToken),
            httpClient,
            scope,
        )
        label = "WebSocket"
        client = connection.client
        isConnected = connection.connectionState.map { it.isReady }
        failureReason = { null }
        closeTransport = { connection.close("closed") }
    }
    var router: AppServerRuntimeEventRouter? = null
    try {
        val eventRouter = AppServerRuntimeEventRouter().also { router = it }
        return WasmAppServerSession(
            client = client,
            engine = eventRouter.attachTurnStack(client, scope, AppServerTurnStackSpec(requestIdFactory = { nextRequestId("turn") })),
            router = eventRouter,
            isConnected = isConnected,
            failureReason = failureReason,
            label = label,
            scope = scope,
            closeTransport = closeTransport,
        )
    } catch (error: Throwable) {
        withContext(NonCancellable) {
            router?.detach()
            closeTransport()
        }
        throw error
    }
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
