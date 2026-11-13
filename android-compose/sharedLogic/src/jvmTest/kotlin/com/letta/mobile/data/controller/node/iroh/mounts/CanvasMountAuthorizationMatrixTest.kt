package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.controller.node.iroh.AgentScopeGuard
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A canvas as the host knows it. [owner] is its owning agent in the host's records; [registered] says whether it
 * exists on the host at all (a missing canvas has no record anywhere).
 */
private enum class Board(val id: CanvasId, val owner: AgentId?, val registered: Boolean) {
    OWNED_BY_A(CanvasId("canvas-owned-a"), AGENT_A, registered = true),
    OWNED_BY_B(CanvasId("canvas-owned-b"), AGENT_B, registered = true),
    NO_KNOWN_OWNER(CanvasId("canvas-relay-only"), null, registered = true),
    MISSING(CanvasId("canvas-missing"), null, registered = false),
}

/** The caller's connection scope relative to the addressed agent A record. */
private enum class Scope(val peer: Peer, val conversation: ConversationId) {
    /** Unrestricted agents and conversations (a conversation.manage peer without agent scope). */
    FULL(PEER, CONV_A),

    /** Paired with agent A only: sees canvases owned by A, nothing owned by others or by no one. */
    AGENT_A_ONLY(PEER.copy(agents = setOf(AGENT_A.value)), CONV_A),

    /** Paired with another agent only: the router's agent scope guard refuses before the handler. */
    AGENT_OUT(PEER.copy(agents = setOf("agent-other")), CONV_A),

    /** Bounded to another conversation (a chat-only peer viewing elsewhere). */
    CONVERSATION_OUT(PEER.copy(conversations = setOf("conv-elsewhere")), CONV_A),

    /** Addresses agent A with a conversation that belongs to agent B. */
    FOREIGN_CONVERSATION(PEER, CONV_B),

    /** Unrestricted scopes, but the connection holds only `canvas.mount.read`: no agent default change. */
    READ_ONLY(PEER.copy(capabilities = setOf(CanvasMountWire.READ_CAPABILITY)), CONV_A),
    ;

    val unrestrictedAgents: Boolean get() = peer.agents == null

    /** The D3 visibility rule, stated independently of the production code. */
    fun sees(board: Board): Boolean = when {
        !board.registered -> false
        board.owner == null -> unrestrictedAgents
        else -> unrestrictedAgents || board.owner.value in peer.agents.orEmpty()
    }
}

/** What the addressed record holds before the call. */
private enum class Winner(val board: Board?) {
    ABSENT(null),
    OWNED_BY_A(Board.OWNED_BY_A),
    OWNED_BY_B(Board.OWNED_BY_B),
    MISSING(Board.MISSING),
}

/** The answer the policy requires. */
private enum class Expect { ROUTER_FORBIDDEN, DENIED, NONE, FOUND, BOUND_WON, BOUND_LOST, REPLACED, DEFAULT_SET, DEFAULT_CLEARED, LISTED }

private class Case(val route: CanvasMountRoute, val scope: Scope, val proposal: Board, val winner: Winner) {
    override fun toString(): String = "$route/$scope/$proposal/$winner"
}

/**
 * Table-driven authorization matrix through the real router: every route x connection scope x proposed canvas x
 * stored winner. Denials must be the one fixed refusal, leave the record untouched and never name the winner.
 */
class CanvasMountAuthorizationMatrixTest {
    @Test
    fun everyRouteScopeProposalAndWinnerCombinationAnswersTheVisibilityRule() = runBlocking {
        val cases = CanvasMountRoute.entries.flatMap { route ->
            Scope.entries.flatMap { scope ->
                Board.entries.flatMap { proposal -> Winner.entries.map { Case(route, scope, proposal, it) } }
            }
        }
        assertEquals(CanvasMountRoute.entries.size * Scope.entries.size * Board.entries.size * Winner.entries.size, cases.size)
        cases.forEach { check(it) }
    }

    private suspend fun check(case: Case) {
        val harness = MountHarness()
        Board.entries.filter { it.registered }.forEach { harness.canvas(it.id, it.owner) }
        seed(harness, case)
        val reply = harness.call(case.route, paramsFor(case.route, case.scope.conversation, case.proposal.id), case.scope.peer)
        val expected = expected(case)
        verify(reply, expected, case)
        if (expected == Expect.LISTED) everyCandidateIsAProposalTheHostAccepts(harness, reply, case)
        if (expected == Expect.DENIED || expected == Expect.ROUTER_FORBIDDEN) {
            assertFalse(case.winner.board?.id?.value.orEmpty().let { it.isNotEmpty() && it in reply.raw }, "$case leaked the winner")
            assertEquals(case.winner.board?.id, stored(harness, case), "$case changed the record")
        }
        if (expected == Expect.DEFAULT_CLEARED) assertEquals(null, stored(harness, case), "$case left the default")
    }

    private val Case.addressesDefault: Boolean
        get() = route == CanvasMountRoute.SET_DEFAULT || route == CanvasMountRoute.CLEAR_DEFAULT

