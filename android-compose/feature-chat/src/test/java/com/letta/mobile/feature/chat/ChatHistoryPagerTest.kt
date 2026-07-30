package com.letta.mobile.feature.chat
import com.letta.mobile.ui.chat.render.*
import com.letta.mobile.data.repository.api.OlderMessagesPage

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.mapper.toAppMessage
import com.letta.mobile.data.chat.projection.ApprovalRequestFact
import com.letta.mobile.data.chat.projection.ApprovalTerminalEvidence
import com.letta.mobile.data.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import com.letta.mobile.feature.chat.coordination.ChatHistoryPager
import com.letta.mobile.feature.chat.coordination.ChatTimelineObserver

@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryPagerTest {

    @Test
    fun `load older messages merges page through timeline observer prefix`() = runTest {
        val harness = Harness(scope = this)
        val older = appMessage(id = "older-1", content = "old")
        coEvery {
            harness.messageRepository.fetchOlderMessagesPage(AgentId("agent-1"), ConversationId("conv-1"), "live-1")
        } returns OlderMessagesPage(listOf(older), hasMore = null)
        every {
            harness.chatTimelineObserver.mergeOlderPage("conv-1", any(), any())
        } answers {
            secondArg<List<UiMessage>>() + thirdArg<List<UiMessage>>()
        }

        harness.pager.loadOlderMessages(clientModeEnabled = false)
        advanceUntilIdle()

        assertEquals(listOf("older-1", "live-1"), harness.uiState.value.messages.map { it.id })
        assertFalse(harness.uiState.value.isLoadingOlderMessages)
        coVerify(exactly = 1) {
            harness.messageRepository.fetchOlderMessagesPage(AgentId("agent-1"), ConversationId("conv-1"), "live-1")
        }
    }

    @Test
    fun `load older messages is ignored while streaming`() = runTest {
        val harness = Harness(scope = this)
        harness.uiState.value = harness.uiState.value.copy(isStreaming = true)

        harness.pager.loadOlderMessages(clientModeEnabled = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { harness.messageRepository.fetchOlderMessagesPage(any<AgentId>(), any<ConversationId>(), any()) }
    }

    @Test
    fun `stale older page result does not mutate messages`() = runTest {
        val harness = Harness(scope = this)
        harness.activeConversationId = "conv-1"
        coEvery { harness.messageRepository.fetchOlderMessagesPage(any<AgentId>(), any<ConversationId>(), any()) } answers {
            harness.activeConversationId = "conv-2"
            OlderMessagesPage(listOf(appMessage(id = "older-1", content = "old")), hasMore = null)
        }

        harness.pager.loadOlderMessages(clientModeEnabled = false)
        advanceUntilIdle()

        assertEquals(listOf("live-1"), harness.uiState.value.messages.map { it.id })
    }

    /**
     * letta-mobile-f0ixs: a SHORT page does not mean "start of conversation".
     *
     * MessageListPageGuard trims an oversized window to fit its byte budget, so a page with
     * older history still behind it can come back shorter than the requested size. The pager
     * used to infer end-of-history from page size alone, which silently truncated scroll-back
     * on exactly the long conversations where scroll-back matters.
     */
    @Test
    fun `trimmed short page with hasMore true keeps pagination open`() = runTest {
        val harness = Harness(scope = this)
        val older = appMessage(id = "older-1", content = "old")
        // One message, far below OLDER_MESSAGES_PAGE_SIZE, but the guard says more remains.
        coEvery {
            harness.messageRepository.fetchOlderMessagesPage(any<AgentId>(), any<ConversationId>(), any())
        } returns OlderMessagesPage(listOf(older), hasMore = true)

        harness.pager.loadOlderMessages(clientModeEnabled = false)
        advanceUntilIdle()

        assertTrue(
            "a trimmed page must not be read as the beginning of the conversation",
            harness.uiState.value.hasMoreOlderMessages,
        )
    }

    /** With no signal (HTTP path), the page-size heuristic still decides. */
    @Test
    fun `short page with no hasMore signal still ends pagination`() = runTest {
        val harness = Harness(scope = this)
        val older = appMessage(id = "older-1", content = "old")
        coEvery {
            harness.messageRepository.fetchOlderMessagesPage(any<AgentId>(), any<ConversationId>(), any())
        } returns OlderMessagesPage(listOf(older), hasMore = null)

        harness.pager.loadOlderMessages(clientModeEnabled = false)
        advanceUntilIdle()

        assertFalse(harness.uiState.value.hasMoreOlderMessages)
    }

    @Test
    fun `newer response resolves older approval across page boundary`() = runTest {
        val harness = Harness(scope = this)
        val response = ApprovalResponseMessage(
            id = "response-1",
            approvalRequestId = "approval-1",
            approve = false,
            runId = "run-1",
        )
        val request = ApprovalRequestMessage(
            id = "approval-1",
            runId = "run-1",
            toolCall = ToolCall(toolCallId = "call-1", name = "Bash", arguments = "{\"command\":\"pwd\"}"),
        )
        coEvery { harness.messageRepository.fetchOlderMessagesPage(any<AgentId>(), any<ConversationId>(), any()) } returnsMany
            listOf(
                OlderMessagesPage(
                    listOf(appMessage("newer-1", "newer")),
                    hasMore = true,
                    approvalEvidence = ApprovalTerminalEvidence(setOf("approval-1" to "run-1"), emptySet()),
                ),
                OlderMessagesPage(
                    listOf(request.toAppMessage()!!),
                    hasMore = false,
                    approvalRequests = listOf(ApprovalRequestFact("approval-1", "run-1", listOf("call-1"))),
                ),
            )

        harness.pager.loadOlderMessages(clientModeEnabled = false)
        advanceUntilIdle()
        harness.pager.loadOlderMessages(clientModeEnabled = false)
        advanceUntilIdle()

        val approvalRow = harness.uiState.value.messages.single { it.id == "approval-1" }
        assertEquals("tool", approvalRow.role)
        assertEquals(null, approvalRow.approvalRequest)
        assertEquals("{\"command\":\"pwd\"}", approvalRow.toolCalls!!.single().arguments)
    }

    @Test
    fun `releaseOlderMessages shrinks resident messages via the timeline observer`() = runTest {
        val harness = Harness(scope = this)
        val trimmed = listOf(uiMessage("live-1", "new"))
        every { harness.chatTimelineObserver.releaseOlderMessages("conv-1", any()) } returns trimmed

        harness.pager.releaseOlderMessages()
        advanceUntilIdle()

        assertEquals(trimmed.map { it.id }, harness.uiState.value.messages.map { it.id })
        coVerify(exactly = 0) { harness.messageRepository.fetchOlderMessagesPage(any<AgentId>(), any<ConversationId>(), any()) }
    }

    @Test
    fun `releaseOlderMessages is a no-op when the observer reports nothing to release`() = runTest {
        val harness = Harness(scope = this)
        val before = harness.uiState.value.messages
        every { harness.chatTimelineObserver.releaseOlderMessages("conv-1", any()) } returns before

        harness.pager.releaseOlderMessages()
        advanceUntilIdle()

        assertEquals(before, harness.uiState.value.messages)
    }

    private class Harness(
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        val messageRepository: MessageRepository = mockk(relaxed = true)
        val chatTimelineObserver: ChatTimelineObserver = mockk(relaxed = true)
        var activeConversationId: String? = "conv-1"
        val uiState = MutableStateFlow(
            ChatUiState(
                messages = persistentListOf(uiMessage("live-1", "new")),
                isLoadingMessages = false,
                hasMoreOlderMessages = true,
            )
        )
        val pager = ChatHistoryPager(
            scope = scope,
            agentId = "agent-1",
            messageRepository = messageRepository,
            chatTimelineObserver = chatTimelineObserver,
            uiState = uiState,
            activeConversationId = { activeConversationId },
        )

        init {
            coEvery { messageRepository.fetchOlderMessages(any<AgentId>(), any<ConversationId>(), any()) } returns emptyList()
            // letta-mobile-f0ixs: the pager reads the page variant now. mockk intercepts the
            // interface default, so stubbing only fetchOlderMessages leaves this unstubbed.
            coEvery { messageRepository.fetchOlderMessagesPage(any<AgentId>(), any<ConversationId>(), any()) } returns
                OlderMessagesPage(emptyList(), hasMore = null)
            every { chatTimelineObserver.mergeOlderPage(any(), any(), any()) } answers {
                secondArg<List<UiMessage>>() + thirdArg<List<UiMessage>>()
            }
        }
    }

    private companion object {
        fun appMessage(id: String, content: String) = AppMessage(
            id = id,
            date = Instant.parse("2026-05-10T00:00:00Z"),
            messageType = MessageType.USER,
            content = content,
        )

        fun uiMessage(id: String, content: String) = UiMessage(
            id = id,
            role = "user",
            content = content,
            timestamp = "2026-05-10T00:00:00Z",
        )
    }
}
