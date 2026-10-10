package com.letta.mobile.data.meridian.endpoint

import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.meridian.MeridianError
import com.letta.mobile.data.meridian.MeridianErrorCode

/**
 * How the local endpoint decides who a CLI call comes from (letta-mobile-jna0o.4; design
 * "Caller identity"). The shim forwards `LETTA_AGENT_ID` / `LETTA_CONVERSATION_ID` from the agent's
 * shell env, which the agent can override, so that claim is never taken at face value in
 * [LIVE_CALL].
 */
enum class MeridianCallerBindingMode(val wire: String) {
    /**
     * Phase 1 (default): the call binds only while the claimed conversation has a shell tool call
     * running `meridian` in flight on a runtime this host relays; the scope and the call id are the
     * App Server's, not the env's. No live call, no service.
     */
    LIVE_CALL("live-call"),

    /**
     * Soft env-scoped attribution, the clearly weaker fallback for runtimes whose tool frames this
     * host cannot see (runtimes started by another App Server client, cron or channel turns nobody
     * here subscribed to). The claimed scope is used as is when no live call vouches for it. Only
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

/** Turns a request's claimed scope into the [ExternalToolCaller] the router runs with, or refuses it. */
class MeridianCallerBinder(
    private val mode: MeridianCallerBindingMode,
    private val liveCalls: MeridianLiveCalls,
) {
    suspend fun bind(agentId: String?, conversationId: String?): Result<ExternalToolCaller> {
        val claimedAgent = agentId?.trim()?.takeIf { it.isNotEmpty() }
        val claimedConversation = conversationId?.trim()?.takeIf { it.isNotEmpty() }
        val live = claimedConversation?.let { liveCalls.claim(it, claimedAgent) }
        return when {
            live != null -> Result.success(live.toCaller())
            mode == MeridianCallerBindingMode.ENV_SCOPED && (claimedAgent != null || claimedConversation != null) ->
                Result.success(ExternalToolCaller(claimedAgent, claimedConversation))
            claimedConversation == null -> denied(
                "no conversation scope: meridian runs from an agent's shell tool, which sets LETTA_CONVERSATION_ID",
            )
            else -> denied(
                "no running meridian shell call in conversation $claimedConversation" +
                    (claimedAgent?.let { " for agent $it" } ?: ""),
            )
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

    private fun denied(message: String): Result<ExternalToolCaller> = Result.failure(
        MeridianWireRefusal(
            MeridianError(
                MeridianErrorCode.DENIED,
                message,
                hint = "Run meridian from your shell tool in this conversation; do not override LETTA_AGENT_ID or LETTA_CONVERSATION_ID.",
            ).toResponse(),
        ),
    )
}
