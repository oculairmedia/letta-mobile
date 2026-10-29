package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerReadinessExpectation
import com.letta.mobile.data.transport.appserver.AppServerWebSocketDial
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * letta-mobile-by4vm.110.6: an [IrohChannelTransport] with a [DirectAppServerDialer] runs against a
 * stock ws:// App Server, one that answers native commands and no `admin_rpc`: the dial carries the
 * bearer token, the transport's admin_rpc is served in process over native commands, and the socket
 * closing is reported as a connection loss the supervisor redials.
 */
class DirectAppServerDialTest {
    private var server: EmbeddedServer<*, *>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val received = Collections.synchronizedList(mutableListOf<String>())
    private val authHeaders = Collections.synchronizedList(mutableListOf<String?>())
    private val sessions = AtomicInteger()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server?.stop(0, 0)
    }

    @Test
    fun adminRpcIsAnsweredInProcessOverNativeCommandsOfAStockServer() = runBlocking {
        val port = startStockServer()
        val releases = AtomicInteger()
        val transport = directTransport(port, releases)

        val response = withTimeout(TIMEOUT) { transport.adminRpc("agent.list", "/v1/agents?limit=10", null) }

        assertTrue(response.success, "agent.list failed: ${response.error}")
        assertEquals("agent-1", (response.result as JsonArray).single().jsonObject["id"]?.jsonPrimitive?.content)
        assertTrue(received.any { it == "agent_list" }, "the native command reached the server: $received")
        assertFalse(received.any { it == "admin_rpc" }, "a stock server is never sent admin_rpc: $received")
        assertEquals("Bearer lease-token", authHeaders.first(), "the dial carries the lease token")
        transport.close()
        assertEquals(1, releases.get(), "closing the transport releases what the dial held")
    }

    @Test
    fun aClosedSocketIsAConnectionLossThatRedials() = runBlocking {
        val port = startStockServer(closeFirstSessionAfterInfo = true)
        val transport = directTransport(port, AtomicInteger())

        withTimeout(TIMEOUT) { transport.adminRpc("agent.list", "/v1/agents", null) }
        withTimeout(TIMEOUT) { while (sessions.get() < 2) delay(POLL_MS) }

        val again = withTimeout(TIMEOUT) { transport.adminRpc("agent.list", "/v1/agents", null) }
        assertTrue(again.success, "the redialled socket serves admin_rpc again: ${again.error}")
        transport.close()
    }

    private fun directTransport(port: Int, releases: AtomicInteger) = IrohChannelTransport(
        scope = scope,
        activeConfigProvider = { IrohConnectConfig("ws://127.0.0.1:$port", "", "test-device", "test") },
        directDialer = { _ ->
            openDirectAppServerDial(
                target = AppServerWebSocketDial(
                    serverUrl = "ws://127.0.0.1:$port",
                    bearerToken = "lease-token",
                    readiness = AppServerReadinessExpectation.LocalBackend,
                ),
                scope = scope,
                release = { releases.incrementAndGet() },
            )
        },
    )

    private fun startStockServer(closeFirstSessionAfterInfo: Boolean = false): Int {
        val embedded = embeddedServer(ServerCIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/ws") {
                    authHeaders += call.request.headers["Authorization"]
                    val session = sessions.incrementAndGet()
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val request = Json.parseToJsonElement(frame.readText()).jsonObject
                        val type = request["type"]?.jsonPrimitive?.content.orEmpty()
                        received += type
                        answer(type, request)
                        if (endsSession(closeFirstSessionAfterInfo, session, type)) return@webSocket
                    }
                }
            }
        }
        embedded.start(wait = false)
        server = embedded
        return runBlocking { embedded.engine.resolvedConnectors().first().port }
    }

    /** The first session ends right after answering its first agent_list, when asked to. */
    private fun endsSession(closeFirstSession: Boolean, session: Int, type: String): Boolean =
        closeFirstSession && session == 1 && type == "agent_list"

    private suspend fun DefaultWebSocketServerSession.answer(type: String, request: JsonObject) {
        val requestId = request["request_id"]?.jsonPrimitive?.content ?: return
        val reply = when (type) {
            "app_server_info" -> buildJsonObject {
                put("type", "app_server_info_response")
                put("request_id", requestId)
                put("success", true)
                put("letta_code_version", "0.34.14")
                put("protocol_version", 1)
                put("backend", "local")
                put(
                    "capabilities",
                    buildJsonObject {
                        put("runtime_start", true)
                        put("split_channels", false)
                        put("agent_management", true)
                        put("conversation_management", true)
                        put("memory_management", true)
                    },
                )
            }
            "agent_list" -> buildJsonObject {
                put("type", "agent_list_response")
                put("request_id", requestId)
                put("success", true)
                put("agents", buildJsonArray { add(buildJsonObject { put("id", "agent-1"); put("name", "Local") }) })
            }
            // A stock server has no admin_rpc: it would answer an unknown type with an error.
            else -> return
        }
        send(Frame.Text(reply.toString()))
    }

    private companion object {
        val TIMEOUT = 20.seconds
        const val POLL_MS = 50L
    }
}
