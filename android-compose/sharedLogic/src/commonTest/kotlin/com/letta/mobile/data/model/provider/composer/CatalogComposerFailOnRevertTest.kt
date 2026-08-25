package com.letta.mobile.data.model.provider.composer

import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderDefinitionId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.provider.CanonicalModelRoute
import com.letta.mobile.data.model.provider.RedactedProviderInstance
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogComposerFailOnRevertTest {

    private val hostId = HostId("host-1")

    @Test
    fun composerNeverFiltersByMissingProviderInstanceRecords() {
        val singleProvider = RedactedProviderInstance(
            id = ProviderInstanceId("openai-inst"),
            hostId = hostId,
            definitionId = ProviderDefinitionId("openai"),
            displayName = "OpenAI",
        )

        val mixedRoutes = persistentListOf(
            CanonicalModelRoute(ModelRouteId("r1"), hostId, ProviderInstanceId("openai-inst"), "gpt-4o", "GPT-4o"),
            CanonicalModelRoute(ModelRouteId("r2"), hostId, ProviderInstanceId("anthropic-inst"), "claude-3-5-sonnet", "Claude 3.5 Sonnet"),
            CanonicalModelRoute(ModelRouteId("r3"), hostId, ProviderInstanceId("google-inst"), "gemini-2.0-flash", "Gemini 2.0 Flash"),
        )

        val input = CatalogComposerInput(
            activeHostId = hostId,
            modelRoutes = mixedRoutes,
            providerInstances = persistentListOf(singleProvider),
        )

        val result = CanonicalCatalogComposer.compose(input)

        // Control assertion: All 3 routes preserved
        assertEquals(3, result.routes.size)

        // Negative regression control: Proves that naive provider filtering would drop 2 routes
        val naiveFiltered = mixedRoutes.filter { r -> r.providerInstanceId == singleProvider.id }
        assertEquals(1, naiveFiltered.size)
        assertTrue(result.routes.size > naiveFiltered.size)
    }
}
