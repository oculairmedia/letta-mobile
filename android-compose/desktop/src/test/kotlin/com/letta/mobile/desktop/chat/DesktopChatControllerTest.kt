package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.TimelineNoActiveRunException
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatControllerTest {
    @Test
    fun startLoadsRemoteConversationsAndHydratesSelectedTimeline() = runTest {
        val controller = testController(FakeDesktopChatGateway())

        controller.start()
        runCurrent()

        val state = controller.state.value
        assertTrue(state.isRemoteBacked)
        assertFalse(state.isLoading)
        assertEquals("conv-1", state.selectedConversationId)
        assertEquals("Remote planning", state.conversations.first().title)
        assertTrue(state.selectedMessages.any { it.role == "user" && it.content == "Hello from history" })

        controller.close()
    }

    @Test
    fun sendUsesRemoteTimelineTransportAndUpdatesMessages() = runTest {
        val gateway = FakeDesktopChatGateway()
        val controller = testController(gateway)

        controller.start()
        runCurrent()
        controller.updateComposerText("Ship live desktop chat")
        controller.send()
        runCurrent()

        val state = controller.state.value
        assertEquals("", state.composerText)
        assertFalse(state.isSending)
        val sentMessage = gateway.sentRequests.single().messages?.single() as JsonObject
        assertEquals("user", sentMessage["role"]?.jsonPrimitive?.content)
        assertEquals("Ship live desktop chat", sentMessage["content"]?.jsonPrimitive?.content)
        assertTrue(state.selectedMessages.any { it.role == "assistant" && it.content == "Remote response" })

        controller.close()
    }

    @Test
    fun unavailableBackendKeepsLocalPreviewAndSurfacesError() = runTest {
        val controller = testController(
            object : FakeDesktopChatGateway() {
                override suspend fun listConversations(limit: Int): List<Conversation> {
                    error("backend offline")
                }
            },
        )

        controller.start()
        runCurrent()

        val state = controller.state.value
        assertFalse(state.isRemoteBacked)
        assertFalse(state.isLoading)
        assertNotNull(state.errorMessage)
        assertEquals("desktop-readiness", state.selectedConversationId)

        controller.close()
    }

    private fun TestScope.testController(gateway: DesktopChatGateway): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            gatewayFactory = { gateway },
        )
}

open class FakeDesktopChatGateway : DesktopChatGateway {
    val sentRequests = mutableListOf<MessageCreateRequest>()

    override suspend fun listConversations(limit: Int): List<Conversation> = listOf(
        Conversation(
            id = ConversationId("conv-1"),
            agentId = AgentId("agent-1"),
            summary = "Remote planning",
            createdAt = "2026-06-07T01:00:00Z",
            updatedAt = "2026-06-07T01:01:00Z",
            lastMessageAt = "2026-06-07T01:02:00Z",
        ),
    )

    override suspend fun getConversation(conversationId: String): Conversation =
        listConversations().first { it.id.value == conversationId }

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> {
        sentRequests += request
        return flowOf(
            AssistantMessage(
                id = "assistant-1",
                contentRaw = JsonPrimitive("Remote response"),
                date = "2026-06-07T01:03:00Z",
                runId = "run-1",
                stepId = "step-1",
            ),
        )
    }

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> {
        throw TimelineNoActiveRunException(conversationId)
    }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = listOf(
        UserMessage(
            id = "user-1",
            contentRaw = JsonPrimitive("Hello from history"),
            date = "2026-06-07T01:00:00Z",
        ),
    )

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> = emptyList()
}
