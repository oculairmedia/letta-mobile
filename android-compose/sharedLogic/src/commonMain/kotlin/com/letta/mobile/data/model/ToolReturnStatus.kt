package com.letta.mobile.data.model

/**
 * Canonical values for `tool_return_message.status` as emitted by the Letta
 * server, plus a single classifier used by every UI surface that needs to know
 * whether a tool call ended in error.
 *
 * Background (bead `letta-mobile-o9ce`): the admin client previously treated
 * "anything not literally `success`" as an error, including `null`,
 * `"completed"`, or anything else the server might emit. That mis-painted
 * healthy Bash/Grep/Read calls with the red Error icon. The empirical CLI
 * survey of `conv-4d764880-…` showed the server emits exactly two values for
 * Bash-grade tool calls: `"success"` (109×) and `"error"` (1×). So the
 * correct rule is **error-whitelist**, not success-only.
 *
 * Conservative null-handling: `null` status is treated as success here. The
 * UI can still surface real failures because (a) the server's `"error"` value
 * is honored, and (b) per-call content rendering doesn't depend on this
 * classifier — only the row/icon coloring does.
 */
object ToolReturnStatus {
    /** Server canonical "this tool call succeeded". */
    const val SUCCESS = "success"

    /** Server canonical "this tool call failed". */
    const val ERROR = "error"

    /**
     * `true` iff the supplied status string is a known explicit error value.
     * Treats `null`, `SUCCESS`, and any unrecognized value as non-error.
     *
     * If we later discover other explicit error values from the server, add
     * them here — keep the rule explicit-error-whitelist.
     */
    fun isError(status: String?): Boolean = status == ERROR
}
