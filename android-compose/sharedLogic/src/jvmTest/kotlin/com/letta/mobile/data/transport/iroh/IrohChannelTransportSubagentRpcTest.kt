package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

import kotlin.time.Duration.Companion.seconds
class IrohChannelTransportSubagentRpcTest {
    private val config = IrohConnectConfig("iroh://ticket", "token", "device", "client")
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `list uses hydrated conversation scope and maps server frame`() = runTest {
        val calls = mutableListOf<Call>()
        val transport = transport(calls = calls) { method, _, _ ->
            when (method) {
                "message.list" -> response("hydrate", result = buildJsonObject { put("messages", "ok") })
                "subagent.list" -> response(
                    "list",
                    result = json.parseToJsonElement(
                        """{"subagents":[{"toolCallId":"tc-1","status":"running","parentConversationId":"conv-a"}]}""",
                    ),
                )
                else -> error("unexpected $method")
            }
        }

        transport.adminRpc("message.list", "/v1/conversations/conv-a/messages", null)
        val result = transport.sendSubagentList(all = true, timeoutMs = 1_000)

        assertTrue(result.success)
        assertEquals("tc-1", result.subagents.single().toolCallId)
        val request = calls.single { it.method == "subagent.list" }
        val body = json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("conv-a", body["conversation_id"]!!.jsonPrimitive.content)
        assertTrue(body["all"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `todos maps found flags and todo payload`() = runTest {
        val calls = mutableListOf<Call>()
        val transport = transport(calls = calls) { method, _, _ ->
            when (method) {
                "message.list" -> response("hydrate")
                "subagent.todos" -> response(
                    "todos",
                    result = json.parseToJsonElement(
                        """{"found":true,"subagent":{"toolCallId":"tc-1","status":"running","parentConversationId":"conv-a"},"todos":[{"content":"Test it","status":"completed","activeForm":"Testing it"}],"todos_found":true}""",
                    ),
                )
                else -> error("unexpected $method")
            }
        }

        transport.adminRpc("message.list", "/v1/conversations/conv-a/messages", null)
        val result = transport.sendSubagentTodos("tc-1", timeoutMs = 1_000)

        assertTrue(result.success)
        assertTrue(result.found)
        assertTrue(result.todosFound)
        assertEquals("Test it", result.todos.single().content)
        val request = calls.single { it.method == "subagent.todos" }
        assertEquals("tc-1", json.parseToJsonElement(request.body!!).jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing scope and old node capability degrade without unscoped request`() = runTest {
        val calls = mutableListOf<Call>()
        val unhydrated = transport(calls = calls) { _, _, _ -> response("unused") }
        val missingScope = unhydrated.sendSubagentList(all = false, timeoutMs = 1_000)
        assertFalse(missingScope.success)
        assertTrue(missingScope.error.orEmpty().contains("scope unavailable"))
        assertTrue(calls.isEmpty())

        val oldCalls = mutableListOf<Call>()
        val oldNode = transport(capabilities = emptySet(), calls = oldCalls) { _, _, _ -> response("hydrate") }
        oldNode.adminRpc("message.list", "/v1/conversations/conv-a/messages", null)
        val degraded = oldNode.sendSubagentList(all = false, timeoutMs = 1_000)
        assertFalse(degraded.success)
        assertTrue(degraded.error.orEmpty().contains("unavailable"))
        assertTrue(oldCalls.isNotEmpty())
        assertTrue(oldCalls.all { it.method == "message.list" })
    }

    @Test
    fun `unknown method and timeout become typed failures`() = runTest {
        var timeout = false
        val transport = transport { method, _, _ ->
            when (method) {
                "message.list" -> response("hydrate")
                "subagent.list" -> if (timeout) {
                    delay(1.seconds)
                    response("late")
                } else {
                    response("missing", success = false, error = "Unknown method: subagent.list")
                }
                else -> error("unexpected $method")
            }
        }
        transport.adminRpc("message.list", "/v1/conversations/conv-a/messages", null)

        val unsupported = transport.sendSubagentList(all = false, timeoutMs = 1_000)
        assertFalse(unsupported.success)
        assertTrue(unsupported.error.orEmpty().contains("unavailable"))

        timeout = true
        val timedOut = transport.sendSubagentList(all = false, timeoutMs = 10)
        assertFalse(timedOut.success)
        assertTrue(timedOut.error.orEmpty().contains("timed out"))
    }

    @Test
    fun `request error becomes typed failure`() = runTest {
        val transport = transport { method, _, _ ->
            if (method == "message.list") response("hydrate") else error("registry read failed")
        }
        transport.adminRpc("message.list", "/v1/conversations/conv-a/messages", null)

        val result = transport.sendSubagentTodos("tc-1", timeoutMs = 1_000)

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("registry read failed"))
    }

    private fun kotlinx.coroutines.test.TestScope.transport(
        capabilities: Set<String>? = setOf(IrohChannelTransport.SUBAGENT_RPC_CAPABILITY),
        calls: MutableList<Call> = mutableListOf(),
        responder: suspend (String, String, String?) -> AppServerInboundFrame.AdminRpcResponse,
    ) = IrohChannelTransport(
        scope = this,
        activeConfigProvider = { config },
        testDialer = {
            IrohConnectionHandle(
                config = config,
                ticket = "ticket",
                sessionId = "session",
                serverCapabilities = capabilities,
                adminRpcCall = { method, path, body ->
                    calls += Call(method, path, body)
                    responder(method, path, body)
                },
                close = {},
            )
        },
    )

    private fun response(
        requestId: String,
        success: Boolean = true,
        result: kotlinx.serialization.json.JsonElement? = buildJsonObject {},
        error: String? = null,
    ) = AppServerInboundFrame.AdminRpcResponse(requestId, success, result, error)

    private data class Call(val method: String, val path: String, val body: String?)
}
