package com.letta.mobile.web.data

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerTransport
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import com.letta.mobile.web.iroh.IrohWasmAppServerTransport
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

internal class WasmAppServerSession(
    val client: DefaultAppServerClient,
    val engine: AppServerTurnEngine,
    val router: AppServerRuntimeEventRouter,
    val transport: AppServerTransport,
    val label: String,
    private val closeTransport: suspend () -> Unit,
) {
    val conversationByAgent = mutableMapOf<String, String>()

    suspend fun ensureConversation(agentId: String, nextRequestId: (String) -> String): String {
        conversationByAgent[agentId]?.let { return it }
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
        return conversation.id.value.also { conversationByAgent[agentId] = it }
    }

    suspend fun admin(
        method: String,
        params: JsonObject,
        nextRequestId: (String) -> String,
    ): JsonElement? = bounded(RequestTimeoutMs, "$method timed out") {
        val response = client.adminRpc(
            com.letta.mobile.data.transport.appserver.AppServerCommand.AdminRpc(
                requestId = nextRequestId(method.substringAfterLast('.')),
                method = method,
                params = params,
            ),
        )
        check(response.success) { response.error ?: "$method failed" }
        response.result
    }

    suspend fun close() {
        router.detach()
        conversationByAgent.clear()
        closeTransport()
    }

    internal fun onTransportDisconnected() {
        router.detach()
        conversationByAgent.clear()
    }
}

internal suspend fun connectWasmAppServerSession(
    config: LettaConfig,
    scope: CoroutineScope,
    httpClient: HttpClient,
    nextRequestId: (String) -> String,
): WasmAppServerSession {
    val transport: AppServerTransport
    val label: String
    val closeTransport: suspend () -> Unit
    if (config.serverUrl.startsWith("iroh://")) {
        val iroh = IrohWasmAppServerTransport.connect(config.serverUrl, scope)
        transport = iroh
        label = "Iroh"
        closeTransport = iroh::close
    } else {
        val websocket = KtorAppServerWebSocketTransport(
            httpClient = httpClient,
            baseUrl = resolveWebSocketUrl(config.serverUrl),
            scope = scope,
            bearerToken = config.accessToken,
        )
        transport = websocket
        label = "WebSocket"
        closeTransport = websocket::close
    }
    try {
        bounded(ConnectTimeoutMs, "Connection timed out") { transport.isConnected.first { it } }
        val client = DefaultAppServerClient(transport, parentScope = scope)
        val router = AppServerRuntimeEventRouter()
        router.attach(scope, client.events)
        return WasmAppServerSession(
            client = client,
            engine = AppServerTurnEngine(
                client = client,
                requestIdFactory = { nextRequestId("turn") },
                eventRouter = router,
            ),
            router = router,
            transport = transport,
            label = label,
            closeTransport = closeTransport,
        )
    } catch (error: Throwable) {
        closeTransport()
        throw error
    }
}

private suspend fun <T> bounded(timeoutMs: Long, message: String, block: suspend () -> T): T = try {
    withTimeout(timeoutMs) { block() }
} catch (timeout: TimeoutCancellationException) {
    throw IllegalStateException(message, timeout)
}

private const val ConnectTimeoutMs = 15_000L
private const val RequestTimeoutMs = 20_000L
