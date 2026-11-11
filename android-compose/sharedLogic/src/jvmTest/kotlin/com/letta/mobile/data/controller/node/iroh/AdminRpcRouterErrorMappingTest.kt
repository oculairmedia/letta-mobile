package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import com.letta.mobile.util.TelemetryValue
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AdminRpcRouterErrorMappingTest {
    private val hostPath = "/home/operator/.letta/mounts/records/9f1c2d/mount.json"

    @Test
    fun nioFailureAnswersFixedMessageAndNeverLeaksThePath() = runTest {
        val recorder = TelemetryRecorder()
        Telemetry.installRecorder(recorder).use {
            val router = AdminRpcRouter()
            router.register("mount.read") { throw AccessDeniedException(hostPath) }
            router.register("mount.get") { throw NoSuchFileException(hostPath, null, "missing record") }

            for (method in listOf("mount.read", "mount.get")) {
                val response = dispatchJson(router, method)
                assertEquals(false, response["success"]?.jsonPrimitive?.boolean)
                assertEquals(ADMIN_RPC_INTERNAL_ERROR, response["error"]?.jsonPrimitive?.content)
                assertFalse(hostPath in response.toString(), method)
            }
            val events = recorder.snapshot()
            assertFalse(hostPath in events.toString())
            assertFalse(hostPath in events.joinToString { it.toLine() })
            val failures = events.filter { it.name == "handler.error" }
            assertEquals(
                setOf("AccessDeniedException", "NoSuchFileException"),
                failures.map { (it.attrs.getValue("class") as TelemetryValue.Text).value }.toSet(),
            )
            assertEquals(setOf("internal"), failures.map { (it.attrs.getValue("category") as TelemetryValue.Text).value }.toSet())
        }
    }

    @Test
    fun explicitClientRefusalKeepsItsText() = runTest {
        val recorder = TelemetryRecorder()
        Telemetry.installRecorder(recorder).use {
            val router = AdminRpcRouter()
            router.register("agent.get") { adminError("agent_id required") }

            val response = dispatchJson(router, "agent.get")

            assertEquals(false, response["success"]?.jsonPrimitive?.boolean)
            assertEquals("agent_id required", response["error"]?.jsonPrimitive?.content)
            val failure = recorder.snapshot().single { it.name == "handler.error" }
            assertEquals(TelemetryValue.Text("client_refusal"), failure.attrs["category"])
            assertFalse("agent_id required" in failure.toLine())
        }
    }

    @Test
    fun bareInternalExceptionsAnswerFixedMessage() = runTest {
        val secret = "secret internal detail"
        val recorder = TelemetryRecorder()
        Telemetry.installRecorder(recorder).use {
            val router = AdminRpcRouter()
            router.register("state.read") { throw IllegalStateException(secret) }
            router.register("arg.read") { throw IllegalArgumentException(secret) }
            router.register("check.read") { error(secret) }

            for (method in listOf("state.read", "arg.read", "check.read")) {
                val response = dispatchJson(router, method)
                assertEquals(ADMIN_RPC_INTERNAL_ERROR, response["error"]?.jsonPrimitive?.content, method)
                assertFalse(secret in response.toString(), method)
            }
            assertFalse(secret in recorder.snapshot().toString())
        }
    }

    @Test
    fun successfulCallsAreUnchanged() = runTest {
        val router = AdminRpcRouter()
        router.register("echo") { JsonPrimitive("ok") }

        val response = dispatchJson(router, "echo")

        assertEquals(true, response["success"]?.jsonPrimitive?.boolean)
        assertEquals("ok", response["result"]?.jsonPrimitive?.content)
    }

    private suspend fun dispatchJson(router: AdminRpcRouter, method: String): JsonObject =
        Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("req-1", method, null))).jsonObject
}
