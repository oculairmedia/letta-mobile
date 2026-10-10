package com.letta.mobile.data.chat.projection.meridian

import com.letta.mobile.data.canvas.CanvasToolContract
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one `meridian canvas <verb> …` command of a script, with the input its stdin carries
 * (letta-mobile-jna0o.6). [find] reads the script; [toCall] names the native canvas tool.
 */
internal class MeridianInvocation private constructor(
    /** The words after `meridian`. */
    private val args: List<String>,
    /** The JSON input from a heredoc, here-string or `echo … |`, or null when it is not in the script. */
    private val stdin: String?,
) {
    fun toCall(inputOverride: String?): MeridianCommandCall? {
        if (args.any { it in HELP_WORDS }) return null
        if (args.firstOrNull() != CANVAS) return null
        val verb = args.getOrNull(1)?.lowercase()?.replace('_', '-') ?: return null
        val flags = Flags.read(args.drop(2))
        val tool = toolFor(verb, flags.positionals) ?: return null
        val input = inputOverride ?: flags.input ?: stdin
        return MeridianCommandCall(toolName = tool, arguments = arguments(input, flags.fields))
    }

    /** The input object with the flags merged in; the raw input when it is not a JSON object. */
    private fun arguments(input: String?, fields: Map<String, JsonElement>): String {
        val body = input?.takeIf { it.isNotBlank() }
        val parsed = body?.let { parseJsonObject(it, strict = true) }
        if (body != null && parsed == null) return body
        val merged = LinkedHashMap<String, JsonElement>(parsed.orEmpty())
        fields.forEach { (key, value) -> if (key !in merged) merged[key] = value }
        return JsonObject(merged).toString()
    }

    companion object {
        /**
         * The single `meridian` invocation of [script], looking inside `bash -lc '…'` wrappers;
         * null when there is none, more than one, or the script cannot be read.
         */
        fun find(script: String, depth: Int = 0): MeridianInvocation? {
            val commands = ShellScript.scan(script) ?: return null
            val found = commands.indices.mapNotNull { index -> invocationAt(commands, index, depth) }
            return found.singleOrNull()
        }

        private fun invocationAt(commands: List<ShellCommand>, index: Int, depth: Int): MeridianInvocation? {
            val command = commands[index]
            val words = command.words.dropWhile(::isPrefixWord)
            val program = words.firstOrNull() ?: return null
            if (isShell(program)) return wrapped(words, depth)
            if (program.substringAfterLast('/') != MeridianCommandCall.META_TOOL) return null
            return MeridianInvocation(words.drop(1), stdinOf(commands, index))
        }

        /** `bash -lc '<script>'`: the script inside, read once more. */
        private fun wrapped(words: List<String>, depth: Int): MeridianInvocation? {
            if (depth >= MAX_WRAP_DEPTH) return null
            val flag = words.getOrNull(1) ?: return null
            if (!flag.startsWith("-") || 'c' !in flag) return null
            return words.getOrNull(2)?.let { find(it, depth + 1) }
        }

        private fun stdinOf(commands: List<ShellCommand>, index: Int): String? {
            val command = commands[index]
            return when {
                command.stdin != null -> command.stdin
                command.stdinFromFile || !command.piped -> null
                else -> commands.getOrNull(index - 1)?.let(::producedText)
            }
        }

        /** What `echo …`, `printf …` or `cat <<EOF` writes into a pipe; null for anything else. */
        private fun producedText(command: ShellCommand): String? {
            val words = command.words.dropWhile(::isPrefixWord)
            return when (words.firstOrNull()) {
                "echo" -> words.drop(1).dropWhile { it in ECHO_FLAGS }.joinToString(" ")
                "printf" -> words.drop(1).lastOrNull()
                "cat" -> command.stdin.takeIf { words.size == 1 }
                else -> null
            }
        }

        /** `NAME=value`, and `env` / `exec` / `command` / `time` before the program. */
        private fun isPrefixWord(word: String): Boolean = ENV_ASSIGNMENT.matches(word) || word in PREFIX_PROGRAMS

        private fun toolFor(verb: String, positionals: List<String>): String? = when (verb) {
            "guide" -> CanvasToolContract.COMPOSE_GUIDE.takeIf { positionals.firstOrNull()?.let { it == "compose" } ?: true }
            else -> VERB_TOOLS[verb]
        }

        private const val CANVAS = "canvas"
        private const val MAX_WRAP_DEPTH = 2
        private val HELP_WORDS = setOf("--help", "-h", "help")
        private val ECHO_FLAGS = setOf("-n", "-e", "-E", "-ne", "-en")
        private val PREFIX_PROGRAMS = setOf("env", "exec", "command", "time", "nohup")
        private val ENV_ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*", RegexOption.DOT_MATCHES_ALL)

        /** The CLI verbs (design doc, "CLI surface") and the native tools they run. */
        private val VERB_TOOLS = mapOf(
            "compose" to CanvasToolContract.COMPOSE,
            "layout" to CanvasToolContract.GET_LAYOUT,
            "get-layout" to CanvasToolContract.GET_LAYOUT,
            "scene" to CanvasToolContract.GET_SCENE,
            "get-scene" to CanvasToolContract.GET_SCENE,
            "apply-ops" to CanvasToolContract.APPLY_OPS,
            "replace-scene" to CanvasToolContract.REPLACE_SCENE,
            "list" to CanvasToolContract.LIST,
            "create" to CanvasToolContract.CREATE,
            "preview" to CanvasToolContract.RENDER_PREVIEW,
            "render-preview" to CanvasToolContract.RENDER_PREVIEW,
            "export-svg" to CanvasToolContract.EXPORT_SVG,
        )
    }
}

