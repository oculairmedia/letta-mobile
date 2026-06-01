package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderCheckParams
import com.letta.mobile.data.model.ProviderCreateParams
import com.letta.mobile.data.model.ProviderId
import com.letta.mobile.data.model.ProviderUpdateParams
import com.letta.mobile.testutil.FakeProviderApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `refreshProviders updates state flow`() = runTest {
        fakeApi.providers.add(Provider(id = ProviderId("provider-1"), name = "OpenAI", providerType = "openai"))

        repository.refreshProviders()

        assertEquals(1, repository.providers.first().size)
    }

    @Test
    fun `createProvider upserts cache`() = runTest {
        val provider = repository.createProvider(ProviderCreateParams(name = "OpenAI", providerType = "openai", apiKey = "secret"))

        assertEquals("OpenAI", provider.name)
        assertEquals(1, repository.providers.first().size)
    }

    @Test
    fun `updateProvider updates cache`() = runTest {
        fakeApi.providers.add(Provider(id = ProviderId("provider-1"), name = "OpenAI", providerType = "openai", apiKey = "old"))
        repository.refreshProviders()

        repository.updateProvider(ProviderId("provider-1"), ProviderUpdateParams(apiKey = "new"))

        assertEquals("new", repository.providers.first().first().apiKey)
    }

    @Test
    fun `checkProvider delegates to api`() = runTest {
        repository.checkProvider(ProviderCheckParams(providerType = "openai", apiKey = "secret"))

        assertTrue(fakeApi.calls.contains("checkProvider:openai"))
    }
}
