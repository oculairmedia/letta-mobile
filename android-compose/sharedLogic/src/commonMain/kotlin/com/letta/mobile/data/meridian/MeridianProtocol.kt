package com.letta.mobile.data.meridian

import com.letta.mobile.data.controller.extras.ExternalToolCaller
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One call on the Meridian command surface (letta-mobile-jna0o.3), whichever front door it came
 * through: the `meridian` shim in an agent's shell (argv, stdin and the caller scope the wrapper
 * bound to the call) or the `meridian` meta-tool (its `command` split into [argv], its `input` as
 * [stdin], and the scope the App Server stamped).
 *
 * [stdin] is the JSON input, or null/blank when there is none. JSON never travels in argv: flags
 * carry only scalars, so a shell cannot mangle a document.
 */
data class MeridianRequest(
    val argv: List<String>,
    val stdin: String? = null,
    val caller: ExternalToolCaller = ExternalToolCaller(agentId = null),
)

/**
 * What the front door prints: [stdout] (a tool's result JSON byte for byte, a structured
 * `{"error": ...}` object, or help text), [stderr] (human hints only) and the process [exitCode]
 * ([MeridianExit]).
 */
data class MeridianResponse(val exitCode: Int, val stdout: String, val stderr: String = "") {
    val ok: Boolean get() = exitCode == MeridianExit.OK
}

/** The exit-code contract of docs/design/on-demand-tools-via-meridian-cli.md ("CLI surface"). */
object MeridianExit {
    const val OK = 0

    /** The input was refused: a usage error, bad JSON, a size cap, or the tool refused it. */
    const val REFUSED = 2

    /** The caller may not do this: no identity, an ACL, or the per-conversation rate limit. */
    const val DENIED = 3

    /** Nothing here can answer: the host is unreachable, or does not serve the command now. */
    const val HOST_UNAVAILABLE = 4
}

/** Every structured error the surface prints, with its wire code and exit code. */
enum class MeridianErrorCode(val wire: String, val exitCode: Int) {
    USAGE("usage", MeridianExit.REFUSED),
    UNKNOWN_COMMAND("unknown_command", MeridianExit.REFUSED),
    INVALID_JSON("invalid_json", MeridianExit.REFUSED),
    INVALID_INPUT("invalid_input", MeridianExit.REFUSED),
    INPUT_TOO_LARGE("input_too_large", MeridianExit.REFUSED),
    OUTPUT_TOO_LARGE("output_too_large", MeridianExit.REFUSED),
    REFUSED("refused", MeridianExit.REFUSED),
    DENIED("denied", MeridianExit.DENIED),
    RATE_LIMITED("rate_limited", MeridianExit.DENIED),
    HOST_UNAVAILABLE("host_unavailable", MeridianExit.HOST_UNAVAILABLE),
}

/**
 * A structured error. Printed as one JSON object with a fixed key order (error, command, message,
 * pointer, detail, retry_after_ms), absent keys left out, so the same failure always prints the
 * same bytes. [pointer] is an RFC 6901 JSON pointer into the input; [detail] is the tool's own
 * structured refusal (canvas_compose's), passed through whole.
 */
data class MeridianError(
    val code: MeridianErrorCode,
    val message: String,
    val command: String? = null,
    val pointer: String? = null,
    val detail: JsonElement? = null,
    val retryAfterMs: Long? = null,
    val hint: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("error", code.wire)
        command?.let { put("command", it) }
        put("message", message)
        pointer?.let { put("pointer", it) }
        detail?.let { put("detail", it) }
        retryAfterMs?.let { put("retry_after_ms", it) }
    }

    fun toResponse(): MeridianResponse =
        MeridianResponse(code.exitCode, toJson().toString(), hint?.let { "$it\n" }.orEmpty())

    companion object {
        /** What a shim or endpoint prints when it cannot reach the host at all. */
        fun hostUnavailable(reason: String): MeridianResponse = MeridianError(
            MeridianErrorCode.HOST_UNAVAILABLE,
            reason,
            hint = "The Meridian host is not answering; retry in a few seconds.",
        ).toResponse()
    }
}

/**
 * The size caps (design: "Inputs and results are size-capped"). Defaults sit above every cap the
 * tools themselves enforce (canvas_compose's 64 KiB request, the 8M-char scene), so the router only
 * stops input no tool would accept and output no tool return could carry.
 */
data class MeridianLimits(
    val maxInputBytes: Int = DEFAULT_MAX_INPUT_BYTES,
    val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    val maxArgs: Int = DEFAULT_MAX_ARGS,
    val maxArgBytes: Int = DEFAULT_MAX_ARG_BYTES,
) {
    companion object {
        const val DEFAULT_MAX_INPUT_BYTES = 9 * 1024 * 1024
        const val DEFAULT_MAX_OUTPUT_BYTES = 10 * 1024 * 1024
        const val DEFAULT_MAX_ARGS = 64
        const val DEFAULT_MAX_ARG_BYTES = 4 * 1024
    }
}
