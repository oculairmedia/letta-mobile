package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class IdentityProperty(
    val key: String,
    val value: JsonElement,
    val type: String,
)

@Serializable
data class Identity(
    val id: String,
    @SerialName("identifier_key") val identifierKey: String,
    val name: String,
    @SerialName("identity_type") val identityType: String,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("agent_ids") val agentIds: List<String> = emptyList(),
    @SerialName("block_ids") val blockIds: List<String> = emptyList(),
    @SerialName("organization_id") val organizationId: String? = null,
    val properties: List<IdentityProperty> = emptyList(),
)

@Serializable
data class IdentityCreateParams(
    @SerialName("identifier_key") val identifierKey: String,
    val name: String,
    @SerialName("identity_type") val identityType: String,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("agent_ids") val agentIds: List<String>? = null,
    @SerialName("block_ids") val blockIds: List<String>? = null,
    val properties: List<IdentityProperty>? = null,
)

@Serializable
data class IdentityUpsertParams(
    @SerialName("identifier_key") val identifierKey: String,
    val name: String,
    @SerialName("identity_type") val identityType: String,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("agent_ids") val agentIds: List<String>? = null,
    @SerialName("block_ids") val blockIds: List<String>? = null,
    val properties: List<IdentityProperty>? = null,
)

@Serializable
data class IdentityUpdateParams(
    @SerialName("identifier_key") val identifierKey: String? = null,
    val name: String? = null,
    @SerialName("identity_type") val identityType: String? = null,
    @SerialName("agent_ids") val agentIds: List<String>? = null,
    @SerialName("block_ids") val blockIds: List<String>? = null,
    val properties: List<IdentityProperty>? = null,
)
