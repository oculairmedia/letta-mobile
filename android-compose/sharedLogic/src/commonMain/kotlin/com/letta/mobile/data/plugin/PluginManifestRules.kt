package com.letta.mobile.data.plugin

import com.letta.mobile.data.schema.SchemaProblem
import kotlinx.serialization.json.JsonPrimitive

/** What a [PluginManifestRules] problem is: the rules a schema cannot say (plan section 3.1). */
enum class PluginManifestProblem {
    SYNTAX,
    UNSUPPORTED_CONTRACT,
    DUPLICATE,
    BAD_ORIGIN,
    NO_ORIGINS,
    CAPABILITY_MISSING,
    BAD_PATH,
    UNKNOWN_PAGE,
    BAD_PROPS_SCHEMA,
    PROPS_SCHEMA_TOO_LARGE,
    BAD_MIGRATION,
    BAD_TOOL_NAME,
    MISSING_DESCRIPTION,
    BAD_INPUT_SCHEMA,
    BAD_TEMPLATE,
    UNDECLARED_REFERENCE,
    SECRET_IN_URL,
    BAD_SETTING,
}

/** A JSON pointer (RFC 6901) from [segments]: `pointer("actions", "start", "description")` is `/actions/start/description`. */
internal fun pointer(vararg segments: Any): String =
    segments.joinToString("") { "/" + it.toString().replace("~", "~0").replace("/", "~1") }

internal fun problem(path: String, code: PluginManifestProblem, message: String): SchemaProblem = SchemaProblem(path, code.name, message)

/** The index of every entry of [values] that repeats an earlier one, refused at `[path]/<index>`. */
internal fun <T> duplicates(values: List<T>, path: String): List<SchemaProblem> {
    val seen = mutableSetOf<T>()
    return values.mapIndexedNotNull { index, value ->
        problem("$path/$index", PluginManifestProblem.DUPLICATE, "'$value' is listed twice").takeIf { !seen.add(value) }
    }
}

/**
 * The manifest rules a schema cannot say (plan section 3.1, letta-mobile-s416w.24): the contract
 * version the host supports, capabilities that what the manifest declares needs, origins, setting
 * defaults, tool names and descriptions. Runtime paths and templates are [PluginRuntimeRules];
 * element kinds and pages are [PluginContentRules]. Each problem is at its JSON pointer.
 */
object PluginManifestRules {
    /** The `contract.version`s this host speaks; a package outside them is refused at install. */
    val SUPPORTED_CONTRACT_VERSIONS: Set<Int> = setOf(1)

    /** Agent tool names: what the App Server and the models accept. */
    const val MAX_TOOL_NAME_LENGTH: Int = 64

    fun check(manifest: PluginManifest): List<SchemaProblem> =
        contract(manifest) + capabilities(manifest) + net(manifest) + settings(manifest) + secrets(manifest) +
            actions(manifest) + PluginRuntimeRules.check(manifest) + PluginContentRules.check(manifest)

    private fun contract(manifest: PluginManifest): List<SchemaProblem> = listOfNotNull(
        problem(
            pointer("contract", "version"), PluginManifestProblem.UNSUPPORTED_CONTRACT,
            "contract version ${manifest.contract.version} is not supported here; this host speaks ${SUPPORTED_CONTRACT_VERSIONS.joinToString()}",
        ).takeIf { manifest.contract.version !in SUPPORTED_CONTRACT_VERSIONS },
    )

    private fun capabilities(manifest: PluginManifest): List<SchemaProblem> {
        val declared = manifest.capabilities.toSet()
        val needs = listOf(
            Triple(manifest.elements.isNotEmpty(), PluginCapability.CANVAS_PLACE, pointer("elements")),
            Triple(manifest.pages.isNotEmpty(), PluginCapability.UI_PAGES, pointer("pages")),
            Triple(manifest.net.connect.isNotEmpty(), PluginCapability.NET_CONNECT, pointer("net", "connect")),
        )
        return duplicates(manifest.capabilities, pointer("capabilities")) + needs.mapNotNull { (uses, capability, path) ->
            missing(capability, path).takeIf { uses && capability !in declared }
        }
    }

    internal fun missing(capability: PluginCapability, path: String): SchemaProblem =
        problem(path, PluginManifestProblem.CAPABILITY_MISSING, "this needs the '${capability.wire}' capability; add it to capabilities")

