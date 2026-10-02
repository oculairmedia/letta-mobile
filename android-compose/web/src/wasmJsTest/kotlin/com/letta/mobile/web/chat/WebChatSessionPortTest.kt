package com.letta.mobile.web.chat

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.chat.session.TimelineChatRunControls
import com.letta.mobile.ui.chat.session.TimelineChatTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-o4ygk.4.5: the web's port against a fake App Server timeline transport, the way
 * desktop's port tests drive DesktopChatSessionPort: the real shared timeline loop and presenter
 * run, only the socket is fake.
 */
class WebChatSessionPortTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val target = TimelineChatTarget(agentId = "agent-1", agentName = "Nora", conversationId = "conv-1")

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun hydratesTheConversationIntoTheSharedPageState() = runTest {
        val transport = FakeTimelineTransport(history = listOf(user("m-1", "hi"), assistant("m-2", "hello there")))
        val port = WebChatSessionPort(WebChatBinding(target, transport, TimelineChatRunControls()), scope)

        val state = awaitState(port) { it.conversationState is ConversationState.Ready && it.messages.size == 2 }

        assertEquals("Nora", state.agentName)
        assertEquals(listOf("hi", "hello there"), state.messages.map { it.content })
        port.close()
    }

    @Test
    fun aSendGoesOutOverTheTransportAndTheReplyLands() = runTest {
        val transport = FakeTimelineTransport(reply = assistant("m-9", "pong"))
        val port = WebChatSessionPort(WebChatBinding(target, transport, TimelineChatRunControls()), scope)
        awaitState(port) { it.conversationState is ConversationState.Ready }

        port.actions.updateComposerText("ping")
        port.actions.send()

        val state = awaitState(port) { ui -> ui.messages.any { it.content == "pong" } }
        assertTrue(state.messages.any { it.role == "user" && it.content == "ping" })
        assertEquals(listOf("ping"), transport.sentTexts)
        port.close()
    }

    @Test
    fun theTransportsControlsDecideWhatThePageOffers() = runTest {
        val answers = mutableListOf<ChatApprovalAnswer>()
        val withControls = WebChatSessionPort(
            WebChatBinding(target, FakeTimelineTransport(), TimelineChatRunControls(answerApproval = { answers += it })),
            scope,
        )
        val bare = WebChatSessionPort(WebChatBinding(target, FakeTimelineTransport(), TimelineChatRunControls()), scope)

        assertTrue(withControls.capabilities.value.approvals)
        assertEquals(false, bare.capabilities.value.approvals)
        assertEquals(false, bare.capabilities.value.modelSwitch)
        withControls.close()
        bare.close()
    }

    private suspend fun awaitState(port: WebChatSessionPort, predicate: (com.letta.mobile.ui.chat.render.ChatUiState) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { port.uiState.first(predicate) } }

    private fun user(id: String, text: String) =
        UserMessage(id = id, contentRaw = JsonPrimitive(text), date = "2026-10-02T09:00:00Z")

    private fun assistant(id: String, text: String) =
        AssistantMessage(id = id, contentRaw = JsonPrimitive(text), date = "2026-10-02T09:00:01Z")

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

/** A conversation's history and one canned reply; the stream stays open and quiet. */
private class FakeTimelineTransport(
    private val history: List<LettaMessage> = emptyList(),
    private val reply: LettaMessage? = null,
) : TimelineTransport {
    val sentTexts = mutableListOf<String>()
    private var delivered = history

    override suspend fun sendConversationMessage(conversationId: String, request: MessageCreateRequest): Flow<LettaMessage> {
        val text = com.letta.mobile.data.chat.send.OutboundMessageCreate.decode(request).text
        sentTexts += text
        val echo = UserMessage(id = "u-${sentTexts.size}", contentRaw = JsonPrimitive(text), date = "2026-10-02T09:01:00Z")
        delivered = delivered + listOfNotNull(echo, reply)
        return flowOf(*listOfNotNull(reply).toTypedArray())
    }

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = flow { awaitCancellation() }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = delivered

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> = delivered
}
