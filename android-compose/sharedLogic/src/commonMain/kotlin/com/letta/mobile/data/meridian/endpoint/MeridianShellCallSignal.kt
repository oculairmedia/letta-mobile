package com.letta.mobile.data.meridian.endpoint

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.canonicalToolReturn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What one relayed App Server frame says about a shell tool call that runs `meridian`
 * (letta-mobile-jna0o.4, live-call binding).
 *
 * letta-code announces every client-side tool it is about to execute with a `client_tool_start`
 * stream delta carrying `tool_call_id`, `tool_name` and the approved `tool_args`, and closes it with
 * `client_tool_end` (also on abort). The `tool_return_message` and the runtime's `turn_finished`
 * close it too, in case an end delta is lost. Subagent frames (`subagent_id`) are ignored: a
 * subagent's shell does not run in the parent conversation's scope.
 */
sealed interface MeridianShellCallSignal {
    /** A shell call whose command runs `meridian` started executing in [scope]. */
    data class Started(val scope: AppServerRuntimeScope, val toolCallId: String) : MeridianShellCallSignal

    /** The call [toolCallId] finished, failed or was aborted. */
    data class Ended(val toolCallId: String) : MeridianShellCallSignal

    /** The turn in [scope] ended: nothing in it is still running. */
    data class TurnEnded(val scope: AppServerRuntimeScope) : MeridianShellCallSignal

    companion object {
        /** letta-code's shell tool names (canonical and per-toolset spellings). */
        val SHELL_TOOLS: Set<String> = setOf(
            "Bash", "shell", "shell_command", "exec_command", "run_shell_command", "RunShellCommand",
        )

        private val MERIDIAN_WORD = Regex("""(?:^|[\s/;&|(`$])meridian(?:$|[\s;&|)])""")
        private val json = Json { ignoreUnknownKeys = true }

        /** The signal [received] carries, or null when it says nothing about meridian shell calls. */
        fun of(received: AppServerReceivedFrame): MeridianShellCallSignal? = when (val frame = received.frame) {
            is AppServerInboundFrame.TurnFinished -> TurnEnded(frame.runtime)
            is AppServerInboundFrame.StreamDelta ->
                if (frame.subagentId != null) null else ofDelta(frame.runtime, frame.delta as? JsonObject)
            else -> null
        }

        /** True when [command] runs the `meridian` program (as a word, any path prefix). */
        fun runsMeridian(command: String): Boolean = MERIDIAN_WORD.containsMatchIn(command)

        private fun ofDelta(scope: AppServerRuntimeScope, delta: JsonObject?): MeridianShellCallSignal? {
            delta ?: return null
            return when (delta.string("message_type")) {
                "client_tool_start" -> started(scope, delta)
                "client_tool_end" -> delta.string("tool_call_id")?.let(::Ended)
                "tool_return_message" -> canonicalToolReturn(delta).toolCallId.takeIf { it.isNotBlank() }?.let(::Ended)
                else -> null
            }
        }

        private fun started(scope: AppServerRuntimeScope, delta: JsonObject): Started? {
            val id = delta.string("tool_call_id") ?: return null
            if (delta.string("tool_name") !in SHELL_TOOLS) return null
            val command = shellCommand(delta["tool_args"]) ?: return null
            return if (runsMeridian(command)) Started(scope, id) else null
        }

        /** `command` / `cmd` from a shell tool's args (an object, or the JSON text of one). */
        fun shellCommand(args: JsonElement?): String? {
            val obj = when (args) {
                is JsonObject -> args
                is JsonPrimitive -> if (args.isString) parseObject(args.content) else null
                else -> null
            } ?: return null
            return commandText(obj["command"]) ?: commandText(obj["cmd"])
        }

        private fun commandText(element: JsonElement?): String? = when (element) {
            is JsonPrimitive -> element.takeIf { it.isString }?.content
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.joinToString(" ")
            else -> null
        }

        private fun parseObject(text: String): JsonObject? = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
