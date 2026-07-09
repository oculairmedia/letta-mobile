package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AdminRpcRegistryTest {
    @Test
    fun registryBuildsNonEmptyRouterWithRequiredMethods() = runTest {
        val router = AdminRpcRegistry.buildRouter("http://127.0.0.1:8291")

        assertTrue(router.methodCount > 0)
        assertTrue("message.list" in router.registeredMethods)
        assertTrue("conversation.list" in router.registeredMethods)
        assertTrue("goal.get" in router.registeredMethods)
        assertTrue("goal.command" in router.registeredMethods)
    }

    @Test
    fun unknownMethodReturnsStandardErrorEnvelope() = runTest {
        val router = AdminRpcRegistry.buildRouter("http://127.0.0.1:8291")

        val response = Json.parseToJsonElement(
            router.dispatch(
                requestId = "req-unknown",
                method = "unknown.method",
                params = null,
            ),
        ).jsonObject

        assertEquals("admin_rpc_response", response["type"]?.jsonPrimitive?.content)
        assertEquals("req-unknown", response["request_id"]?.jsonPrimitive?.content)
        assertEquals("false", response["success"]?.jsonPrimitive?.content)
        assertEquals("Unknown method: unknown.method", response["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun missingRequiredParamYieldsFalseSuccessEnvelope() = runTest {
        val router = AdminRpcRegistry.buildRouter("http://127.0.0.1:8291")

        val response = Json.parseToJsonElement(
            router.dispatch(
                requestId = "req-missing-param",
                method = "agent.get",
                params = buildJsonObject { },
            ),
        ).jsonObject

        assertEquals("false", response["success"]?.jsonPrimitive?.content)
        assertEquals("agent_id required", response["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun sweepNoJsonErrorReturnsInHandlers() {
        val irohDir = File("src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh")
        irohDir.listFiles { _, name -> name.endsWith("AdminHandlers.kt") }?.forEach { file ->
            val content = file.readText()
            if (content.contains("\"_error\"")) {
                fail("Found '_error' in ${file.name}; handlers must throw an exception via adminError instead of wrapping success:true with _error.")
            }
        }
    }
}
