package com.letta.mobile.data.plugin

import com.letta.mobile.data.schema.SchemaProblem
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The outcome of reading a `letta-plugin.json`. */
sealed interface PluginManifestResult {
    data class Parsed(val manifest: PluginManifest) : PluginManifestResult

    /** Every problem, each at its JSON pointer into the manifest; never empty. */
    data class Refused(val problems: List<SchemaProblem>) : PluginManifestResult {
        override fun toString(): String = "Refused(${problems.joinToString { "${it.path.ifEmpty { "/" }} ${it.code}: ${it.message}" }})"
    }
}

/**
 * Reads a `letta-plugin.json` strictly (plan section 3.1, letta-mobile-s416w.24): the JSON is held to
 * [PluginManifestSchema] (unknown fields refused, capabilities a closed enum), decoded with no
 * unknown keys ignored, then held to [PluginManifestRules]. A manifest with any problem is refused
 * whole, with every problem it has.
 */
object PluginManifestParser {
    /** Strict: an unknown key is an error, never skipped. `runtime` is told apart by `kind`. */
    val json: Json = Json {
        ignoreUnknownKeys = false
        classDiscriminator = "kind"
        explicitNulls = false
    }

    fun parse(text: String): PluginManifestResult {
        val document = try {
            json.parseToJsonElement(text)
        } catch (malformed: SerializationException) {
            return refused(PluginManifestProblem.SYNTAX.at(ManifestPointer.ROOT, "not JSON: ${malformed.message?.lineSequence()?.firstOrNull()}"))
        }
        return parse(document)
    }

    fun parse(document: JsonElement): PluginManifestResult {
        val schemaProblems = PluginManifestSchema.check(document)
        if (schemaProblems.isNotEmpty()) return PluginManifestResult.Refused(schemaProblems)
        val manifest = try {
            json.decodeFromJsonElement(PluginManifest.serializer(), document)
        } catch (undecodable: IllegalArgumentException) {
            // SerializationException is one; the schema has already held the shape, so this is a backstop.
            return refused(PluginManifestProblem.SYNTAX.at(ManifestPointer.ROOT, "does not decode: ${undecodable.message?.lineSequence()?.firstOrNull()}"))
        }
        val ruleProblems = PluginManifestRules.check(manifest)
        return if (ruleProblems.isEmpty()) PluginManifestResult.Parsed(manifest) else PluginManifestResult.Refused(ruleProblems)
    }

    /** [manifest] as the JSON a package carries. */
    fun encode(manifest: PluginManifest): String = json.encodeToString(PluginManifest.serializer(), manifest)

    private fun refused(problem: SchemaProblem) = PluginManifestResult.Refused(listOf(problem))
}
