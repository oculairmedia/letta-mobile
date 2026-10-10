package com.letta.mobile.data.meridian

import com.letta.mobile.data.canvas.CanvasSceneSchema
import com.letta.mobile.data.canvas.CanvasToolContract
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The help tree (letta-mobile-jna0o.3), generated from the tools' own definitions
 * ([CanvasToolContract] for canvas, each tool's description and input schema for the rest), so
 * it never drifts from what the tools accept. Every text is deterministic: same tools, same bytes.
 */
internal object MeridianHelp {
    private const val SUMMARY_MAX_CHARS = 100
    private const val NAME_COLUMN = 16

    fun top(commands: List<MeridianCommand>): String = buildString {
        appendLine("meridian: commands for this conversation's host. Input is one JSON object on stdin; output is JSON.")
        appendLine()
        appendLine("Commands:")
        presentGroups(commands).forEach { group ->
            val count = commands.count { it.path.first() == group.name && !it.hidden }
            appendLine("  ${group.name.padEnd(NAME_COLUMN)}${group.summary} ($count)")
        }
        if (guideTopics(commands).isNotEmpty()) appendLine("  ${MeridianCommandCatalog.GUIDE.padEnd(NAME_COLUMN)}Reference text on demand: ${guideTopics(commands).joinToString(", ")}.")
        appendLine()
        appendLine("Run `meridian <command> --help` for its verbs, `meridian schema <command...>` for an input schema.")
        appendLine(EXIT_CODES)
    }

    fun group(name: String, commands: List<MeridianCommand>): String = buildString {
        val group = MeridianCommandCatalog.groups.firstOrNull { it.name == name }
        appendLine("meridian $name: ${group?.summary.orEmpty()}".trimEnd())
        appendLine()
        val listed = commands.filter { it.path.first() == name && !it.hidden }
        listed.forEach { command ->
            val verb = command.path.drop(1).joinToString(" ")
            appendLine("  ${verb.padEnd(NAME_COLUMN)}${summary(command.tool.description)}")
        }
        appendLine()
        appendLine("Run `meridian $name <verb> --help` for a verb's flags and input.")
        appendLine(EXIT_CODES)
    }

    fun command(command: MeridianCommand): String = buildString {
        appendLine("meridian ${command.display}${usageTail(command)}")
        appendLine("Runs the ${command.toolName} tool.")
        appendLine()
        appendLine(command.tool.description)
        val flags = flagLines(command)
        if (flags.isNotEmpty()) {
            appendLine()
            appendLine("Flags:")
            flags.forEach { appendLine("  $it") }
        }
        val stdinOnly = stdinOnlyProperties(command)
        if (stdinOnly.isNotEmpty()) {
            appendLine()
            appendLine("On stdin only (JSON): ${stdinOnly.joinToString(", ")}")
        }
        required(command).takeIf { it.isNotEmpty() }?.let {
            appendLine("Required: ${it.joinToString(", ")}")
        }
        appendLine()
        appendLine("Input schema: meridian schema ${command.display}")
    }

    /**
     * The guide topics: `compose` when the host answers canvas_compose_guide (the tool's own text,
     * run as the hidden `guide compose` command), and the scene and op grammar when it serves canvas.
     */
    fun guideTopics(commands: List<MeridianCommand>): List<String> = buildList {
        if (commands.any { it.path == listOf(MeridianCommandCatalog.GUIDE, COMPOSE_TOPIC) }) add(COMPOSE_TOPIC)
        if (commands.any { it.path.first() == MeridianCommandCatalog.CANVAS }) addAll(GUIDE_TEXTS.keys)
    }

    /** The contract text for [topic] (scene, ops), or null when there is none. */
    fun guideText(topic: String): String? = GUIDE_TEXTS[topic]?.invoke()

    fun guideIndex(commands: List<MeridianCommand>): String = buildString {
        appendLine("meridian guide <topic>: reference text, fetched only when needed.")
        appendLine()
        guideTopics(commands).forEach { topic -> appendLine("  ${topic.padEnd(NAME_COLUMN)}${GUIDE_SUMMARIES.getValue(topic)}") }
    }

    /** The first sentence of [description], cut to [SUMMARY_MAX_CHARS]. */
    fun summary(description: String): String {
        val sentence = description.substringBefore(". ").trimEnd('.').trim() + "."
        return if (sentence.length <= SUMMARY_MAX_CHARS) sentence else sentence.take(SUMMARY_MAX_CHARS - 3).trimEnd() + "..."
    }

    private fun presentGroups(commands: List<MeridianCommand>): List<MeridianGroup> =
        MeridianCommandCatalog.groups.filter { group -> commands.any { it.path.first() == group.name && !it.hidden } }

    private fun usageTail(command: MeridianCommand): String {
        val positionals = command.positional.joinToString("") { " [$it]" }
        val takesStdin = stdinOnlyProperties(command).isNotEmpty() || required(command).isNotEmpty()
        return positionals + if (takesStdin) " [flags] < input.json" else " [flags]"
    }

    private fun properties(command: MeridianCommand): JsonObject =
        command.tool.inputSchema?.get("properties") as? JsonObject ?: JsonObject(emptyMap())

    private fun typeOf(schema: JsonObject?): String =
        (schema?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "string"

    private fun flagLines(command: MeridianCommand): List<String> {
        val aliases = command.flagAliases.entries.associate { (alias, property) -> property to alias }
        return properties(command).entries
            .filter { (_, schema) -> typeOf(schema as? JsonObject) !in COMPOUND }
            .map { (name, schema) ->
                val schemaObject = schema as? JsonObject
                val type = typeOf(schemaObject)
                val flag = "--${name.replace('_', '-')}" + if (type == "boolean") "" else " <$type>"
                val alias = aliases[name]?.let { " (or --$it)" }.orEmpty()
                val description = (schemaObject?.get("description") as? JsonPrimitive)?.content?.let { "  $it" }.orEmpty()
                "$flag$alias$description"
            }
    }

    private fun stdinOnlyProperties(command: MeridianCommand): List<String> =
        properties(command).entries.filter { (_, schema) -> typeOf(schema as? JsonObject) in COMPOUND }.map { it.key }

    private fun required(command: MeridianCommand): List<String> =
        (command.tool.inputSchema?.get("required") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }

    private val COMPOUND = setOf("array", "object")

    private const val EXIT_CODES = "Exit codes: 0 ok, 2 refused input, 3 denied, 4 host unavailable. Errors print {\"error\": ...}."

    private const val COMPOSE_TOPIC = "compose"

    private val GUIDE_TEXTS: Map<String, () -> String> = linkedMapOf(
        "scene" to { CanvasSceneSchema.description },
        "ops" to { CanvasToolContract.applyOps.description },
    )

    private val GUIDE_SUMMARIES: Map<String, String> = mapOf(
        COMPOSE_TOPIC to "The canvas compose format: kinds, caps, markdown, errors, an example.",
        "scene" to "The drawing (scene) format used by canvas replace-scene and apply-ops elements.",
        "ops" to "The canvas apply-ops operations.",
    )
}
