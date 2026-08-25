package com.letta.mobile.data.model.provider.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val PROVIDER_MANAGEMENT_CONTRACT_VERSION = 1

@Serializable
data class ProviderFieldSchemaDto(
    @SerialName("id") val id: String,
    @SerialName("label") val label: String,
    @SerialName("description") val description: String? = null,
    @SerialName("is_secret") val isSecret: Boolean = false,
    @SerialName("is_required") val isRequired: Boolean = false,
    @SerialName("placeholder") val placeholder: String? = null,
)

@Serializable
data class ProviderDefinitionDto(
    @SerialName("id") val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("description") val description: String? = null,
    @SerialName("supported_protocols") val supportedProtocols: List<String> = emptyList(),
    @SerialName("fields") val fields: List<ProviderFieldSchemaDto> = emptyList(),
    @SerialName("default_base_url") val defaultBaseUrl: String? = null,
)

@Serializable
data class ProviderDefinitionsListResponseDto(
    @SerialName("contract_version") val contractVersion: Int,
    @SerialName("definitions") val definitions: List<ProviderDefinitionDto> = emptyList(),
)

/** Strictly redacted provider state: values and credential material are not representable. */
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
    @SerialName("configured_header_names") val configuredHeaderNames: List<String> = emptyList(),
)

@Serializable
data class ProviderInstancesListResponseDto(
    @SerialName("contract_version") val contractVersion: Int,
    @SerialName("host_id") val hostId: String,
    @SerialName("instances") val instances: List<RedactedProviderInstanceDto> = emptyList(),
)

@Serializable
data class ProviderInstanceResponseDto(
    @SerialName("contract_version") val contractVersion: Int,
    @SerialName("instance") val instance: RedactedProviderInstanceDto,
)

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

@Serializable
data class ModelRoutesListResponseDto(
    @SerialName("contract_version") val contractVersion: Int,
    @SerialName("host_id") val hostId: String,
    @SerialName("routes") val routes: List<ModelRouteDto> = emptyList(),
)

@Serializable
data class CatalogRevisionDto(
    @SerialName("host_id") val hostId: String,
    @SerialName("revision") val revision: String,
)

@Serializable
data class ProviderRevisionDto(
    @SerialName("instance_id") val instanceId: String,
    @SerialName("revision") val revision: String,
)
