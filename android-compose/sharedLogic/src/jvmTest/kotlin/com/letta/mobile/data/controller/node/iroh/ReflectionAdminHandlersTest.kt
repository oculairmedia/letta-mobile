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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ReflectionAdminHandlersTest {
    private class FakeReflectionClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        val calls = mutableListOf<String>()
        var storedSettings = buildJsonObject {
            put("trigger", "step_count")
            put("step_count", 5)
            put("model", "anthropic/sonnet")
            put("focus_instructions", "durable facts")
            put(
                "last_outcome",
                buildJsonObject {
                    put("status", "skipped")
                    put("reason", "nothing new")
                    put("at", "2026-10-31T18:00:00Z")
                },
            )
        }

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
                reflectionSettings = storedSettings,
            )
        }

        var commandOutput: String = "Started a reflection pass for this conversation."
        var commandSuccess: Boolean = true
        val commands = mutableListOf<AppServerCommand.ExecuteCommand>()

        override suspend fun executeCommand(command: AppServerCommand.ExecuteCommand): AppServerInboundFrame.ExecuteCommandResponse {
            commands += command
            return AppServerInboundFrame.ExecuteCommandResponse(command.requestId, commandSuccess, output = commandOutput)
        }

        override suspend fun setReflectionSettings(command: AppServerCommand.SetReflectionSettings): AppServerInboundFrame.SetReflectionSettingsResponse {
            calls += "set:${command.settings}"
            storedSettings = buildJsonObject {
                storedSettings.forEach { (key, value) -> put(key, value) }
                command.settings.forEach { (key, value) -> put(key, value) }
            }
            return AppServerInboundFrame.SetReflectionSettingsResponse(
                requestId = command.requestId,
                success = true,
                reflectionSettings = storedSettings,
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
        dispatch(r, method, buildJsonObject { params.forEach { (key, value) -> put(key, value) } })

    private suspend fun dispatch(r: AdminRpcRouter, method: String, params: JsonObject): String =
        r.dispatch(
            AdminRpcInvocation(
                requestId = "t",
                method = method,
                params = params,
                context = AdminRpcRequestContext.Authenticated,
            ),
        )

    /** String params plus explicit JSON nulls, which a string-only map cannot represent. */
    private fun paramsWithNull(strings: Map<String, String>, vararg nullKeys: String): JsonObject = buildJsonObject {
        strings.forEach { (key, value) -> put(key, value) }
        nullKeys.forEach { put(it, JsonNull) }
    }

    @Test
    fun getAndSetRouteNativelyWithRuntimeScope() = runTest {
        val client = FakeReflectionClient()
        val r = router(client)

        val get = dispatch(r, "reflection.get", mapOf("agent_id" to "a-1", "conversation_id" to "c-1"))
        assertTrue(get.contains("\"success\":true") && get.contains("focus_instructions") && get.contains("last_outcome"), get)
        assertTrue("get:a-1/c-1" in client.calls)

        val set = dispatch(r, "reflection.set", mapOf("agent_id" to "a-1", "conversation_id" to "c-1", "trigger" to "step_count", "step_count" to "7"))
        assertTrue(set.contains("\"success\":true"))
        assertTrue(client.calls.any { it.startsWith("set:") && it.contains("\"step_count\":7") })
    }

    @Test
    fun oldClientSetPreservesReflectionV2Settings() = runTest {
        val client = FakeReflectionClient()
        val response = dispatch(
            router(client),
            "reflection.set",
            mapOf("agent_id" to "a", "trigger" to "off", "step_count" to "7"),
        )
        assertEquals("anthropic/sonnet", client.storedSettings.getValue("model").toString().trim('"'))
        assertEquals("durable facts", client.storedSettings.getValue("focus_instructions").toString().trim('"'))
        assertTrue(response.contains("last_outcome") && response.contains("nothing new"), response)
    }

    @Test
    fun withoutNativeClientReflectionFailsClearlyNotViaShim() = runTest {
        val r = router(client = null)
        listOf("reflection.get", "reflection.set", "reflection.run").forEach { method ->
            val response = dispatch(r, method, mapOf("agent_id" to "a", "conversation_id" to "c", "trigger" to "step_count", "step_count" to "1"))
            assertTrue(response.contains("\"success\":false"), method)
            assertTrue(response.contains("capability_unavailable") && response.contains("native App Server client"), "$method: $response")
        }
    }

    @Test
    fun setRejectsMissingOrNonIntegerStepCount() = runTest {
        val r = router(FakeReflectionClient())
        val bad = dispatch(r, "reflection.set", mapOf("agent_id" to "a", "conversation_id" to "c", "trigger" to "step_count", "step_count" to "not-a-number"))
        assertTrue(bad.contains("\"success\":false") && bad.contains("step_count"))
    }

    @Test
    fun aMissingConversationIsTheAgentsDefault() = runTest {
        val client = FakeReflectionClient()
        dispatch(router(client), "reflection.get", mapOf("agent_id" to "a-1"))
        assertTrue("get:a-1/default" in client.calls)
    }

    @Test
    fun setPassesMergeFieldsOnlyWhenSentAndValidatesThem() = runTest {
        val client = FakeReflectionClient()
        val r = router(client)
        dispatch(r, "reflection.set", mapOf("agent_id" to "a", "trigger" to "compaction-event", "step_count" to "25"))
        assertTrue(client.calls.last().let { !it.contains("merge") }, client.calls.last())
        dispatch(
            r, "reflection.set",
            mapOf("agent_id" to "a", "trigger" to "step-count", "step_count" to "9", "merge" to "explicit", "merge_instructions" to "short", "scope" to "global"),
        )
        assertTrue(client.calls.last().contains("\"merge\":\"explicit\"") && client.calls.last().contains("\"merge_instructions\":\"short\""), client.calls.last())
        val badMerge = dispatch(r, "reflection.set", mapOf("agent_id" to "a", "trigger" to "off", "step_count" to "1", "merge" to "sometimes"))
        assertTrue(badMerge.contains("invalid_merge"), badMerge)
        val badTrigger = dispatch(r, "reflection.set", mapOf("agent_id" to "a", "trigger" to "on-idle", "step_count" to "1"))
        assertTrue(badTrigger.contains("invalid_trigger"), badTrigger)
        val badScope = dispatch(r, "reflection.set", mapOf("agent_id" to "a", "trigger" to "off", "step_count" to "1", "scope" to "machine"))
        assertTrue(badScope.contains("invalid_scope"), badScope)
    }

    @Test
    fun setPassesAndValidatesOptionalReflectionModelAndFocus() = runTest {
        val client = FakeReflectionClient()
        val r = router(client)
        val strings = mapOf(
            "agent_id" to "a",
            "trigger" to "step-count",
            "step_count" to "9",
            "model" to "openai/gpt-5",
            "focus_instructions" to "project decisions",
        )
        dispatch(r, "reflection.set", strings)
        assertEquals("\"openai/gpt-5\"", client.storedSettings.getValue("model").toString())
        assertEquals("\"project decisions\"", client.storedSettings.getValue("focus_instructions").toString())

        dispatch(r, "reflection.set", paramsWithNull(strings - "model", "model"))
        assertEquals(JsonNull, client.storedSettings["model"])

        val blankModel = dispatch(r, "reflection.set", strings + ("model" to " "))
        assertTrue(blankModel.contains("invalid_model"), blankModel)
        val nullFocus = dispatch(r, "reflection.set", paramsWithNull(strings - "focus_instructions", "focus_instructions"))
        assertTrue(nullFocus.contains("focus_instructions"), nullFocus)
        val booleanModel = dispatch(
            r,
            "reflection.set",
            buildJsonObject {
                strings.forEach { (key, value) -> put(key, value) }
                put("model", true)
            },
        )
        assertTrue(booleanModel.contains("invalid_model"), booleanModel)
    }

    @Test
    fun runStartsLettaCodesDreamWithTheInstruction() = runTest {
        val client = FakeReflectionClient()
        val response = dispatch(router(client), "reflection.run", mapOf("agent_id" to "a-1", "conversation_id" to "c-1", "instruction" to "tools"))
        assertTrue(response.contains("\"success\":true") && response.contains("Started"), response)
        val command = client.commands.single()
        assertEquals("dream", command.commandId)
        assertEquals("-- tools", command.args)
        assertEquals("c-1", command.runtime?.conversationId)
    }

    @Test
    fun runOnARuntimeWithoutDreamIsCapabilityUnavailable() = runTest {
        val client = FakeReflectionClient().apply {
            commandSuccess = false
            commandOutput = "Unknown command: dream"
        }
        val response = dispatch(router(client), "reflection.run", mapOf("agent_id" to "a-1"))
        assertTrue(response.contains("capability_unavailable: this App Server has no /dream"), response)
    }

    @Test
    fun bothMethodsRegistered() {
        assertEquals(setOf("reflection.get", "reflection.set", "reflection.run"), ReflectionAdminHandlers.methods)
        val r = router(FakeReflectionClient())
        assertTrue(ReflectionAdminHandlers.methods.all { it in r.registeredMethods })
    }
}
