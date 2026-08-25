package com.letta.mobile.data.model.provider.composer

import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderDefinitionId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.provider.CanonicalModelRoute
import com.letta.mobile.data.model.provider.ModelAvailability
import com.letta.mobile.data.model.provider.OperationalStatus
import com.letta.mobile.data.model.provider.RedactedProviderInstance
import com.letta.mobile.data.model.provider.VisibilityPolicy
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogComposerPrecedenceTest {
    private val host = HostId("host")
    private val providerId = ProviderInstanceId("provider")

    @Test
    fun hostStatusOutranksExplicitUserVisibility() {
        listOf(
            OperationalStatus.Disabled to ExclusionReason.ProviderDisabled,
            OperationalStatus.Unavailable to ExclusionReason.ProviderUnavailable,
        ).forEach { (status, expectedReason) ->
            val route = route(availability = ModelAvailability.Available, visibility = VisibilityPolicy.Hidden)
            val result = compose(
                route = route,
                status = status,
                override = VisibilityPolicy.Visible,
            )
            assertEquals(expectedReason, result.excludedRoutes.single().reason)
            assertTrue(result.routes.isEmpty())
        }
    }

    @Test
    fun routeAvailabilityStatesHaveExplicitBehavior() {
        data class Case(val availability: ModelAvailability, val included: Boolean, val available: Boolean)
        listOf(
            Case(ModelAvailability.Available, included = true, available = true),
            Case(ModelAvailability.Deprecated, included = true, available = true),
            Case(ModelAvailability.QuotaExceeded, included = true, available = false),
            Case(ModelAvailability.Disabled, included = false, available = false),
            Case(ModelAvailability.Unknown("future-state"), included = true, available = true),
        ).forEach { case ->
            val result = compose(route(case.availability), OperationalStatus.Active, VisibilityPolicy.Visible)
            assertEquals(case.included, result.routes.isNotEmpty())
            if (case.included) assertEquals(case.available, result.routes.single().isAvailable)
            if (case.availability == ModelAvailability.Disabled) {
                assertEquals(ExclusionReason.RouteDisabled, result.excludedRoutes.single().reason)
            }
        }
    }

    @Test
    fun missingProviderRecordNeverInfersUnavailability() {
        val result = CanonicalCatalogComposer.compose(
            CatalogComposerInput(host, persistentListOf(route(ModelAvailability.Available))),
        )
        assertTrue(result.routes.single().isAvailable)
        assertEquals(null, result.routes.single().providerOperationalStatus)
    }

    @Test
    fun userAndDefaultVisibilityPrecedencePreservesUnknownSource() {
        val unknown = VisibilityPolicy.Unknown("future-policy")
        val route = route(ModelAvailability.Available, unknown)
        val hiddenByDefault = CanonicalCatalogComposer.compose(
            CatalogComposerInput(host, persistentListOf(route), defaultVisibility = VisibilityPolicy.Hidden),
        )
        assertEquals(ExclusionReason.HiddenByPolicy, hiddenByDefault.excludedRoutes.single().reason)

        val madeVisible = compose(route, OperationalStatus.Active, VisibilityPolicy.Visible)
        assertEquals(unknown, madeVisible.routes.single().sourceVisibility)
        assertEquals(VisibilityPolicy.Visible, madeVisible.routes.single().userVisibilityOverride)
        assertEquals(VisibilityPolicy.Visible, madeVisible.routes.single().effectiveVisibility)
    }

    @Test
    fun userHiddenOutranksRouteVisibleAndUserVisibleOutranksRouteHidden() {
        val visible = route(ModelAvailability.Available, VisibilityPolicy.Visible)
        val hidden = compose(visible, OperationalStatus.Active, VisibilityPolicy.Hidden)
        assertEquals(ExclusionReason.HiddenByUser, hidden.excludedRoutes.single().reason)

        val routeHidden = route(ModelAvailability.Available, VisibilityPolicy.Hidden)
        val visibleResult = compose(routeHidden, OperationalStatus.Active, VisibilityPolicy.Visible)
        assertEquals(1, visibleResult.routes.size)
    }

    @Test
    fun degradedAndUnknownProviderStatusesRemainExplicitAndAvailable() {
        listOf(OperationalStatus.Degraded, OperationalStatus.Unknown("future-health")).forEach { status ->
            val result = compose(route(ModelAvailability.Available), status, null)
            assertEquals(status, result.routes.single().providerOperationalStatus)
            assertTrue(result.routes.single().isAvailable)
        }
    }

    private fun compose(
        route: CanonicalModelRoute,
        status: OperationalStatus,
        override: VisibilityPolicy?,
    ): EffectiveCatalogProjection {
        val provider = RedactedProviderInstance(
            providerId,
            host,
            ProviderDefinitionId("definition"),
            "Provider",
            operationalStatus = status,
        )
        return CanonicalCatalogComposer.compose(
            CatalogComposerInput(
                activeHostId = host,
                modelRoutes = persistentListOf(route),
                providerInstances = persistentListOf(provider),
                userVisibilityOverrides = if (override == null) persistentMapOf() else persistentMapOf(route.id to override),
            ),
        )
    }

    private fun route(
        availability: ModelAvailability,
        visibility: VisibilityPolicy = VisibilityPolicy.Automatic,
    ) = CanonicalModelRoute(
        id = ModelRouteId("route"),
        hostId = host,
        providerInstanceId = providerId,
        modelHandle = "model",
        displayName = "Model",
        availability = availability,
        visibility = visibility,
    )
}
