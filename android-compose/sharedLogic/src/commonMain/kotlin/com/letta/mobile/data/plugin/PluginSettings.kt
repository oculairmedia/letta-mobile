package com.letta.mobile.data.plugin

import com.letta.mobile.data.schema.JsonSchemaCheck
import com.letta.mobile.data.schema.SchemaProblem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The outcome of holding a plugin's settings to its manifest. */
sealed interface PluginSettingsResult {
    /** [settings] are the values with every unset field's default filled in. */
    data class Valid(val settings: JsonObject) : PluginSettingsResult

    /** Every problem at its pointer into the settings object (`/baseUrl`). */
    data class Invalid(val problems: List<SchemaProblem>) : PluginSettingsResult
}

/** What the registry keeps about a plugin's settings: which fields the owner set, and what is still missing. */
@Serializable
data class PluginSettingsSummary(val configured: Set<String> = emptySet(), val missingRequired: Set<String> = emptySet()) {
    val complete: Boolean get() = missingRequired.isEmpty()

    companion object {
        /** The summary of [values] (as the owner set them, defaults not filled in) against [manifest]. */
        fun of(manifest: PluginManifest, values: JsonObject): PluginSettingsSummary = PluginSettingsSummary(
            configured = values.keys.intersect(manifest.settings.keys),
            missingRequired = manifest.settings.filter { (name, field) ->
                field.required && field.default == null && values[name].let { it == null || it is JsonNull }
            }.keys,
        )
    }
}

/**
 * Typed settings (plan section 3.4): each field of the manifest's `settings` map becomes a schema of
 * the shared [JsonSchemaCheck] vocabulary, so a value the settings UI sends is refused at its
 * pointer the same way a manifest is; unknown fields are refused, required fields without a
 * default must be set.
 */
object PluginSettings {
    /** Matches what `format: uri` admits: a scheme, `://`, and no whitespace. */
    const val URI_PATTERN: String = "^[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s]+$"

    fun validate(manifest: PluginManifest, values: JsonObject): PluginSettingsResult {
        val problems = JsonSchemaCheck(schemaOf(manifest.settings)).check(values)
        if (problems.isNotEmpty()) return PluginSettingsResult.Invalid(problems)
        val defaults = manifest.settings.mapNotNull { (name, field) -> field.default?.let { name to it } }.toMap()
        return PluginSettingsResult.Valid(JsonObject(defaults + values))
    }

    /** Every place [value] breaks [field], at [path]; empty when it holds. */
    fun problems(field: PluginSettingField, value: JsonElement, path: String): List<SchemaProblem> =
        JsonSchemaCheck(schemaOf(field)).check(value, path)

    /** The closed object schema of a settings map. */
    fun schemaOf(settings: Map<String, PluginSettingField>): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", JsonArray(settings.filter { (_, it) -> it.required && it.default == null }.keys.map(::JsonPrimitive)))
        put("properties", JsonObject(settings.mapValues { (_, field) -> schemaOf(field) }))
    }

    /** The schema one value of [field] must hold to. */
    fun schemaOf(field: PluginSettingField): JsonObject = buildJsonObject {
        when (field.type) {
            PluginSettingType.ENUM -> put("enum", JsonArray(field.values.orEmpty().map(::JsonPrimitive)))
            PluginSettingType.STRING -> putString(field)
            PluginSettingType.INTEGER -> putNumber("integer", field)
            PluginSettingType.NUMBER -> putNumber("number", field)
            PluginSettingType.BOOLEAN -> put("type", "boolean")
        }
    }

    private fun JsonObjectBuilder.putString(field: PluginSettingField) {
        put("type", "string")
        field.maxLength?.let { put("maxLength", it) }
        if (field.format == "uri") put("pattern", URI_PATTERN)
    }

    private fun JsonObjectBuilder.putNumber(type: String, field: PluginSettingField) {
        put("type", type)
        field.minimum?.let { put("minimum", it) }
        field.maximum?.let { put("maximum", it) }
    }
}
