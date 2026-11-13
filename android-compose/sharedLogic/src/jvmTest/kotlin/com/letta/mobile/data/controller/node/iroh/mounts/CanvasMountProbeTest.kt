package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.CanvasRelayProtocol
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** A canonical-shaped id of a conversation the host has never heard of. */
private val GHOST = CanvasId.forConversation(ConversationId("conv-ghost"))

/** Agent B's conversation canvas, which the relay holds. */
private val B_CANVAS = CanvasId.forConversation(CONV_B)

private val A_ONLY = PEER.copy(agents = setOf(AGENT_A.value))

/**
 * A device probing canvas ids must not tell a canvas the host does not have from one it may not see: both take the
 * same owner lookup, and when that lookup fails both are the same plain denial, never `unavailable`.
 */
class CanvasMountProbeTest {
    @Test
    fun aMissingAndAnInvisibleConversationCanvasTakeTheSameLookupsAndTheSameDenial() = runBlocking {
        val harness = withOtherAgentsCanvas()
        val missing = probe(harness, GHOST)
        val invisible = probe(harness, B_CANVAS)
        assertEquals(DENIED_RESPONSE, missing.first)
        assertEquals(missing, invisible, "same answer and the same number of owner lookups")
    }

    @Test
    fun aFailedLookupWhileJudgingAProposalIsTheSameDenialNeverUnavailable() = runBlocking {
        listOf(GHOST, B_CANVAS).forEach { canvas ->
            val harness = withOtherAgentsCanvas()
            // Lookup 1 authorizes addressing agent A's binding; every later one (the proposal's) fails.
            harness.directory.failFromLookup = harness.directory.ownerLookups.get() + 2
            harness.directory.failure = IllegalStateException("conversation records down")
            val reply = harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, canvas), A_ONLY)
            assertEquals(DENIED_RESPONSE, reply.raw, canvas.value)
            assertEquals(null, harness.store().readBinding(AGENT_A, CONV_A))
        }
    }

    /** The reply to agent A's device proposing [canvas], and how many owner lookups it made. */
    private suspend fun probe(harness: MountHarness, canvas: CanvasId): Pair<String, Int> {
        val before = harness.directory.ownerLookups.get()
        val reply = harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, canvas), A_ONLY)
        return reply.raw to harness.directory.ownerLookups.get() - before
    }

    private suspend fun withOtherAgentsCanvas(): MountHarness = MountHarness().apply {
        records.relay.bind(CanvasRelayProtocol.conversationTopic(CONV_B), B_CANVAS)
    }
}
