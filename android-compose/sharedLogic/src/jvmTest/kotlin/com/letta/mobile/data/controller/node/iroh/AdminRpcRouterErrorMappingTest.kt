package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.beads.BeadsOperation
import com.letta.mobile.data.controller.node.iroh.beads.BeadsRouteClient
import com.letta.mobile.data.controller.node.iroh.records.FileWrapperRecordStore
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import com.letta.mobile.util.TelemetryValue
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlin.io.path.createTempDirectory
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

    /** One converted refusal per handler file: the client still reads the same text as before. */
    private class RefusalCase(
        val name: String,
        val invocation: AdminRpcInvocation,
        val expected: String,
        val install: (AdminRpcRouter) -> Unit,
    )

    private val refusalCases = listOf(
        RefusalCase("approval param", call("approval.submit", agentParams), "payload required") { router ->
            ApprovalAdminHandlers.register(router)
        },
        RefusalCase("record validation", call("run.record"), "validation.invalid_params: agentId") { router ->
            registerRunRecordHandlers(router, FileWrapperRecordStore())
        },
        RefusalCase("beads validation", call("beads.ready"), "validation.invalid_params: query") { router ->
            val client = BeadsRouteClient("http://127.0.0.1:9", null)
            router.register("beads.ready") { client.execute(BeadsOperation.LIST_READY_WORK, invalidBeadsQuery) }
        },
        RefusalCase("pairing service", call("pair.invite.mint"), "an invite must grant at least one capability") { router ->
            val service = IrohPairingService(InMemoryPairedPeerStore())
            router.register("pair.invite.mint") {
                service.createInvite(IrohPairingService.InviteRequest(suggestedName = "phone", capabilities = emptySet()))
                JsonPrimitive("unreachable")
            }
        },
        RefusalCase("memfs segment", call("block.list_agent"), "agentId must be a single path segment, got '../escape'") { router ->
            val store = LocalBackendAdminStore(createTempDirectory("admin-rpc-blocks").toFile())
            router.register("block.list_agent") { store.blocksForAgentProjected("../escape") }
        },
    )

    @Test
    fun convertedRefusalsKeepTheirClientText() = runTest {
        for (case in refusalCases) {
            val router = AdminRpcRouter().also(case.install)

            val response = Json.parseToJsonElement(router.dispatch(case.invocation)).jsonObject

            assertEquals(false, response["success"]?.jsonPrimitive?.boolean, case.name)
            assertEquals(case.expected, response["error"]?.jsonPrimitive?.content, case.name)
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

    private companion object {
        val invalidBeadsQuery = JsonObject(mapOf("query" to JsonPrimitive("not-an-object")))
        val agentParams = JsonObject(mapOf("agent_id" to JsonPrimitive("agent-1")))

        fun call(method: String, params: JsonObject = JsonObject(emptyMap())) = AdminRpcInvocation("req-1", method, params)
    }
}
