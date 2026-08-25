package com.letta.mobile.data.model.provider.composer

import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderDefinitionId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.provider.CanonicalModelRoute
import com.letta.mobile.data.model.provider.RedactedProviderInstance
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogComposerFailOnRevertTest {
    private val host = HostId("host")

    @Test
    fun productionShapedMixedCatalogSurvivesHistoricalProviderFilter() {
        val fixture = mixedCatalogFixture()
        val canonical = fixture.single { it.route.modelHandle == "openai/MiniMax-M3" }.route
        val legacy = fixture.single { it.route.modelHandle == "lmstudio/MiniMax-M3" }.route
        val configuredOpenAi = RedactedProviderInstance(
            ProviderInstanceId("openai"),
            host,
            ProviderDefinitionId("openai"),
            "OpenAI",
        )
        val projection = CanonicalCatalogComposer.compose(
            CatalogComposerInput(
                activeHostId = host,
                modelRoutes = fixture.map(FixtureRoute::route).toPersistentList(),
                providerInstances = persistentListOf(configuredOpenAi),
                aliasBindings = persistentListOf(CatalogAliasBinding(canonical.id, legacy.id, legacy.modelHandle)),
            ),
        )

        assertEquals(11, projection.routes.size)
        assertEquals(setOf(legacy.modelHandle), projection.routes.single { it.id == canonical.id }.aliases.toSet())

        val providerTypeById = fixture.associate { it.route.id to it.providerType }
        val historicalCredentialedTypes = setOf("openai")
        val oldFiltered = projection.routes.filter { route ->
            providerTypeById[route.id] in historicalCredentialedTypes ||
                providerPrefix(route.modelHandle) in historicalCredentialedTypes ||
                route.aliases.any { providerPrefix(it) in historicalCredentialedTypes }
        }
        assertEquals(
            setOf("openai/gpt-4o", "custom-openai/my-model", "openai/MiniMax-M3"),
            oldFiltered.map(EffectiveModelRoute::modelHandle).toSet(),
        )
        assertTrue("anthropic/claude-3-5-sonnet" in projection.routes.map(EffectiveModelRoute::modelHandle))
        assertTrue("google/gemini-1.5-pro" in projection.routes.map(EffectiveModelRoute::modelHandle))
    }

    private fun mixedCatalogFixture(): List<FixtureRoute> = listOf(
        fixture("openai/gpt-4o", "openai"),
        fixture("anthropic/claude-3-5-sonnet", "anthropic"),
        fixture("google/gemini-1.5-pro", "google"),
        fixture("xai/grok-2", "xai"),
        fixture("zai/glm-4", "zai"),
        fixture("minimax/minimax-01", "minimax"),
        fixture("moonshot/moonshot-v1-8k", "moonshot"),
        fixture("bedrock/anthropic.claude-3-sonnet", "bedrock"),
        fixture("lmstudio/llama-3.1-8b", "lmstudio"),
        fixture("custom-openai/my-model", "openai"),
        fixture("openai/MiniMax-M3", "openai"),
        fixture("lmstudio/MiniMax-M3", "lmstudio"),
    )

    private fun fixture(handle: String, providerType: String): FixtureRoute {
        val prefix = providerPrefix(handle)
        return FixtureRoute(
            CanonicalModelRoute(
                id = ModelRouteId(handle),
                hostId = host,
                providerInstanceId = ProviderInstanceId(prefix),
                modelHandle = handle,
                displayName = handle.substringAfter('/'),
            ),
            providerType,
        )
    }

    private fun providerPrefix(identity: String): String = identity.substringBefore('/').trim().lowercase()

    private data class FixtureRoute(val route: CanonicalModelRoute, val providerType: String)
}
