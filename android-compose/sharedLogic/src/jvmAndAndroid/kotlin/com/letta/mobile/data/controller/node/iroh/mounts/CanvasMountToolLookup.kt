package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.HostCanvasCaller
import com.letta.mobile.data.canvas.mount.AuthorizedCanvasMounts
import com.letta.mobile.data.canvas.mount.CanvasMountAuthority
import com.letta.mobile.data.canvas.mount.CanvasMountDecision
import com.letta.mobile.data.canvas.mount.CanvasMountPrincipal
import com.letta.mobile.data.canvas.mount.CanvasMountRequest
import com.letta.mobile.data.canvas.mount.CanvasMountResult
import com.letta.mobile.data.canvas.mount.CanvasMountScope
import com.letta.mobile.data.canvas.mount.lookup.HostCanvasMount
import com.letta.mobile.data.canvas.mount.lookup.HostCanvasMountLookup
import com.letta.mobile.data.controller.node.iroh.mounts.deadline.CanvasMountDeadline
import com.letta.mobile.data.controller.node.iroh.mounts.deadline.CanvasMountTimed
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.CoroutineScope

/**
 * The host canvas tools' view of the mount (S1): which canvas the runtime-stamped caller's OWN conversation is
 * mounted on, read through [AuthorizedCanvasMounts] like every other access, never changed (owner decision O4).
 *
 * Each lookup runs as [RuntimeToolAuthority.PRINCIPAL], a host-internal principal that is not a 64-hex endpoint id and
 * so can never be, or be mistaken for, a remote peer. Its authority admits only the caller's own binding and its
 * agent's default, refuses every mutation, and lets the tool learn the stored canvas. The host canvas backend derives
 * its grant-on-mount (owner decision D5) from this same answer at every check, so the canvas found here is the only
 * one the mount lets the agent read and write, and only while it stays mounted.
 */
class CanvasMountToolLookup internal constructor(
    private val mounts: (CanvasMountAuthority) -> AuthorizedCanvasMounts,
    private val deadline: CanvasMountDeadline,
) : HostCanvasMountLookup<HostCanvasCaller> {
    /** Production lookup over the host's mount records at [location]; calls run, bounded, in the host-owned [scope]. */
    constructor(location: CanvasMountStoreLocation, scope: CoroutineScope) : this(location::mounts, CanvasMountDeadline.production(scope))

    override suspend fun mounted(caller: HostCanvasCaller): HostCanvasMount {
        val conversation = caller.conversationId?.let(::ConversationId) ?: return HostCanvasMount.None
        val agent = AgentId(caller.agentId)
        val facade = mounts(RuntimeToolAuthority(agent, conversation))
        return when (val timed = deadline.run { resolve(facade, agent, conversation) }) {
            is CanvasMountTimed.Settled -> timed.value.asMount()
            is CanvasMountTimed.Expired -> HostCanvasMount.Unavailable
        }
    }

    private suspend fun resolve(facade: AuthorizedCanvasMounts, agent: AgentId, conversation: ConversationId): CanvasMountOutcome {
        val principal = RuntimeToolAuthority.PRINCIPAL
        val own = facade.read(CanvasMountRequest(principal, CanvasMountScope.Conversation(agent, conversation)))
        if (own != CanvasMountResult.None) return own.toOutcome(CanvasMountSource.BINDING, null)
        return facade.read(CanvasMountRequest(principal, CanvasMountScope.AgentDefault(agent))).toOutcome(CanvasMountSource.DEFAULT, null)
    }

    private fun CanvasMountOutcome.asMount(): HostCanvasMount = when (this) {
        is CanvasMountOutcome.Found -> HostCanvasMount.Mounted(canvas.value)
        CanvasMountOutcome.None -> HostCanvasMount.None
        else -> HostCanvasMount.Unavailable
    }
}

/**
 * Read-only authority for one tool call: [agent] and [conversation] are the runtime's stamp, never tool input. It
 * admits exactly that binding and that agent's default, never a proposal, and only for [PRINCIPAL].
 */
internal class RuntimeToolAuthority(private val agent: AgentId, private val conversation: ConversationId) : CanvasMountAuthority {
    override suspend fun mayAddress(request: CanvasMountRequest<CanvasMountScope>): CanvasMountDecision {
        val ownScopes = setOf(CanvasMountScope.Conversation(agent, conversation), CanvasMountScope.AgentDefault(agent))
        return decide(request.principal == PRINCIPAL && request.scope in ownScopes)
    }

    override suspend fun mayPropose(request: CanvasMountRequest<CanvasMountScope>, proposed: CanvasId): CanvasMountDecision =
        CanvasMountDecision.DENY

    override suspend fun maySee(request: CanvasMountRequest<CanvasMountScope>, winner: CanvasId): CanvasMountDecision =
        decide(request.principal == PRINCIPAL)

    private fun decide(allowed: Boolean): CanvasMountDecision = if (allowed) CanvasMountDecision.ALLOW else CanvasMountDecision.DENY

    companion object {
        /** The host runtime acting for its own tool calls; never a 64-hex endpoint id, so never a remote peer. */
        val PRINCIPAL: CanvasMountPrincipal = checkNotNull(CanvasMountPrincipal.fromVerifiedConnection("host-runtime-tools"))
    }
}
