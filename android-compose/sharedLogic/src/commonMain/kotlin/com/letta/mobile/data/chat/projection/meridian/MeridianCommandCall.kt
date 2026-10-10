package com.letta.mobile.data.chat.projection.meridian

import com.letta.mobile.data.model.ToolCall
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * letta-mobile-jna0o.6: a `meridian` CLI call read as the canvas tool it runs
 * (docs/design/on-demand-tools-via-meridian-cli.md, "Results, receipts and timeline rendering").
 *
 * An agent reaches the canvas either through native `canvas_*` tools, or by running
 * `meridian canvas <verb>` from its Bash tool (front door A), or through the single `meridian`
 * meta-tool `{command, input}` (front door B). [parse] recognises the last two and names the
 * native tool and the arguments it would have been called with, so the timeline projects the
 * call exactly as it projects the native one: the same row, the same compose receipt.
 *
 * Parsing is lenient and never throws. Anything it cannot read with confidence (an unterminated
 * quote, two `meridian` invocations in one script, a help or schema command, a verb with no
 * native tool) is not a [MeridianCommandCall], and the call keeps its ordinary Bash row.
 */
data class MeridianCommandCall(
    /** The native tool this call runs, e.g. `canvas_compose`. */
    val toolName: String,
    /**
     * The arguments the native tool would have received: the JSON input object (compact) merged
     * with the flags, else the raw input text when it is not a JSON object. Never the shell
     * script, so env prefixes and other commands in it stay out of the row.
     */
    val arguments: String,
) {
    /** [call] with this call's native name and arguments, keeping its id. */
    fun applyTo(call: ToolCall): ToolCall = call.copy(name = toolName, arguments = arguments)

    companion object {
        /** The meta-tool's name (front door B). */
        const val META_TOOL: String = "meridian"

        fun parse(call: ToolCall): MeridianCommandCall? = parse(call.name, call.arguments)

        fun parse(name: String?, arguments: String?): MeridianCommandCall? {
            if (name == null || arguments.isNullOrBlank()) return null
            return when {
                name == META_TOOL -> parseJsonObject(arguments)?.let(::fromMetaTool)
                // Cheap reject first: most shell calls never mention meridian.
                name.lowercase() in SHELL_TOOLS && META_TOOL in arguments ->
                    parseJsonObject(arguments)?.let(::shellScript)?.let(::fromScript)
                else -> null
            }
        }

        /** [call] read as its native tool when it is a `meridian` call, else [call] itself. */
        fun canonical(call: ToolCall): ToolCall = parse(call)?.applyTo(call) ?: call

        /**
         * The tool's result JSON out of a Bash return: the CLI prints the native result on stdout,
         * but a shell tool may frame it (an exit-code line, stderr hints). Returns the JSON object
         * text when one is found, else [raw] unchanged.
         */
        fun stdoutJson(raw: String?): String? {
            if (raw == null) return null
            val trimmed = raw.trim()
            if (parseJsonObject(trimmed, strict = true) != null) return raw
            val end = trimmed.lastIndexOf('}')
            if (end < 0) return raw
            return objectStarts(trimmed).take(MAX_STDOUT_PROBES)
                .map { start -> trimmed.substring(start, end + 1) }
                .firstOrNull { parseJsonObject(it, strict = true) != null } ?: raw
        }

        /** Whether a CLI result says it failed: `{"error": …}` or `{"ok": false}`. */
        fun isErrorResult(json: String?): Boolean {
            val body = json?.let { parseJsonObject(it, strict = true) } ?: return false
            val ok = (body["ok"] as? JsonPrimitive)?.booleanOrNull
            return ok == false || (ok == null && body["error"].let { it != null && it !is JsonNull })
        }

        private fun fromMetaTool(args: JsonObject): MeridianCommandCall? {
            val command = args["command"] ?: args["argv"] ?: return null
            val script = when (command) {
                is JsonArray -> command.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(" ") { quote(it) }
                is JsonPrimitive -> command.content
                else -> return null
            }
            val prefixed = if (script.trimStart().startsWith(META_TOOL)) script else "$META_TOOL $script"
            val invocation = MeridianInvocation.find(prefixed) ?: return null
            val input = when (val value = args["input"] ?: args["stdin"]) {
                is JsonObject -> value.toString()
                is JsonPrimitive -> value.content.takeIf { value.isString }
                else -> null
            }
            return invocation.toCall(inputOverride = input)
        }

        private fun fromScript(script: String): MeridianCommandCall? =
            MeridianInvocation.find(script)?.toCall(inputOverride = null)

        /** The script a shell tool runs: `command` / `cmd`, a string or an argv (`["bash","-lc",…]`). */
        private fun shellScript(args: JsonObject): String? {
            val command = args["command"] ?: args["cmd"] ?: return null
            return when (command) {
                is JsonPrimitive -> command.content.takeIf { command.isString }
                is JsonArray -> argvScript(command.mapNotNull { (it as? JsonPrimitive)?.content })
                else -> null
            }
        }

        private fun argvScript(argv: List<String>): String? {
            if (argv.isEmpty()) return null
            val shellWrapped = argv.size >= SHELL_ARGV_SIZE && isShell(argv[0]) && argv[1].startsWith("-") && 'c' in argv[1]
            return if (shellWrapped) argv[2] else argv.joinToString(" ") { quote(it) }
        }

        private fun objectStarts(text: String): Sequence<Int> =
            text.indices.asSequence().filter { text[it] == '{' && (it == 0 || text[it - 1] == '\n') }

        private const val MAX_STDOUT_PROBES = 4
        private const val SHELL_ARGV_SIZE = 3

        /** Shell-tool names across runtimes (letta-code `Bash`, Codex-style `exec_command`, …). */
        private val SHELL_TOOLS = setOf(
            "bash", "shell", "run_shell_command", "shell_command", "local_shell",
            "exec_command", "functions.exec_command",
        )
    }
}

internal fun isShell(word: String): Boolean = word.substringAfterLast('/') in SHELLS

private val SHELLS = setOf("bash", "sh", "zsh", "dash")

/** [word] single-quoted when it needs it, so an argv re-reads as the same words. */
private fun quote(word: String): String =
    if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in SAFE_CHARS }) word else "'" + word.replace("'", "'\\''") + "'"

private const val SAFE_CHARS = "-_./=:,@%+"

internal fun parseJsonObject(text: String, strict: Boolean = false): JsonObject? = try {
    (if (strict) StrictJson else LenientJson).parseToJsonElement(text.trim()) as? JsonObject
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

private val LenientJson = Json { isLenient = true }
private val StrictJson = Json
