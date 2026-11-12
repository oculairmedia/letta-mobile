package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.CanvasListResult
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasCaller
import com.letta.mobile.data.canvas.HostCanvasTools
import com.letta.mobile.data.canvas.mount.AuthorizedCanvasMounts
import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An agent no conversation of the harness belongs to. */
private val AGENT_C = AgentId("agent-c")
private val CONV_C = ConversationId("conv-c")

/** A canvas only the relay holds (made by an app): no known owner, not in the host directory. */
private val APP_MADE = CanvasId("canvas-app-made")

/** Tool input naming nothing: the call acts on the conversation's mounted (else own) canvas. */
private val NOTHING = JsonObject(emptyMap())

/**
 * Owner decision D5, grant on mount: the canvas mounted for a conversation (through the real routes) may be read and
 * written by that conversation's own agent through its host canvas tools, derived from the live mount at every check.
 */
class CanvasMountGrantTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val harness = MountHarness()
    private val records = harness.records
    private val lookup = CanvasMountToolLookup(
        { authority -> AuthorizedCanvasMounts(harness.store(), authority) },
        testDeadline(10.seconds, 1.seconds),
    )
    private val backend = HostCanvasBackend(
        CanvasRelayHost(records.relay, hostId = { "host" }),
        records.relay,
        records.canvases,
        mounts = lookup,
    )
    private val tools = ExternalToolRegistry.hostTools(HostCanvasTools.all(backend))

    @Test
    fun aCanvasCreatedByAnotherAgentIsReadableAndWritableOnceMountedForTheConversation() = runBlocking {
        val board = backend.create(HostCanvasCaller(AGENT_B.value, CONV_B.value), "Agent B's board")
        assertEquals(CanvasMountWire.REPLACED, mount(CONV_A, board.id).status)

        call(CanvasToolContract.APPLY_OPS, ops("mounted-write"), AGENT_A, CONV_A).success()
        assertTrue("mounted-write" in call(CanvasToolContract.GET_SCENE, NOTHING, AGENT_A, CONV_A).success())
        assertTrue("mounted-write" in call(CanvasToolContract.GET_SCENE, named(board.id), AGENT_A, CONV_A).success())
        assertTrue(call(CanvasToolContract.APPLY_OPS, ops("named-write", board.id), AGENT_A, CONV_A) is ExternalToolResult.Success)
        val listed = canvasList(AGENT_A, CONV_A)
        assertEquals(board.canvasId, listed.canvases.first { it.current }.canvasId)
        // The canvas's stored ACL is untouched: the access is the mount's, not a rewritten ACL.
        assertEquals(setOf(AGENT_B.value), records.canvases.get(board.id)?.acl?.writerAgentIds)
    }

    @Test
    fun replacingBackToTheConversationsOwnCanvasRevokesTheAccessAtOnce() = runBlocking {
        val board = backend.create(HostCanvasCaller(AGENT_B.value, CONV_B.value), "Agent B's board")
        mount(CONV_A, board.id)
        call(CanvasToolContract.APPLY_OPS, ops("while-mounted"), AGENT_A, CONV_A).success()

        assertEquals(CanvasMountWire.REPLACED, mount(CONV_A, CanvasId.forConversation(CONV_A)).status)
        assertTrue("cannot read" in call(CanvasToolContract.GET_SCENE, named(board.id), AGENT_A, CONV_A).refusal())
        assertTrue("cannot read" in call(CanvasToolContract.APPLY_OPS, ops("after", board.id), AGENT_A, CONV_A).refusal())
        val own = call(CanvasToolContract.GET_SCENE, NOTHING, AGENT_A, CONV_A).success()
        assertTrue("while-mounted" !in own)
        val listed = canvasList(AGENT_A, CONV_A)
        assertTrue(listed.canvases.none { it.canvasId == board.canvasId })
    }

    @Test
    fun noOtherConversationOrAgentGainsAnythingFromAMount() = runBlocking {
        val board = backend.create(HostCanvasCaller(AGENT_B.value, CONV_B.value), "Agent B's board")
        mount(CONV_A, board.id)
        // The same agent in another conversation, an agent of another conversation, and the agent outside any conversation.
        assertTrue("cannot read" in call(CanvasToolContract.GET_SCENE, named(board.id), AGENT_A, CONV_A2).refusal())
        assertTrue("cannot read" in call(CanvasToolContract.GET_SCENE, named(board.id), AGENT_C, CONV_C).refusal())
        assertTrue("cannot read" in call(CanvasToolContract.APPLY_OPS, ops("x", board.id), AGENT_C, CONV_C).refusal())
        assertTrue("cannot read" in call(CanvasToolContract.GET_SCENE, named(board.id), AGENT_A, null).refusal())
        // A runtime stamp naming agent C on conversation A reads C's (absent) binding, never A's.
        assertTrue("cannot read" in call(CanvasToolContract.GET_SCENE, named(board.id), AGENT_C, CONV_A).refusal())
    }

    @Test
    fun anAgentsDefaultMountGrantsOnlyWhereItIsTheEffectiveMount() = runBlocking {
        val board = backend.create(HostCanvasCaller(AGENT_B.value, CONV_B.value), "Agent B's board")
        assertEquals(CanvasMountWire.DEFAULT_SET, harness.call(CanvasMountRoute.SET_DEFAULT, defaultParams(AGENT_A, board.id), PEER).status)
        mount(CONV_A, CanvasId.forConversation(CONV_A))

        // CONV_A2 has no binding, so the default is its mount; CONV_A's own binding wins over the default.
        call(CanvasToolContract.APPLY_OPS, ops("via-default"), AGENT_A, CONV_A2).success()
        assertTrue("via-default" in call(CanvasToolContract.GET_SCENE, named(board.id), AGENT_A, CONV_A2).success())
        assertTrue("cannot read" in call(CanvasToolContract.GET_SCENE, named(board.id), AGENT_A, CONV_A).refusal())
    }

    @Test
    fun anAppMadeRelayOnlyCanvasWorksOnceMountedAndIsNeverCreatedByAsking() = runBlocking {
        records.canvas(APP_MADE, owner = null)
        assertEquals("Canvas not found: ${APP_MADE.value}", call(CanvasToolContract.GET_SCENE, named(APP_MADE), AGENT_A, CONV_A).refusal())

        assertEquals(CanvasMountWire.REPLACED, mount(CONV_A, APP_MADE).status)
        call(CanvasToolContract.APPLY_OPS, ops("on-app-board"), AGENT_A, CONV_A).success()
        assertTrue("on-app-board" in call(CanvasToolContract.GET_SCENE, named(APP_MADE), AGENT_A, CONV_A).success())
        val listed = canvasList(AGENT_A, CONV_A)
        assertEquals(APP_MADE.value, listed.canvases.first { it.current }.canvasId)
        assertNull(records.canvases.get(APP_MADE), "a mount grant must not create a directory entry")

        mount(CONV_A, CanvasId.forConversation(CONV_A))
        assertEquals("Canvas not found: ${APP_MADE.value}", call(CanvasToolContract.GET_SCENE, named(APP_MADE), AGENT_A, CONV_A).refusal())
    }

    @Test
    fun aMountedCanvasTheHostDoesNotHaveIsRefusedNotGuessedOrCreated() = runBlocking {
        val gone = CanvasId("canvas-gone")
        harness.store().replaceBinding(AGENT_A, CONV_A, gone)
        assertEquals("Canvas not found: ${gone.value}", call(CanvasToolContract.GET_SCENE, NOTHING, AGENT_A, CONV_A).refusal())
        assertNull(records.relay.boundCanvas(com.letta.mobile.data.canvas.CanvasRelayProtocol.canvasTopic(gone)))
        assertNull(records.canvases.get(gone))
    }

    private suspend fun canvasList(agent: AgentId, conversation: ConversationId): CanvasListResult =
        json.decodeFromString(call(CanvasToolContract.LIST, NOTHING, agent, conversation).success())

    private suspend fun mount(conversation: ConversationId, canvas: CanvasId): Reply =
        harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, conversation, canvas), PEER)

    private suspend fun call(tool: String, input: JsonObject, agent: AgentId, conversation: ConversationId?): ExternalToolResult =
        tools.invoke(tool, input, agentId = agent.value, conversationId = conversation?.value)

    private fun named(canvas: CanvasId) = buildJsonObject { put("canvas_id", canvas.value) }

    private fun ops(text: String, canvas: CanvasId? = null) = buildJsonObject {
        canvas?.let { put("canvas_id", it.value) }
        put("ops", JsonArray(listOf(json.encodeToJsonElement<CanvasOp>(addText(text)))))
    }

    private fun addText(text: String) = CanvasOp.AddElementOp(
        opId = "model-chosen", actorId = "someone-else", lamport = 1L, elementId = "el-$text",
        elementJson = buildJsonObject {
            put("id", "el-$text")
            put("type", "Text")
            put("text", text)
            put("textTopLeft", "10.0,10.0")
        }.toString(),
    )

    private fun ExternalToolResult.success(): String = assertIs<ExternalToolResult.Success>(this, "tool call failed: $this").content

    private fun ExternalToolResult.refusal(): String = assertIs<ExternalToolResult.Error>(this, "expected a refusal, got $this").error
}
