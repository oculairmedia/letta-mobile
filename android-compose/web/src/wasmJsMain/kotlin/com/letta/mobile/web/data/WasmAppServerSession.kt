package com.letta.mobile.web.data

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerTransport
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import com.letta.mobile.web.iroh.IrohWasmAppServerTransport
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class WasmAppServerSession(
    val client: DefaultAppServerClient,
    val engine: AppServerTurnEngine,
    val router: AppServerRuntimeEventRouter,
    val transport: AppServerTransport,
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
                conversationInFlight.remove(agentId)
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
    var router: AppServerRuntimeEventRouter? = null
    try {
        bounded(CONNECT_TIMEOUT_MS, "Connection timed out") { transport.isConnected.first { it } }
        val client = DefaultAppServerClient(transport, parentScope = scope)
        val eventRouter = AppServerRuntimeEventRouter()
        router = eventRouter
        eventRouter.attach(scope, client.events)
        return WasmAppServerSession(
            client = client,
            engine = AppServerTurnEngine(
                client = client,
                requestIdFactory = { nextRequestId("turn") },
                eventRouter = eventRouter,
            ),
            router = eventRouter,
            transport = transport,
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
    withTimeout(timeoutMs) { block() }
} catch (timeout: TimeoutCancellationException) {
    throw IllegalStateException(message, timeout)
}

private const val CONNECT_TIMEOUT_MS = 15_000L
internal const val REQUEST_TIMEOUT_MS = 20_000L
