package com.letta.mobile.data.chat.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reading helpers every presenter shares, so no two of them parse a call differently. */
object ToolArguments {
    /**
     * The human-meaningful payload of [arguments] (the command, code, query…), else its arguments as
     * `key: value` lines, else the raw text: what an approval card or a generic step shows.
     */
    fun primary(arguments: String): String {
        val facts = ToolCallFacts("", arguments, null)
        val obj = facts.arguments ?: return arguments
        return PRIMARY_KEYS.firstNotNullOfOrNull(facts::string)
            ?: fields(obj).joinToString("\n") { "${it.key}: ${it.value}" }
    }

    /** The one-line target of [call]: the first line of its preferred argument, or blank. */
    fun target(call: ToolCallFacts): String =
        TARGET_KEYS.firstNotNullOfOrNull(call::string)?.let(::firstLine).orEmpty()

    /** The first non-blank line of [text], capped so a step line never measures a whole script. */
    fun firstLine(text: String): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(TARGET_MAX)

    /** [obj] flattened for reading: strings as written, anything else as compact JSON. */
    fun fields(obj: JsonObject): List<ToolArgumentField> =
        obj.mapNotNull { (key, value) -> readable(value)?.let { ToolArgumentField(key, it) } }

    /**
     * The file a call names (`file_path`, `path`…), as a working-directory file preview. Only the
     * memory presenters open the agent's memory; a call no presenter knows names a file.
     */
    fun pathPreview(call: ToolCallFacts): ToolPreviewTarget? {
        val path = PATH_KEYS.firstNotNullOfOrNull(call::string)?.takeIf { '\n' !in it } ?: return null
        return ToolPreviewTarget.File(path)
    }

    /**
     * A non-zero exit code a result reports (`Exit code: 1`, `exited with code 2`), or null. Only the
     * result's last [EXIT_SCAN_CHARS] characters are read, where shells report it, so a step line never
     * scans a whole output.
     */
    fun exitFailure(result: String?): ToolFailure? {
        val reported = result?.takeLast(EXIT_SCAN_CHARS)?.let { EXIT_CODE.findAll(it).lastOrNull() } ?: return null
        val code = reported.groupValues[1].toIntOrNull() ?: return null
        return ToolFailure(code).takeIf { code != 0 }
    }

    private fun readable(value: JsonElement): String? = when (value) {
        is JsonNull -> null
        is JsonPrimitive -> value.content
        is JsonArray, is JsonObject -> value.toString()
    }

    private const val TARGET_MAX = 200

    const val EXIT_SCAN_CHARS: Int = 512

    private val PRIMARY_KEYS = listOf("command", "code", "query", "input", "text", "content", "cmd", "script", "expression")

    private val TARGET_KEYS = listOf("command", "cmd", "file_path", "path", "pattern", "query", "url") + PRIMARY_KEYS

    private val PATH_KEYS = listOf("file_path", "filePath", "notebook_path", "target_file", "path")

    private val EXIT_CODE = Regex("""(?i)(?:exit code:?|exited with code)\s*(-?\d+)""")
}
