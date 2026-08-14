package com.letta.mobile.web.data

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.*
import com.letta.mobile.ui.agents.AgentItemState
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import com.letta.mobile.web.iroh.IrohWasmBridge

/**
 * Wasm-native AppServer gateway that connects directly to the Letta AppServer
 * over WebSocket using the canonical AppServerProtocol frames from :sharedLogic.
 */
class WasmAppServerClientGateway(
    private val scope: CoroutineScope,
) {
    private val httpClient = HttpClient(Js) {
        install(WebSockets)
    }

    private var activeTransport: KtorAppServerWebSocketTransport? = null
    private var activeClient: DefaultAppServerClient? = null

    /**
     * Resolve the WebSocket endpoint URL from a LettaConfig serverUrl.
     */
    fun resolveWsUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim()
        if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
            return trimmed
        }
        if (trimmed.startsWith("http://")) {
            return "ws://" + trimmed.removePrefix("http://").removeSuffix("/") + "/ws"
        }
        if (trimmed.startsWith("https://")) {
            return "wss://" + trimmed.removePrefix("https://").removeSuffix("/") + "/ws"
        }
        if (trimmed.startsWith("iroh://")) {
            // Format: iroh://<node_id>@<ip>:<port> or iroh://<node_id>@<ip>
            val atSplit = trimmed.substringAfter("iroh://").substringAfter("@", "")
            if (atSplit.isNotBlank()) {
                val host = atSplit.substringBefore(":")
                val port = atSplit.substringAfter(":", "").takeIf { it.isNotBlank() } ?: "8283"
                return "ws://$host:$port/ws"
            }
        }
        return "ws://127.0.0.1:8283/ws"
    }

    /**
     * Connect to the Letta AppServer over WebSocket and fetch the live agent list
     * using the canonical AppServerProtocol.AgentList command.
     */
    suspend fun listAgents(config: LettaConfig): Result<List<AgentItemState>> = runCatching {
        val ticket = IrohWasmBridge.parseTicket(config.serverUrl)
        if (ticket.publicKeyValid) {
            println("Connecting via direct Iroh 1.0 P2P to Node: ${ticket.nodeId}")
            try {
                val requestId = "req-iroh-list-${kotlin.random.Random.nextInt(1000, 9999)}"
                val commandPayload = """{"channel":"control","command":"agent_list","request_id":"$requestId"}"""
                val responseStr = IrohWasmBridge.dialAndSend(ticket.nodeId, "letta/appserver/0", commandPayload)
                println("Received Iroh P2P response: $responseStr")
                // Parse response JSON and map agents
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val parsed = json.parseToJsonElement(responseStr).jsonObject
                val agentsJson = parsed["agents"]?.jsonArray
                if (agentsJson != null && agentsJson.isNotEmpty()) {
                    return@runCatching agentsJson.map { element ->
                        val obj = element.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.content ?: "unknown"
                        val name = obj["name"]?.jsonPrimitive?.content ?: "Agent"
                        val description = obj["description"]?.jsonPrimitive?.content
                        val model = obj["model"]?.jsonPrimitive?.content ?: "letta/letta-free"
                        AgentItemState(id = id, name = name, description = description, model = model, isOnline = true)
                    }
                }
            } catch (e: Throwable) {
                println("Iroh P2P direct attempt failed, falling back to WebSocket: ${e.message}")
            }
        }

        val wsUrl = resolveWsUrl(config.serverUrl)
        println("Connecting to AppServer via WebSocket: $wsUrl")

        // Create the KtorAppServerWebSocketTransport from :sharedLogic
        val transport = KtorAppServerWebSocketTransport(
            httpClient = httpClient,
            baseUrl = wsUrl,
            scope = scope,
            bearerToken = config.accessToken,
        )
        activeTransport = transport

        val client = DefaultAppServerClient(
            transport = transport,
            parentScope = scope,
        )
        activeClient = client

        // Wait for connection readiness
        val isReady = withTimeoutOrNull(5000) {
            transport.isConnected.first { it }
        } ?: false

        if (!isReady) {
            println("WebSocket transport not ready, attempting fallback...")
            // Fallback to HTTP REST loader if WebSocket is not listening on that specific path
            return WebAgentLoader.fetchAgents(config)
        }

        // Issue canonical AppServerProtocol.AgentList command
        val requestId = "req-agent-list-${kotlin.random.Random.nextInt(1000, 9999)}"
        println("Sending AppServer AgentList request: $requestId")

        val response = withTimeoutOrNull(10000) {
            client.agentList(
                AppServerCommand.AgentList(
                    requestId = requestId,
                )
            )
        } ?: throw Exception("AppServer AgentList timed out")

        println("Received AppServer AgentList response: success=${response.success}, count=${response.agents?.size}")

        val agentsJson = response.agents
        if (agentsJson != null && agentsJson.isNotEmpty()) {
            agentsJson.map { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: "unknown"
                val name = obj["name"]?.jsonPrimitive?.content ?: "Agent"
                val description = obj["description"]?.jsonPrimitive?.content
                val model = obj["model"]?.jsonPrimitive?.content ?: "letta/letta-free"

                AgentItemState(
                    id = id,
                    name = name,
                    description = description,
                    model = model,
                    isOnline = true,
                )
            }
        } else {
            // If empty or unsupported, try REST loader
            WebAgentLoader.fetchAgents(config).getOrThrow()
        }
    }
}
