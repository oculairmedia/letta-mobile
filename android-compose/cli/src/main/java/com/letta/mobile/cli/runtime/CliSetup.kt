package com.letta.mobile.cli.runtime

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
internal data class CliSetupDocument(
    val activeProfile: String? = null,
    val profiles: List<CliProfile> = emptyList(),
    val resources: CliSetupResources = CliSetupResources(),
    val links: CliSetupLinks = CliSetupLinks(),
)

@Serializable
internal data class CliSetupResources(
    val agents: List<CliSetupResource> = emptyList(),
    val tools: List<CliSetupResource> = emptyList(),
    val blocks: List<CliSetupResource> = emptyList(),
    val archives: List<CliSetupResource> = emptyList(),
    val folders: List<CliSetupResource> = emptyList(),
    val groups: List<CliSetupResource> = emptyList(),
    val identities: List<CliSetupResource> = emptyList(),
    val providers: List<CliSetupResource> = emptyList(),
    val mcpServers: List<CliSetupResource> = emptyList(),
    val projects: List<CliSetupResource> = emptyList(),
    val schedules: List<CliSetupSchedule> = emptyList(),
)

@Serializable
internal data class CliSetupResource(
    val id: String? = null,
    val ref: String? = null,
    val body: JsonObject = JsonObject(emptyMap()),
    val create: JsonObject? = null,
    val update: JsonObject? = null,
)

@Serializable
internal data class CliSetupSchedule(
    val id: String? = null,
    val ref: String? = null,
    val agentId: String? = null,
    val agentRef: String? = null,
    val body: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class CliSetupLinks(
    val agentTools: List<CliSetupLink> = emptyList(),
    val agentBlocks: List<CliSetupLink> = emptyList(),
    val agentArchives: List<CliSetupLink> = emptyList(),
    val agentIdentities: List<CliSetupLink> = emptyList(),
    val blockIdentities: List<CliSetupLink> = emptyList(),
)

@Serializable
internal data class CliSetupLink(
    val agentId: String? = null,
    val agentRef: String? = null,
    val toolId: String? = null,
    val toolRef: String? = null,
    val blockId: String? = null,
    val blockRef: String? = null,
    val archiveId: String? = null,
    val archiveRef: String? = null,
    val identityId: String? = null,
    val identityRef: String? = null,
)

@Serializable
internal data class CliSetupPlan(
    val steps: List<CliSetupPlanStep> = emptyList(),
)

@Serializable
internal data class CliSetupPlanStep(
    val action: String,
    val target: String,
    val detail: String? = null,
)

internal fun readCliSetupDocument(path: Path): CliSetupDocument {
    val text = String(Files.readAllBytes(path), Charsets.UTF_8).removePrefix("\uFEFF")
    return when (path.fileName.toString().substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "yaml", "yml" -> CliJson.decodeFromJsonElement(CliSetupDocument.serializer(), yamlToJsonElement(text))
        else -> CliJson.decodeFromString(text)
    }
}

internal fun writeCliSetupDocument(document: CliSetupDocument, compact: Boolean = false): String {
    val json = if (compact) CliJson else prettySetupJson
    return json.encodeToString(CliSetupDocument.serializer(), document)
}

internal fun writeCliSetupPlan(plan: CliSetupPlan, compact: Boolean = false): String {
    val json = if (compact) CliJson else prettySetupJson
    return json.encodeToString(CliSetupPlan.serializer(), plan)
}

internal fun JsonObject.stringField(vararg names: String): String? =
    names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.contentOrNull }

internal fun JsonElement.asObjectList(): List<JsonObject> = when (this) {
    is JsonArray -> filterIsInstance<JsonObject>()
    is JsonObject -> {
        val nested = listOf("projects", "items", "data", "results")
            .firstNotNullOfOrNull { key -> this[key] as? JsonArray }
        nested?.filterIsInstance<JsonObject>() ?: listOf(this)
    }
    else -> emptyList()
}

private fun yamlToJsonElement(text: String): JsonElement {
    val mapper = ObjectMapper(YAMLFactory())
    val parsed = mapper.readValue(text, Any::class.java)
    return parsed.toJsonElement()
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is Map<*, *> -> JsonObject(
        entries.associate { (key, value) ->
            key.toString() to value.toJsonElement()
        }
    )
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    is Boolean -> JsonPrimitive(this)
    is Number -> toJsonPrimitive()
    else -> JsonPrimitive(toString())
}

private fun Number.toJsonPrimitive(): JsonPrimitive {
    val text = toString()
    return when {
        text.toIntOrNull() != null -> JsonPrimitive(text.toInt())
        text.toLongOrNull() != null -> JsonPrimitive(text.toLong())
        text.toDoubleOrNull() != null -> JsonPrimitive(text.toDouble())
        else -> JsonPrimitive(text)
    }
}

private val prettySetupJson = kotlinx.serialization.json.Json {
    prettyPrint = true
    explicitNulls = false
    encodeDefaults = true
}
