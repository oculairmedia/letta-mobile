package com.letta.mobile.data.session

import com.letta.mobile.data.repository.api.IProviderRepository
import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderId
import com.letta.mobile.testutil.FakeProviderApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionScopedAdminRepositoriesTest {

    @Test
    fun `provider repository proxy switches caches to rebuilt graph`() = runTest {
        val providerApi = FakeProviderApi().apply { providers = mutableListOf(sampleProvider(ProviderId("provider-a"), "Backend A Provider")) }

        val scenarios = listOf(
            AdminProxySwitchSpec(providerApi,
                setup = { this.providerApi = providerApi },
                createProxy = { sm, _ -> sm.sessionScoped(IProviderRepository::class) { it.providerRepository } },
                refresh = { it.refreshProviders() },
                observeIds = { it.providers.value.map { p -> p.id } },
                mutate = { it.providers = mutableListOf(sampleProvider(ProviderId("provider-b"), "Backend B Provider")) },
                before = listOf(ProviderId("provider-a")),
                after = listOf(ProviderId("provider-b")),
            ),
        )
        for (spec in scenarios) {
            assertProxySwitchesCaches(testScheduler, spec.toScenario())
        }
    }

    private fun sampleProvider(id: ProviderId, name: String) = Provider(
        id = id,
        name = name,
        providerType = "openai",
    )
}
