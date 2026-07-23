package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ReflectionAdminHandlersTest {
    private class FakeReflectionClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        val calls = mutableListOf<String>()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")

        override suspend fun getReflectionSettings(command: AppServerCommand.GetReflectionSettings): AppServerInboundFrame.GetReflectionSettingsResponse {
            calls += "get:${command.runtime.agentId}/${command.runtime.conversationId}"
            return AppServerInboundFrame.GetReflectionSettingsResponse(
                requestId = command.requestId,
                success = true,
                reflectionSettings = buildJsonObject { put("trigger", "step_count"); put("step_count", 5) },
            )
        }

        override suspend fun setReflectionSettings(command: AppServerCommand.SetReflectionSettings): AppServerInboundFrame.SetReflectionSettingsResponse {
            calls += "set:${command.settings}"
            return AppServerInboundFrame.SetReflectionSettingsResponse(
                requestId = command.requestId,
                success = true,
                reflectionSettings = command.settings,
                scope = command.scope ?: "conversation",
            )
        }
    }

    private fun router(client: AppServerClient?): AdminRpcRouter {
        val r = AdminRpcRouter()
        ReflectionAdminHandlers.register(r, client)
        return r
    }

    private suspend fun dispatch(r: AdminRpcRouter, method: String, params: Map<String, String>): String =
        r.dispatch(
            AdminRpcInvocation(
                requestId = "t",
                method = method,
                params = buildJsonObject { params.forEach { (k, v) -> put(k, v) } },
                context = AdminRpcRequestContext.Authenticated,
            ),
        )

    @Test
    fun getAndSetRouteNativelyWithRuntimeScope() = runTest {
        val client = FakeReflectionClient()
        val r = router(client)

        val get = dispatch(r, "reflection.get", mapOf("agent_id" to "a-1", "conversation_id" to "c-1"))
        assertTrue(get.contains("\"success\":true") && get.contains("step_count"))
        assertTrue("get:a-1/c-1" in client.calls)

        val set = dispatch(r, "reflection.set", mapOf("agent_id" to "a-1", "conversation_id" to "c-1", "trigger" to "step_count", "step_count" to "7"))
        assertTrue(set.contains("\"success\":true"))
        assertTrue(client.calls.any { it.startsWith("set:") && it.contains("\"step_count\":7") })
    }

    @Test
    fun withoutNativeClientReflectionFailsClearlyNotViaShim() = runTest {
        val r = router(client = null)
        listOf("reflection.get", "reflection.set").forEach { method ->
            val response = dispatch(r, method, mapOf("agent_id" to "a", "conversation_id" to "c", "trigger" to "step_count", "step_count" to "1"))
            assertTrue(response.contains("\"success\":false"), method)
            assertTrue(response.contains("native App Server client"), "$method: $response")
        }
    }

    @Test
    fun setRejectsMissingOrNonIntegerStepCount() = runTest {
        val r = router(FakeReflectionClient())
        val bad = dispatch(r, "reflection.set", mapOf("agent_id" to "a", "conversation_id" to "c", "trigger" to "step_count", "step_count" to "not-a-number"))
        assertTrue(bad.contains("\"success\":false") && bad.contains("step_count"))
    }

    @Test
    fun bothMethodsRegistered() {
        assertEquals(setOf("reflection.get", "reflection.set"), ReflectionAdminHandlers.methods)
        val r = router(FakeReflectionClient())
        assertTrue(ReflectionAdminHandlers.methods.all { it in r.registeredMethods })
    }
}