    private fun net(manifest: PluginManifest): List<SchemaProblem> {
        val path = pointer("net", "connect")
        val noOrigins = problem(path, PluginManifestProblem.NO_ORIGINS, "'net:connect' needs at least one origin here")
            .takeIf { PluginCapability.NET_CONNECT in manifest.capabilities && manifest.net.connect.isEmpty() }
        return listOfNotNull(noOrigins) + origins(manifest.net.connect, path) + duplicates(manifest.net.connect, path)
    }

    /** Every entry of [values] that is not an origin, at `[path]/<index>`. */
    internal fun origins(values: List<String>, path: String): List<SchemaProblem> = values.mapIndexedNotNull { index, value ->
        problem(
            "$path/$index", PluginManifestProblem.BAD_ORIGIN,
            "'$value' is not an origin: scheme://host[:port] (http, https, ws or wss; no path; a wildcard only as a leading *.)",
        ).takeIf { PluginOrigin.parse(value) == null }
    }

    private fun settings(manifest: PluginManifest): List<SchemaProblem> = manifest.settings.flatMap { (name, field) ->
        PluginSettingRules.check(field, pointer("settings", name))
    }

    private fun secrets(manifest: PluginManifest): List<SchemaProblem> =
        duplicates(manifest.secrets.map { it.name }, pointer("secrets")).map { it.copy(path = it.path + "/name") }

    private fun actions(manifest: PluginManifest): List<SchemaProblem> = manifest.actions.flatMap { (name, action) ->
        val path = pointer("actions", name)
        val tool = manifest.toolName(name)
        listOfNotNull(
            problem(path, PluginManifestProblem.BAD_TOOL_NAME, "the agent tool '$tool' is longer than $MAX_TOOL_NAME_LENGTH characters; shorten the action name")
                .takeIf { PluginActionVisibility.AGENT in action.visibility && tool.length > MAX_TOOL_NAME_LENGTH },
            problem("$path/description", PluginManifestProblem.MISSING_DESCRIPTION, "an action the agent sees needs a description; it is the tool's description")
                .takeIf { PluginActionVisibility.AGENT in action.visibility && action.description == null },
            problem("$path/input/type", PluginManifestProblem.BAD_INPUT_SCHEMA, "an action's input is an object schema: \"type\": \"object\"")
                .takeIf { (action.input["type"] as? JsonPrimitive)?.content != "object" },
        ) + duplicates(action.visibility, "$path/visibility")
    }
}

/** A setting's own consistency: keywords that fit its type, bounds in order, a default that holds. */
internal object PluginSettingRules {
    private val NUMERIC = setOf(PluginSettingType.INTEGER, PluginSettingType.NUMBER)

    fun check(field: PluginSettingField, path: String): List<SchemaProblem> {
        val misplaced = listOf(
            Triple("values", field.values != null, field.type == PluginSettingType.ENUM),
            Triple("format", field.format != null, field.type == PluginSettingType.STRING),
            Triple("maxLength", field.maxLength != null, field.type == PluginSettingType.STRING),
            Triple("minimum", field.minimum != null, field.type in NUMERIC),
            Triple("maximum", field.maximum != null, field.type in NUMERIC),
        ).mapNotNull { (keyword, present, fits) ->
            bad("$path/$keyword", "'$keyword' does not apply to a ${field.type.name.lowercase()} setting").takeIf { present && !fits }
        }
        val values = bad("$path/values", "an enum setting lists its values").takeIf { field.type == PluginSettingType.ENUM && field.values == null }
        val bounds = bad("$path/minimum", "minimum ${field.minimum} is over maximum ${field.maximum}")
            .takeIf { field.minimum != null && field.maximum != null && field.minimum > field.maximum }
        return misplaced + listOfNotNull(values, bounds) + defaultProblems(field, path, misplaced.isEmpty())
    }

    private fun defaultProblems(field: PluginSettingField, path: String, wellFormed: Boolean): List<SchemaProblem> {
        val default = field.default ?: return emptyList()
        return if (wellFormed) PluginSettings.problems(field, default, "$path/default") else emptyList()
    }

    private fun bad(path: String, message: String) = problem(path, PluginManifestProblem.BAD_SETTING, message)
}
