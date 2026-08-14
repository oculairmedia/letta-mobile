package com.letta.mobile.data.controller.extras

/**
 * letta-mobile-bn008-phase2-custom-tool (1vuec): platform-resolved default
 * [IrohCliRunner] for the agent-message tool. The common tool resolves this
 * lazily at invoke time so tests that inject an explicit runner never hit
 * the platform-specific path.
 *
 * JVM / Android: returns a singleton [DefaultIrohCliRunner] that uses
 * `ProcessBuilder` and pipes the body through stdin.
 *
 * Other KMP targets (currently the only ones configured are hostNative
 * variants on the build host): return null. The tool surface degrades to a
 * structured "no IrohCliRunner available" error rather than crashing.
 *
 * The `expect`/`actual` pattern (rather than a top-level function in the
 * jvmAndAndroid source set) is the canonical KMP way to make a common
 * declaration's body platform-specific — top-level functions from a
 * dependent source set are NOT visible to commonMain callers, which the
 * build error pins. See `DefaultIrohCliRunnerFactory.kt`.
 */
expect fun defaultIrohCliRunnerOrNull(): IrohCliRunner?

/**
 * letta-mobile-bn008-phase2-custom-tool (1vuec).
 *
 * The wrapper that drives `meridian agent-message send --from ... --to ...
 * --body-file -` as a subprocess. Kept behind an interface so unit tests can
 * stub it without spawning a process; the production default implementation
 * uses [ProcessBuilder] and pipes the body through stdin (the safest channel
 * for arbitrary multi-line content — see bn008-phase2-handoff risk #1, where
 * the Meridian→Lester send collapsed a multi-line body to "" because of
 * `tr '\n' ' '` + shell quoting).
 *
 * The contract:
 *   - The body is supplied via `--body-file -` (stdin), NEVER as `--body "<...>"`
 *     with shell escaping. This is the load-bearing line that fixes the
 *     regression: stdin is byte-exact, no quoting layer collapses newlines.
 *   - The CLI exits 0 on [IrohCliSendResult.Delivered], non-zero on
 *     [IrohCliSendResult.Unaddressable] / [IrohCliSendResult.Failed].
 *   - The runner captures stdout (the JSON result line) and stderr.
 *
 * The default invocation expects a binary that exposes the same
 * `agent-message send` subcommand the `:cli` module's `AgentMessageSendCommand`
 * already implements (do not duplicate the send path; reuse the existing CLI).
 */
/**
 * letta-mobile-bn008-phase2-custom-tool (1vuec): the pair of optional Iroh
 * filesystem paths a CLI invocation may override.
 *
 * Grouped into a single value class so the runner's `send` signature stays
 * under CodeScene's "≤4 function arguments" threshold and so callers that
 * override both directories do so via named fields rather than positional
 * nulls. `identityDir` and `addressStore` are independent overrides — null
 * means "use the CLI's default" (`~/.letta/iroh/identities` and
 * `~/.letta/iroh/agent-addresses.kv` respectively).
 */
data class IrohCliPaths(
    val identityDir: String? = null,
    val addressStore: String? = null,
) {
    companion object {
        /** The path triple that means "no overrides — let the CLI default." */
        val DEFAULTS: IrohCliPaths = IrohCliPaths()
    }
}

interface IrohCliRunner {
    /**
     * Run `meridian agent-message send --from <fromAgentId> --to <toAgentId>
     * --body-file -` with [body] piped to the child's stdin.
     *
     * @param binary Absolute path or PATH-relative name of the `meridian`
     *   binary. Defaults are wired in [DefaultIrohCliRunner]; tests pass a
     *   throwaway script path.
     * @param fromAgentId The id of the calling agent — supplied by the App
     *   Server's runtime scope (see [ExternalTool.invoke]'s `agentId` param).
     * @param toAgentId The id of the recipient agent (taken from the tool's
     *   `to` input field).
     * @param body The message body. Multi-line content MUST round-trip exactly:
     *   this is the regression pinned by the
     *   `multiLineBodyRoundTripsViaStdin` unit test. Empty body is allowed
     *   (the CLI treats it as a ping).
     * @param paths Optional overrides for the Iroh filesystem layout
     *   (identity dir + address-book kv). Pass [IrohCliPaths.DEFAULTS] for
     *   "no overrides — let the CLI default."
     * @return Typed outcome — never throws to the caller (the dispatcher
     *   contract is "always answer"; throwing would surface as a tool error
     *   and break the turn's answer guarantee).
     */
    suspend fun send(
        binary: String,
        fromAgentId: String,
        toAgentId: String,
        body: String,
        paths: IrohCliPaths = IrohCliPaths.DEFAULTS,
    ): IrohCliSendResult
}

/**
 * Typed outcome of a CLI invocation. Mirrors [com.letta.mobile.data.transport.iroh.AgentSendResult]
 * one-for-one so the tool result string is identical regardless of whether
 * the agent-message wire path runs in-process (direct sender) or via this
 * CLI subprocess wrapper.
 *
 * The runner NEVER throws. Any spawn failure, non-zero exit, parse failure,
 * or IO failure is collapsed into [IrohCliSendResult.Failed] with a human
 * reason — the dispatcher's external-tool answer guarantee requires an
 * `external_tool_call_response` for every request, so a thrown exception
 * from here would leave the App Server parked on its 5-minute timeout.
 */
sealed interface IrohCliSendResult {
    /** Application delivery was confirmed by the recipient. */
    data class Delivered(val msgId: String) : IrohCliSendResult

    /** Transport was accepted, but application delivery is not confirmed. */
    data class Accepted(val msgId: String, val toAgentId: String) : IrohCliSendResult

    /** The CLI exited non-zero because the target was unaddressable. */
    data class Unaddressable(val toAgentId: String, val reason: String) : IrohCliSendResult

    /**
     * Either the CLI exited non-zero for a reason other than unaddressable,
     * the spawn itself failed, the stdout didn't parse as the expected JSON
     * result contract, or the body write to stdin failed.
     */
    data class Failed(val toAgentId: String, val reason: String) : IrohCliSendResult
}
