package com.letta.mobile.data.model.provider.composer

import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ImmutableListSerializer
import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderDefinitionId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.provider.CanonicalModelRoute
import com.letta.mobile.data.model.provider.ModelAvailability
import com.letta.mobile.data.model.provider.OperationalStatus
import com.letta.mobile.data.model.provider.ProviderDefinition
import com.letta.mobile.data.model.provider.ProviderProtocol
import com.letta.mobile.data.model.provider.RedactedProviderInstance
import com.letta.mobile.data.model.provider.VisibilityPolicy
import kotlin.jvm.JvmInline
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class CatalogAccountScopeId(val value: String)

@JvmInline
@Serializable
value class CatalogSessionScopeId(val value: String)

/** Scope carried by projections so host/account/session caches cannot share an entry. */
@Serializable
data class CatalogScope(
    val hostId: HostId,
    val accountId: CatalogAccountScopeId? = null,
    val sessionId: CatalogSessionScopeId? = null,
)

@Serializable
sealed interface ExclusionReason {
    data object HostMismatch : ExclusionReason
    data object HiddenByUser : ExclusionReason
    data object HiddenByPolicy : ExclusionReason
    data object ProviderDisabled : ExclusionReason
    data object ProviderUnavailable : ExclusionReason
    data object RouteDisabled : ExclusionReason
}

/** Host-authoritative declaration that one route is a legacy identity of another route. */
data class CatalogAliasBinding(
    val canonicalRouteId: ModelRouteId,
    val aliasRouteId: ModelRouteId,
    val legacyIdentity: String,
)

@Serializable
data class EffectiveModelRoute(
    val id: ModelRouteId,
    val hostId: HostId,
    val providerInstanceId: ProviderInstanceId,
    val providerDisplayName: String,
    val providerDefinitionId: ProviderDefinitionId? = null,
    val providerDefinitionDisplayName: String? = null,
    @Serializable(with = ImmutableListSerializer::class)
    val supportedProtocols: ImmutableList<ProviderProtocol> = persistentListOf(),
    val providerOperationalStatus: OperationalStatus? = null,
    val modelHandle: String,
    val displayName: String,
    val contextWindowLimit: Int? = null,
    val availability: ModelAvailability = ModelAvailability.Available,
    val sourceVisibility: VisibilityPolicy = VisibilityPolicy.Automatic,
    val userVisibilityOverride: VisibilityPolicy? = null,
    val effectiveVisibility: VisibilityPolicy = VisibilityPolicy.Visible,
    @Serializable(with = ImmutableListSerializer::class)
    val aliases: ImmutableList<String> = persistentListOf(),
    val isAvailable: Boolean = true,
)

@Serializable
data class ExcludedModelRoute(
    val route: CanonicalModelRoute,
    val reason: ExclusionReason,
)

@Serializable
sealed interface SelectionResolution {
    data object None : SelectionResolution
    data object Unresolved : SelectionResolution

    data class Resolved(
        val canonicalRouteId: ModelRouteId,
        /** Preserves a matched saved legacy identity for transport and persistence. */
        val transportIdentity: String,
    ) : SelectionResolution
}

@Serializable
data class EffectiveCatalogProjection(
    val scope: CatalogScope,
    @Serializable(with = ImmutableListSerializer::class)
    val routes: ImmutableList<EffectiveModelRoute> = persistentListOf(),
    @Serializable(with = ImmutableListSerializer::class)
    val excludedRoutes: ImmutableList<ExcludedModelRoute> = persistentListOf(),
    val selection: SelectionResolution = SelectionResolution.None,
) {
    val activeHostId: HostId get() = scope.hostId
}

/** All state affecting composition is explicit; the composer reads no process-global state. */
data class CatalogComposerInput(
    val activeHostId: HostId,
    val modelRoutes: ImmutableList<CanonicalModelRoute>,
    val accountScopeId: CatalogAccountScopeId? = null,
    val sessionScopeId: CatalogSessionScopeId? = null,
    val providerInstances: ImmutableList<RedactedProviderInstance> = persistentListOf(),
    val providerDefinitions: ImmutableMap<ProviderDefinitionId, ProviderDefinition> = persistentMapOf(),
    val aliasBindings: ImmutableList<CatalogAliasBinding> = persistentListOf(),
    val selectedIdentity: String? = null,
    val userVisibilityOverrides: ImmutableMap<ModelRouteId, VisibilityPolicy> = persistentMapOf(),
    val defaultVisibility: VisibilityPolicy = VisibilityPolicy.Automatic,
    /** Explicit prior value enables referential reuse without an ambient cache. */
    val previousProjection: EffectiveCatalogProjection? = null,
)
