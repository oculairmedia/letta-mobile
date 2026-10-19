package com.letta.mobile.data.meridian.endpoint

import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.meridian.MeridianError
import com.letta.mobile.data.meridian.MeridianErrorCode

/**
 * How the local endpoint decides who a CLI call comes from (design
 * "Caller identity"). The shim forwards `LETTA_TOOL_TOKEN` plus `LETTA_AGENT_ID` /
 * `LETTA_CONVERSATION_ID` from the agent's shell env. The ids are the agent's to override, so in
 * [LIVE_CALL] they are never used: only the per-call token says which call, and so which scope, a
 * request belongs to.
 */
enum class MeridianCallerBindingMode(val wire: String) {
    /**
     * Default: the request must carry the token this host issued for one in-flight shell call that
     * runs `meridian` ([MeridianLiveCalls.issueToken]); the scope and the call id are that call's,
     * as the App Server stamped them. No live token, no service.
     */
    LIVE_CALL("live-call"),

    /**
     * Soft env-scoped attribution, the clearly weaker fallback for runtimes whose tool frames this
     * host cannot see (runtimes started by another App Server client, cron or channel turns nobody
     * here subscribed to). The claimed scope is used as is when no live token vouches for it. Only
     * acceptable on a single-tenant host where the agent's shell already runs as the user that owns
     * the canvas relay, so the CLI opens no hole the shell does not already have.
     */
    ENV_SCOPED("env-scoped"),
    ;

    companion object {
        fun parse(value: String?): MeridianCallerBindingMode? =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** Turns a request's tool token (or, in [MeridianCallerBindingMode.ENV_SCOPED], its claimed scope) into a caller. */
class MeridianCallerBinder(
    private val mode: MeridianCallerBindingMode,
    private val liveCalls: MeridianLiveCalls,
) {
    /** The token for the live call [toolCallId], issued once (letta-code's PreToolUse hook asks). */
    suspend fun issue(toolCallId: String): Result<String> =
        toolCallId.trim().takeIf { it.isNotEmpty() }?.let { liveCalls.issueToken(it) }?.let { Result.success(it) }
            ?: denied("no tool token for call $toolCallId: it is not a running meridian shell call, or its token was already issued")

    suspend fun bind(toolToken: String?, agentId: String?, conversationId: String?): Result<ExternalToolCaller> {
        val token = toolToken?.trim()?.takeIf { it.isNotEmpty() }
        val live = token?.let { liveCalls.claim(it) }
        val claimedAgent = agentId?.trim()?.takeIf { it.isNotEmpty() }
        val claimedConversation = conversationId?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            live != null -> Result.success(live.toCaller())
            mode == MeridianCallerBindingMode.ENV_SCOPED && (claimedAgent != null || claimedConversation != null) ->
                Result.success(ExternalToolCaller(claimedAgent, claimedConversation))
            token == null -> denied(
                "no tool token: meridian runs from an agent's shell tool, which gets LETTA_TOOL_TOKEN for that one call",
            )
            else -> denied("the tool token does not belong to a running meridian shell call (it ends with its call)")
        }
    }

    private fun MeridianLiveCall.toCaller() = ExternalToolCaller(
        agentId = scope.agentId.takeUnless { scope.isAgentFree },
        conversationId = scope.conversationId,
        // The first meridian invocation of a shell call carries the call's own id, so a compose
        // lands on the artifact the native tool would have named; later ones in the same command
        // get their own, or a second compose would answer as a retry of the first.
        toolCallId = if (invocation <= 1) toolCallId else "$toolCallId:$invocation",
    )

    private fun <T> denied(message: String): Result<T> = Result.failure(
        MeridianWireRefusal(
            MeridianError(
                MeridianErrorCode.DENIED,
                message,
                hint = "Run meridian from your shell tool in this conversation; a token works only inside the call it was issued for.",
            ).toResponse(),
        ),
    )
}
