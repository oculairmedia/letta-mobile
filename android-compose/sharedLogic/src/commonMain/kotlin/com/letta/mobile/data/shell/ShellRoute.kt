package com.letta.mobile.data.shell

import com.letta.mobile.data.pairing.PairLink

/**
 * The canonical text form of a [ShellDestination]: `name` or
 * `name?key=value&key=value`, values percent-encoded. It is the web shell's route model, the path
 * of a `meridian://` deep link ([ShellDeepLink]) and the saved form of a destination.
 *
 * ```
 * home | chat?agent=a&conversation=c | canvas?id=b | memory?agent=a | schedules?agent=a | channels
 * skills?agent=a&section=tools | settings?section=pairing | agent-editor?agent=a | agent-editor (a new agent) | providers | overview
 * runs | templates | projects | plugins
 * ```
 *
 * [decode] is the inverse of [encode]; anything else (an unknown name, a missing required id, an
 * unknown section, a malformed escape) decodes to null.
 */
object ShellRoute {
    private const val AGENT = "agent"
    private const val CONVERSATION = "conversation"
    private const val ID = "id"
    private const val SECTION = "section"

    private val Names: Map<ShellPageKind, String> = ShellPageKind.entries.associateWith { kind ->
        if (kind == ShellPageKind.AgentEditor) "agent-editor" else kind.name.lowercase()
    }
    private val KindsByName: Map<String, ShellPageKind> = Names.entries.associate { (kind, name) -> name to kind }

    /** Builds each kind of destination from its route's query; null when a required value is missing or unknown. */
    private val Factories: Map<ShellPageKind, (Map<String, String>) -> ShellDestination?> = mapOf(
        ShellPageKind.Home to { _ -> ShellDestination.Home },
        ShellPageKind.Chat to { q -> ShellDestination.Chat(q[AGENT], q[CONVERSATION]) },
        ShellPageKind.Canvas to { q -> ShellDestination.Canvas(q[ID], q[CONVERSATION], q[AGENT]) },
        ShellPageKind.Memory to { q -> ShellDestination.Memory(q[AGENT]) },
        ShellPageKind.Schedules to { q -> ShellDestination.Schedules(q[AGENT]) },
        ShellPageKind.Channels to { _ -> ShellDestination.Channels },
        ShellPageKind.Skills to { q ->
            section(q, ShellSkillsSection.Skills)?.let { ShellDestination.Skills(q[AGENT], it) }
        },
        ShellPageKind.Settings to { q ->
            if (q[SECTION] == null) ShellDestination.Settings() else section<ShellSettingsSection>(q)?.let(ShellDestination::Settings)
        },
        ShellPageKind.AgentEditor to { q -> ShellDestination.AgentEditor(q[AGENT]) },
        ShellPageKind.Providers to { _ -> ShellDestination.Providers },
        ShellPageKind.Overview to { _ -> ShellDestination.Overview },
        ShellPageKind.Runs to { _ -> ShellDestination.Runs },
        ShellPageKind.Templates to { _ -> ShellDestination.Templates },
        ShellPageKind.Projects to { _ -> ShellDestination.Projects },
        ShellPageKind.Plugins to { _ -> ShellDestination.Plugins },
    )

    fun encode(destination: ShellDestination): String {
        val section = destination.skillsSection.takeIf { it != ShellSkillsSection.Skills } ?: destination.settingsSection
        return path(
            Names.getValue(destination.kind),
            AGENT to destination.agentId,
            CONVERSATION to destination.conversationId,
            ID to (destination as? ShellDestination.Canvas)?.canvasId,
            SECTION to section?.name?.lowercase(),
        )
    }

    fun decode(route: String): ShellDestination? {
        val trimmed = route.trim().trimStart('/')
        val kind = KindsByName[trimmed.substringBefore('?').trimEnd('/').lowercase()] ?: return null
        val query = parseQuery(trimmed.substringAfter('?', missingDelimiterValue = "")) ?: return null
        return Factories.getValue(kind)(query)
    }

    /** The query's section, [default] when there is none, null when it names no [E]. */
    private inline fun <reified E : Enum<E>> section(query: Map<String, String>, default: E? = null): E? {
        val wire = query[SECTION] ?: return default
        return enumValues<E>().firstOrNull { it.name.equals(wire, ignoreCase = true) }
    }

    private fun path(name: String, vararg params: Pair<String, String?>): String {
        val query = params.mapNotNull { (key, value) ->
            value?.let { "$key=${PairLink.percentEncode(it)}" }
        }
        return if (query.isEmpty()) name else "$name?${query.joinToString("&")}"
    }

    /** Null on a malformed escape; blank values count as absent. */
    private fun parseQuery(query: String): Map<String, String>? {
        if (query.isBlank()) return emptyMap()
        val params = mutableMapOf<String, String>()
        for (pair in query.substringBefore('#').split('&')) {
            val key = pair.substringBefore('=', missingDelimiterValue = "")
            if (key.isEmpty()) continue
            val value = PairLink.percentDecode(pair.substringAfter('=')) ?: return null
            if (value.isNotBlank()) params[key] = value
        }
        return params
    }
}
