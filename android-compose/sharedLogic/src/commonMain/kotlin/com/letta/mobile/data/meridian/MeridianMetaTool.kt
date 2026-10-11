package com.letta.mobile.data.meridian

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The `meridian` meta-tool (letta-mobile-jna0o.8; design section 4, front door B): one small tool
 * in place of every canvas, agent and plugin tool, which reaches them all through
 * [MeridianCommandRouter]. Like `device_action`, `--help` lists the commands and
 * `<command> --help` gives one's input, so the reference text is fetched only when needed.
 *
 * The caller is the scope the App Server stamped on this call, passed through unchanged: the model
 * cannot choose it. No Bash and no approval prompt are involved.
 *
 * A command that succeeds answers its stdout: a tool's result byte for byte, or help text. One that
 * fails answers [ExternalToolResult.Error] with the structured error JSON, and the hint after it.
 */
class MeridianMetaTool(private val router: () -> MeridianCommandRouter) : HostExternalTool {
    override val name: String = MeridianCommandCatalog.META_TOOL_NAME
    override val description: String = DESCRIPTION
    override val inputSchema: JsonObject = SCHEMA

    /** Unused for a [HostExternalTool], which is advertised regardless; required by the interface. */
    override val capability: Capability = Capability.ImageHydration

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult =
        invoke(input, ExternalToolCaller(agentId))

    override suspend fun invoke(input: JsonObject, caller: ExternalToolCaller): ExternalToolResult {
        val command = (input[COMMAND] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return ExternalToolResult.Error(MISSING_COMMAND)
        val toolInput = input[INPUT] as? JsonObject
        if (input[INPUT] != null && toolInput == null) return ExternalToolResult.Error(INPUT_NOT_OBJECT)
        val response = router().execute(command, toolInput, caller)
        return if (response.ok) ExternalToolResult.Success(response.stdout) else ExternalToolResult.Error(errorText(response))
    }

    private fun errorText(response: MeridianResponse): String =
        if (response.stderr.isBlank()) response.stdout else response.stdout + "\n" + response.stderr.trimEnd()

    companion object {
        const val COMMAND = "command"
        const val INPUT = "input"

        /** Kept short on purpose: it rides on every LLM step (budget: MeridianMetaToolTokenBudgetTest). */
        const val DESCRIPTION =
            "Host commands: canvas, agents, plugins. command is argv, e.g. \"canvas compose\"; input is its JSON. " +
                "Run \"--help\" first."

        private val MISSING_COMMAND =
            MeridianError(MeridianErrorCode.USAGE, "command is required, e.g. \"--help\"").toJson().toString()

        private val INPUT_NOT_OBJECT =
            MeridianError(MeridianErrorCode.INVALID_JSON, "input must be one JSON object", pointer = "/input").toJson().toString()

        val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject(COMMAND) { put("type", "string") }
                putJsonObject(INPUT) { put("type", "object") }
            }
            put("required", buildJsonArray { add(JsonPrimitive(COMMAND)) })
            put("additionalProperties", false)
        }
    }
}
