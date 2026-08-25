package com.letta.mobile.data.model.provider.composer

import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.provider.CanonicalModelRoute
import com.letta.mobile.data.model.provider.ModelAvailability
import com.letta.mobile.data.model.provider.OperationalStatus
import com.letta.mobile.data.model.provider.RedactedProviderInstance
import com.letta.mobile.data.model.provider.VisibilityPolicy
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/** Pure canonical catalog projection. All cache and selection state enters through [CatalogComposerInput]. */
object CanonicalCatalogComposer {

    fun compose(input: CatalogComposerInput): EffectiveCatalogProjection {
        validateInput(input)
        val context = compositionContext(input)
        val projections = input.modelRoutes.map { route -> projectRoute(route, context) }
        val sortedRoutes = projections.filterIsInstance<RouteProjection.Included>()
            .map(RouteProjection.Included::route)
            .sortedWith(routeComparator)
            .toPersistentList()
        val sortedExcluded = projections.filterIsInstance<RouteProjection.Excluded>()
            .map(RouteProjection.Excluded::route)
            .sortedWith(excludedComparator)
            .toPersistentList()
        validateSelectionIdentities(sortedRoutes)
        val candidate = EffectiveCatalogProjection(
            scope = CatalogScope(input.activeHostId, input.accountScopeId, input.sessionScopeId),
            routes = sortedRoutes,
            excludedRoutes = sortedExcluded,
            selection = resolveSelection(input.selectedIdentity, sortedRoutes),
        )
        return input.previousProjection?.takeIf(candidate::equals) ?: candidate
    }

    private fun compositionContext(input: CatalogComposerInput) = CompositionContext(
        input = input,
        routesById = input.modelRoutes.associateBy(CanonicalModelRoute::id),
        aliasRouteIds = input.aliasBindings.map(CatalogAliasBinding::aliasRouteId).toSet(),
        activeInstances = input.providerInstances
            .filter { it.hostId == input.activeHostId }
            .associateBy(RedactedProviderInstance::id),
    )

    private fun projectRoute(
        route: CanonicalModelRoute,
        context: CompositionContext,
    ): RouteProjection {
        if (route.id in context.aliasRouteIds) return RouteProjection.CollapsedAlias
        if (route.hostId != context.input.activeHostId) {
            return RouteProjection.Excluded(ExcludedModelRoute(route, ExclusionReason.HostMismatch))
        }
        val instance = context.activeInstances[route.providerInstanceId]
        val exclusion = evaluateExclusion(route, instance, context.input)
        if (exclusion != null) return RouteProjection.Excluded(ExcludedModelRoute(route, exclusion))
        return RouteProjection.Included(effectiveRoute(route, instance, context))
    }

    private fun effectiveRoute(
        route: CanonicalModelRoute,
        instance: RedactedProviderInstance?,
        context: CompositionContext,
    ): EffectiveModelRoute {
        val definition = instance?.let { context.input.providerDefinitions[it.definitionId] }
        return EffectiveModelRoute(
            id = route.id,
            hostId = route.hostId,
            providerInstanceId = route.providerInstanceId,
            providerDisplayName = instance?.displayName ?: route.providerInstanceId.value,
            providerDefinitionId = definition?.id,
            providerDefinitionDisplayName = definition?.displayName,
            supportedProtocols = definition?.supportedProtocols ?: persistentListOf(),
            providerOperationalStatus = instance?.operationalStatus,
            modelHandle = route.modelHandle,
            displayName = route.displayName,
            contextWindowLimit = route.contextWindowLimit,
            availability = route.availability,
            sourceVisibility = route.visibility,
            userVisibilityOverride = context.input.userVisibilityOverrides[route.id],
            aliases = buildAliases(route, context.input.aliasBindings, context.routesById),
            isAvailable = evaluateAvailability(route.availability, instance?.operationalStatus),
        )
    }

    private fun validateInput(input: CatalogComposerInput) {
        require(input.activeHostId.value.isNotBlank()) { "Catalog host identity must not be blank" }
        require(input.accountScopeId?.value?.isNotBlank() != false) { "Catalog account scope must not be blank" }
        require(input.sessionScopeId?.value?.isNotBlank() != false) { "Catalog session scope must not be blank" }
        require(input.modelRoutes.map(CanonicalModelRoute::id).distinct().size == input.modelRoutes.size) {
            "Catalog contains duplicate route identities"
        }
        require(input.modelRoutes.all { it.id.value.isNotBlank() && it.modelHandle.isNotBlank() }) {
            "Catalog route identities and handles must not be blank"
        }

        val activeProviderIds = input.providerInstances
            .filter { it.hostId == input.activeHostId }
            .map(RedactedProviderInstance::id)
        require(activeProviderIds.distinct().size == activeProviderIds.size) {
            "Catalog contains duplicate provider instance identities for the active host"
        }
        require(input.providerDefinitions.all { (key, definition) -> key == definition.id }) {
            "Provider definition keys must match their typed identities"
        }

        val routesById = input.modelRoutes.associateBy(CanonicalModelRoute::id)
        require(input.aliasBindings.map(CatalogAliasBinding::aliasRouteId).distinct().size == input.aliasBindings.size) {
            "A route cannot be bound to multiple canonical routes"
        }
        require(input.aliasBindings.all { binding ->
            val canonical = routesById[binding.canonicalRouteId]
            val alias = routesById[binding.aliasRouteId]
            canonical != null && alias != null &&
                canonical.id != alias.id && canonical.hostId == alias.hostId &&
                binding.legacyIdentity.isNotBlank()
        }) { "Catalog alias bindings must reference distinct routes on the same host" }
        val aliasIds = input.aliasBindings.map(CatalogAliasBinding::aliasRouteId).toSet()
        require(input.aliasBindings.none { it.canonicalRouteId in aliasIds }) {
            "Catalog alias bindings must be direct and acyclic"
        }
    }

