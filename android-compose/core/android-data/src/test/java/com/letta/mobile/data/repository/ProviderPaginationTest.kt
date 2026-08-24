package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ProviderApi
import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderId
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Phase 2.2 (data-efficiency-audit Q3): focused pagination test for
 * [ProviderRepository.refreshProviders]. The previous implementation used
 * `limit = 1000`; the new implementation routes through [exhaustCursorPages].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ProviderPaginationTest {

    @Test
    fun `refreshProviders fetches both pages when API returns exactly two`() = runTest {
        val providers = (1..75).map {
            Provider(id = ProviderId("provider-$it"), name = "Provider $it", providerType = "openai")
        }
        val api = PaginatingProviderApi(providers)
        val repo = ProviderRepository(api)

        repo.refreshProviders(name = null, providerType = null)

        assertEquals(75, repo.providers.value.size)
        assertEquals(listOf(null, "provider-50"), api.observedAfters)
        assertEquals(listOf(50, 50), api.observedLimits)
    }

    @Test
    fun `refreshProviders fails when API returns multiple providers without id`() = runTest {
        val providers = listOf(
            Provider(id = null, name = "Provider A", providerType = "openai"),
            Provider(id = null, name = "Provider B", providerType = "openai"),
        )
        val api = PaginatingProviderApi(providers)
        val repo = ProviderRepository(api)

        val error = runCatching {
            repo.refreshProviders(name = null, providerType = null)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(1, api.observedAfters.size)
    }

    @Test
    fun `refreshProviders fails when full page ends with null id`() = runTest {
        val providers = (1..49).map {
            Provider(id = ProviderId("provider-$it"), name = "Provider $it", providerType = "openai")
        } + Provider(id = null, name = "Tail null", providerType = "openai")
        val api = PaginatingProviderApi(providers)
        val repo = ProviderRepository(api)

        val error = runCatching {
            repo.refreshProviders(name = null, providerType = null)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(1, api.observedAfters.size)
    }

    private class PaginatingProviderApi(
        private val providers: List<Provider>,
    ) : ProviderApi(mockk(relaxed = true)) {
        val observedAfters = mutableListOf<String?>()
        val observedLimits = mutableListOf<Int?>()

        override suspend fun listProviders(
            before: String?,
            after: String?,
            limit: Int?,
            order: String?,
            name: String?,
            providerType: String?,
        ): List<Provider> {
            observedAfters += after
            observedLimits += limit
            val pageSize = limit ?: 50
            val start = after?.let { id ->
                providers.indexOfFirst { it.id?.value == id }.let { if (it < 0) providers.size else it + 1 }
            } ?: 0
            val end = (start + pageSize).coerceAtMost(providers.size)
            return providers.subList(start, end)
        }
    }
}