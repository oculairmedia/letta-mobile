package com.letta.mobile.data.model.provider.composer

import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ImmutableListSerializer
import com.letta.mobile.data.model.ImmutableMapSerializer
import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderDefinitionId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.provider.CanonicalModelRoute
import com.letta.mobile.data.model.provider.ModelAvailability
import com.letta.mobile.data.model.provider.ProviderDefinition
import com.letta.mobile.data.model.provider.RedactedProviderInstance
import com.letta.mobile.data.model.provider.VisibilityPolicy
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.Serializable

/**
 * Reason why a model route was excluded from active presentation.
 */
@Serializable
sealed interface ExclusionReason {
    data object HostMismatch : ExclusionReason
    data object HiddenByUser : ExclusionReason
    data object HiddenByPolicy : ExclusionReason
    data object ProviderDisabled : ExclusionReason
    data object RouteDisabled : ExclusionReason
    data class Custom(val reason: String) : ExclusionReason
}

/**
 * An effective model route resolved by the catalog composer for active UI/agent selection.
 */
@Serializable
data class EffectiveModelRoute(
    val id: ModelRouteId,
    val hostId: HostId,
    val providerInstanceId: ProviderInstanceId,
    val providerDisplayName: String,
    val modelHandle: String,
    val displayName: String,
    val contextWindowLimit: Int? = null,
    val availability: ModelAvailability = ModelAvailability.Available,
    val effectiveVisibility: VisibilityPolicy = VisibilityPolicy.Visible,
    @Serializable(with = ImmutableListSerializer::class)
    val aliases: ImmutableList<String> = persistentListOf(),
    val isAvailable: Boolean = true,
)

/**
 * Record of a route that was excluded during catalog composition.
 */
@Serializable
data class ExcludedModelRoute(
    val route: CanonicalModelRoute,
    val reason: ExclusionReason,
)

/**
 * Immutable output of the canonical catalog composer.
 */
@Serializable
data class EffectiveCatalogProjection(
    val activeHostId: HostId,
    @Serializable(with = ImmutableListSerializer::class)
    val routes: ImmutableList<EffectiveModelRoute> = persistentListOf(),
    @Serializable(with = ImmutableListSerializer::class)
    val excludedRoutes: ImmutableList<ExcludedModelRoute> = persistentListOf(),
)

/**
 * Parameter object encapsulating all inputs to the pure catalog composer.
 */
data class CatalogComposerInput(
    val activeHostId: HostId,
    val modelRoutes: ImmutableList<CanonicalModelRoute>,
    val providerInstances: ImmutableList<RedactedProviderInstance> = persistentListOf(),
    val providerDefinitions: ImmutableMap<ProviderDefinitionId, ProviderDefinition> = persistentMapOf(),
    val userVisibilityOverrides: ImmutableMap<ModelRouteId, VisibilityPolicy> = persistentMapOf(),
    val defaultVisibility: VisibilityPolicy = VisibilityPolicy.Automatic,
)