    private fun buildAliases(
        canonical: CanonicalModelRoute,
        bindings: ImmutableList<CatalogAliasBinding>,
        routesById: Map<ModelRouteId, CanonicalModelRoute>,
    ) = buildList {
        addAll(canonical.aliases)
        bindings.filter { it.canonicalRouteId == canonical.id }.forEach { binding ->
            val aliasRoute = checkNotNull(routesById[binding.aliasRouteId])
            add(binding.legacyIdentity)
            add(aliasRoute.modelHandle)
            addAll(aliasRoute.aliases)
        }
    }.filter(String::isNotBlank).distinct().sorted().toPersistentList()

    private fun evaluateExclusion(
        route: CanonicalModelRoute,
        instance: RedactedProviderInstance?,
        input: CatalogComposerInput,
    ): ExclusionReason? {
        when (instance?.operationalStatus) {
            OperationalStatus.Disabled -> return ExclusionReason.ProviderDisabled
            OperationalStatus.Unavailable -> return ExclusionReason.ProviderUnavailable
            else -> Unit
        }
        if (route.availability == ModelAvailability.Disabled) return ExclusionReason.RouteDisabled

        return when (input.userVisibilityOverrides[route.id]) {
            VisibilityPolicy.Hidden -> ExclusionReason.HiddenByUser
            VisibilityPolicy.Visible -> null
            else -> when (route.visibility) {
                VisibilityPolicy.Hidden -> ExclusionReason.HiddenByPolicy
                VisibilityPolicy.Visible -> null
                VisibilityPolicy.Automatic, is VisibilityPolicy.Unknown ->
                    ExclusionReason.HiddenByPolicy.takeIf { input.defaultVisibility == VisibilityPolicy.Hidden }
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
            ModelAvailability.Available, ModelAvailability.Deprecated, is ModelAvailability.Unknown -> true
            ModelAvailability.Disabled, ModelAvailability.QuotaExceeded -> false
        }
    }

    private fun validateSelectionIdentities(routes: ImmutableList<EffectiveModelRoute>) {
        val owners = mutableMapOf<String, ModelRouteId>()
        routes.forEach { route ->
            (route.aliases + route.modelHandle + route.id.value).forEach { identity ->
                val previous = owners.put(identity, route.id)
                require(previous == null || previous == route.id) {
                    "Catalog contains an ambiguous model selection identity"
                }
            }
        }
    }

    private fun resolveSelection(
        selectedIdentity: String?,
        routes: ImmutableList<EffectiveModelRoute>,
    ): SelectionResolution {
        if (selectedIdentity == null) return SelectionResolution.None
        val route = routes.firstOrNull { candidate ->
            selectedIdentity == candidate.id.value || selectedIdentity == candidate.modelHandle ||
                selectedIdentity in candidate.aliases
        } ?: return SelectionResolution.Unresolved
        return SelectionResolution.Resolved(route.id, selectedIdentity)
    }

    private data class CompositionContext(
        val input: CatalogComposerInput,
        val routesById: Map<ModelRouteId, CanonicalModelRoute>,
        val aliasRouteIds: Set<ModelRouteId>,
        val activeInstances: Map<ProviderInstanceId, RedactedProviderInstance>,
    )

    private sealed interface RouteProjection {
        data class Included(val route: EffectiveModelRoute) : RouteProjection
        data class Excluded(val route: ExcludedModelRoute) : RouteProjection
        data object CollapsedAlias : RouteProjection
    }

    private val routeComparator = compareBy<EffectiveModelRoute>(
        { it.providerDisplayName.lowercase() },
        { it.displayName.lowercase() },
        { it.modelHandle.lowercase() },
        { it.id.value },
    )

    private val excludedComparator = compareBy<ExcludedModelRoute>(
        { it.route.hostId.value },
        { it.route.displayName.lowercase() },
        { it.route.modelHandle.lowercase() },
        { it.route.id.value },
    )
}
