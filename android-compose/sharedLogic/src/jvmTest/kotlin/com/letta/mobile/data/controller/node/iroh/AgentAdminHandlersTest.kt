package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.nio.file.Files

class AgentAdminHandlersTest {

    private suspend fun dispatch(r: AdminRpcRouter, method: String, params: Map<String, String>?): String =
        r.dispatch(
            AdminRpcInvocation(
                requestId = "t-1",
                method = method,
                params = params?.let { p -> buildJsonObject { p.forEach { (k, v) -> put(k, v) } } },
                context = AdminRpcRequestContext.Authenticated,
            ),
        )

    @Test
    fun agentListCoercesInvalidLimitAndOffsetAndSucceeds() = runTest {
        val root = Files.createTempDirectory("agent-admin-handlers").toFile()
        LocalBackendFixtureStore.create(root)
        val native = FakeNativeClient(agentRosterSize = 5)
        val r = AdminRpcRouter()
        AgentAdminHandlers.register(
            r,
            controller = null,
            tiers = NativeReadTiers(
                nativeClient = native,
                localBackendStore = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1"),
            ),
        )

        val invalidLimitResponse = dispatch(r, "agent.list", mapOf("limit" to "0"))
        val jsonLimit = Json.parseToJsonElement(invalidLimitResponse).jsonObject
        assertTrue(jsonLimit["success"]!!.jsonPrimitive.boolean)

        val invalidOffsetResponse = dispatch(r, "agent.list", mapOf("offset" to "-1"))
        val jsonOffset = Json.parseToJsonElement(invalidOffsetResponse).jsonObject
        assertTrue(jsonOffset["success"]!!.jsonPrimitive.boolean)

        root.deleteRecursively()
    }

    @Test
    fun requiresAgentIdWhereApplicable() = runTest {
        val root = Files.createTempDirectory("agent-admin-handlers2").toFile()
        LocalBackendFixtureStore.create(root)
        val native = FakeNativeClient(agentRosterSize = 5)
        val r = AdminRpcRouter()
        AgentAdminHandlers.register(
            r,
            controller = null,
            tiers = NativeReadTiers(
                nativeClient = native,
                localBackendStore = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1"),
            ),
        )

        val methods = listOf("agent.get", "agent.update", "agent.delete", "agent.context")
        for (method in methods) {
            val response = dispatch(r, method, emptyMap())
            val json = Json.parseToJsonElement(response).jsonObject
            assertFalse(json["success"]!!.jsonPrimitive.boolean, "$method did not return success=false")
            assertEquals("agent_id required", json["error"]?.jsonPrimitive?.content)

            val responseNull = dispatch(r, method, null)
            val jsonNull = Json.parseToJsonElement(responseNull).jsonObject
            assertFalse(jsonNull["success"]!!.jsonPrimitive.boolean, "$method did not return success=false on null")
            assertEquals("agent_id required", jsonNull["error"]?.jsonPrimitive?.content)
        }

        root.deleteRecursively()
    }
}
