package com.letta.mobile.data.chat.projection.meridian

/**
 * What the host's `meridian` router prints on failure (letta-mobile-jna0o.3 `MeridianError`): one
 * JSON object with the keys in a fixed order (error, command, message, pointer, detail,
 * retry_after_ms), then the hint. Through Bash the hint is on stderr, which a shell tool shows
 * after stdout; the meta-tool answers `stdout + "\n" + hint`. Either way it follows the JSON.
 */
object MeridianCliFixtures {
    /** A tool's own JSON refusal (canvas_compose's) passed through under `detail`. */
    fun refused(toolRefusal: String, command: String = "canvas compose", tool: String = "canvas_compose"): String =
        """{"error":"refused","command":"$command","message":"$tool refused the input","detail":$toolRefusal}"""

    /** A refusal about the caller, in the tool's own words. */
    fun deniedMessage(message: String, command: String = "canvas compose"): String =
        """{"error":"denied","command":"$command","message":"$message"}"""

    const val HOST_UNAVAILABLE_JSON = """{"error":"host_unavailable","message":"connect /run/meridian/tools.sock: no such file"}"""
    const val HOST_UNAVAILABLE = "$HOST_UNAVAILABLE_JSON\nThe Meridian host is not answering; retry in a few seconds."

    const val USAGE_JSON = """{"error":"usage","command":"canvas compose","message":"give the input on stdin or with --input-file, not both"}"""
    const val USAGE = "$USAGE_JSON\nRun: meridian canvas --help"
}
