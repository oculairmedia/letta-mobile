package com.letta.mobile.data.plugin

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where a `${namespace.name}` template reads its value. */
enum class TemplateNamespace(val wire: String) {
    SETTINGS("settings"),
    SECRETS("secrets"),
}

/** One `${settings.baseUrl}` or `${secrets.apiToken}` reference in a runtime string. */
data class TemplateRef(val namespace: TemplateNamespace, val name: String) {
    override fun toString(): String = "\${${namespace.wire}.$name}"
}

/** The references in one string, and the `${...}` tokens that are not references (refused). */
data class TemplateScan(val refs: List<TemplateRef>, val malformed: List<String>)

/** The outcome of resolving a runtime's env or headers. */
sealed interface TemplateResolution {
    data class Resolved(val values: ResolvedTemplates) : TemplateResolution

    /** [problems] name the key and the reference that has no value, never a value. */
    data class Refused(val problems: List<String>) : TemplateResolution
}

/**
 * Resolved env or headers, ready for a driver. Values may hold secrets, so [toString] lists the
 * keys only: a resolved set in a log line or an exception shows no value.
 */
class ResolvedTemplates(private val values: Map<String, String>) {
    operator fun get(key: String): String? = values[key]

    /** The values, for the one call that hands them to the process or the WebSocket. */
    fun reveal(): Map<String, String> = values

    override fun toString(): String = "ResolvedTemplates(${values.keys.joinToString()})"
}

/**
 * The host's only substitution (plan section 3.1): `${settings.<name>}` and `${secrets.<name>}` in a
 * `process` runtime's env and a `service` runtime's headers, resolved on the host only. Anything
 * else of the form `${...}` is malformed; secrets never reach a URL (refused by the manifest rules).
 */
object PluginTemplates {
    private val TOKEN = Regex("\\$\\{([^}]*)\\}")
    private val REF = Regex("^(settings|secrets)\\.([a-zA-Z][a-zA-Z0-9_]{0,63})$")
    private const val OPEN = "\${"

    fun scan(text: String): TemplateScan {
        val refs = mutableListOf<TemplateRef>()
        val malformed = mutableListOf<String>()
        TOKEN.findAll(text).forEach { token ->
            val ref = refOf(token.groupValues[1])
            if (ref != null) refs += ref else malformed += token.value
        }
        if (TOKEN.replace(text, "").contains(OPEN)) malformed += OPEN
        return TemplateScan(refs, malformed)
    }

    /**
     * [templates] with every reference replaced: settings from [settings] (validated, defaults
     * filled in), secrets from [secrets]. A reference with no value refuses the whole set, so a
     * plugin never starts with half its configuration.
     */
    fun resolve(templates: Map<String, String>, settings: JsonObject, secrets: PluginSecrets): TemplateResolution {
        val problems = mutableListOf<String>()
        val resolved = templates.mapValues { (key, text) ->
            TOKEN.replace(text) { token ->
                val ref = refOf(token.groupValues[1])
                val value = ref?.let { valueOf(it, settings, secrets) }
                if (value == null) problems += "$key: ${ref ?: token.value} has no value"
                value.orEmpty()
            }
        }
        return if (problems.isEmpty()) TemplateResolution.Resolved(ResolvedTemplates(resolved)) else TemplateResolution.Refused(problems)
    }

    private fun refOf(body: String): TemplateRef? {
        val match = REF.matchEntire(body) ?: return null
        val namespace = TemplateNamespace.entries.first { it.wire == match.groupValues[1] }
        return TemplateRef(namespace, match.groupValues[2])
    }

    private fun valueOf(ref: TemplateRef, settings: JsonObject, secrets: PluginSecrets): String? = when (ref.namespace) {
        TemplateNamespace.SETTINGS -> (settings[ref.name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        TemplateNamespace.SECRETS -> secrets[ref.name]?.reveal()?.takeIf { it.isNotEmpty() }
    }
}
