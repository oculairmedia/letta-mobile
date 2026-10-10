package com.letta.mobile.data.chat.projection.meridian

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
        if (args.any { it in HELP_WORDS } || args.firstOrNull() != CANVAS) return null
        val verb = args.getOrNull(1)?.lowercase()?.replace('_', '-') ?: return null
        val flags = MeridianFlags(args.drop(2))
        val tool = flags.nativeToolFor(verb) ?: return null
        val input = inputOverride ?: flags.input ?: stdin
        return MeridianCommandCall(toolName = tool, arguments = arguments(input, flags.fields))
    }

    /** The input object with the flags merged in; the raw input when it is not a JSON object. */
    private fun arguments(input: String?, fields: Map<String, JsonElement>): String {
        val body = input?.takeIf { it.isNotBlank() }
        val parsed = body?.toStrictJsonObjectOrNull()
        if (body != null && parsed == null) return body
        val merged = LinkedHashMap<String, JsonElement>(parsed.orEmpty())
        fields.forEach { (key, value) -> merged.getOrPut(key) { value } }
        return JsonObject(merged).toString()
    }

    companion object {
        /**
         * The single `meridian` invocation of [script], looking inside `bash -lc '…'` wrappers;
         * null when there is none, more than one, or the script cannot be read.
         */
        fun find(script: String): MeridianInvocation? = Locator().find(script)
    }

    /** Walks a script's commands, descending into shell wrappers up to [MAX_WRAP_DEPTH] deep. */
    private class Locator {
        private var depth = 0

        fun find(script: String): MeridianInvocation? {
            val commands = ShellScript.scan(script) ?: return null
            val previous = listOf(null) + commands
            return commands.zip(previous).mapNotNull { (command, before) -> at(command, before) }.singleOrNull()
        }

        private fun at(command: ShellCommand, before: ShellCommand?): MeridianInvocation? {
            val words = command.programWords()
            val program = words.firstOrNull() ?: return null
            return when {
                program.isShellProgram() -> wrapped(words)
                program.substringAfterLast('/') == MeridianCommandCall.META_TOOL ->
                    MeridianInvocation(words.drop(1), command.stdinAfter(before))
                else -> null
            }
        }

        /** `bash -lc '<script>'`: the script inside, read once more. */
        private fun wrapped(words: List<String>): MeridianInvocation? {
            val inner = words.getOrNull(2)?.takeIf { words[1].isShellScriptFlag() } ?: return null
            if (depth >= MAX_WRAP_DEPTH) return null
            depth++
            return find(inner).also { depth-- }
        }
    }
}

/** The command's words from its program on: `NAME=value`, `env`, `exec`, … dropped. */
private fun ShellCommand.programWords(): List<String> = words.dropWhile { it.isPrefixWord() }

private fun String.isPrefixWord(): Boolean = ENV_ASSIGNMENT.matches(this) || this in PREFIX_PROGRAMS

/** This command's stdin: its own heredoc or here-string, else what [before] pipes into it. */
private fun ShellCommand.stdinAfter(before: ShellCommand?): String? = when {
    stdin != null -> stdin
    stdinFromFile || !piped -> null
    else -> before?.producedText()
}

/** What `echo …`, `printf …` or `cat <<EOF` writes into a pipe; null for anything else. */
private fun ShellCommand.producedText(): String? {
    val words = programWords()
    return when (words.firstOrNull()) {
        "echo" -> words.drop(1).dropWhile { it in ECHO_FLAGS }.joinToString(" ")
        "printf" -> words.drop(1).lastOrNull()
        "cat" -> stdin.takeIf { words.size == 1 }
        else -> null
    }
}

private const val CANVAS = "canvas"
private const val MAX_WRAP_DEPTH = 2
private val HELP_WORDS = setOf("--help", "-h", "help")
private val ECHO_FLAGS = setOf("-n", "-e", "-E", "-ne", "-en")
private val PREFIX_PROGRAMS = setOf("env", "exec", "command", "time", "nohup")
private val ENV_ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*", RegexOption.DOT_MATCHES_ALL)
