package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Tool(
    val id: ToolId,
    val name: String,
    val description: String? = null,
    @SerialName("tool_type") val toolType: String? = null,
    @SerialName("source_code") val sourceCode: String? = null,
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("json_schema") val jsonSchema: JsonObject? = null,
    val tags: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ToolCreateParams(
    @SerialName("source_code") val sourceCode: String,
    @SerialName("source_type") val sourceType: String = "python",
    @SerialName("json_schema") val jsonSchema: JsonObject? = null,
    val description: String? = null,
    val tags: List<String>? = null,
)

@Serializable
data class ToolUpdateParams(
    @SerialName("source_code") val sourceCode: String? = null,
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("json_schema") val jsonSchema: JsonObject? = null,
    val description: String? = null,
    val tags: List<String>? = null,
)

@Serializable
data class ToolSchemaGenerateParams(
    val code: String,
    @SerialName("source_type") val sourceType: String = "python",
)
