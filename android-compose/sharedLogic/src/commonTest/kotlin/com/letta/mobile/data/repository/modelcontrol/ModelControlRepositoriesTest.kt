package com.letta.mobile.data.repository.modelcontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class ModelControlRepositoriesTest {
    @Test
    fun providerListDecodesAuthMethodsImplicitFieldsAndConnections() = runTest {
        val repo = ProviderConnectionRepository(RecordingInvoker { _, _ -> ModelControlFixtures.providerList })

        val providers = repo.refresh()

        assertEquals(listOf("amazon-bedrock", "lmstudio", "anthropic-oauth"), providers.map { it.id })
        val bedrock = providers[0]
        assertEquals(listOf("iam", "profile"), bedrock.authMethods.map { it.id })
        assertTrue(bedrock.authMethods[0].fields.first { it.key == "apiKey" }.secret)
        val lmstudio = providers[1]
        assertEquals(listOf<String?>(null), lmstudio.authMethods.map { it.id })
        assertTrue(lmstudio.isConnected)
        assertEquals("http://127.0.0.1:1234/v1", lmstudio.connections.single().baseUrl)
        assertFalse(providers[2].canConnectFromApp)
        assertEquals(providers, repo.providers.value)
    }

    @Test
    fun connectSendsTheMethodAndFieldsAndAdoptsTheReturnedCatalog() = runTest {
        val invoker = RecordingInvoker { _, _ -> ModelControlFixtures.mutation(modelsMayHaveChanged = true) }
        val repo = ProviderConnectionRepository(invoker)

        val result = repo.connect(ProviderConnectRequest("amazon-bedrock", "iam", mapOf("apiKey" to "fixture-secret")))

        val (method, params) = invoker.calls.single()
        assertEquals("provider.connect", method)
        assertEquals(JsonPrimitive("iam"), params["auth_method_id"])
        assertEquals(JsonPrimitive("fixture-secret"), params.getValue("fields").jsonObject["apiKey"])
        assertTrue(result.modelsMayHaveChanged)
        assertEquals(3, repo.providers.value.size)
    }

    @Test
    fun connectOmitsAuthMethodForImplicitFieldProviders() = runTest {
        val invoker = RecordingInvoker { _, _ -> ModelControlFixtures.mutation(modelsMayHaveChanged = false) }

        ProviderConnectionRepository(invoker).connect(ProviderConnectRequest("lmstudio", null, emptyMap()))

        assertFalse("auth_method_id" in invoker.calls.single().second)
    }

    @Test
    fun disconnectPassesTheAlias() = runTest {
        val invoker = RecordingInvoker { _, _ -> ModelControlFixtures.mutation(modelsMayHaveChanged = true) }

        ProviderConnectionRepository(invoker).disconnect(ProviderDisconnectTarget(ConnectableProviderId("lmstudio"), "lc-lmstudio"))

        val (method, params) = invoker.calls.single()
        assertEquals("provider.disconnect", method)
        assertEquals(JsonPrimitive("lc-lmstudio"), params["provider_name"])
    }

    @Test
    fun catalogIncludesHiddenModelsWithEfforts() = runTest {
        val invoker = RecordingInvoker { _, _ -> ModelControlFixtures.modelList }
        val repo = ModelCatalogRepository(invoker)

        val models = repo.refresh()

        assertEquals(JsonPrimitive(true), invoker.calls.single().second["include_hidden"])
        assertEquals(listOf("openai/gpt-sol", "lmstudio/minimax-m3"), models.map { it.handle.value })
        assertEquals(listOf("openai/gpt-sol"), repo.exposedModels.map { it.handle.value })
        assertEquals(listOf("none", "high"), repo.reasoningEffortsFor(ModelHandle("openai/gpt-sol")))
        assertEquals(emptyList(), repo.reasoningEffortsFor(ModelHandle("lmstudio/minimax-m3")))
    }

    @Test
    fun setExposedIsOptimisticAndRollsBackOnFailure() = runTest {
        var failWrites = false
        val invoker = RecordingInvoker { method, _ ->
            if (method == "model.exposure.set" && failWrites) throw ModelControlException("denied")
            if (method == "model.list") ModelControlFixtures.modelList else JsonNull
        }
        val repo = ModelCatalogRepository(invoker)
        repo.refresh()

        repo.setExposed(ExposureChange(ModelHandle("lmstudio/minimax-m3"), true))
        assertTrue(repo.models.value.all { it.exposed })

        failWrites = true
        assertFailsWith<ModelControlException> { repo.setExposed(ExposureChange(ModelHandle("openai/gpt-sol"), false)) }
        assertTrue(repo.models.value.first { it.handle.value == "openai/gpt-sol" }.exposed)
    }

    @Test
    fun modelUpdateEncodesEachEffortChoice() = runTest {
        val invoker = RecordingInvoker { _, _ -> JsonNull }
        val repo = ConversationModelRepository(invoker)
        val target = ConversationModelTarget("agent-1", "conv-1")

        val sol = ModelHandle("openai/gpt-sol")
        repo.updateModel(target, sol)
        repo.updateModel(target, null, ReasoningEffortChoice.ProviderDefault)
        repo.updateModel(target, sol, ReasoningEffortChoice.Named("high"))

        val params = invoker.calls.map { it.second }
        assertFalse("reasoning_effort" in params[0])
        assertEquals(JsonNull, params[1]["reasoning_effort"])
        assertFalse("model_handle" in params[1])
        assertEquals(JsonPrimitive("high"), params[2]["reasoning_effort"])
        assertEquals(JsonPrimitive("conv-1"), params[2]["conversation_id"])
    }

    @Test
    fun modelUpdateRejectsAnEmptyChange() = runTest {
        val repo = ConversationModelRepository(RecordingInvoker { _, _ -> JsonNull })

        assertFailsWith<IllegalArgumentException> { repo.updateModel(ConversationModelTarget("a", "c"), null) }
    }

    @Test
    fun conversationScopedSwitchBecomesTheConversationsSelectedModel() = runTest {
        // letta-mobile-okvyf: update_model on a conversation leaves agent.model alone, so the
        // picker must read the recorded conversation selection or it never moves.
        val repo = ConversationModelRepository(
            RecordingInvoker { _, _ ->
                buildJsonObject {
                    put("applied_to", "conversation")
                    put("model_handle", "openai/gpt-sol")
                }
            },
        )

        repo.updateModel(ConversationModelTarget("agent-1", "conv-1"), ModelHandle("openai/gpt-sol"))

        assertEquals(mapOf("conv-1" to "openai/gpt-sol"), repo.selections.byConversation.value)
        assertEquals("openai/gpt-sol", repo.selections.effectiveModel("conv-1", agentModel = "anthropic/old"))
        assertEquals("anthropic/old", repo.selections.effectiveModel("conv-2", agentModel = "anthropic/old"))
    }

    @Test
    fun switchWithoutAHandleInTheReplyRecordsTheRequestedModel() = runTest {
        val repo = ConversationModelRepository(RecordingInvoker { _, _ -> JsonNull })

        repo.updateModel(ConversationModelTarget("agent-1", "conv-1"), ModelHandle("openai/gpt-sol"))

        assertEquals("openai/gpt-sol", repo.selections["conv-1"])
    }

    @Test
    fun agentScopedSwitchDefersToTheAgentsModel() = runTest {
        val repo = ConversationModelRepository(
            RecordingInvoker { _, _ -> buildJsonObject { put("applied_to", "agent") } },
        )
        repo.selections.record("conv-1", "stale/model")

        repo.updateModel(ConversationModelTarget("agent-1", "conv-1"), ModelHandle("openai/gpt-sol"))

        assertEquals(null, repo.selections["conv-1"])
    }

    @Test
    fun effortOnlyUpdateAndFailedSwitchLeaveTheSelectionAlone() = runTest {
        val repo = ConversationModelRepository(RecordingInvoker { _, _ -> error("rejected") })
        repo.selections.record("conv-1", "openai/gpt-sol")

        assertFailsWith<IllegalStateException> {
            repo.updateModel(ConversationModelTarget("agent-1", "conv-1"), ModelHandle("openai/other"))
        }
        assertEquals("openai/gpt-sol", repo.selections["conv-1"])

        val effortOnly = ConversationModelRepository(RecordingInvoker { _, _ -> JsonNull }, repo.selections)
        effortOnly.updateModel(ConversationModelTarget("agent-1", "conv-1"), null, ReasoningEffortChoice.Named("high"))
        assertEquals("openai/gpt-sol", repo.selections["conv-1"])
    }
}
