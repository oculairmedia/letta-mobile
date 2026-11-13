package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.mount.AuthorizedCanvasMounts
import com.letta.mobile.data.canvas.mount.CanvasMountAuthority
import com.letta.mobile.data.canvas.mount.CanvasMountPrincipal
import com.letta.mobile.data.canvas.mount.CanvasMountRequest
import com.letta.mobile.data.canvas.mount.CanvasMountResult
import com.letta.mobile.data.canvas.mount.CanvasMountScope
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Everything the routes need from the host; the raw store is reachable only through [mounts]. */
internal class CanvasMountHost(
    /** The authorized facade for one call's authority; the only path to mount storage. */
    val mounts: (CanvasMountAuthority) -> AuthorizedCanvasMounts,
    /** The host records the authority decides from, and the viewers change pushes go to. */
    val sources: CanvasMountSources,
    val bounds: CanvasMountBounds,
) {
    val changes = CanvasMountChanges(sources, bounds.durability, bounds.scope)
}

/**
 * Host-wide properties of every answer: the durability promise (O1), the per-call deadline (F5) and the host-owned
 * [scope] background work (deadline-bounded calls, change pushes) runs in.
 */
internal class CanvasMountBounds(val durability: CanvasMountDurability, val deadline: CanvasMountDeadline, val scope: CoroutineScope) {
    companion object {
        fun production(scope: CoroutineScope): CanvasMountBounds =
            CanvasMountBounds(CanvasMountDurability.ofHost(), CanvasMountDeadline.production(scope), scope)
    }
}

/**
 * One handler for all six routes. Order of work: verified identity from the connection, strict params, then the
 * authorized facade under the deadline. No request field reaches the authority except as a target.
 */
internal class CanvasMountHandlers(private val host: CanvasMountHost) {
    suspend fun handle(route: CanvasMountRoute, params: JsonObject?, context: AdminRpcRequestContext): JsonElement =
        outcome(route, params, context).reply(route, host.bounds.durability)

    private suspend fun outcome(route: CanvasMountRoute, params: JsonObject?, context: AdminRpcRequestContext): CanvasMountOutcome {
        val connection = CanvasMountConnection.of(context) ?: return CanvasMountOutcome.Unavailable(CanvasMountUnavailable.IDENTITY)
        val input = when (val parsed = route.parse(params)) {
            is CanvasMountOutcome.InvalidInput -> return parsed
            is CanvasMountInput -> parsed
        }
        val trace = CanvasMountCallTrace()
        val authority = HostCanvasMountAuthority(connection, host.sources.catalog, host.sources.directory, trace)
        val candidates = CanvasMountCandidates(authority, host.sources.catalog)
        val call = CanvasMountCall(host.mounts(authority), candidates, connection.principal, input)
        val outcome = when (val timed = host.bounds.deadline.run { call.run(route) }) {
            is CanvasMountTimed.Settled -> timed.value.settledWith(route, trace)
            is CanvasMountTimed.Expired -> expired(route, trace, timed.unwound)
        }
        changedCanvas(outcome)?.let { host.changes.publish(CanvasMountChange(input.agent, input.conversation, it)) }
        return outcome
    }
}

/**
 * The canvas a binding now holds after a write this call committed, else null: a replace, or a bind whose winner is
 * the caller's proposal. Defaults, reads, failures and unknown outcomes push nothing.
 */
private fun changedCanvas(outcome: CanvasMountOutcome): CanvasId? = when (outcome) {
    is CanvasMountOutcome.Replaced -> outcome.canvas
    is CanvasMountOutcome.Bound -> outcome.canvas.takeIf { outcome.won }
    else -> null
}

/**
 * One authorized call: every mount record operation goes through the facade with the verified principal; the
 * read-only candidates listing goes through the same per-call authority and touches no mount record.
 */
private class CanvasMountCall(
    private val mounts: AuthorizedCanvasMounts,
    private val candidates: CanvasMountCandidates,
    private val principal: CanvasMountPrincipal,
    private val input: CanvasMountInput,
) {
    private val binding get() = CanvasMountRequest(principal, CanvasMountScope.Conversation(input.agent, input.conversation))

    private val agentDefault get() = CanvasMountRequest(principal, CanvasMountScope.AgentDefault(input.agent))

    suspend fun run(route: CanvasMountRoute): CanvasMountOutcome = when (route) {
        CanvasMountRoute.RESOLVE -> resolve()
        CanvasMountRoute.BIND -> mounts.bindIfAbsent(binding, input.canvas).toOutcome(CanvasMountSource.BINDING, input.canvas)
        CanvasMountRoute.REPLACE -> mounts.replaceBinding(binding, input.canvas).toOutcome(CanvasMountSource.BINDING, null)
        CanvasMountRoute.SET_DEFAULT -> mounts.setDefault(agentDefault, input.canvas).toOutcome(CanvasMountSource.DEFAULT, null)
        CanvasMountRoute.CLEAR_DEFAULT -> mounts.clearDefault(agentDefault).toOutcome(CanvasMountSource.DEFAULT, null)
        CanvasMountRoute.CANDIDATES -> candidates.list(binding)
    }

    /**
     * The resolution order: the conversation's own binding, else its agent's default, else [CanvasMountOutcome.None]
     * (the conversation shows its own canvas). A denial at either step is the answer.
     */
    private suspend fun resolve(): CanvasMountOutcome {
        val own = mounts.read(binding)
        if (own != CanvasMountResult.None) return own.toOutcome(CanvasMountSource.BINDING, null)
        return mounts.read(agentDefault).toOutcome(CanvasMountSource.DEFAULT, null)
    }
}

/**
 * A denial caused by a failed grant or directory lookup is not a policy answer: before anything committed it is
 * `unavailable`; once a mutation's storage returned (the winner check) its outcome is unknown.
 */
private fun CanvasMountOutcome.settledWith(route: CanvasMountRoute, trace: CanvasMountCallTrace): CanvasMountOutcome = when {
    !trace.authorityFailed -> this
    // A candidate the authority could not decide was left out: never answer a partial list as if it were whole.
    this is CanvasMountOutcome.Listed -> CanvasMountOutcome.Unavailable(CanvasMountUnavailable.AUTHORITY)
    this != CanvasMountOutcome.Denied -> this
    route.mutates && trace.phase == CanvasMountPhase.REVEALING -> CanvasMountOutcome.OutcomeUnknown
    else -> CanvasMountOutcome.Unavailable(CanvasMountUnavailable.AUTHORITY)
}

/**
 * Past the deadline: nothing can have committed while authorizing, nor when storage unwound by cancellation (the
 * store propagates cancellation only before its commit point); otherwise a mutation may have committed.
 */
private fun expired(route: CanvasMountRoute, trace: CanvasMountCallTrace, unwound: Boolean): CanvasMountOutcome {
    val beforeCommit = when (trace.phase) {
        CanvasMountPhase.AUTHORIZING -> true
        CanvasMountPhase.STORING -> unwound
        CanvasMountPhase.REVEALING -> false
    }
    return if (beforeCommit || !route.mutates) {
        CanvasMountOutcome.Unavailable(CanvasMountUnavailable.TIMEOUT_BEFORE_COMMIT)
    } else {
        CanvasMountOutcome.OutcomeUnknown
    }
}
