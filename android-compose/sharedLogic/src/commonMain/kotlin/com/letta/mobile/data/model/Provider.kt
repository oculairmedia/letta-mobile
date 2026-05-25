package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Provider(
    val id: String? = null,
    val name: String,
    @SerialName("provider_type") val providerType: String,
    @SerialName("provider_category") val providerCategory: String? = null,
    @SerialName("api_key") val apiKey: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("access_key") val accessKey: String? = null,
    val region: String? = null,
    @SerialName("organization_id") val organizationId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ProviderCreateParams(
    val name: String,
    @SerialName("provider_type") val providerType: String,
    @SerialName("api_key") val apiKey: String,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("access_key") val accessKey: String? = null,
    val region: String? = null,
)

@Serializable
data class ProviderUpdateParams(
    @SerialName("api_key") val apiKey: String,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("access_key") val accessKey: String? = null,
    val region: String? = null,
)

@Serializable
data class ProviderCheckParams(
    @SerialName("provider_type") val providerType: String,
    @SerialName("api_key") val apiKey: String,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("access_key") val accessKey: String? = null,
    val region: String? = null,
)
