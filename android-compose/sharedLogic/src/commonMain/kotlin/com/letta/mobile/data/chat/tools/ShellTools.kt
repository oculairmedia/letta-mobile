package com.letta.mobile.data.chat.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How a shell tool call reads, across runtimes (letta-code `Bash`, Codex-style `exec_command`,
 * Gemini's `run_shell_command`, …): which tools are shells, the script a call runs, and a
 * command's program words. Every reader of shell calls (the step presenters, the meridian
 * recogniser) goes through here, so they agree on what ran.
 */
object ShellTools {
    /** Shell-tool names, lower-cased. */
    val NAMES: Set<String> = setOf(
        "bash", "shell", "run_shell_command", "shell_command", "local_shell",
        "exec_command", "functions.exec_command",
    )

    fun isShellTool(name: String): Boolean = name.lowercase() in NAMES

    /** The script a shell call runs: `command` / `cmd`, a string or an argv (`["bash","-lc",…]`). */
    fun scriptOf(args: JsonObject): String? = when (val command = args["command"] ?: args["cmd"]) {
        is JsonPrimitive -> command.content.takeIf { command.isString }
        is JsonArray -> argvScript(command.argvWords())
        else -> null
    }

    /** An argv's script: the `<script>` of `["bash", "-lc", "<script>"]`, else the words joined. */
    fun argvScript(argv: List<String>): String? {
        if (argv.isEmpty()) return null
        val shell = argv[0].isShellProgram()
        val scriptFlag = argv.getOrNull(1)?.isShellScriptFlag() == true
        return argv.getOrNull(2)?.takeIf { shell && scriptFlag } ?: argv.asShellScript()
    }

    /**
     * The commands [script] runs, in order, each from its program on, looking inside
     * `bash -lc '…'` wrappers up to [MAX_WRAP_DEPTH] deep; empty when it cannot be read.
     */
    fun commandsOf(script: String): List<ShellCommandLine> = commandsOf(script, depth = 0)

    private fun commandsOf(script: String, depth: Int): List<ShellCommandLine> =
        ShellScript.scan(script).orEmpty().flatMap { command ->
            val words = command.programWords()
            val inner = words.getOrNull(2)?.takeIf { words[0].isShellProgram() && words[1].isShellScriptFlag() }
            when {
                words.isEmpty() -> emptyList()
                inner != null && depth < MAX_WRAP_DEPTH -> commandsOf(inner, depth + 1)
                else -> listOf(ShellCommandLine(words, command.stdin))
            }
        }

    /**
     * A shell call's inset: `dir $ command`, then its output, which reads as stderr when the command
     * exited non-zero. The output is the call's result as it is, never copied.
     */
    fun commandInset(call: ToolCallFacts): ToolInset? {
        val script = call.arguments?.let(::scriptOf).orEmpty()
        val result = call.result?.takeIf { it.isNotBlank() }
        if (script.isEmpty() && result == null) return null
        val kind = if (ToolArguments.exitFailure(result) != null) ToolOutputStream.Kind.Stderr else ToolOutputStream.Kind.Output
        return ToolInset.Command(
            workingDirectory = WORKDIR_KEYS.firstNotNullOfOrNull(call::string),
            command = script,
            output = listOfNotNull(result?.let { ToolOutputStream(kind, it) }),
        )
    }

    private const val MAX_WRAP_DEPTH = 2

    private val WORKDIR_KEYS = listOf("workdir", "cwd", "directory", "dir_path")
}

/** The command's words from its program on: `NAME=value`, `env`, `exec`, … dropped. */
fun ShellCommand.programWords(): List<String> = words.dropWhile { it.isPrefixWord() }

/** Whether this word names a shell (`bash`, `/bin/sh`, …). */
fun String.isShellProgram(): Boolean = substringAfterLast('/') in SHELLS

/** Whether this word is a shell's run-a-script flag (`-c`, `-lc`, `-ec`). */
fun String.isShellScriptFlag(): Boolean = SCRIPT_FLAG.matches(this)

/** This word single-quoted when it needs it, so an argv re-reads as the same words. */
fun String.shellQuoted(): String = if (SAFE_WORD.matches(this)) this else "'" + replace("'", "'\\''") + "'"

/** An argv's words as one script. */
fun List<String>.asShellScript(): String = joinToString(" ") { it.shellQuoted() }

/** The string elements of a JSON array argv. */
fun List<JsonElement>.argvWords(): List<String> = mapNotNull { (it as? JsonPrimitive)?.content }

private fun String.isPrefixWord(): Boolean = ENV_ASSIGNMENT.matches(this) || this in PREFIX_PROGRAMS

private val SHELLS = setOf("bash", "sh", "zsh", "dash")
private val SCRIPT_FLAG = Regex("-[a-z]*c[a-z]*")
private val SAFE_WORD = Regex("[A-Za-z0-9_./=:,@%+-]+")
private val PREFIX_PROGRAMS = setOf("env", "exec", "command", "time", "nohup")
private val ENV_ASSIGNMENT = Regex("""[A-Za-z_][A-Za-z0-9_]*=[\s\S]*""")
