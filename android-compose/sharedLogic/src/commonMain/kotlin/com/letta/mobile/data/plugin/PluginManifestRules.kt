package com.letta.mobile.data.plugin

import com.letta.mobile.data.schema.SchemaProblem
import com.letta.mobile.plugin.api.PluginApi
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

    ;

    /** This problem at [at], worded by [message]. */
    fun at(at: ManifestPointer, message: String): SchemaProblem = SchemaProblem(at.path, name, message)
}

/** A JSON pointer (RFC 6901) into a manifest, built a segment at a time: `ManifestPointer.ROOT / "actions" / "start"`. */
class ManifestPointer private constructor(val path: String) {
    operator fun div(segment: String): ManifestPointer = ManifestPointer(path + "/" + segment.replace("~", "~0").replace("/", "~1"))

    operator fun div(index: Int): ManifestPointer = ManifestPointer("$path/$index")

    override fun toString(): String = path

    companion object {
        val ROOT: ManifestPointer = ManifestPointer("")

        fun of(vararg segments: String): ManifestPointer = segments.fold(ROOT) { pointer, segment -> pointer / segment }
    }
}

/** The index of every entry of [values] that repeats an earlier one, refused at `[at]/<index>`. */
internal fun <T> duplicates(values: List<T>, at: ManifestPointer): List<SchemaProblem> {
    val seen = mutableSetOf<T>()
    return values.mapIndexedNotNull { index, value ->
        PluginManifestProblem.DUPLICATE.at(at / index, "'$value' is listed twice").takeIf { !seen.add(value) }
    }
}

/**
 * The manifest rules a schema cannot say (plan section 3.1, letta-mobile-s416w.24): the contract
 * version the host supports, capabilities that what the manifest declares needs, origins, setting
 * defaults, tool names and descriptions. Runtime paths and templates are [PluginRuntimeRules];
 * element kinds and pages are [PluginContentRules]. Each problem is at its JSON pointer.
 */
object PluginManifestRules {
    /** The `contract.version`s this host speaks (the `:plugin-api` major it ships); a package outside them is refused at install. */
    val SUPPORTED_CONTRACT_VERSIONS: Set<Int> = setOf(PluginApi.CONTRACT_VERSION)

    /** Agent tool names: what the App Server and the models accept. */
    const val MAX_TOOL_NAME_LENGTH: Int = 64

    private val ROOT = ManifestPointer.ROOT

    fun check(manifest: PluginManifest): List<SchemaProblem> =
        contract(manifest) + capabilities(manifest) + net(manifest) + settings(manifest) + secrets(manifest) +
            actions(manifest) + PluginRuntimeRules.check(manifest) + PluginContentRules.check(manifest)

    private fun contract(manifest: PluginManifest): List<SchemaProblem> = listOfNotNull(
        PluginManifestProblem.UNSUPPORTED_CONTRACT.at(
            ROOT / "contract" / "version",
            "contract version ${manifest.contract.version} is not supported here; this host speaks ${SUPPORTED_CONTRACT_VERSIONS.joinToString()}",
        ).takeIf { manifest.contract.version !in SUPPORTED_CONTRACT_VERSIONS },
    )

    private fun capabilities(manifest: PluginManifest): List<SchemaProblem> {
        val declared = manifest.capabilities.toSet()
        val needs = listOf(
            Triple(manifest.elements.isNotEmpty(), PluginCapability.CANVAS_PLACE, ROOT / "elements"),
            Triple(manifest.pages.isNotEmpty(), PluginCapability.UI_PAGES, ROOT / "pages"),
            Triple(manifest.net.connect.isNotEmpty(), PluginCapability.NET_CONNECT, ROOT / "net" / "connect"),
        )
        return duplicates(manifest.capabilities, ROOT / "capabilities") + needs.mapNotNull { (uses, capability, at) ->
            missing(capability, at).takeIf { uses && capability !in declared }
        }
    }

    internal fun missing(capability: PluginCapability, at: ManifestPointer): SchemaProblem =
        PluginManifestProblem.CAPABILITY_MISSING.at(at, "this needs the '${capability.wire}' capability; add it to capabilities")