/** The flags after the verb: native argument fields, an inline input, and the rest. */
private class Flags private constructor(
    val fields: Map<String, JsonElement>,
    val input: String?,
    val positionals: List<String>,
) {
    companion object {
        fun read(words: List<String>): Flags {
            val fields = LinkedHashMap<String, JsonElement>()
            val positionals = mutableListOf<String>()
            var input: String? = null
            var index = 0
            while (index < words.size) {
                val (name, inline) = split(words[index])
                index++
                if (name == null) {
                    positionals += words[index - 1]
                    continue
                }
                val takesValue = name in VALUE_FLAGS || name in INPUT_FLAGS || name in FILE_FLAGS
                val value = inline ?: if (takesValue) words.getOrNull(index)?.also { index++ } else null
                when {
                    name in BOOLEAN_FLAGS -> fields[BOOLEAN_FLAGS.getValue(name)] = JsonPrimitive(inline?.toBooleanStrictOrNull() ?: true)
                    name in INPUT_FLAGS -> input = value
                    name in VALUE_FLAGS && value != null -> fields[VALUE_FLAGS.getValue(name)] = fieldValue(name, value)
                }
            }
            return Flags(fields, input, positionals)
        }

        /** `--name=value` / `--name` into (name, inline value); (null, null) for a positional. */
        private fun split(word: String): Pair<String?, String?> {
            if (!word.startsWith("-") || word == "-") return null to null
            val name = word.trimStart('-')
            val eq = name.indexOf('=')
            return if (eq < 0) name to null else name.substring(0, eq) to name.substring(eq + 1)
        }

        private fun fieldValue(flag: String, value: String): JsonElement =
            if (flag == "limit") value.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value) else JsonPrimitive(value)

        private val BOOLEAN_FLAGS = mapOf("dry-run" to "dry_run", "dry_run" to "dry_run")
        private val VALUE_FLAGS = mapOf(
            "canvas" to "canvas_id", "canvas-id" to "canvas_id", "canvas_id" to "canvas_id",
            "cursor" to "cursor", "limit" to "limit", "title" to "title",
        )
        private val INPUT_FLAGS = setOf("input", "json")
        private val FILE_FLAGS = setOf("input-file", "file", "f")
    }
}
