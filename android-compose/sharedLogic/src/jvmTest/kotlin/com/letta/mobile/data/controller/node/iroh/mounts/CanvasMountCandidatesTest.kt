package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.CanvasAcl
import com.letta.mobile.data.canvas.CanvasRelayProtocol
import com.letta.mobile.data.canvas.HostCanvasEntry
import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.controller.node.iroh.AgentScopeGuard
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val A_BOARD = CanvasId("canvas-agent-a-board")
private val B_BOARD = CanvasId("canvas-second-owner-board")
private val RELAY_ONLY = CanvasId("canvas-made-by-an-app")
private val A_SCOPED_PEER = PEER.copy(agents = setOf(AGENT_A.value))

/** Owner decision D6: `canvas.mount.candidates`, bounded and read-only, through the real router. */
class CanvasMountCandidatesTest {
    @Test
    fun refusalsAreTheOneByteIdenticalDenialAndTheRouterGuard() = runBlocking {
        val harness = hosted()
        val conversationOut = harness.candidates(CONV_A, PEER.copy(conversations = setOf("x")))
        val foreignConversation = harness.candidates(CONV_B, PEER)
        val unknownConversation = harness.candidates(ConversationId("conv-none"), PEER)
        val agentOut = harness.candidates(CONV_A, PEER.copy(agents = setOf("agent-other")))
        listOf(conversationOut, foreignConversation, unknownConversation).forEach { assertEquals(DENIED_RESPONSE, it.raw) }
        assertEquals(AgentScopeGuard.DENIED, agentOut.error)
        val strict = harness.call(CanvasMountRoute.CANDIDATES, bindingParams(AGENT_A, CONV_A, A_BOARD), PEER)
        assertEquals("canvas.mount.invalid_input:params:unknown_field", strict.error)
    }

    @Test
    fun theOwnCanvasComesFirstThenWhatTheScopeMaySeeByKindNeverRelayOnlyCanvases() = runBlocking {
        val harness = hosted()
        val full = harness.candidates(CONV_A, PEER)
        val ownCanvas = CanvasId.forConversation(CONV_A).value
        val otherConversation = CanvasId.forConversation(CONV_A2).value
        assertEquals(listOf(ownCanvas, A_BOARD.value, B_BOARD.value, otherConversation), full.listed)
        assertEquals(false, full.truncated)
        val kinds = canvases(full).associate { it.first to it.second }
        assertEquals(CanvasMountWire.OWNER_CONVERSATION, kinds[ownCanvas])
        assertEquals(CanvasMountWire.OWNER_AGENT, kinds[A_BOARD.value])
        assertEquals(CanvasMountWire.OWNER_CONVERSATION, kinds[otherConversation])
        assertTrue("agent-b" !in full.raw, "an owner id must never be answered")

        val scoped = harness.candidates(CONV_A, A_SCOPED_PEER)
        assertEquals(listOf(ownCanvas, A_BOARD.value, otherConversation), scoped.listed)
        assertNull(harness.store().readBinding(AGENT_A, CONV_A), "listing must not touch a mount record")
    }

    @Test
    fun theAnswerIsCappedAndSaysSo() = runBlocking {
        val harness = MountHarness()
        repeat(CanvasMountWire.MAX_CANDIDATES + 50) { harness.canvas(CanvasId("canvas-many-$it"), AGENT_A) }
        val reply = harness.candidates(CONV_A, PEER)
        assertEquals(CanvasMountWire.MAX_CANDIDATES, reply.listed?.size)
        assertEquals(CanvasId.forConversation(CONV_A).value, reply.listed?.first())
        assertEquals(true, reply.truncated)
    }

    @Test
    fun oneCallReadsTheDirectoryOnceAndLooksEachOwnerUpAtMostOnce() = runBlocking {
        val harness = hosted()
        val before = harness.directory.ownerLookups.get()
        val reply = harness.candidates(CONV_A, PEER)
        assertEquals(CanvasMountWire.LISTED, reply.status)
        assertEquals(1, harness.canvases.reads.get(), "the directory is read once per call")
        // The binding's own conversation (addressing) and the one other conversation canvas, each once.
        assertEquals(2, harness.directory.ownerLookups.get() - before)
        harness.candidates(CONV_A, PEER)
        assertEquals(2, harness.canvases.reads.get(), "nothing is kept across calls")
    }

    @Test
    fun atMostTheScanBoundIsJudgedAndTheAnswerSaysCanvasesWereLeftOut() = runBlocking {
        val harness = MountHarness()
        repeat(CanvasMountCandidates.MAX_SCANNED + 20) { harness.canvas(CanvasId("canvas-b-$it"), AGENT_B) }
        harness.canvas(CanvasId("canvas-a-late"), AGENT_A)
        val reply = harness.candidates(CONV_A, A_SCOPED_PEER)
        assertEquals(listOf(CanvasId.forConversation(CONV_A).value), reply.listed, "the unscanned tail is not listed")
        assertEquals(true, reply.truncated)
    }

    @Test
    fun aFailedHostLookupAnswersUnavailableNeverAPartialList() = runBlocking {
        val harness = hosted()
        // Lookup 1 authorizes addressing the binding; lookup 2, the other conversation canvas's owner, fails.
        harness.directory.failFromLookup = 2
        harness.directory.failure = IllegalStateException("conversation records down")
        val reply = harness.candidates(CONV_A, PEER)
        assertEquals(CanvasMountWire.UNAVAILABLE, reply.status)
        assertEquals(CanvasMountWire.REASON_AUTHORITY, reply.reason)
        assertNull(reply.listed)
    }

    private suspend fun MountHarness.candidates(conversation: ConversationId, peer: Peer): Reply =
        call(CanvasMountRoute.CANDIDATES, bindingParams(AGENT_A, conversation), peer)

    private suspend fun hosted(): MountHarness = MountHarness().apply {
        canvas(A_BOARD, AGENT_A)
        canvas(B_BOARD, AGENT_B)
        canvas(RELAY_ONLY, null)
        val acl = CanvasAcl(ownerUserId = "local-user", writerAgentIds = setOf(AGENT_A.value), readerAgentIds = setOf(AGENT_A.value))
        val topic = CanvasRelayProtocol.conversationTopic(CONV_A2)
        canvases.putIfAbsent(HostCanvasEntry(CanvasId.forConversation(CONV_A2).value, topic, "Second", CONV_A2.value, acl))
    }

    /** (canvas id, owner kind) of every listed candidate. */
    private fun canvases(reply: Reply): List<Pair<String, String>> =
        Json.parseToJsonElement(reply.raw).jsonObject.getValue("result").jsonObject.getValue(CanvasMountWire.CANVASES).jsonArray.map {
            val candidate = it.jsonObject
            candidate.getValue(CanvasMountWire.CANVAS_ID).jsonPrimitive.content to
                candidate.getValue(CanvasMountWire.OWNER_KIND).jsonPrimitive.content
        }
}
