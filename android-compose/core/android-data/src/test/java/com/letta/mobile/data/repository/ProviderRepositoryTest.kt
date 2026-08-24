package com.letta.mobile.data.repository

import com.letta.mobile.data.api.IrohAdminApiUnavailableException
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeProviderApi
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ProviderRepositoryTest {

    private lateinit var fakeApi: FakeProviderApi
    private lateinit var repository: ProviderRepository

    @Before
    fun setup() {
        fakeApi = FakeProviderApi()
        repository = ProviderRepository(fakeApi)
    }

    @Test
    fun `refreshProviders calls API`() = runTest {
        fakeApi.providers.add(Provider(
            id = ProviderId("p1"),
            name = "OpenAI",
            providerType = "openai",
        ))
        repository.refreshProviders(name = null, providerType = null)
        assertEquals(1, fakeApi.calls.size)
    }

    // ─── Iroh Purity Tests (letta-mobile client batch) ────────────────────────

    @Test(expected = IrohAdminApiUnavailableException::class)
    fun `refreshProviders in iroh mode without source throws IrohAdminApiUnavailableException`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val apiThatThrows = object : FakeProviderApi() {
            override suspend fun listProviders(before: String?, after: String?, limit: Int?, order: String?, name: String?, providerType: String?): List<Provider> {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden in iroh:// mode")
            }
        }
        val repo = ProviderRepository(apiThatThrows)
        repo.refreshProviders(name = null, providerType = null)
    }

    @Test
    fun `refreshProviders in iroh mode routes via admin_rpc`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val transport = FakeChannelTransport()
        val testProviders = listOf(
            Provider(
                id = ProviderId("p1"),
                name = "OpenAI",
                providerType = "openai",
            ),
            Provider(
                id = ProviderId("p2"),
                name = "Anthropic",
                providerType = "anthropic",
            ),
        )
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("provider.list", method)
            assertEquals("/v1/providers", path)
            val json = Json { ignoreUnknownKeys = true }
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(ListSerializer(Provider.serializer()), testProviders),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcProviderSource(transport, settings)
        val apiThatThrows = object : FakeProviderApi() {
            override suspend fun listProviders(before: String?, after: String?, limit: Int?, order: String?, name: String?, providerType: String?): List<Provider> {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = ProviderRepository(apiThatThrows, irohSource)
        repo.refreshProviders(name = null, providerType = null)
        assertEquals(2, repo.providers.value.size)
        assertEquals(1, transport.adminRpcCalls.size)
    }
}
