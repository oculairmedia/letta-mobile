package com.letta.mobile.data.chat.projection.meridian

import com.letta.mobile.data.canvas.CanvasToolContract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `meridian` command surface as the timeline reads it (letta-mobile-jna0o.6): the same paths,
 * positionals and flag aliases as the host's `MeridianCommandCatalog` (letta-mobile-jna0o.3), so a
 * CLI call names the native tool the router runs. Help, `guide <topic>` and `schema …` run no tool
 * and are not here; they keep their Bash row.
 */
internal data class MeridianCommandSpec(
    val toolName: String,
    /** Input properties filled, in order, by the words after the path. */
    val positional: List<String> = emptyList(),
    /** Short flag names for input properties (`--canvas` for `canvas_id`). */
    val aliases: Map<String, String> = emptyMap(),
) {
    /** The input property `--[flag]` sets: an alias, else the flag with dashes as underscores. */
    fun property(flag: String): String = aliases[flag] ?: flag.replace('-', '_')

    /** The JSON type the tool's input schema gives [property]; null when unknown (a string then). */
    fun typeOf(property: String): String? = PROPERTY_TYPES[toolName]?.get(property)
}

internal object MeridianCommandTable {
    /** The command [argv] names (the longest matching path) and the words after it; null for none. */
    fun resolve(argv: List<String>): Pair<MeridianCommandSpec, List<String>>? =
        curated(argv) ?: plugin(argv) ?: generic(argv)

    private fun curated(argv: List<String>): Pair<MeridianCommandSpec, List<String>>? =
        CURATED.entries
            .filter { (path, _) -> path.size <= argv.size && argv.subList(0, path.size) == path }
            .maxByOrNull { (path, _) -> path.size }
            ?.let { (path, spec) -> spec to argv.drop(path.size) }

    /** `plugin <pluginId> <action>` runs the action's tool, `<idShort>_<action>` (PluginManifest.toolName). */
    private fun plugin(argv: List<String>): Pair<MeridianCommandSpec, List<String>>? {
        if (argv.firstOrNull() != PLUGIN || argv.size < PLUGIN_PATH_SIZE) return null
        val idShort = argv[1].substringAfterLast('.')
        return MeridianCommandSpec("${idShort}_${argv[2]}") to argv.drop(PLUGIN_PATH_SIZE)
    }

    /** `tool <name>` runs any other tool by name, never the meta-tool itself. */
    private fun generic(argv: List<String>): Pair<MeridianCommandSpec, List<String>>? {
        val name = argv.getOrNull(1)?.takeIf { argv[0] == TOOL && it != MeridianCommandCall.META_TOOL } ?: return null
        return MeridianCommandSpec(name) to argv.drop(2)
    }

    private const val PLUGIN = "plugin"
    private const val TOOL = "tool"
    private const val PLUGIN_PATH_SIZE = 3
}

private const val CANVAS = "canvas"
private val CANVAS_ALIAS = mapOf("canvas" to "canvas_id")
private val CONVERSATION_ALIAS = mapOf("conversation" to "conversation_id")
private const val AGENT_DISCOVER = "agent_discover"
private const val AGENT_MESSAGE_SEND = "agent_message_send"

/** The curated commands, as `MeridianCommandCatalog` lists them. */
private val CURATED: Map<List<String>, MeridianCommandSpec> = mapOf(
    listOf(CANVAS, "compose") to MeridianCommandSpec(CanvasToolContract.COMPOSE, aliases = CANVAS_ALIAS),
    listOf(CANVAS, "guide") to MeridianCommandSpec(CanvasToolContract.COMPOSE_GUIDE),
    listOf(CANVAS, "guide", "compose") to MeridianCommandSpec(CanvasToolContract.COMPOSE_GUIDE),
    listOf("guide", "compose") to MeridianCommandSpec(CanvasToolContract.COMPOSE_GUIDE),
    listOf(CANVAS, "layout") to MeridianCommandSpec(CanvasToolContract.GET_LAYOUT, aliases = CANVAS_ALIAS),
    listOf(CANVAS, "scene") to MeridianCommandSpec(CanvasToolContract.GET_SCENE, aliases = CANVAS_ALIAS),
    listOf(CANVAS, "apply-ops") to MeridianCommandSpec(CanvasToolContract.APPLY_OPS, aliases = CANVAS_ALIAS),
    listOf(CANVAS, "replace-scene") to MeridianCommandSpec(CanvasToolContract.REPLACE_SCENE, aliases = CANVAS_ALIAS),
    listOf(CANVAS, "list") to MeridianCommandSpec(CanvasToolContract.LIST, aliases = CONVERSATION_ALIAS),
    listOf(CANVAS, "create") to MeridianCommandSpec(CanvasToolContract.CREATE, aliases = CONVERSATION_ALIAS),
    listOf(CANVAS, "preview") to MeridianCommandSpec(CanvasToolContract.RENDER_PREVIEW, aliases = CANVAS_ALIAS),
    listOf(CANVAS, "export-svg") to MeridianCommandSpec(CanvasToolContract.EXPORT_SVG, aliases = CANVAS_ALIAS),
    listOf("agents", "find") to MeridianCommandSpec(AGENT_DISCOVER, positional = listOf("query")),
    listOf("agent-message", "send") to MeridianCommandSpec(AGENT_MESSAGE_SEND, positional = listOf("to")),
)

/**
 * Non-string input types by tool, read from the canvas contract's schemas; the agent tools'
 * mirror AgentDiscoveryTool's and CustomIrohMessagingTool's schemas (all strings but these).
 */
private val PROPERTY_TYPES: Map<String, Map<String, String>> =
    (CanvasToolContract.withPreview + CanvasToolContract.exportSvg).associate { it.name to it.inputSchema.propertyTypes() } +
        mapOf(AGENT_DISCOVER to mapOf("limit" to "integer", "offset" to "integer"))

private fun JsonObject.propertyTypes(): Map<String, String> =
    (this["properties"] as? JsonObject).orEmpty().mapNotNull { (name, schema) ->
        ((schema as? JsonObject)?.get("type") as? JsonPrimitive)?.content?.let { name to it }
    }.toMap()
