package com.letta.mobile.data.model.provider.composer

import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.provider.CanonicalModelRoute
import com.letta.mobile.data.model.provider.ModelAvailability
import com.letta.mobile.data.model.provider.VisibilityPolicy
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogComposerPrecedenceTest {

    private val hostId = HostId("host-1")

    @Test
    fun userOverrideHiddenOverridesRouteVisible() {
        val route = CanonicalModelRoute(
            id = ModelRouteId("r1"),
            hostId = hostId,
            providerInstanceId = ProviderInstanceId("inst-1"),
            modelHandle = "model-1",
            displayName = "Model 1",
            visibility = VisibilityPolicy.Visible,
        )

        val input = CatalogComposerInput(
            activeHostId = hostId,
            modelRoutes = persistentListOf(route),
            userVisibilityOverrides = persistentMapOf(route.id to VisibilityPolicy.Hidden),
        )

        val result = CanonicalCatalogComposer.compose(input)
        assertEquals(0, result.routes.size)
        assertEquals(1, result.excludedRoutes.size)
        assertEquals(ExclusionReason.HiddenByUser, result.excludedRoutes.first().reason)
    }

    @Test
    fun userOverrideVisibleOverridesRouteHidden() {
        val route = CanonicalModelRoute(
            id = ModelRouteId("r1"),
            hostId = hostId,
            providerInstanceId = ProviderInstanceId("inst-1"),
            modelHandle = "model-1",
            displayName = "Model 1",
            visibility = VisibilityPolicy.Hidden,
        )

        val input = CatalogComposerInput(
            activeHostId = hostId,
            modelRoutes = persistentListOf(route),
            userVisibilityOverrides = persistentMapOf(route.id to VisibilityPolicy.Visible),
        )

        val result = CanonicalCatalogComposer.compose(input)
        assertEquals(1, result.routes.size)
        assertEquals(0, result.excludedRoutes.size)
    }

    @Test
    fun defaultHiddenExcludesAutomaticVisibilityRoutes() {
        val route = CanonicalModelRoute(
            id = ModelRouteId("r1"),
            hostId = hostId,
            providerInstanceId = ProviderInstanceId("inst-1"),
            modelHandle = "model-1",
            displayName = "Model 1",
            visibility = VisibilityPolicy.Automatic,
        )

        val input = CatalogComposerInput(
            activeHostId = hostId,
            modelRoutes = persistentListOf(route),
            defaultVisibility = VisibilityPolicy.Hidden,
        )

        val result = CanonicalCatalogComposer.compose(input)
        assertEquals(0, result.routes.size)
        assertEquals(1, result.excludedRoutes.size)
        assertEquals(ExclusionReason.HiddenByPolicy, result.excludedRoutes.first().reason)
    }
}
