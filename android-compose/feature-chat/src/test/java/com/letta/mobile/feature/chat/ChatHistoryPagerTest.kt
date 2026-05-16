package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.UiMessage
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
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryPagerTest {

    @Test
    fun `load older messages merges page through timeline observer prefix`() = runTest {
        val harness = Harness(scope = this)
        val older = appMessage(id = "older-1", content = "old")
        coEvery { harness.messageRepository.fetchOlderMessages("agent-1", "conv-1", "live-1") } returns listOf(older)
        every {
            harness.chatTimelineObserver.mergeOlderPage("conv-1", any(), any())
        } answers {
            secondArg<List<UiMessage>>() + thirdArg<List<UiMessage>>()
        }

        harness.pager.loadOlderMessages(clientModeEnabled = false)
        advanceUntilIdle()

        assertEquals(listOf("older-1", "live-1"), harness.uiState.value.messages.map { it.id })
        assertFalse(harness.uiState.value.isLoadingOlderMessages)
        coVerify(exactly = 1) { harness.messageRepository.fetchOlderMessages("agent-1", "conv-1", "live-1") }
    }

    @Test
    fun `load older messages is ignored while streaming`() = runTest {
        val harness = Harness(scope = this)
        harness.uiState.value = harness.uiState.value.copy(isStreaming = true)

        harness.pager.loadOlderMessages(clientModeEnabled = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { harness.messageRepository.fetchOlderMessages(any(), any(), any()) }
    }

    @Test
    fun `stale older page result does not mutate messages`() = runTest {
        val harness = Harness(scope = this)
        harness.activeConversationId = "conv-1"
        coEvery { harness.messageRepository.fetchOlderMessages(any(), any(), any()) } answers {
            harness.activeConversationId = "conv-2"
            listOf(appMessage(id = "older-1", content = "old"))
        }

        harness.pager.loadOlderMessages(clientModeEnabled = false)
        advanceUntilIdle()

        assertEquals(listOf("live-1"), harness.uiState.value.messages.map { it.id })
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
            coEvery { messageRepository.fetchOlderMessages(any(), any(), any()) } returns emptyList()
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
