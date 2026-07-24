package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * lgns8: the Iroh transport bridges the IChannelTransport cron surface onto the
 * native cron.* admin_rpc methods (CronAdminHandlers). Unlike the subagent RPC
 * these methods are not conversation-scoped and carry no capability gate, so no
 * message.list hydration is needed first.
 */
class IrohChannelTransportCronRpcTest {
    private val config = IrohConnectConfig("iroh://ticket", "token", "device", "client")
    private val json = Json { ignoreUnknownKeys = true }

    private val taskJson =
        """{"id":"task-1","agent_id":"agent-1","conversation_id":"conv-a","name":"Nightly","description":"d","cron":"0 3 * * *","timezone":"UTC","recurring":true,"prompt":"go","status":"active","created_at":"2026-01-01T00:00:00Z"}"""

    @Test
    fun `list maps tasks and forwards agent and conversation filters`() = runTest {
        val calls = mutableListOf<Call>()
        val transport = transport(calls = calls) { method, _, _ ->
            when (method) {
                "cron.list" -> response("list", result = json.parseToJsonElement("""{"tasks":[$taskJson]}"""))
                else -> error("unexpected $method")
            }
        }

        val result = transport.sendCronList(agentId = "agent-1", conversationId = "conv-a", timeoutMs = 1_000)

        assertTrue(result.success)
        assertEquals("task-1", result.tasks.single().id)
        assertEquals("0 3 * * *", result.tasks.single().cron)
        val body = json.parseToJsonElement(calls.single { it.method == "cron.list" }.body!!).jsonObject
        assertEquals("agent-1", body["agent_id"]!!.jsonPrimitive.content)
        assertEquals("conv-a", body["conversation_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `add maps task plus warning and routes a one-off at to scheduled_for`() = runTest {
        val calls = mutableListOf<Call>()
        val transport = transport(calls = calls) { method, _, _ ->
            when (method) {
                "cron.add" -> response("add", result = json.parseToJsonElement("""{"task":$taskJson,"warning":"clamped"}"""))
                else -> error("unexpected $method")
            }
        }

        val result = transport.sendCronAdd(
            agentId = "agent-1", name = "Nightly", description = "d", prompt = "go",
            recurring = false, cron = null, every = null, at = "2026-02-01T09:00:00Z",
            timezone = "UTC", conversationId = "conv-a", timeoutMs = 1_000,
        )

        assertTrue(result.success)
        assertEquals("task-1", result.task!!.id)
        assertEquals("clamped", result.warning)
        val body = json.parseToJsonElement(calls.single { it.method == "cron.add" }.body!!).jsonObject
        assertEquals("2026-02-01T09:00:00Z", body["scheduled_for"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("cron"), "no cron expression should be sent for a one-off add")
        assertFalse(body.containsKey("every"), "the native contract has no interval field")
    }

    @Test
    fun `get maps the task payload`() = runTest {
        val transport = transport { method, _, _ ->
            when (method) {
                "cron.get" -> response("get", result = json.parseToJsonElement("""{"found":true,"task":$taskJson}"""))
                else -> error("unexpected $method")
            }
        }
        val result = transport.sendCronGet("task-1", timeoutMs = 1_000)
        assertTrue(result.success)
        assertEquals("task-1", result.task!!.id)
    }

    @Test
    fun `delete succeeds and delete_all maps the deleted count`() = runTest {
        val calls = mutableListOf<Call>()
        val transport = transport(calls = calls) { method, _, _ ->
            when (method) {
                "cron.delete" -> response("del", result = buildJsonObject { put("found", true) })
                "cron.delete_all" -> response("delAll", result = buildJsonObject { put("deleted", 3) })
                else -> error("unexpected $method")
            }
        }

        assertTrue(transport.sendCronDelete("task-1", timeoutMs = 1_000).success)

        val all = transport.sendCronDeleteAll("agent-1", timeoutMs = 1_000)
        assertTrue(all.success)
        assertEquals(3L, all.count)
        assertEquals("agent-1", json.parseToJsonElement(calls.single { it.method == "cron.delete_all" }.body!!).jsonObject["agent_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `server error and timeout become typed failures`() = runTest {
        var timeout = false
        val transport = transport { method, _, _ ->
            when (method) {
                "cron.list" -> if (timeout) {
                    delay(1.seconds)
                    response("late")
                } else {
                    response("err", success = false, error = "cron_list failed")
                }
                else -> error("unexpected $method")
            }
        }

        val failed = transport.sendCronList(agentId = null, conversationId = null, timeoutMs = 1_000)
        assertFalse(failed.success)
        assertTrue(failed.error.orEmpty().contains("cron_list failed"))
        assertNull(failed.tasks.firstOrNull())

        timeout = true
        val timedOut = transport.sendCronList(agentId = null, conversationId = null, timeoutMs = 10)
        assertFalse(timedOut.success)
        assertTrue(timedOut.error.orEmpty().contains("timed out"))
    }

    private fun kotlinx.coroutines.test.TestScope.transport(
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
                serverCapabilities = setOf(IrohChannelTransport.SUBAGENT_RPC_CAPABILITY),
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
