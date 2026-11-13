package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasCaller
import com.letta.mobile.data.canvas.mount.AuthorizedCanvasMounts
import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

private val AGENT_DEFAULT = CanvasId("canvas-agent-default")
private val PICKED = CanvasId("canvas-picked-for-one-conversation")

/**
 * Owner decision: a conversation's binding overrides its agent's default, which overrides the conversation's own
 * board. The resolve route and the host canvas tools (through the mount lookup) must agree at every level.
 */
class CanvasMountResolutionOrderTest {
    private val harness = MountHarness()
    private val backend = HostCanvasBackend(
        CanvasRelayHost(harness.records.relay, hostId = { "host" }),
        harness.records.relay,
        harness.records.canvases,
        mounts = CanvasMountToolLookup(
            { authority -> AuthorizedCanvasMounts(harness.store(), authority) },
            testDeadline(10.seconds, 1.seconds),
        ),
    )

    @Test
    fun bindingThenAgentDefaultThenTheConversationsOwnBoard() = runBlocking {
        harness.canvas(AGENT_DEFAULT, AGENT_A)
        harness.canvas(PICKED, AGENT_A)

        // Level 3: nothing stored, so each conversation shows its own board.
        assertShows(CONV_A, null, null, CanvasId.forConversation(CONV_A))

        // Level 2: the agent default reaches every conversation of the agent without a binding.
        assertEquals(CanvasMountWire.DEFAULT_SET, harness.call(CanvasMountRoute.SET_DEFAULT, defaultParams(AGENT_A, AGENT_DEFAULT), PEER).status)
        assertShows(CONV_A, AGENT_DEFAULT, CanvasMountWire.SOURCE_DEFAULT, AGENT_DEFAULT)
        assertShows(CONV_A2, AGENT_DEFAULT, CanvasMountWire.SOURCE_DEFAULT, AGENT_DEFAULT)

        // Level 1: a conversation's own binding wins over the default, for that conversation only.
        assertEquals(CanvasMountWire.REPLACED, harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, PICKED), PEER).status)
        assertShows(CONV_A, PICKED, CanvasMountWire.SOURCE_BINDING, PICKED)
        assertShows(CONV_A2, AGENT_DEFAULT, CanvasMountWire.SOURCE_DEFAULT, AGENT_DEFAULT)

        // Clearing the default sends unbound conversations back to their own board; bindings stay.
        assertEquals(CanvasMountWire.DEFAULT_CLEARED, harness.call(CanvasMountRoute.CLEAR_DEFAULT, clearParams(AGENT_A), PEER).status)
        assertShows(CONV_A, PICKED, CanvasMountWire.SOURCE_BINDING, PICKED)
        assertShows(CONV_A2, null, null, CanvasId.forConversation(CONV_A2))
    }

    @Test
    fun aConversationKeepsItsOwnBoardDespiteAnAgentDefaultByBindingItsCanonicalId() = runBlocking {
        harness.canvas(AGENT_DEFAULT, AGENT_A)
        harness.call(CanvasMountRoute.SET_DEFAULT, defaultParams(AGENT_A, AGENT_DEFAULT), PEER)
        val own = CanvasId.forConversation(CONV_A2)

        assertEquals(CanvasMountWire.REPLACED, harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A2, own), PEER).status)

        assertShows(CONV_A2, own, CanvasMountWire.SOURCE_BINDING, own)
        assertShows(CONV_A, AGENT_DEFAULT, CanvasMountWire.SOURCE_DEFAULT, AGENT_DEFAULT)
    }

    /** The resolve route answers [resolved] from [source] (null: `none`), and the canvas tools act on [tools]. */
    private suspend fun assertShows(conversation: ConversationId, resolved: CanvasId?, source: String?, tools: CanvasId) {
        val reply = harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, conversation), PEER)
        val expectedStatus = if (resolved == null) CanvasMountWire.NONE else CanvasMountWire.FOUND
        assertEquals(listOf(expectedStatus, resolved?.value, source), listOf(reply.status, reply.canvas, reply.source), "$conversation resolve")
        assertEquals(tools, backend.currentCanvas(HostCanvasCaller(AGENT_A.value, conversation.value)), "$conversation tools")
    }
}
