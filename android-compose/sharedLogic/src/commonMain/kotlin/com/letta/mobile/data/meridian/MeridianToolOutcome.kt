package com.letta.mobile.data.meridian

import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How a tool's answer is printed (letta-mobile-jna0o.3). A success is the tool's result string,
 * byte for byte, exactly what the native tool call returns. A refusal is a structured error: the
 * tool's own JSON refusal (canvas_compose's `{code, problems, hint}`) under `detail`, any other
 * message under `message`; a refusal that is about who is calling exits [MeridianExit.DENIED].
 */
internal object MeridianToolOutcome {
    fun response(command: MeridianCommand, result: ExternalToolResult, limits: MeridianLimits): MeridianResponse =
        when (result) {
            is ExternalToolResult.Success -> success(command, result.content, limits)
            is ExternalToolResult.Error -> refusal(command, result.error).toResponse()
        }

    private fun success(command: MeridianCommand, content: String, limits: MeridianLimits): MeridianResponse {
        val size = content.encodeToByteArray().size
        if (size > limits.maxOutputBytes) {
            return MeridianError(
                MeridianErrorCode.OUTPUT_TOO_LARGE,
                "the result is $size bytes; at most ${limits.maxOutputBytes}",
                command = command.display,
                hint = "Ask for less: a canvas id, a --limit, or a --cursor page.",
            ).toResponse()
        }
        return MeridianResponse(MeridianExit.OK, content)
    }

    fun refusal(command: MeridianCommand, message: String): MeridianError {
        val detail = structured(message)
        val denied = isDenied(message, detail)
        return MeridianError(
            code = if (denied) MeridianErrorCode.DENIED else MeridianErrorCode.REFUSED,
            message = if (detail != null) "${command.toolName} refused the input" else message,
            command = command.display,
            detail = detail,
        )
    }

    private fun structured(message: String): JsonObject? {
        if (!message.trimStart().startsWith("{")) return null
        return try {
            Json.parseToJsonElement(message) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
    }

    /**
     * The tools' own words for a caller problem: the canvas ACL ("Unauthorized: actor ..."), a call
     * with no agent identity, and canvas_compose's UNAUTHORIZED code.
     */
    private fun isDenied(message: String, detail: JsonObject?): Boolean =
        message.startsWith(UNAUTHORIZED) ||
            IDENTITY_REQUIRED in message ||
            (detail?.get("code") as? JsonPrimitive)?.content == UNAUTHORIZED_CODE

    private const val UNAUTHORIZED = "Unauthorized"
    private const val UNAUTHORIZED_CODE = "UNAUTHORIZED"
    private const val IDENTITY_REQUIRED = "require an authenticated agent identity"
}
