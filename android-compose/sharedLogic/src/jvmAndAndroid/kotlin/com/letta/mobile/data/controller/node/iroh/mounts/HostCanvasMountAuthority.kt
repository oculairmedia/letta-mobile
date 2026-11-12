package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.mount.CanvasMountAuthority
import com.letta.mobile.data.canvas.mount.CanvasMountDecision
import com.letta.mobile.data.canvas.mount.CanvasMountPrincipal
import com.letta.mobile.data.canvas.mount.CanvasMountRequest
import com.letta.mobile.data.canvas.mount.CanvasMountScope
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.util.runCatchingCancellable

/**
 * What the serving connection itself established about the caller; nothing here comes from request params.
 * [agents] is the paired peer's agent scope and [conversations] its conversation-content scope (null = all),
 * exactly as the connection put them in [AdminRpcRequestContext].
 */
internal class CanvasMountConnection private constructor(
    val principal: CanvasMountPrincipal,
    private val agents: Set<String>?,
    private val conversations: Set<String>?,
) {
    fun allowsAgent(agent: AgentId): Boolean = agents == null || agent.value in agents

    fun allowsConversation(conversation: ConversationId): Boolean = conversations == null || conversation.value in conversations

    /**
     * Owner decision O3: the connection grants unrestricted conversation scope exactly to `conversation.manage`
     * and `admin.full` peers ([com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities.conversationScope]).
     */
    val managesConversations: Boolean get() = conversations == null

    companion object {
        private val ENDPOINT_ID = Regex("[0-9a-f]{64}")

        /** The verified connection, or null unless it is authenticated with a well-formed endpoint identity. */
        fun of(context: AdminRpcRequestContext): CanvasMountConnection? {
            val endpoint = context.authenticatedEndpointId?.takeIf { context.authenticated } ?: return null
            if (!ENDPOINT_ID.matches(endpoint)) return null
            val principal = CanvasMountPrincipal.fromVerifiedConnection(endpoint) ?: return null
            return CanvasMountConnection(principal, context.authorizedAgentIds, context.authorizedConversationIds)
        }
    }
}

/** How far one call got; the deadline uses it to tell "nothing committed" from "may have committed". */
internal enum class CanvasMountPhase {
    /** Still authorizing: storage has not been entered, so nothing can have committed. */
    AUTHORIZING,

    /** The proposal was allowed and storage may be running: before or after its commit point. */
    STORING,

    /** Storage returned and the winner is being authorized: a mutation's outcome is already decided. */
    REVEALING,
}

/** Per-call observations of the authority, read after the call settles or expires. */
internal class CanvasMountCallTrace {
    @Volatile
    var phase: CanvasMountPhase = CanvasMountPhase.AUTHORIZING
        private set

    /** True once a grant or directory lookup failed; the resulting denial is reported as `unavailable`. */
    @Volatile
    var authorityFailed: Boolean = false
        private set

    fun enter(next: CanvasMountPhase) {
        phase = next
    }

    fun recordAuthorityFailure() {
        authorityFailed = true
    }
}

/**
 * The host's mount policy for one call, built from the verified [connection] only. Every answer is ALLOW only on
 * positive evidence; any failed lookup abstains (deny) and is recorded in [trace].
 *
 * - Address: the request's principal is the connection's; the agent is in the peer's agent scope; for a binding,
 *   the conversation is in its conversation scope and the host's [directory] says the agent owns it.
 * - Propose: for a default, the peer manages conversations (O3) and the agent exists; the principal's canvas grant
 *   allows mounting the proposed canvas.
 * - See: the principal's canvas grant allows seeing the stored canvas.
 */
internal class HostCanvasMountAuthority(
    private val connection: CanvasMountConnection,
    private val grants: CanvasMountGrants,
    private val directory: CanvasMountDirectory,
    private val trace: CanvasMountCallTrace,
) : CanvasMountAuthority {
    override suspend fun mayAddress(request: CanvasMountRequest<CanvasMountScope>): CanvasMountDecision =
        decide(request) { scope ->
            when {
                !connection.allowsAgent(scope.agent) -> CanvasMountDecision.DENY
                scope is CanvasMountScope.Conversation -> addressConversation(scope)
                else -> CanvasMountDecision.ALLOW
            }
        }

    override suspend fun mayPropose(request: CanvasMountRequest<CanvasMountScope>, proposed: CanvasId): CanvasMountDecision {
        val decision = decide(request) { scope ->
            when {
                scope is CanvasMountScope.AgentDefault && !mayChangeDefault(scope) -> CanvasMountDecision.DENY
                grants.of(connection.principal, proposed).mayMount -> CanvasMountDecision.ALLOW
                else -> CanvasMountDecision.DENY
            }
        }
        if (decision == CanvasMountDecision.ALLOW) trace.enter(CanvasMountPhase.STORING)
        return decision
    }

    override suspend fun maySee(request: CanvasMountRequest<CanvasMountScope>, winner: CanvasId): CanvasMountDecision {
        trace.enter(CanvasMountPhase.REVEALING)
        return decide(request) { allowIf(grants.of(connection.principal, winner).maySee) }
    }

    private suspend fun addressConversation(scope: CanvasMountScope.Conversation): CanvasMountDecision = when {
        !connection.allowsConversation(scope.conversation) -> CanvasMountDecision.DENY
        else -> allowIf(directory.ownerOf(scope.conversation) == scope.agent)
    }

    private suspend fun mayChangeDefault(scope: CanvasMountScope.AgentDefault): Boolean =
        connection.managesConversations && directory.hasAgent(scope.agent)

    private suspend fun decide(
        request: CanvasMountRequest<CanvasMountScope>,
        question: suspend (CanvasMountScope) -> CanvasMountDecision,
    ): CanvasMountDecision {
        if (request.principal != connection.principal) return CanvasMountDecision.DENY
        return runCatchingCancellable { question(request.scope) }.getOrElse { failure ->
            if (failure is Error) throw failure
            trace.recordAuthorityFailure()
            CanvasMountDecision.ABSTAIN
        }
    }
}

private fun allowIf(allowed: Boolean): CanvasMountDecision = if (allowed) CanvasMountDecision.ALLOW else CanvasMountDecision.DENY
