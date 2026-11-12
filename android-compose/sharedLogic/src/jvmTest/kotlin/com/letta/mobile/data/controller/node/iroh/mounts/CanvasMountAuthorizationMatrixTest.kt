package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.controller.node.iroh.AgentScopeGuard
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private val PROPOSAL = CanvasId("canvas-proposal")
private val VISIBLE = CanvasId("canvas-visible")
private val HIDDEN = CanvasId("canvas-hidden-winner")

/** The caller's connection scope relative to the addressed agent A record. */
private enum class Scope(val peer: Peer, val conversation: ConversationId) {
    /** Unrestricted agents and conversations (a conversation.manage peer). */
    FULL(PEER, CONV_A),

    /** Paired with another agent only: the router's agent scope guard refuses before the handler. */
    AGENT_OUT(PEER.copy(agents = setOf("agent-other")), CONV_A),

    /** Bounded to another conversation (a chat-only peer viewing elsewhere). */
    CONVERSATION_OUT(PEER.copy(conversations = setOf("conv-elsewhere")), CONV_A),

    /** Addresses agent A with a conversation that belongs to agent B. */
    FOREIGN_CONVERSATION(PEER, CONV_B),
}

/** The caller's grant on the canvas it proposes. */
private enum class Proposal(val level: CanvasMountGrant) {
    MOUNT(CanvasMountGrant.MOUNT),
    SEE(CanvasMountGrant.SEE),
    NONE(CanvasMountGrant.NONE),
}

/** What the addressed record holds before the call, and whether the caller may see it. */
private enum class Winner(val stored: CanvasId?) { ABSENT(null), VISIBLE_WINNER(VISIBLE), HIDDEN_WINNER(HIDDEN) }

/** The answer the policy requires. */
private enum class Expect { ROUTER_FORBIDDEN, DENIED, NONE, FOUND, BOUND_WON, BOUND_LOST, REPLACED, DEFAULT_SET }

private class Case(val route: CanvasMountRoute, val scope: Scope, val proposal: Proposal, val winner: Winner) {
    override fun toString(): String = "$route/$scope/$proposal/$winner"
}

/**
 * Table-driven authorization matrix through the real router: every route x connection scope x proposal grant x
 * winner visibility. Denials must leave the record untouched and never name the hidden winner.
 */
class CanvasMountAuthorizationMatrixTest {
    @Test
    fun everyRouteScopeProposalAndWinnerCombinationAnswersThePolicy() = runBlocking {
        val cases = CanvasMountRoute.entries.flatMap { route ->
            Scope.entries.flatMap { scope ->
                Proposal.entries.flatMap { proposal -> Winner.entries.map { Case(route, scope, proposal, it) } }
            }
        }
        assertEquals(CanvasMountRoute.entries.size * Scope.entries.size * Proposal.entries.size * Winner.entries.size, cases.size)
        cases.forEach { check(it) }
    }

    private suspend fun check(case: Case) {
        val harness = MountHarness()
        seed(harness, case)
        val reply = harness.call(case.route, paramsFor(case.route, case.scope.conversation, PROPOSAL), case.scope.peer)
        val expected = expected(case)
        verify(reply, expected, case)
        assertFalse(HIDDEN.value in reply.raw, "$case leaked the hidden winner")
        if (expected == Expect.DENIED || expected == Expect.ROUTER_FORBIDDEN) {
            assertEquals(case.winner.stored, stored(harness, case), "$case changed the record")
        }
    }

    private suspend fun seed(harness: MountHarness, case: Case) {
        harness.grant(PEER, PROPOSAL, case.proposal.level)
        harness.grant(PEER, VISIBLE, CanvasMountGrant.SEE)
        val canvas = case.winner.stored ?: return
        val store = harness.store()
        if (case.route == CanvasMountRoute.SET_DEFAULT) store.setDefault(AGENT_A, canvas)
        else store.bindIfAbsent(AGENT_A, case.scope.conversation, canvas)
    }

    private suspend fun stored(harness: MountHarness, case: Case): CanvasId? =
        if (case.route == CanvasMountRoute.SET_DEFAULT) harness.store().readDefault(AGENT_A)
        else harness.store().readBinding(AGENT_A, case.scope.conversation)

    private fun verify(reply: Reply, expected: Expect, case: Case) {
        val label = "$case -> $reply"
        when (expected) {
            Expect.ROUTER_FORBIDDEN -> assertEquals(AgentScopeGuard.DENIED, reply.error, label)
            Expect.DENIED -> assertEquals(DENIED_RESPONSE, reply.raw, label)
            else -> assertEquals(success(expected), Triple(reply.status, reply.canvas, reply.won), label)
        }
    }

    private fun success(expected: Expect): Triple<String?, String?, Boolean?> = when (expected) {
        Expect.NONE -> Triple(CanvasMountWire.NONE, null, null)
        Expect.FOUND -> Triple(CanvasMountWire.FOUND, VISIBLE.value, null)
        Expect.BOUND_WON -> Triple(CanvasMountWire.BOUND, PROPOSAL.value, true)
        Expect.BOUND_LOST -> Triple(CanvasMountWire.BOUND, VISIBLE.value, false)
        Expect.REPLACED -> Triple(CanvasMountWire.REPLACED, PROPOSAL.value, null)
        else -> Triple(CanvasMountWire.DEFAULT_SET, PROPOSAL.value, null)
    }

    private fun expected(case: Case): Expect = when {
        case.scope == Scope.AGENT_OUT -> Expect.ROUTER_FORBIDDEN
        case.route == CanvasMountRoute.SET_DEFAULT -> expectedDefault(case)
        case.scope != Scope.FULL -> Expect.DENIED
        else -> expectedBinding(case)
    }

    /** O3: an agent default needs unrestricted conversation scope; no conversation is addressed. */
    private fun expectedDefault(case: Case): Expect = when {
        case.scope == Scope.CONVERSATION_OUT -> Expect.DENIED
        case.proposal == Proposal.MOUNT -> Expect.DEFAULT_SET
        else -> Expect.DENIED
    }

    private fun expectedBinding(case: Case): Expect = when (case.route) {
        CanvasMountRoute.RESOLVE -> expectedRead(case.winner)
        CanvasMountRoute.BIND -> if (case.proposal == Proposal.MOUNT) expectedBind(case.winner) else Expect.DENIED
        else -> if (case.proposal == Proposal.MOUNT) Expect.REPLACED else Expect.DENIED
    }

    private fun expectedRead(winner: Winner): Expect = when (winner) {
        Winner.ABSENT -> Expect.NONE
        Winner.VISIBLE_WINNER -> Expect.FOUND
        Winner.HIDDEN_WINNER -> Expect.DENIED
    }

    private fun expectedBind(winner: Winner): Expect = when (winner) {
        Winner.ABSENT -> Expect.BOUND_WON
        Winner.VISIBLE_WINNER -> Expect.BOUND_LOST
        Winner.HIDDEN_WINNER -> Expect.DENIED
    }
}
