package com.letta.mobile.data.model.provider.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val PROVIDER_MANAGEMENT_CONTRACT_VERSION = 1

/**
 * Wire DTO for a provider field definition schema.
 */
@Serializable
data class ProviderFieldSchemaDto(
    @SerialName("id") val id: String,
    @SerialName("label") val label: String,
    @SerialName("description") val description: String? = null,
    @SerialName("is_secret") val isSecret: Boolean = false,
    @SerialName("is_required") val isRequired: Boolean = false,
    @SerialName("placeholder") val placeholder: String? = null,
)

/**
 * Wire DTO for an LLM provider definition.
 */
@Serializable
data class ProviderDefinitionDto(
    @SerialName("id") val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("description") val description: String? = null,
    @SerialName("supported_protocols") val supportedProtocols: List<String> = emptyList(),
    @SerialName("fields") val fields: List<ProviderFieldSchemaDto> = emptyList(),
    @SerialName("default_base_url") val defaultBaseUrl: String? = null,
)

/**
 * Wire response envelope for listing provider definitions.
 */
@Serializable
data class ProviderDefinitionsListResponseDto(
    @SerialName("contract_version") val contractVersion: Int = PROVIDER_MANAGEMENT_CONTRACT_VERSION,
    @SerialName("definitions") val definitions: List<ProviderDefinitionDto> = emptyList(),
)

/**
 * Wire DTO for a configured provider instance. Strictly redacted: never contains secrets.
 */
@Serializable
data class RedactedProviderInstanceDto(
    @SerialName("id") val id: String,
    @SerialName("host_id") val hostId: String,
    @SerialName("definition_id") val definitionId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("credential_status") val credentialStatus: String = "not_required",
    @SerialName("operational_status") val operationalStatus: String = "active",
    @SerialName("revision") val revision: String? = null,
    @SerialName("configured_field_ids") val configuredFieldIds: List<String> = emptyList(),
    @SerialName("custom_headers") val customHeaders: Map<String, String> = emptyMap(),
)

/**
 * Wire response envelope for listing provider instances on a host.
 */
@Serializable
data class ProviderInstancesListResponseDto(
    @SerialName("contract_version") val contractVersion: Int = PROVIDER_MANAGEMENT_CONTRACT_VERSION,
    @SerialName("host_id") val hostId: String,
    @SerialName("instances") val instances: List<RedactedProviderInstanceDto> = emptyList(),
)

/**
 * Wire response envelope for a single provider instance.
 */
@Serializable
data class ProviderInstanceResponseDto(
    @SerialName("contract_version") val contractVersion: Int = PROVIDER_MANAGEMENT_CONTRACT_VERSION,
    @SerialName("instance") val instance: RedactedProviderInstanceDto,
)

/**
 * Wire DTO for a canonical model route on a provider instance.
 */
@Serializable
data class ModelRouteDto(
    @SerialName("id") val id: String,
    @SerialName("host_id") val hostId: String,
    @SerialName("provider_instance_id") val providerInstanceId: String,
    @SerialName("model_handle") val modelHandle: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("context_window_limit") val contextWindowLimit: Int? = null,
    @SerialName("availability") val availability: String = "available",
    @SerialName("visibility") val visibility: String = "auto",
    @SerialName("aliases") val aliases: List<String> = emptyList(),
    @SerialName("revision") val revision: String? = null,
)

/**
 * Wire response envelope for listing model routes on a host.
 */
@Serializable
data class ModelRoutesListResponseDto(
    @SerialName("contract_version") val contractVersion: Int = PROVIDER_MANAGEMENT_CONTRACT_VERSION,
    @SerialName("host_id") val hostId: String,
    @SerialName("routes") val routes: List<ModelRouteDto> = emptyList(),
)

/**
 * Wire DTO for catalog revision tracking.
 */
@Serializable
data class CatalogRevisionDto(
    @SerialName("host_id") val hostId: String,
    @SerialName("revision") val revision: String,
)

/**
 * Wire DTO for provider instance revision tracking.
 */
@Serializable
data class ProviderRevisionDto(
    @SerialName("instance_id") val instanceId: String,
    @SerialName("revision") val revision: String,
)
