package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** letta-mobile-w4q4p: model.update, model.list exposure filtering, model.exposure.*. */
class ModelControlHandlersTest {
    @BeforeTest
    fun resetBreaker() = NativeAdmin.resetCircuitForTest()

    @AfterTest
    fun resetBreakerAfter() = NativeAdmin.resetCircuitForTest()

    @Test
    fun modelUpdateTargetsTheConversationScopeAndForwardsTheHandle() = runTest {
        val client = FakeProviderModelClient()
        val params = scope { put("model_handle", "lmstudio/minimax-m3") }

        val result = AdminRpcTestEnvelope.result(router(client).dispatch("r", "model.update", params)).jsonObject

        val sent = client.commands.single() as AppServerCommand.UpdateModel
        assertEquals("agent-1", sent.runtime.agentId)
        assertEquals("conv-1", sent.runtime.conversationId)
        assertEquals("lmstudio/minimax-m3", sent.payload.modelHandle)
        assertNull(sent.payload.reasoningEffort, "absent reasoning_effort must stay absent")
        assertEquals("conversation", result.string("applied_to"))
    }

    @Test
    fun modelUpdateMapsExplicitNullEffortToRestoreDefault() = runTest {
        val client = FakeProviderModelClient()
        val params = scope { put("reasoning_effort", JsonNull) }

        router(client).dispatch("r", "model.update", params)

        assertEquals(JsonNull, (client.commands.single() as AppServerCommand.UpdateModel).payload.reasoningEffort)
    }

    @Test
    fun modelUpdateRejectsUnknownEffortAndEmptyPayload() = runTest {
        val client = FakeProviderModelClient()
        val r = router(client)

        val badEffort = r.dispatch("r", "model.update", scope { put("reasoning_effort", "turbo") })
        val empty = r.dispatch("r", "model.update", scope { })

        assertTrue(badEffort.contains("reasoning_effort must be one of"), badEffort)
        assertTrue(empty.contains("requires model_handle"), empty)
        assertTrue(client.commands.isEmpty())
    }

    @Test
    fun modelUpdateSurfacesTheRecordedUpstreamError() = runTest {
        val response = router(FakeProviderModelClient(updateSucceeds = false))
            .dispatch("r", "model.update", scope { put("model_handle", "lmstudio/none") })

        assertTrue(response.contains("Agent agent-does-not-exist not found"), response)
    }

    @Test
    fun modelListMarksExposureAndCarriesReasoningEfforts() = runTest {
        val rows = listRows(router(FakeProviderModelClient()), includeHidden = false)

        assertEquals(listOf("openai/gpt-sol", "lmstudio/minimax-m3"), rows.map { it.string("handle") })
        assertTrue(rows.all { it.getValue("exposed").jsonPrimitive.boolean })
        val sol = rows.first { it.string("handle") == "openai/gpt-sol" }
        assertEquals(listOf("none", "high"), sol.getValue("reasoning_efforts").jsonArray.map { it.jsonPrimitive.content })
        assertTrue("reasoning_efforts" !in rows.last())
    }

    @Test
    fun hiddenModelsDropOutUnlessIncludeHiddenIsSet() = runTest {
        val store = InMemoryModelExposureStore()
        val r = router(FakeProviderModelClient(), store)

        val set = r.dispatch("r", "model.exposure.set", buildJsonObject { put("handle", "openai/gpt-sol"); put("exposed", false) })
        val visible = listRows(r, includeHidden = false)
        val all = listRows(r, includeHidden = true)

        assertTrue(set.contains("\"hidden\":[\"openai/gpt-sol\"]"), set)
        assertEquals(listOf("lmstudio/minimax-m3"), visible.map { it.string("handle") })
        assertEquals(false, all.first { it.string("handle") == "openai/gpt-sol" }.getValue("exposed").jsonPrimitive.boolean)
    }

    @Test
    fun exposureSetAcceptsABatchAndGetReportsTheDefault() = runTest {
        val r = router(FakeProviderModelClient(), InMemoryModelExposureStore(mapOf("a/x" to false)))
        val batch = buildJsonObject {
            put("models", buildJsonObject { put("a/x", true); put("b/y", false) })
        }

        r.dispatch("r", "model.exposure.set", batch)
        val state = AdminRpcTestEnvelope.result(r.dispatch("r", "model.exposure.get", null)).jsonObject

        assertEquals(JsonPrimitive(true), state["default_exposed"])
        assertEquals(listOf("b/y"), state.getValue("hidden").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun exposureSetRequiresAChange() = runTest {
        val response = router(FakeProviderModelClient()).dispatch("r", "model.exposure.set", buildJsonObject { })

        assertTrue(response.contains("requires handle+exposed or models"), response)
    }

    private suspend fun listRows(r: AdminRpcRouter, includeHidden: Boolean): List<JsonObject> {
        val params = buildJsonObject { put("include_hidden", includeHidden) }
        return AdminRpcTestEnvelope.result(r.dispatch("r", "model.list", params)).jsonArray.map { it.jsonObject }
    }

    private fun scope(extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("agent_id", "agent-1")
        put("conversation_id", "conv-1")
        extra()
    }

    private fun router(
        client: FakeProviderModelClient?,
        exposure: ModelExposureStore = InMemoryModelExposureStore(),
    ): AdminRpcRouter = AdminRpcRouter().also { ModelAdminHandlers.register(it, client, exposure) }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
