package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.HostCanvasComposeToolsTest.Companion.receipt
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasResolver
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasTarget
import com.letta.mobile.data.canvas.mount.client.FakeCanvasMountClient
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The app's canvas tools default to the board the person sees: the conversation's mount, else its own. */
class CanvasToolsFollowMountTest {
    private val store = InMemoryCanvasDocumentStore()
    private val client = FakeCanvasMountClient()
    private val registry = ExternalToolRegistry.hostTools(
        CanvasExternalTools.all(store, CanvasSessionRegistry(), mounts = ConversationCanvasResolver(client)::mountedCanvas),
    )
    private val own = CanvasId.forConversation(ConversationId(CONVERSATION))
    private val mounted = CanvasId("canvas-made-with-another-agent")
    private val target = ConversationCanvasTarget(AgentId(AGENT), ConversationId(CONVERSATION))

    private suspend fun boards() {
        listOf(own to CONVERSATION, mounted to "conv-elsewhere").forEach { (id, conversation) ->
            store.upsert(
                CanvasDocument(
                    id = id, agentId = AGENT, conversationId = conversation, title = id.value, revision = 1L, sceneJson = "",
                    acl = CanvasAcl(CanvasSession.LOCAL_USER_ACTOR_ID, writerAgentIds = setOf(AGENT)), updatedAtEpochMs = 0L,
                ),
            )
        }
    }

    private suspend fun compose(): ExternalToolResult = registry.invoke(
        CanvasToolContract.COMPOSE,
        Json.parseToJsonElement("""{"items":[{"kind":"NOTE","markdown":"From the agent"}]}""").jsonObject,
        ExternalToolCaller(agentId = AGENT, conversationId = CONVERSATION, toolCallId = "call-1"),
    )

    private suspend fun listed(): String = assertIs<ExternalToolResult.Success>(
        registry.invoke(CanvasToolContract.LIST, JsonObject(mapOf("conversation_id" to JsonPrimitive(CONVERSATION))), AGENT),
    ).content

    @Test
    fun aMountedCanvasIsTheOneTheAgentWritesTo() = runTest {
        boards()
        client.stored[target] = mounted

        assertEquals(mounted.value, receipt(compose()).canvasId)
        assertTrue(store.get(mounted)!!.revision > 1L)
        assertEquals(1L, store.get(own)!!.revision)
        assertTrue(mounted.value in listed())
    }

    @Test
    fun withoutAMountTheConversationsOwnBoardIsWrittenTo() = runTest {
        boards()
        assertEquals(own.value, receipt(compose()).canvasId)
        assertTrue(own.value in listed())
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-1"
    }
}
