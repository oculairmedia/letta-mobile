package com.letta.mobile.data.chat.projection.meridian

import com.letta.mobile.data.model.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
                name == META_TOOL -> arguments.toJsonObjectOrNull()?.let(::fromMetaTool)
                // Cheap reject first: most shell calls never mention meridian.
                name.lowercase() !in SHELL_TOOLS -> null
                META_TOOL !in arguments -> null
                else -> arguments.toJsonObjectOrNull()?.let(::shellScript)?.let { MeridianInvocation.find(it) }?.toCall(inputOverride = null)
            }
        }

        /** [call] read as its native tool when it is a `meridian` call, else [call] itself. */
        fun canonical(call: ToolCall): ToolCall = parse(call)?.applyTo(call) ?: call

        /**
         * The tool's result JSON out of a Bash return: the CLI prints the native result on stdout,
         * but a shell tool may frame it (an exit-code line, stderr hints). Returns the JSON object
         * text when one is found, else [raw] unchanged.
         */
        fun stdoutJson(raw: String?): String? = when {
            raw == null -> null
            raw.toStrictJsonObjectOrNull() != null -> raw
            else -> raw.trim().firstJsonObject() ?: raw
        }

        /** Whether a CLI result says it failed: `{"error": …}` or `{"ok": false}`. */
        fun isErrorResult(json: String?): Boolean {
            val body = json?.toStrictJsonObjectOrNull() ?: return false
            return when ((body["ok"] as? JsonPrimitive)?.booleanOrNull) {
                false -> true
                true -> false
                null -> body["error"].isPresent()
            }
        }

        private fun JsonElement?.isPresent(): Boolean = this != null && this !is JsonNull

        /** The meta-tool's `{command, input}`: the command with or without `meridian`, a string or an argv. */
        private fun fromMetaTool(args: JsonObject): MeridianCommandCall? {
            val script = metaCommand(args["command"] ?: args["argv"]) ?: return null
            val prefixed = if (script.trimStart().startsWith(META_TOOL)) script else "$META_TOOL $script"
            return MeridianInvocation.find(prefixed)?.toCall(inputOverride = metaInput(args["input"] ?: args["stdin"]))
        }

        private fun metaCommand(command: JsonElement?): String? = when (command) {
            is JsonArray -> command.argvWords().asShellScript()
            is JsonPrimitive -> command.content.takeIf { command.isString }
            else -> null
        }

        /** The input as an object (sent as JSON) or as the text the agent wrote. */
        private fun metaInput(input: JsonElement?): String? = when (input) {
            is JsonObject -> input.toString()
            is JsonPrimitive -> input.content.takeIf { input.isString }
            else -> null
        }

        /** The script a shell tool runs: `command` / `cmd`, a string or an argv (`["bash","-lc",…]`). */
        private fun shellScript(args: JsonObject): String? = when (val command = args["command"] ?: args["cmd"]) {
            is JsonPrimitive -> command.content.takeIf { command.isString }
            is JsonArray -> argvScript(command.argvWords())
            else -> null
        }

        /** An argv's script: the `<script>` of `["bash", "-lc", "<script>"]`, else the words joined. */
        private fun argvScript(argv: List<String>): String? {
            if (argv.isEmpty()) return null
            val shell = argv[0].isShellProgram()
            val scriptFlag = argv.getOrNull(1)?.isShellScriptFlag() == true
            return argv.getOrNull(2)?.takeIf { shell && scriptFlag } ?: argv.asShellScript()
        }

        /** Shell-tool names across runtimes (letta-code `Bash`, Codex-style `exec_command`, …). */
        private val SHELL_TOOLS = setOf(
            "bash", "shell", "run_shell_command", "shell_command", "local_shell",
            "exec_command", "functions.exec_command",
        )
    }
}