    private suspend fun seed(harness: MountHarness, case: Case) {
        val canvas = case.winner.board?.id ?: return
        val store = harness.store()
        if (case.addressesDefault) store.setDefault(AGENT_A, canvas)
        else store.bindIfAbsent(AGENT_A, case.scope.conversation, canvas)
    }

    private suspend fun stored(harness: MountHarness, case: Case): CanvasId? =
        if (case.addressesDefault) harness.store().readDefault(AGENT_A)
        else harness.store().readBinding(AGENT_A, case.scope.conversation)

    private fun verify(reply: Reply, expected: Expect, case: Case) {
        val label = "$case -> $reply"
        when (expected) {
            Expect.ROUTER_FORBIDDEN -> assertEquals(AgentScopeGuard.DENIED, reply.error, label)
            Expect.DENIED -> assertEquals(DENIED_RESPONSE, reply.raw, label)
            Expect.LISTED -> assertEquals(listedFor(case), reply.listed, label)
            else -> assertEquals(success(expected, case), Triple(reply.status, reply.canvas, reply.won), label)
        }
    }

    private fun success(expected: Expect, case: Case): Triple<String?, String?, Boolean?> = when (expected) {
        Expect.NONE -> Triple(CanvasMountWire.NONE, null, null)
        Expect.FOUND -> Triple(CanvasMountWire.FOUND, case.winner.board?.id?.value, null)
        Expect.BOUND_WON -> Triple(CanvasMountWire.BOUND, case.proposal.id.value, true)
        Expect.BOUND_LOST -> Triple(CanvasMountWire.BOUND, case.winner.board?.id?.value, false)
        Expect.REPLACED -> Triple(CanvasMountWire.REPLACED, case.proposal.id.value, null)
        Expect.DEFAULT_CLEARED -> Triple(CanvasMountWire.DEFAULT_CLEARED, null, null)
        else -> Triple(CanvasMountWire.DEFAULT_SET, case.proposal.id.value, null)
    }

    private fun expected(case: Case): Expect = when {
        case.scope == Scope.AGENT_OUT -> Expect.ROUTER_FORBIDDEN
        case.addressesDefault -> expectedDefault(case)
        case.scope == Scope.CONVERSATION_OUT || case.scope == Scope.FOREIGN_CONVERSATION -> Expect.DENIED
        else -> expectedBinding(case)
    }

    /**
     * Owner decision: an agent default needs `canvas.mount.write` and the agent in the agent scope (no conversation is
     * addressed, so conversation scope plays no part); a set also needs the proposed canvas visible, a clear proposes none.
     */
    private fun expectedDefault(case: Case): Expect = when {
        case.scope == Scope.READ_ONLY -> Expect.DENIED
        case.route == CanvasMountRoute.CLEAR_DEFAULT -> Expect.DEFAULT_CLEARED
        case.scope.sees(case.proposal) -> Expect.DEFAULT_SET
        else -> Expect.DENIED
    }

    private fun expectedBinding(case: Case): Expect = when (case.route) {
        CanvasMountRoute.RESOLVE -> expectedRead(case)
        CanvasMountRoute.CANDIDATES -> Expect.LISTED
        CanvasMountRoute.BIND -> if (case.scope.sees(case.proposal)) expectedBind(case) else Expect.DENIED
        else -> if (case.scope.sees(case.proposal)) Expect.REPLACED else Expect.DENIED
    }

    /**
     * D6, stated independently: the binding's own canvas first, then every directory canvas the scope sees, in host
     * order. Relay-only canvases (no known owner) are never enumerated.
     */
    private fun listedFor(case: Case): List<String> {
        val own = CanvasId.forConversation(case.scope.conversation).value
        return listOf(own) + Board.entries.filter { it.registered && it.owner != null && case.scope.sees(it) }.map { it.id.value }
    }

    /** Candidates never list a canvas a proposal would deny: each listed canvas is replaced into the binding for real. */
    private suspend fun everyCandidateIsAProposalTheHostAccepts(harness: MountHarness, reply: Reply, case: Case) {
        reply.listed.orEmpty().forEach { listed ->
            val params = bindingParams(AGENT_A, case.scope.conversation, CanvasId(listed))
            val proposal = harness.call(CanvasMountRoute.REPLACE, params, case.scope.peer)
            assertEquals(CanvasMountWire.REPLACED, proposal.status, "$case listed $listed but a proposal of it -> $proposal")
        }
    }

    private fun expectedRead(case: Case): Expect {
        val winner = case.winner.board ?: return Expect.NONE
        return if (case.scope.sees(winner)) Expect.FOUND else Expect.DENIED
    }

    private fun expectedBind(case: Case): Expect {
        val winner = case.winner.board ?: return Expect.BOUND_WON
        return when {
            winner == case.proposal -> Expect.BOUND_WON
            case.scope.sees(winner) -> Expect.BOUND_LOST
            else -> Expect.DENIED
        }
    }
}