    private fun net(manifest: PluginManifest): List<SchemaProblem> {
        val at = ROOT / "net" / "connect"
        val noOrigins = PluginManifestProblem.NO_ORIGINS.at(at, "'net:connect' needs at least one origin here")
            .takeIf { PluginCapability.NET_CONNECT in manifest.capabilities && manifest.net.connect.isEmpty() }
        return listOfNotNull(noOrigins) + origins(manifest.net.connect, at) + duplicates(manifest.net.connect, at)
    }

    /** Every entry of [values] that is not an origin, at `[at]/<index>`. */
    internal fun origins(values: List<String>, at: ManifestPointer): List<SchemaProblem> = values.mapIndexedNotNull { index, value ->
        PluginManifestProblem.BAD_ORIGIN.at(
            at / index,
            "'$value' is not an origin: scheme://host[:port] (http, https, ws or wss; no path; a wildcard only as a leading *.)",
        ).takeIf { PluginOrigin.parse(value) == null }
    }

    private fun settings(manifest: PluginManifest): List<SchemaProblem> = manifest.settings.flatMap { (name, field) ->
        PluginSettingRules.check(field, ROOT / "settings" / name)
    }

    private fun secrets(manifest: PluginManifest): List<SchemaProblem> =
        duplicates(manifest.secrets.map { it.name }, ROOT / "secrets").map { it.copy(path = it.path + "/name") }

    private fun actions(manifest: PluginManifest): List<SchemaProblem> = manifest.actions.flatMap { (name, action) ->
        val at = ROOT / "actions" / name
        val forAgent = PluginActionVisibility.AGENT in action.visibility
        val tool = manifest.toolName(name)
        listOfNotNull(
            PluginManifestProblem.BAD_TOOL_NAME.at(at, "the agent tool '$tool' is longer than $MAX_TOOL_NAME_LENGTH characters; shorten the action name")
                .takeIf { forAgent && tool.length > MAX_TOOL_NAME_LENGTH },
            PluginManifestProblem.MISSING_DESCRIPTION.at(at / "description", "an action the agent sees needs a description; it is the tool's description")
                .takeIf { forAgent && action.description == null },
            PluginManifestProblem.BAD_INPUT_SCHEMA.at(at / "input" / "type", "an action's input is an object schema: \"type\": \"object\"")
                .takeIf { (action.input["type"] as? JsonPrimitive)?.content != "object" },
        ) + duplicates(action.visibility, at / "visibility")
    }
}

/** A setting's own consistency: keywords that fit its type, bounds in order, a default that holds. */
internal object PluginSettingRules {
    private val NUMERIC = setOf(PluginSettingType.INTEGER, PluginSettingType.NUMBER)

    fun check(field: PluginSettingField, at: ManifestPointer): List<SchemaProblem> {
        val misplaced = listOf(
            Triple("values", field.values != null, field.type == PluginSettingType.ENUM),
            Triple("format", field.format != null, field.type == PluginSettingType.STRING),
            Triple("maxLength", field.maxLength != null, field.type == PluginSettingType.STRING),
            Triple("minimum", field.minimum != null, field.type in NUMERIC),
            Triple("maximum", field.maximum != null, field.type in NUMERIC),
        ).mapNotNull { (keyword, present, fits) ->
            bad(at / keyword, "'$keyword' does not apply to a ${field.type.name.lowercase()} setting").takeIf { present && !fits }
        }
        val values = bad(at / "values", "an enum setting lists its values").takeIf { field.type == PluginSettingType.ENUM && field.values == null }
        val bounds = bad(at / "minimum", "minimum ${field.minimum} is over maximum ${field.maximum}").takeIf { boundsReversed(field) }
        val default = field.default?.takeIf { misplaced.isEmpty() }?.let { PluginSettings.problems(field, it, (at / "default").path) }.orEmpty()
        return misplaced + listOfNotNull(values, bounds) + default
    }

    private fun boundsReversed(field: PluginSettingField): Boolean {
        val minimum = field.minimum ?: return false
        val maximum = field.maximum ?: return false
        return minimum > maximum
    }

    private fun bad(at: ManifestPointer, message: String) = PluginManifestProblem.BAD_SETTING.at(at, message)
}
