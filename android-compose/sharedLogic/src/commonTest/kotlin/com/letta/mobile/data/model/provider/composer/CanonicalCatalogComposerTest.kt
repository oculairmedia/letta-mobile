package com.letta.mobile.data.model.provider.composer

import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderDefinitionId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.provider.CanonicalModelRoute
import com.letta.mobile.data.model.provider.CredentialStatus
import com.letta.mobile.data.model.provider.ModelAvailability
import com.letta.mobile.data.model.provider.OperationalStatus
import com.letta.mobile.data.model.provider.ProviderDefinition
import com.letta.mobile.data.model.provider.RedactedProviderInstance
import com.letta.mobile.data.model.provider.VisibilityPolicy
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CanonicalCatalogComposerTest {

    private val hostPrimary = HostId("host-primary")
    private val hostSecondary = HostId("host-secondary")

    @Test
    fun twoInstancesSharingProtocolRemainDistinct() {
        val inst1 = RedactedProviderInstance(
            id = ProviderInstanceId("openai-official"),
            hostId = hostPrimary,
            definitionId = ProviderDefinitionId("openai"),
            displayName = "OpenAI Official",
            credentialStatus = CredentialStatus.Configured,
        )
        val inst2 = RedactedProviderInstance(
            id = ProviderInstanceId("openai-azure"),
            hostId = hostPrimary,
            definitionId = ProviderDefinitionId("openai"),
            displayName = "Azure OpenAI",
            credentialStatus = CredentialStatus.Configured,
        )

        val route1 = CanonicalModelRoute(
            id = ModelRouteId("route-gpt4-official"),
            hostId = hostPrimary,
            providerInstanceId = inst1.id,
            modelHandle = "gpt-4o",
            displayName = "GPT-4o (Official)",
        )
        val route2 = CanonicalModelRoute(
            id = ModelRouteId("route-gpt4-azure"),
            hostId = hostPrimary,
            providerInstanceId = inst2.id,
            modelHandle = "gpt-4o",
            displayName = "GPT-4o (Azure)",
        )

        val input = CatalogComposerInput(
            activeHostId = hostPrimary,
            modelRoutes = persistentListOf(route1, route2),
            providerInstances = persistentListOf(inst1, inst2),
        )

        val result = CanonicalCatalogComposer.compose(input)
        assertEquals(2, result.routes.size)
        assertEquals("Azure OpenAI", result.routes[0].providerDisplayName)
        assertEquals("OpenAI Official", result.routes[1].providerDisplayName)
        assertTrue(result.excludedRoutes.isEmpty())
    }

    @Test
    fun mixedProviderFixtureSurvivesMissingProviderRecords() {
        // Models exist from 5 different providers, but ONLY 1 provider record exists on host
        val onlyProvider = RedactedProviderInstance(
            id = ProviderInstanceId("openai-inst"),
            hostId = hostPrimary,
            definitionId = ProviderDefinitionId("openai"),
            displayName = "OpenAI",
        )

        val routes = persistentListOf(
            CanonicalModelRoute(ModelRouteId("r1"), hostPrimary, ProviderInstanceId("openai-inst"), "gpt-4o", "GPT-4o"),
            CanonicalModelRoute(ModelRouteId("r2"), hostPrimary, ProviderInstanceId("anthropic-inst"), "claude-3-5-sonnet", "Claude 3.5 Sonnet"),
            CanonicalModelRoute(ModelRouteId("r3"), hostPrimary, ProviderInstanceId("google-inst"), "gemini-2.0-flash", "Gemini 2.0 Flash"),
            CanonicalModelRoute(ModelRouteId("r4"), hostPrimary, ProviderInstanceId("groq-inst"), "llama-3.3-70b", "Llama 3.3 70B"),
            CanonicalModelRoute(ModelRouteId("r5"), hostPrimary, ProviderInstanceId("mistral-inst"), "mistral-large", "Mistral Large"),
        )

        val input = CatalogComposerInput(
            activeHostId = hostPrimary,
            modelRoutes = routes,
            providerInstances = persistentListOf(onlyProvider),
        )

        val result = CanonicalCatalogComposer.compose(input)
        // All 5 routes MUST be preserved, none dropped due to missing provider instances
        assertEquals(5, result.routes.size)
        assertTrue(result.excludedRoutes.isEmpty())
    }

    @Test
    fun activeHostIsolationExcludesMismatchedHosts() {
        val routePrimary = CanonicalModelRoute(ModelRouteId("r-p"), hostPrimary, ProviderInstanceId("p-inst"), "model-1", "Model 1")
        val routeSecondary = CanonicalModelRoute(ModelRouteId("r-s"), hostSecondary, ProviderInstanceId("s-inst"), "model-2", "Model 2")

        val input = CatalogComposerInput(
            activeHostId = hostPrimary,
            modelRoutes = persistentListOf(routePrimary, routeSecondary),
        )

        val result = CanonicalCatalogComposer.compose(input)
        assertEquals(1, result.routes.size)
        assertEquals(ModelRouteId("r-p"), result.routes[0].id)

        assertEquals(1, result.excludedRoutes.size)
        assertEquals(ModelRouteId("r-s"), result.excludedRoutes[0].route.id)
        assertEquals(ExclusionReason.HostMismatch, result.excludedRoutes[0].reason)
    }

    @Test
    fun unknownAvailabilityFailsOpenAsAvailable() {
        val route = CanonicalModelRoute(
            id = ModelRouteId("r-unknown"),
            hostId = hostPrimary,
            providerInstanceId = ProviderInstanceId("inst-1"),
            modelHandle = "future-model",
            displayName = "Future Model",
            availability = ModelAvailability.Unknown("future_quota_provisional"),
        )

        val input = CatalogComposerInput(
            activeHostId = hostPrimary,
            modelRoutes = persistentListOf(route),
        )

        val result = CanonicalCatalogComposer.compose(input)
        assertEquals(1, result.routes.size)
        assertTrue(result.routes[0].isAvailable)
    }

    @Test
    fun explicitVisibleCannotResurrectDisabledProviderOrRoute() {
        val disabledInst = RedactedProviderInstance(
            id = ProviderInstanceId("disabled-inst"),
            hostId = hostPrimary,
            definitionId = ProviderDefinitionId("openai"),
            displayName = "Disabled OpenAI",
            operationalStatus = OperationalStatus.Disabled,
        )
        val routeOnDisabledInst = CanonicalModelRoute(
            id = ModelRouteId("r-disabled-inst"),
            hostId = hostPrimary,
            providerInstanceId = disabledInst.id,
            modelHandle = "gpt-4",
            displayName = "GPT-4",
        )
        val disabledRoute = CanonicalModelRoute(
            id = ModelRouteId("r-disabled-route"),
            hostId = hostPrimary,
            providerInstanceId = ProviderInstanceId("active-inst"),
            modelHandle = "gpt-3.5",
            displayName = "GPT-3.5",
            availability = ModelAvailability.Disabled,
        )

        val input = CatalogComposerInput(
            activeHostId = hostPrimary,
            modelRoutes = persistentListOf(routeOnDisabledInst, disabledRoute),
            providerInstances = persistentListOf(disabledInst),
            userVisibilityOverrides = persistentMapOf(
                ModelRouteId("r-disabled-inst") to VisibilityPolicy.Visible,
                ModelRouteId("r-disabled-route") to VisibilityPolicy.Visible,
            ),
        )

        val result = CanonicalCatalogComposer.compose(input)
        assertEquals(0, result.routes.size)
        assertEquals(2, result.excludedRoutes.size)

        val reasons = result.excludedRoutes.associate { it.route.id to it.reason }
        assertEquals(ExclusionReason.ProviderDisabled, reasons[ModelRouteId("r-disabled-inst")])
        assertEquals(ExclusionReason.RouteDisabled, reasons[ModelRouteId("r-disabled-route")])
    }

    @Test
    fun deterministicOutputUnderShuffledInputOrder() {
        val routes = (1..20).map { i ->
            CanonicalModelRoute(
                id = ModelRouteId("route-$i"),
                hostId = hostPrimary,
                providerInstanceId = ProviderInstanceId("provider-${i % 3}"),
                modelHandle = "handle-$i",
                displayName = "Model $i",
            )
        }

        val inputNormal = CatalogComposerInput(
            activeHostId = hostPrimary,
            modelRoutes = routes.toPersistentList(),
        )

        val inputShuffled = CatalogComposerInput(
            activeHostId = hostPrimary,
            modelRoutes = routes.shuffled().toPersistentList(),
        )

        val resultNormal = CanonicalCatalogComposer.compose(inputNormal)
        val resultShuffled = CanonicalCatalogComposer.compose(inputShuffled)

        assertEquals(resultNormal.routes.map { it.id }, resultShuffled.routes.map { it.id })
    }
}
