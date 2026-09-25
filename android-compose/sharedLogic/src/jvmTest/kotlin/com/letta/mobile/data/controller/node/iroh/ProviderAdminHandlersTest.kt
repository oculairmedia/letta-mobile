package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** letta-mobile-w4q4p: provider.* over the live connect-provider commands. */
class ProviderAdminHandlersTest {
    @BeforeTest
    fun resetBreaker() = NativeAdmin.resetCircuitForTest()

    @AfterTest
    fun resetBreakerAfter() = NativeAdmin.resetCircuitForTest()

    private val lenient = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @Test
    fun providerListProjectsTheRecordedCatalogWithUpstreamAndLegacyKeys() = runTest {
        val client = FakeProviderModelClient()
        val rows = AdminRpcTestEnvelope.result(router(client).dispatch("r", "provider.list", null)).jsonArray

        assertEquals(5, rows.size)
        val lmstudio = rows.map { it.jsonObject }.first { it.string("id") == "lmstudio" }
        assertEquals("lmstudio", lmstudio.string("name"))
        assertEquals("byok", lmstudio.string("provider_category"))
        assertEquals("http://127.0.0.1:4002/v1", lmstudio.string("base_url"))
        assertTrue(lmstudio.getValue("is_connected").jsonPrimitive.boolean)
        assertEquals("lc-lmstudio", lmstudio.getValue("connected").jsonObject.string("provider_name"))
        assertEquals(listOf("baseUrl", "apiKey"), lmstudio.getValue("fields").jsonArray.map { it.jsonObject.string("key") })
        val bedrockMethods = rows.map { it.jsonObject }.first { it.string("id") == "amazon-bedrock" }.getValue("auth_methods")
        assertEquals(listOf("iam", "profile"), bedrockMethods.jsonArray.map { it.jsonObject.string("id") })
        assertEquals(listOf(AppServerCommand.ListConnectProviders::class), client.commands.map { it::class })
    }

    @Test
    fun providerListStillDecodesAsTheLegacyProviderModel() = runTest {
        val result = AdminRpcTestEnvelope.result(router(FakeProviderModelClient()).dispatch("r", "provider.list", null))

        val providers = lenient.decodeFromJsonElement(ListSerializer(Provider.serializer()), result)

        assertEquals(5, providers.size)
        assertEquals("anthropic", providers.first { it.id?.value == "anthropic" }.providerType)
    }

    @Test
    fun providerConnectForwardsFieldsAndReturnsTheRefreshedCatalog() = runTest {
        val client = FakeProviderModelClient()
        val params = buildJsonObject {
            put("provider_id", "openai-compatible")
            put("fields", buildJsonObject { put("baseUrl", "http://127.0.0.1:1234/v1"); put("apiKey", "fixture-key") })
        }

        val result = AdminRpcTestEnvelope.result(router(client).dispatch("r", "provider.connect", params)).jsonObject

        val sent = client.commands.single() as AppServerCommand.ConnectProvider
        assertEquals("local", sent.target)
        assertEquals(mapOf("baseUrl" to "http://127.0.0.1:1234/v1", "apiKey" to "fixture-key"), sent.fields)
        assertFalse(sent.toString().contains("fixture-key"), "toString must not print credential values")
        assertTrue(result.getValue("models_may_have_changed").jsonPrimitive.boolean)
        assertEquals(5, result.getValue("providers").jsonArray.size)
    }

    @Test
    fun providerConnectSurfacesTheUpstreamRejection() = runTest {
        val response = router(FakeProviderModelClient(connectSucceeds = false))
            .dispatch("r", "provider.connect", buildJsonObject { put("provider_id", "no-such-provider") })

        assertTrue(response.contains("\"success\":false"), response)
        assertTrue(response.contains("Unknown provider: no-such-provider"), response)
    }

    @Test
    fun providerConnectRejectsNonStringFieldsBeforeCallingUpstream() = runTest {
        val client = FakeProviderModelClient()
        val params = buildJsonObject {
            put("provider_id", "openai")
            put("fields", buildJsonObject { put("apiKey", 42) })
        }

        val response = router(client).dispatch("r", "provider.connect", params)

        assertTrue(response.contains("fields.apiKey must be a string"), response)
        assertTrue(client.commands.isEmpty())
    }

    @Test
    fun providerDisconnectSendsProviderIdAndAlias() = runTest {
        val client = FakeProviderModelClient()
        val params = buildJsonObject {
            put("provider_id", "no-such-provider")
            put("provider_name", "lc-alias")
        }

        val response = router(client).dispatch("r", "provider.disconnect", params)

        val sent = client.commands.single() as AppServerCommand.DisconnectProvider
        assertEquals("no-such-provider", sent.providerId)
        assertEquals("lc-alias", sent.providerName)
        assertTrue(response.contains("Unknown provider: no-such-provider"), response)
    }

    @Test
    fun providerMethodsFailClosedWithoutANativeClient() = runTest {
        val r = router(client = null)
        listOf("provider.list", "provider.disconnect").forEach { method ->
            val response = r.dispatch("r", method, buildJsonObject { put("provider_id", "openai") })
            assertTrue(response.contains("capability_unavailable"), "$method: $response")
        }
    }

    private fun router(client: FakeProviderModelClient?): AdminRpcRouter =
        AdminRpcRouter().also { ModelAdminHandlers.register(it, client) }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
