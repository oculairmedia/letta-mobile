package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.HostCanvasEntry
import com.letta.mobile.data.canvas.mount.CanvasMountAuthority
import com.letta.mobile.data.canvas.mount.CanvasMountDecision
import com.letta.mobile.data.canvas.mount.CanvasMountPrincipal
import com.letta.mobile.data.canvas.mount.CanvasMountRequest
import com.letta.mobile.data.canvas.mount.CanvasMountScope
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.util.runCatchingCancellable

/**
 * What the serving connection itself established about the caller; nothing here comes from request params.
 * [agents] is the paired peer's agent scope and [conversations] its conversation-content scope (null = all), and
 * [capabilities] the grants the connection read from the peer's own record (null = none established), exactly as the
 * connection put them in [AdminRpcRequestContext].
 */
internal class CanvasMountConnection private constructor(
    val principal: CanvasMountPrincipal,
    private val agents: Set<String>?,
    private val conversations: Set<String>?,
    private val capabilities: Set<String>?,
) {
    fun allowsAgent(agent: AgentId): Boolean = agents == null || agent.value in agents

    /** No agent scope: the peer may address, and see the canvases of, every agent. */
    val unrestrictedAgents: Boolean get() = agents == null

    fun allowsConversation(conversation: ConversationId): Boolean = conversations == null || conversation.value in conversations

    /**
     * Owner decision O3: the connection grants unrestricted conversation scope exactly to `conversation.manage`
     * and `admin.full` peers ([com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities.conversationScope]).
     */
    val managesConversations: Boolean get() = conversations == null

    /**
     * Whether the connection holds `canvas.mount.write` (or `admin.full`), checked here as well as by the capability
     * table, so an agent default can never be changed by a caller whose connection did not establish the grant.
     */
    val writesMounts: Boolean
        get() = capabilities?.let { IrohPeerCapabilities.isAllowed(it, IrohPeerCapabilities.CANVAS_MOUNT_WRITE) } == true

    companion object {
        private val ENDPOINT_ID = Regex("[0-9a-f]{64}")

        /** The verified connection, or null unless it is authenticated with a well-formed endpoint identity. */
        fun of(context: AdminRpcRequestContext): CanvasMountConnection? {
            val endpoint = context.authenticatedEndpointId?.takeIf { context.authenticated } ?: return null
            if (!ENDPOINT_ID.matches(endpoint)) return null
            val principal = CanvasMountPrincipal.fromVerifiedConnection(endpoint) ?: return null
            return CanvasMountConnection(principal, context.authorizedAgentIds, context.authorizedConversationIds, context.grantedCapabilities)
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
 * positive evidence; any failed lookup abstains (deny) and is recorded in [trace], except while judging a PROPOSED
 * canvas ([mayPropose]): there a failed lookup is the same plain denial as a canvas the caller may not see or the
 * host does not have, so a probe cannot tell those apart by the answer.
 *
 * - Address: the request's principal is the connection's; the agent is in the peer's agent scope; for a binding,
 *   the conversation is in its conversation scope and the host's [directory] says the agent owns it.
 * - Propose: for a default, the connection holds `canvas.mount.write`, the agent is in its agent scope and exists;
 *   the proposed canvas is visible to the connection ([visible]), the same rule as a binding proposal.
 * - Clear (a default only, [mayClear]): exactly the default's change rule, with no canvas to judge.
 * - See: the stored canvas is visible to the connection.
 * - List (`canvas.mount.candidates`, [mayList]): exactly the proposal rule, so nothing is listed a proposal refuses.
 *
 * Visibility (owner decision D3): a binding's own conversation canvas is visible to a caller allowed to address that
 * binding while the catalog shows that id unused or held through the binding's own conversation
 * ([CanvasMountCatalog.canonicalHeldBy]); otherwise it is judged like any other canvas. Any other canvas must exist
 * on this host and be owned by an agent in the connection's agent scope ([CanvasMountCatalog.ownership], which makes
 * the same owner lookup whether or not the canvas exists). A canvas with no known owner is visible only with
 * unrestricted agent scope, and a canvas the host does not have is visible to no one, so both deny exactly like any
 * other denial.
 */
internal class HostCanvasMountAuthority(
    private val connection: CanvasMountConnection,
    private val catalog: CanvasMountCatalog,
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
        val decision = decide(request) { scope -> proposal(scope, proposed, failureDenies = true) }
        if (decision == CanvasMountDecision.ALLOW) trace.enter(CanvasMountPhase.STORING)
        return decision
    }

    /**
     * Whether `canvas.mount.candidates` may list [canvas] for the addressed record: exactly the proposal rule of
     * [mayPropose] (owner decision D6), without entering the storing phase, since nothing is stored. Unlike a proposal,
     * a failed lookup is recorded, so the listing answers `unavailable` rather than a silently partial list (listed
     * canvases come from the host's own directory, never from a caller's probe).
     */
    suspend fun mayList(request: CanvasMountRequest<CanvasMountScope>, canvas: CanvasId): CanvasMountDecision =
        decide(request) { scope -> proposal(scope, canvas, failureDenies = false) }

    /**
     * This authority over one call's records loaded once: [entries] answer every directory read, and each
     * conversation's owner and each agent are looked up at most once ([MemoisedMountDirectory]). Same connection and
     * trace, so a failed lookup still reaches this call's answer.
     */
    internal fun over(entries: List<HostCanvasEntry>): HostCanvasMountAuthority =
        HostCanvasMountAuthority(connection, catalog.over(entries), MemoisedMountDirectory(directory), trace)

    private suspend fun proposal(scope: CanvasMountScope, proposed: CanvasId, failureDenies: Boolean): CanvasMountDecision = when {
        scope is CanvasMountScope.AgentDefault && !mayChangeDefault(scope) -> CanvasMountDecision.DENY
        failureDenies -> allowIf(visibleOrDenied(scope, proposed))
        else -> allowIf(visible(scope, proposed))
    }

    /** [visible], with a failed lookup answering false: the denial a missing or invisible canvas gets. */
    private suspend fun visibleOrDenied(scope: CanvasMountScope, canvas: CanvasId): Boolean =
        runCatchingCancellable { visible(scope, canvas) }.getOrElse { failure ->
            if (failure is Error) throw failure
            false
        }

    override suspend fun mayClear(request: CanvasMountRequest<CanvasMountScope>): CanvasMountDecision {
        val decision = decide(request) { scope -> allowIf(scope is CanvasMountScope.AgentDefault && mayChangeDefault(scope)) }
        if (decision == CanvasMountDecision.ALLOW) trace.enter(CanvasMountPhase.STORING)
        return decision
    }

    override suspend fun maySee(request: CanvasMountRequest<CanvasMountScope>, winner: CanvasId): CanvasMountDecision {
        trace.enter(CanvasMountPhase.REVEALING)
        return decide(request) { scope -> allowIf(visible(scope, winner)) }
    }

    private suspend fun addressConversation(scope: CanvasMountScope.Conversation): CanvasMountDecision = when {
        !connection.allowsConversation(scope.conversation) -> CanvasMountDecision.DENY
        else -> allowIf(directory.ownerOf(scope.conversation) == scope.agent)
    }

    private suspend fun visible(scope: CanvasMountScope, canvas: CanvasId): Boolean {
        val own = (scope as? CanvasMountScope.Conversation)?.conversation
        // The id string alone proves nothing: the shortcut holds only while the catalog shows the canonical id unused
        // or held through this very conversation (any signed-in app may bind a topic to an id naming another one).
        if (own != null && canvas == CanvasId.forConversation(own) && catalog.canonicalHeldBy(own)) return true
        return when (val ownership = catalog.ownership(canvas, directory)) {
            CanvasOwnership.Absent -> false
            is CanvasOwnership.Owned -> ownedVisible(ownership.agents)
        }
    }

    private fun ownedVisible(owners: Set<AgentId>): Boolean =
        if (owners.isEmpty()) connection.unrestrictedAgents else owners.any(connection::allowsAgent)

    /** Owner decision: any mount-write connection may change the default of an agent in its agent scope. */
    private suspend fun mayChangeDefault(scope: CanvasMountScope.AgentDefault): Boolean =
        connection.writesMounts && connection.allowsAgent(scope.agent) && directory.hasAgent(scope.agent)

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
