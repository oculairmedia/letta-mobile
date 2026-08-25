package com.letta.mobile.data.model.provider.composer

import com.letta.mobile.data.model.provider.CanonicalModelRoute
import com.letta.mobile.data.model.provider.ModelAvailability
import com.letta.mobile.data.model.provider.OperationalStatus
import com.letta.mobile.data.model.provider.RedactedProviderInstance
import com.letta.mobile.data.model.provider.VisibilityPolicy
import kotlinx.collections.immutable.toPersistentList

/**
 * Pure, deterministic catalog composer that joins canonical model routes with provider instances,
 * enforces epic visibility/availability precedence, and guarantees fail-open availability.
 *
 * This function performs zero I/O and has zero UI dependencies.
 */
object CanonicalCatalogComposer {

    fun compose(input: CatalogComposerInput): EffectiveCatalogProjection {
        val activeInstances = input.providerInstances
            .filter { it.hostId == input.activeHostId }
            .associateBy { it.id }

        val effective = mutableListOf<EffectiveModelRoute>()
        val excluded = mutableListOf<ExcludedModelRoute>()

        for (route in input.modelRoutes) {
            if (route.hostId != input.activeHostId) {
                excluded.add(ExcludedModelRoute(route, ExclusionReason.HostMismatch))
                continue
            }

            val instance = activeInstances[route.providerInstanceId]
            val exclusionReason = evaluateExclusion(route, instance, input)
            if (exclusionReason != null) {
                excluded.add(ExcludedModelRoute(route, exclusionReason))
                continue
            }

            val providerDisplayName = instance?.displayName ?: route.providerInstanceId.value
            val isAvailable = evaluateAvailability(route.availability, instance?.operationalStatus)

            effective.add(
                EffectiveModelRoute(
                    id = route.id,
                    hostId = route.hostId,
                    providerInstanceId = route.providerInstanceId,
                    providerDisplayName = providerDisplayName,
                    modelHandle = route.modelHandle,
                    displayName = route.displayName,
                    contextWindowLimit = route.contextWindowLimit,
                    availability = route.availability,
                    effectiveVisibility = VisibilityPolicy.Visible,
                    aliases = route.aliases,
                    isAvailable = isAvailable,
                ),
            )
        }

        return EffectiveCatalogProjection(
            activeHostId = input.activeHostId,
            routes = effective.sortedWith(routeComparator).toPersistentList(),
            excludedRoutes = excluded.sortedWith(excludedComparator).toPersistentList(),
        )
    }

    private fun evaluateExclusion(
        route: CanonicalModelRoute,
        instance: RedactedProviderInstance?,
        input: CatalogComposerInput,
    ): ExclusionReason? {
        if (instance != null && instance.operationalStatus == OperationalStatus.Disabled) {
            return ExclusionReason.ProviderDisabled
        }
        if (route.availability == ModelAvailability.Disabled) {
            return ExclusionReason.RouteDisabled
        }

        val userOverride = input.userVisibilityOverrides[route.id]
        if (userOverride == VisibilityPolicy.Hidden) {
            return ExclusionReason.HiddenByUser
        }
        if (userOverride == VisibilityPolicy.Visible) {
            return null // Explicit user visible overrides route/default hidden policy
        }

        // Inherit policy or default
        return when (route.visibility) {
            VisibilityPolicy.Hidden -> ExclusionReason.HiddenByPolicy
            VisibilityPolicy.Visible -> null
            VisibilityPolicy.Automatic, is VisibilityPolicy.Unknown -> {
                if (input.defaultVisibility == VisibilityPolicy.Hidden) {
                    ExclusionReason.HiddenByPolicy
                } else {
                    null
                }
            }
        }
    }

    private fun evaluateAvailability(
        routeAvailability: ModelAvailability,
        operationalStatus: OperationalStatus?,
    ): Boolean {
        if (operationalStatus == OperationalStatus.Unavailable || operationalStatus == OperationalStatus.Disabled) {
            return false
        }
        return when (routeAvailability) {
            ModelAvailability.Available -> true
            ModelAvailability.Deprecated -> true
            ModelAvailability.Disabled -> false
            ModelAvailability.QuotaExceeded -> false
            is ModelAvailability.Unknown -> true // Fail-open: unknown availability remains available
        }
    }

    private val routeComparator = compareBy<EffectiveModelRoute>(
        { it.providerDisplayName.lowercase() },
        { it.displayName.lowercase() },
        { it.modelHandle.lowercase() },
        { it.id.value },
    )

    private val excludedComparator = compareBy<ExcludedModelRoute>(
        { it.route.displayName.lowercase() },
        { it.route.modelHandle.lowercase() },
        { it.route.id.value },
    )
}
