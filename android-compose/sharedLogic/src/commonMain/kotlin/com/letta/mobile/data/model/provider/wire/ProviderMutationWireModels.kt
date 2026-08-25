package com.letta.mobile.data.model.provider.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Command to create a new provider instance on a host.
 *
 * [initialApiKey] is write-only input for initial credential provisioning.
 * [toString] masks confidential values to prevent accidental log leakage.
 */
@Serializable
data class CreateProviderInstanceCommandDto(
    @SerialName("host_id") val hostId: String,
    @SerialName("definition_id") val definitionId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("initial_api_key") val initialApiKey: String? = null,
    @SerialName("custom_headers") val customHeaders: Map<String, String> = emptyMap(),
) {
    override fun toString(): String =
        "CreateProviderInstanceCommandDto(hostId=$hostId, definitionId=$definitionId, " +
            "displayName=$displayName, baseUrl=$baseUrl, initialApiKey=${if (initialApiKey != null) "<secret withheld>" else "null"}, " +
            "customHeaders=$customHeaders)"
}

/**
 * Non-secret mutation command for updating an existing provider instance.
 *
 * STRICT SECURITY: This DTO intentionally contains NO credential parameters.
 * Credential alterations MUST use [ReplaceProviderCredentialCommandDto] or [ClearProviderCredentialCommandDto].
 */
@Serializable
data class UpdateProviderInstanceCommandDto(
    @SerialName("host_id") val hostId: String,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("expected_revision") val expectedRevision: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("custom_headers") val customHeaders: Map<String, String>? = null,
)

/**
 * Dedicated write-only command to replace credentials for a provider instance.
 *
 * [toString] masks the API key so string interpolation/logging cannot leak credentials.
 */
@Serializable
data class ReplaceProviderCredentialCommandDto(
    @SerialName("host_id") val hostId: String,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("expected_revision") val expectedRevision: String? = null,
    @SerialName("api_key") val apiKey: String,
) {
    override fun toString(): String =
        "ReplaceProviderCredentialCommandDto(hostId=$hostId, instanceId=$instanceId, " +
            "expectedRevision=$expectedRevision, apiKey=<secret withheld>)"
}

/**
 * Dedicated command to clear/revoke credentials for a provider instance.
 */
@Serializable
data class ClearProviderCredentialCommandDto(
    @SerialName("host_id") val hostId: String,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("expected_revision") val expectedRevision: String? = null,
)

/**
 * Command to toggle or adjust visibility for a specific model route.
 */
@Serializable
data class SetModelVisibilityCommandDto(
    @SerialName("host_id") val hostId: String,
    @SerialName("route_id") val routeId: String,
    @SerialName("expected_revision") val expectedRevision: String? = null,
    @SerialName("visibility") val visibility: String,
)

/**
 * Command to enable or disable a provider instance.
 */
@Serializable
data class SetProviderEnabledCommandDto(
    @SerialName("host_id") val hostId: String,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("expected_revision") val expectedRevision: String? = null,
    @SerialName("enabled") val enabled: Boolean,
)

/**
 * Command to delete a provider instance.
 */
@Serializable
data class DeleteProviderInstanceCommandDto(
    @SerialName("host_id") val hostId: String,
    @SerialName("instance_id") val instanceId: String,
    @SerialName("expected_revision") val expectedRevision: String? = null,
)

/**
 * Response envelope returned by provider mutations.
 *
 * Always redacted: [instance] returns public metadata and credential status only, NEVER secrets.
 */
@Serializable
data class ProviderMutationResponseDto(
    @SerialName("contract_version") val contractVersion: Int = PROVIDER_MANAGEMENT_CONTRACT_VERSION,
    @SerialName("success") val success: Boolean,
    @SerialName("revision") val revision: String? = null,
    @SerialName("instance") val instance: RedactedProviderInstanceDto? = null,
    @SerialName("error") val error: ProviderManagementErrorDto? = null,
)
