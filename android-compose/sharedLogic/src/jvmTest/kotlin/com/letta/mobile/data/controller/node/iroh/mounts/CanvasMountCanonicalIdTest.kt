package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.CanvasAcl
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasRelayFeatures
import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.CanvasRelayMessage
import com.letta.mobile.data.canvas.CanvasRelayProtocol
import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasEntry
import com.letta.mobile.data.canvas.HostCanvasTools
import com.letta.mobile.data.canvas.mount.AuthorizedCanvasMounts
import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.identity.ToolCallScope
import com.letta.mobile.data.controller.extras.verifiedToolCaller
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** Agent A's canonical canvas id, which a device proposes for agent B's conversation. */
private val A_CANONICAL = CanvasId.forConversation(CONV_A)

private val NO_CANVAS = JsonObject(emptyMap())

/**
 * A conversation's canonical canvas id is "its own" only while the host's records agree. A device scoped to agent A
 * binds agent B's conversation topic to A's canonical id through the relay; neither B's canvas tools nor the mount
 * routes may then turn that id into a way for agent A to reach B's board.
 */
class CanvasMountCanonicalIdTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val harness = MountHarness()
    private val records = harness.records
    private val relayHost = CanvasRelayHost(records.relay, hostId = { "host" })
    private val backend = HostCanvasBackend(
        relayHost,
        records.relay,
        records.canvases,
        mounts = CanvasMountToolLookup(
            { authority -> AuthorizedCanvasMounts(harness.store(), authority) },
            testDeadline(10.seconds, 1.seconds),
        ),
    )
    private val tools = ExternalToolRegistry.hostTools(HostCanvasTools.all(backend))
    private val scopedToA = Peer(PEER.endpoint, agents = setOf(AGENT_A.value))

    @Test
    fun theOtherConversationsCanvasToolsRefuseAnIdTheRelayBoundForAnotherConversation() = runBlocking {
        joinAsDevice(CanvasRelayProtocol.conversationTopic(CONV_B), A_CANONICAL)
        assertTrue(records.relay.isBound(A_CANONICAL, CONV_B))

        // Agent B uses its conversation's canvas: the relay's foreign id is refused and nothing is recorded.
        val bScene = tool(CanvasToolContract.GET_SCENE, NO_CANVAS, AGENT_B, CONV_B).refusal()
        assertEquals("Canvas not found: ${CanvasId.forConversation(CONV_B).value}", bScene)
        assertTrue(tool(CanvasToolContract.APPLY_OPS, ops("b-secret"), AGENT_B, CONV_B) is ExternalToolResult.Error)
        assertNull(records.canvases.get(A_CANONICAL), "no directory entry may record A's id for B's conversation")
        assertNull(records.canvases.forConversation(CONV_B))

        // The device's replace of A's binding with A's canonical id stays harmless: A's writes land on A's own topic.
        assertEquals(CanvasMountWire.REPLACED, replaceWithOwnCanonical().status)
        tool(CanvasToolContract.APPLY_OPS, ops("a-own"), AGENT_A, CONV_A).success()
        assertEquals(0L, records.relay.head(CanvasRelayProtocol.conversationTopic(CONV_B)), "nothing reached B's conversation topic")
        assertEquals(1L, records.relay.head(CanvasRelayProtocol.conversationTopic(CONV_A)))
    }

    @Test
    fun anIdRecordedForAnotherConversationIsNotOwnedByItsIdString() = runBlocking {
        // A directory entry left by the old adoption: A's canonical id, recorded for B's conversation, B its writer.
        val acl = CanvasAcl(ownerUserId = "local-user", writerAgentIds = setOf(AGENT_B.value), readerAgentIds = setOf(AGENT_B.value))
        records.canvases.putIfAbsent(
            HostCanvasEntry(A_CANONICAL.value, CanvasRelayProtocol.conversationTopic(CONV_B), "Conversation canvas", CONV_B.value, acl),
        )

        listOf(CanvasMountRoute.BIND, CanvasMountRoute.REPLACE).forEach { route ->
            assertEquals(DENIED_RESPONSE, harness.call(route, bindingParams(AGENT_A, CONV_A, A_CANONICAL), scopedToA).raw, route.method)
        }
        val listed = harness.call(CanvasMountRoute.CANDIDATES, bindingParams(AGENT_A, CONV_A), scopedToA).listed.orEmpty()
        assertTrue(A_CANONICAL.value !in listed, "candidates list nothing a proposal refuses")
        assertTrue("cannot read" in tool(CanvasToolContract.GET_SCENE, NO_CANVAS, AGENT_A, CONV_A).refusal())
    }

    @Test
    fun theOwnCanonicalIdStaysProposableWhileUnusedOrHeldByItsOwnConversation() = runBlocking {
        assertEquals(CanvasMountWire.REPLACED, replaceWithOwnCanonical().status)
        tool(CanvasToolContract.APPLY_OPS, ops("claimed"), AGENT_A, CONV_A).success()
        assertEquals(CONV_A.value, records.canvases.get(A_CANONICAL)?.conversationId)
        assertEquals(CanvasMountWire.REPLACED, replaceWithOwnCanonical().status)
    }

    private suspend fun replaceWithOwnCanonical(): Reply =
        harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, A_CANONICAL), scopedToA)

    private suspend fun joinAsDevice(topic: String, proposed: CanvasId) {
        val session = relayHost.connect("app:device") { }
        session.receive(CanvasRelayMessage.Join(topic, proposed.value, 0L, CanvasRelayFeatures.SUPPORTED))
    }

    private suspend fun tool(name: String, input: JsonObject, agent: AgentId, conversation: ConversationId): ExternalToolResult =
        tools.invoke(name, input, verifiedToolCaller(ToolCallScope(agent.value, conversation.value)))

    private fun ops(text: String) = buildJsonObject {
        put(
            "ops",
            JsonArray(
                listOf(
                    json.encodeToJsonElement<CanvasOp>(
                        CanvasOp.AddElementOp(
                            opId = "op-$text", actorId = "anyone", lamport = 1L, elementId = "el-$text",
                            elementJson = buildJsonObject {
                                put("id", "el-$text")
                                put("type", "Text")
                                put("text", text)
                                put("textTopLeft", "10.0,10.0")
                            }.toString(),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun ExternalToolResult.success(): String = assertIs<ExternalToolResult.Success>(this, "tool call failed: $this").content

    private fun ExternalToolResult.refusal(): String = assertIs<ExternalToolResult.Error>(this, "expected a refusal, got $this").error
}
