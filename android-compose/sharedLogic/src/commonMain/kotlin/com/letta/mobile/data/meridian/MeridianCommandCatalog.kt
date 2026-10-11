package com.letta.mobile.data.meridian

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.controller.extras.CustomIrohMessagingTool
import com.letta.mobile.data.controller.extras.ExternalTool
import com.letta.mobile.data.plugin.PluginActionTool

/**
 * One command of the Meridian surface: the words that name it ([path]), the registry tool it runs
 * ([tool]), and how argv fills that tool's input. Built from the registry's own tools, so a command
 * exists exactly when its tool does, and its help and schema are the tool's.
 *
 * @property positional input properties filled, in order, by the words after [path]
 * @property flagAliases short flag names for input properties (`--canvas` for `canvas_id`)
 * @property hidden an alias kept out of the help listing (`canvas guide compose`)
 */
data class MeridianCommand(
    val path: List<String>,
    val tool: ExternalTool,
    val positional: List<String> = emptyList(),
    val flagAliases: Map<String, String> = emptyMap(),
    val hidden: Boolean = false,
) {
    val toolName: String get() = tool.name
    val display: String get() = path.joinToString(" ")
}

/** A group of commands under one first word, e.g. `canvas`, with its one-line purpose. */
data class MeridianGroup(val name: String, val summary: String)

/**
 * Maps the registry's tools onto commands (design table "CLI surface"). Curated names come first:
 * the canvas verbs, `agents find`, `agent-message send`. Every plugin action is
 * `plugin <pluginId> <action>`. Any other tool the registry can invoke, today or added later, is
 * reachable as `tool <name>`, so nothing a host serves is left as a native-only tool.
 */
object MeridianCommandCatalog {
    const val CANVAS = "canvas"
    const val AGENTS = "agents"
    const val AGENT_MESSAGE = "agent-message"
    const val PLUGIN = "plugin"
    const val TOOL = "tool"
    const val GUIDE = "guide"
    const val SCHEMA = "schema"
    const val HELP = "help"

    /** The meta-tool's own name; never listed as a command, so the surface cannot call itself. */
    const val META_TOOL_NAME = "meridian"

    private val canvasAlias = mapOf("canvas" to "canvas_id")

    /** The curated commands: path, tool name, positionals and aliases. */
    private data class Curated(
        val path: List<String>,
        val toolName: String,
        val positional: List<String> = emptyList(),
        val aliases: Map<String, String> = emptyMap(),
        val hidden: Boolean = false,
    )

    private val curated: List<Curated> = listOf(
        Curated(listOf(CANVAS, "compose"), CanvasToolContract.COMPOSE, aliases = canvasAlias),
        Curated(listOf(CANVAS, "guide"), CanvasToolContract.COMPOSE_GUIDE),
        Curated(listOf(CANVAS, "guide", "compose"), CanvasToolContract.COMPOSE_GUIDE, hidden = true),
        Curated(listOf(GUIDE, "compose"), CanvasToolContract.COMPOSE_GUIDE, hidden = true),
        Curated(listOf(CANVAS, "layout"), CanvasToolContract.GET_LAYOUT, aliases = canvasAlias),
        Curated(listOf(CANVAS, "scene"), CanvasToolContract.GET_SCENE, aliases = canvasAlias),
        Curated(listOf(CANVAS, "apply-ops"), CanvasToolContract.APPLY_OPS, aliases = canvasAlias),
        Curated(listOf(CANVAS, "replace-scene"), CanvasToolContract.REPLACE_SCENE, aliases = canvasAlias),
        Curated(listOf(CANVAS, "list"), CanvasToolContract.LIST, aliases = mapOf("conversation" to "conversation_id")),
        Curated(listOf(CANVAS, "create"), CanvasToolContract.CREATE, aliases = mapOf("conversation" to "conversation_id")),
        Curated(listOf(CANVAS, "preview"), CanvasToolContract.RENDER_PREVIEW, aliases = canvasAlias),
        Curated(listOf(CANVAS, "export-svg"), CanvasToolContract.EXPORT_SVG, aliases = canvasAlias),
        Curated(listOf(AGENTS, "find"), AGENT_DISCOVER, positional = listOf("query")),
        Curated(listOf(AGENT_MESSAGE, "send"), CustomIrohMessagingTool.TOOL_NAME, positional = listOf("to")),
    )

    /** The groups, in help order. */
    val groups: List<MeridianGroup> = listOf(
        MeridianGroup(CANVAS, "Read and change this conversation's canvas (notes, checklists, cards, diagrams)."),
        MeridianGroup(AGENTS, "Find other agents by name, role, capability or host."),
        MeridianGroup(AGENT_MESSAGE, "Send a message to another agent."),
        MeridianGroup(PLUGIN, "Run an installed plugin's agent actions."),
        MeridianGroup(TOOL, "Run any other tool this host serves, by name."),
    )

    /** Every command [tools] supports, curated first, then plugin actions, then the rest by name. */
    fun commands(tools: List<ExternalTool>): List<MeridianCommand> {
        val reachable = tools.filter { it.name != META_TOOL_NAME }
        val byName = reachable.associateBy { it.name }
        val curatedCommands = curated.mapNotNull { entry ->
            byName[entry.toolName]?.let { MeridianCommand(entry.path, it, entry.positional, entry.aliases, entry.hidden) }
        }
        val pluginCommands = reachable.filterIsInstance<PluginActionTool>().map { tool ->
            MeridianCommand(listOf(PLUGIN, tool.definition.pluginId, tool.definition.action), tool)
        }
        val covered = (curatedCommands + pluginCommands).map { it.toolName }.toSet()
        val generic = reachable.filter { it.name !in covered }.sortedBy { it.name }.map { tool ->
            MeridianCommand(listOf(TOOL, tool.name), tool)
        }
        return curatedCommands + pluginCommands + generic
    }

    /** The command [argv] names (the longest matching path), with the words after it. */
    fun resolve(commands: List<MeridianCommand>, argv: List<String>): Pair<MeridianCommand, List<String>>? =
        commands
            .filter { it.path.size <= argv.size && argv.subList(0, it.path.size) == it.path }
            .maxByOrNull { it.path.size }
            ?.let { it to argv.drop(it.path.size) }

    private const val AGENT_DISCOVER = "agent_discover"
}
